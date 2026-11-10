package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Self-update: the agent replaces its own binary and restarts via systemd.
// Sources: latest GitHub release (token optional for private repos) or an uploaded file.

var updateMu sync.Mutex
var ghClient = &http.Client{Timeout: 5 * time.Minute}
var repoRe = regexp.MustCompile(`^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`)

const agentAsset = "servercontrol-agent.tar.gz"

type ghRelease struct {
	TagName     string `json:"tag_name"`
	Name        string `json:"name"`
	Body        string `json:"body"`
	PublishedAt string `json:"published_at"`
	Assets      []struct {
		ID   int64  `json:"id"`
		Name string `json:"name"`
		URL  string `json:"url"`
		Size int64  `json:"size"`
	} `json:"assets"`
}

func (s *Server) ghRequest(ctx context.Context, url, accept string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", accept)
	req.Header.Set("User-Agent", "servercontrol-agent/"+version)
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	if b, err := os.ReadFile(s.cfg.Get().Update.TokenFile); err == nil {
		if t := strings.TrimSpace(string(b)); t != "" {
			req.Header.Set("Authorization", "Bearer "+t)
		}
	}
	return ghClient.Do(req)
}

func (s *Server) latestRelease(ctx context.Context) (*ghRelease, error) {
	repo := s.cfg.Get().Update.Repo
	if !repoRe.MatchString(repo) {
		return nil, errors.New("ungültiges Repository (owner/name)")
	}
	resp, err := s.ghRequest(ctx, "https://api.github.com/repos/"+repo+"/releases/latest", "application/vnd.github+json")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	switch resp.StatusCode {
	case 200:
	case 404:
		return nil, errors.New("kein Release gefunden – bei privatem Repository wird ein GitHub-Token benötigt")
	case 401, 403:
		return nil, fmt.Errorf("GitHub verweigert den Zugriff (HTTP %d) – Token prüfen", resp.StatusCode)
	default:
		return nil, fmt.Errorf("GitHub antwortet mit HTTP %d", resp.StatusCode)
	}
	var rel ghRelease
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return nil, err
	}
	return &rel, nil
}

// compareVersions returns -1, 0, 1 for semantic versions like v1.2.3.
func compareVersions(a, b string) int {
	pa := strings.Split(strings.TrimPrefix(strings.SplitN(a, "-", 2)[0], "v"), ".")
	pb := strings.Split(strings.TrimPrefix(strings.SplitN(b, "-", 2)[0], "v"), ".")
	for i := 0; i < 3; i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			if x < y {
				return -1
			}
			return 1
		}
	}
	return 0
}

// GET /api/agent/update/check
func (s *Server) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	rel, err := s.latestRelease(r.Context())
	if err != nil {
		writeJSON(w, 200, map[string]any{"current": version, "error": err.Error()})
		return
	}
	hasAsset := false
	for _, a := range rel.Assets {
		if a.Name == agentAsset {
			hasAsset = true
		}
	}
	assets := []map[string]any{}
	for _, a := range rel.Assets {
		assets = append(assets, map[string]any{"name": a.Name, "size": a.Size})
	}
	writeJSON(w, 200, map[string]any{
		"assets":      assets,
		"current":     version,
		"latest":      strings.TrimPrefix(rel.TagName, "v"),
		"newer":       compareVersions(rel.TagName, version) > 0,
		"hasAsset":    hasAsset,
		"publishedAt": rel.PublishedAt,
		"notes":       rel.Body,
	})
}

// POST /api/agent/update/install – download the agent from the latest release and install it
func (s *Server) handleUpdateInstall(w http.ResponseWriter, r *http.Request) {
	rel, err := s.latestRelease(r.Context())
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	var assetURL string
	for _, a := range rel.Assets {
		if a.Name == agentAsset {
			assetURL = a.URL
		}
	}
	if assetURL == "" {
		writeErr(w, 404, "Release "+rel.TagName+" enthält kein "+agentAsset)
		return
	}
	resp, err := s.ghRequest(r.Context(), assetURL, "application/octet-stream")
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		writeErr(w, 502, fmt.Sprintf("Download fehlgeschlagen (HTTP %d)", resp.StatusCode))
		return
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, 100<<20))
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	s.install(w, r, data)
}

// POST /api/agent/update/upload – body is the release tarball or the bare binary
func (s *Server) handleUpdateUpload(w http.ResponseWriter, r *http.Request) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 100<<20))
	if err != nil {
		writeErr(w, 400, "Upload fehlgeschlagen: "+err.Error())
		return
	}
	s.install(w, r, data)
}

func extractBinary(data []byte) ([]byte, error) {
	switch {
	case bytes.HasPrefix(data, []byte{0x7f, 'E', 'L', 'F'}):
		return data, nil
	case bytes.HasPrefix(data, []byte{0x1f, 0x8b}):
		gz, err := gzip.NewReader(bytes.NewReader(data))
		if err != nil {
			return nil, err
		}
		tr := tar.NewReader(gz)
		want := "servercontrol-agent-linux-" + runtime.GOARCH
		for {
			h, err := tr.Next()
			if err == io.EOF {
				return nil, fmt.Errorf("%s nicht im Archiv", want)
			}
			if err != nil {
				return nil, err
			}
			if filepath.Base(h.Name) == want {
				return io.ReadAll(io.LimitReader(tr, 100<<20))
			}
		}
	default:
		return nil, errors.New("unbekanntes Format (erwartet .tar.gz aus dem Release oder Linux-Binärdatei)")
	}
}

func (s *Server) install(w http.ResponseWriter, r *http.Request, data []byte) {
	if !updateMu.TryLock() {
		writeErr(w, 409, "Update läuft bereits")
		return
	}
	defer updateMu.Unlock()

	bin, err := extractBinary(data)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	exe, err := os.Executable()
	if err == nil {
		exe, err = filepath.EvalSymlinks(exe)
	}
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	newPath := exe + ".new"
	if err := os.WriteFile(newPath, bin, 0o755); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	// sanity check: the new binary must start and report a version
	res := run(r.Context(), 15*time.Second, nil, newPath, "-version")
	newVer := strings.TrimSpace(res.Stdout)
	if res.ExitCode != 0 || newVer == "" {
		_ = os.Remove(newPath)
		writeErr(w, 400, "Neue Binärdatei startet nicht: "+strings.TrimSpace(res.Stderr))
		return
	}
	_ = os.Remove(exe + ".bak")
	if err := os.Link(exe, exe+".bak"); err != nil {
		// fall back to a copy if hard links are not possible
		if old, rerr := os.ReadFile(exe); rerr == nil {
			_ = os.WriteFile(exe+".bak", old, 0o755)
		}
	}
	if err := os.Rename(newPath, exe); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	logf("agent updated %s -> %s, restarting", version, newVer)
	restart := run(r.Context(), 10*time.Second, nil, "systemd-run", "--on-active=2", "systemctl", "restart", "servercontrol-agent.service")
	msg := "Agent wird in 2 Sekunden neu gestartet."
	if restart.ExitCode != 0 {
		msg = "Installiert, aber Neustart fehlgeschlagen – bitte 'systemctl restart servercontrol-agent' ausführen. " + strings.TrimSpace(restart.Stderr)
	}
	writeJSON(w, 200, map[string]any{"ok": true, "oldVersion": version, "newVersion": newVer, "message": msg})
}

// PUT /api/config/update – repository and optional token for update checks
func (s *Server) handleUpdateConfigSave(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Repo       string `json:"repo"`
		Token      string `json:"token"`
		ClearToken bool   `json:"clearToken"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if !repoRe.MatchString(req.Repo) {
		writeErr(w, 400, "Repository im Format owner/name angeben")
		return
	}
	c := s.cfg.Get()
	switch {
	case req.ClearToken:
		_ = os.Remove(c.Update.TokenFile)
	case strings.TrimSpace(req.Token) != "":
		if err := writeSecret(c.Update.TokenFile, strings.TrimSpace(req.Token)); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
	}
	if err := s.cfg.Update(func(c *Config) error { c.Update.Repo = req.Repo; return nil }); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// App files the agent may hand out to the app (downloaded from the release with the server's token).
var appAssetRe = regexp.MustCompile(`^(ServerControl\.apk|ServerControl-[0-9.]+\.msi|servercontrol_[0-9.]+-[0-9]+_amd64\.deb)$`)

// GET /api/agent/release/asset?name=ServerControl.apk – streams an app package of the latest release.
// The app needs no GitHub token of its own: it uses the agent's.
func (s *Server) handleReleaseAsset(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	if !appAssetRe.MatchString(name) {
		writeErr(w, 400, "unbekannte Datei")
		return
	}
	rel, err := s.latestRelease(r.Context())
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	var url string
	var size int64
	for _, a := range rel.Assets {
		if a.Name == name {
			url, size = a.URL, a.Size
		}
	}
	if url == "" {
		writeErr(w, 404, name+" ist im Release "+rel.TagName+" nicht enthalten")
		return
	}
	resp, err := s.ghRequest(r.Context(), url, "application/octet-stream")
	if err != nil {
		writeErr(w, 502, err.Error())
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		writeErr(w, 502, fmt.Sprintf("Download fehlgeschlagen (HTTP %d)", resp.StatusCode))
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	w.Header().Set("X-Release-Version", strings.TrimPrefix(rel.TagName, "v"))
	w.WriteHeader(200)
	n, _ := io.Copy(w, io.LimitReader(resp.Body, 300<<20))
	logf("app package %s (%s, %d bytes) sent", name, rel.TagName, n)
}
