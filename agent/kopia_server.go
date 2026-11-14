package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Kopia data without waking the backup disks: the running "kopia server" processes have
// the repository open and its index in memory. The agent finds them through their systemd
// units (address, user, password – also from EnvironmentFile) and asks their HTTP API.
// A new "kopia" CLI process would read the repository from the disk and spin it up.

type kopiaSrv struct {
	Unit   string
	Config string
	URLs   []string // candidates (https first when the scheme is unknown)
	User   string
	Pass   string

	mu     sync.Mutex
	client *http.Client
	base   string
	csrf   string
}

var (
	kopiaSrvMu    sync.Mutex
	kopiaSrvs     map[string]*kopiaSrv // by clean config path
	kopiaSrvFound time.Time
)

const defaultKopiaConfig = "/root/.config/kopia/repository.config"

var envRefRe = regexp.MustCompile(`\$\{?([A-Za-z_][A-Za-z0-9_]*)\}?`)

func readEnvFile(path string, into map[string]string) {
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		l := strings.TrimSpace(sc.Text())
		if l == "" || strings.HasPrefix(l, "#") {
			continue
		}
		l = strings.TrimPrefix(l, "export ")
		k, v, ok := strings.Cut(l, "=")
		if !ok {
			continue
		}
		into[strings.TrimSpace(k)] = strings.Trim(strings.TrimSpace(v), `"'`)
	}
}

// discoverKopiaServers reads all kopia units once every 10 minutes.
func discoverKopiaServers(ctx context.Context) map[string]*kopiaSrv {
	kopiaSrvMu.Lock()
	defer kopiaSrvMu.Unlock()
	if kopiaSrvs != nil && time.Since(kopiaSrvFound) < 10*time.Minute {
		return kopiaSrvs
	}
	found := map[string]*kopiaSrv{}
	list := run(ctx, 15*time.Second, nil, "systemctl", "list-units", "--type=service", "--state=running", "--no-legend", "--plain", "--no-pager")
	for _, l := range strings.Split(list.Stdout, "\n") {
		unit := strings.Fields(l + " ")
		if len(unit) == 0 || !strings.Contains(strings.ToLower(unit[0]), "kopia") {
			continue
		}
		show := run(ctx, 10*time.Second, nil, "systemctl", "show", unit[0], "-p", "ExecStart", "-p", "EnvironmentFiles", "-p", "Environment")
		env := map[string]string{}
		var argv []string
		for _, sl := range strings.Split(show.Stdout, "\n") {
			switch {
			case strings.HasPrefix(sl, "EnvironmentFiles="):
				for _, part := range strings.Split(strings.TrimPrefix(sl, "EnvironmentFiles="), "\n") {
					p := strings.Fields(part)
					if len(p) > 0 {
						readEnvFile(strings.TrimPrefix(p[0], "-"), env)
					}
				}
			case strings.HasPrefix(sl, "Environment="):
				for _, kv := range strings.Fields(strings.TrimPrefix(sl, "Environment=")) {
					if k, v, ok := strings.Cut(kv, "="); ok {
						env[k] = v
					}
				}
			case strings.HasPrefix(sl, "ExecStart="):
				if i := strings.Index(sl, "argv[]="); i >= 0 {
					a := sl[i+len("argv[]="):]
					if j := strings.Index(a, " ;"); j >= 0 {
						a = a[:j]
					}
					argv = strings.Fields(a)
				}
			}
		}
		joined := " " + strings.Join(argv, " ") + " "
		if !strings.Contains(joined, " server ") || !strings.Contains(joined, " start ") {
			continue
		}
		expand := func(v string) string {
			return envRefRe.ReplaceAllStringFunc(v, func(m string) string {
				return env[envRefRe.FindStringSubmatch(m)[1]]
			})
		}
		flag := func(name string) (string, bool) {
			for i, a := range argv {
				if a == name && i+1 < len(argv) {
					return expand(strings.Trim(argv[i+1], `"'`)), true
				}
				if v, ok := strings.CutPrefix(a, name+"="); ok {
					return expand(strings.Trim(v, `"'`)), true
				}
			}
			return "", false
		}
		cfg, ok := flag("--config-file")
		if !ok {
			cfg = env["KOPIA_CONFIG_PATH"]
		}
		if cfg == "" {
			cfg = defaultKopiaConfig
		}
		addr, _ := flag("--address")
		if addr == "" {
			addr = "127.0.0.1:51515"
		}
		srv := &kopiaSrv{Unit: unit[0], Config: filepath.Clean(cfg)}
		if u, ok := flag("--server-username"); ok {
			srv.User = u
		} else if u := env["KOPIA_SERVER_USERNAME"]; u != "" {
			srv.User = u
		} else {
			srv.User = "kopia"
		}
		if p, ok := flag("--server-password"); ok {
			srv.Pass = p
		} else {
			srv.Pass = env["KOPIA_SERVER_PASSWORD"]
		}
		// listen address → loopback URL
		hostport := addr
		scheme := ""
		if u, err := url.Parse(addr); err == nil && u.Host != "" {
			scheme, hostport = u.Scheme, u.Host
		}
		host, port, err := net.SplitHostPort(hostport)
		if err != nil {
			continue
		}
		if host == "" || host == "0.0.0.0" || host == "::" || host == "localhost" {
			host = "127.0.0.1"
		}
		if host != "127.0.0.1" && host != "::1" {
			// only loopback: the agent skips TLS verification for these connections
			continue
		}
		hp := net.JoinHostPort(host, port)
		_, tlsCert := flag("--tls-cert-file")
		_, tlsGen := flag("--tls-generate-cert")
		_, insecure := flag("--insecure")
		switch {
		case scheme != "":
			srv.URLs = []string{scheme + "://" + hp}
		case tlsCert || tlsGen:
			srv.URLs = []string{"https://" + hp}
		case insecure:
			srv.URLs = []string{"http://" + hp, "https://" + hp}
		default:
			srv.URLs = []string{"https://" + hp, "http://" + hp}
		}
		found[srv.Config] = srv
	}
	kopiaSrvs, kopiaSrvFound = found, time.Now()
	return found
}

func kopiaServerFor(ctx context.Context, k KopiaRepo) *kopiaSrv {
	return discoverKopiaServers(ctx)[filepath.Clean(k.ConfigFile)]
}

var csrfRe = regexp.MustCompile(`name="kopia-csrf-token"\s+content="([^"]+)"`)

// login opens the UI page: it sets the session cookie and carries the CSRF token.
func (s *kopiaSrv) login(ctx context.Context) error {
	jar, _ := cookiejar.New(nil)
	tr := &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}} //nolint:gosec // loopback only
	var lastErr error
	for _, base := range s.URLs {
		c := &http.Client{Jar: jar, Transport: tr, Timeout: 20 * time.Second}
		req, _ := http.NewRequestWithContext(ctx, "GET", base+"/", nil)
		req.SetBasicAuth(s.User, s.Pass)
		resp, err := c.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		if resp.StatusCode == 401 || resp.StatusCode == 403 {
			return fmt.Errorf("Kopia-Server %s: Anmeldung abgelehnt", s.Unit)
		}
		if resp.StatusCode != 200 {
			lastErr = fmt.Errorf("Kopia-Server %s: HTTP %d", s.Unit, resp.StatusCode)
			continue
		}
		s.client, s.base, s.csrf = c, base, ""
		if m := csrfRe.FindSubmatch(b); m != nil {
			s.csrf = string(m[1])
		}
		return nil
	}
	if lastErr == nil {
		lastErr = errors.New("keine Adresse")
	}
	return lastErr
}

// call runs one UI API request; logs in again once if the session expired.
func (s *kopiaSrv) call(ctx context.Context, method, path string, body any, out any) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for attempt := 0; attempt < 2; attempt++ {
		if s.client == nil {
			if err := s.login(ctx); err != nil {
				return err
			}
		}
		var rd io.Reader
		if body != nil {
			b, _ := json.Marshal(body)
			rd = strings.NewReader(string(b))
		}
		req, _ := http.NewRequestWithContext(ctx, method, s.base+path, rd)
		req.SetBasicAuth(s.User, s.Pass)
		if s.csrf != "" {
			req.Header.Set("X-Kopia-Csrf-Token", s.csrf)
		}
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		resp, err := s.client.Do(req)
		if err != nil {
			s.client = nil
			if attempt == 1 {
				return err
			}
			continue
		}
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<20))
		resp.Body.Close()
		if resp.StatusCode == 401 || resp.StatusCode == 403 || (resp.StatusCode == 400 && strings.Contains(string(b), "CSRF")) {
			s.client = nil // session or token expired
			if attempt == 1 {
				return fmt.Errorf("Kopia-Server %s: HTTP %d %s", s.Unit, resp.StatusCode, strings.TrimSpace(string(b)))
			}
			continue
		}
		if resp.StatusCode != 200 {
			return fmt.Errorf("Kopia-Server %s: HTTP %d %s", s.Unit, resp.StatusCode, strings.TrimSpace(string(b)))
		}
		return json.Unmarshal(b, out)
	}
	return errors.New("Kopia-Server nicht erreichbar")
}

// storageFromConfig reads the storage part of the repository config (lies on the system disk).
func storageFromConfig(k KopiaRepo) map[string]any {
	b, err := os.ReadFile(k.ConfigFile)
	if err != nil {
		return nil
	}
	var c map[string]any
	if json.Unmarshal(b, &c) != nil {
		return nil
	}
	st, _ := c["storage"].(map[string]any)
	return st
}

type kopiaSource struct {
	Host     string `json:"host"`
	UserName string `json:"userName"`
	Path     string `json:"path"`
}

func srcQuery(s kopiaSource) string {
	return "userName=" + url.QueryEscape(s.UserName) + "&host=" + url.QueryEscape(s.Host) + "&path=" + url.QueryEscape(s.Path)
}

// serverSnapshots lists the snapshots of all sources the server manages, in the CLI-like slim format.
func (s *kopiaSrv) serverSnapshots(ctx context.Context) ([]map[string]any, error) {
	var src struct {
		Sources []struct {
			Source kopiaSource `json:"source"`
		} `json:"sources"`
	}
	if err := s.call(ctx, "GET", "/api/v1/sources", nil, &src); err != nil {
		return nil, err
	}
	out := []map[string]any{}
	for _, so := range src.Sources {
		var sn struct {
			Snapshots []struct {
				ID          string         `json:"id"`
				StartTime   string         `json:"startTime"`
				EndTime     string         `json:"endTime"`
				Description string         `json:"description"`
				Retention   []string       `json:"retention"`
				Summary     map[string]any `json:"summary"`
			} `json:"snapshots"`
		}
		if err := s.call(ctx, "GET", "/api/v1/snapshots?all=1&"+srcQuery(so.Source), nil, &sn); err != nil {
			return nil, err
		}
		for _, x := range sn.Snapshots {
			m := map[string]any{
				"id": x.ID, "source": so.Source, "startTime": x.StartTime, "endTime": x.EndTime,
				"description": x.Description, "retention": x.Retention,
			}
			if x.Summary != nil {
				m["totalSize"] = x.Summary["size"]
				m["files"] = x.Summary["files"]
				m["errors"] = x.Summary["numFailed"]
			}
			out = append(out, m)
		}
	}
	return out, nil
}

// cached CLI answers that have no server API (maintenance info) or as fallback; cleared after changes
type cliCacheEntry struct {
	at   time.Time
	data any
}

var (
	cliCacheMu sync.Mutex
	cliCache   = map[string]cliCacheEntry{}
)

const cliCacheTTL = 12 * time.Hour

func cachedCLI(key string, fresh bool, load func() (any, bool)) (any, time.Time) {
	cliCacheMu.Lock()
	e, ok := cliCache[key]
	cliCacheMu.Unlock()
	if ok && !fresh && time.Since(e.at) < cliCacheTTL {
		return e.data, e.at
	}
	d, good := load()
	if !good {
		if ok {
			return e.data, e.at
		}
		return d, time.Now()
	}
	now := time.Now()
	cliCacheMu.Lock()
	cliCache[key] = cliCacheEntry{now, d}
	cliCacheMu.Unlock()
	return d, now
}

func dropCLICache(repoID string) {
	cliCacheMu.Lock()
	defer cliCacheMu.Unlock()
	for k := range cliCache {
		if strings.HasPrefix(k, repoID+"|") {
			delete(cliCache, k)
		}
	}
}
