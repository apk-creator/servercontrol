package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// ======================= configuration via the app =======================

// GET /api/config – current module configuration (secrets are never returned)
func (s *Server) handleConfigGet(w http.ResponseWriter, r *http.Request) {
	c := s.cfg.Get()
	_, keyErr := os.Stat(c.Immich.APIKeyFile)
	_, tokErr := os.Stat(c.Update.TokenFile)
	writeJSON(w, 200, map[string]any{
		"version":    version,
		"nextcloud":  map[string]any{"enabled": c.Nextcloud.Enabled, "occ": c.Nextcloud.OCC},
		"immich":     map[string]any{"enabled": c.Immich.Enabled, "url": c.Immich.URL, "hasApiKey": keyErr == nil},
		"update":     map[string]any{"repo": c.Update.Repo, "hasToken": tokErr == nil},
		"allowShell": c.AllowShell,
		"kopiaCount": len(c.Kopia.Repos),
	})
}

func validateOCC(occ []string) error {
	if len(occ) == 0 {
		return errors.New("occ-Befehl fehlt")
	}
	for _, a := range occ {
		if a == "" || strings.ContainsRune(a, 0) {
			return errors.New("ungültiges Argument im occ-Befehl")
		}
	}
	if _, err := exec.LookPath(occ[0]); err != nil {
		return fmt.Errorf("%s nicht gefunden", occ[0])
	}
	return nil
}

type ncConfigReq struct {
	Enabled bool     `json:"enabled"`
	OCC     []string `json:"occ"`
}

// POST /api/config/nextcloud/test – runs "occ status" with the given command
func (s *Server) handleNcConfigTest(w http.ResponseWriter, r *http.Request) {
	var req ncConfigReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := validateOCC(req.OCC); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	res := run(r.Context(), 60*time.Second, nil, req.OCC[0], append(append([]string{}, req.OCC[1:]...), "status", "--output=json")...)
	var st map[string]any
	if json.Unmarshal([]byte(strings.TrimSpace(res.Stdout)), &st) != nil || res.ExitCode != 0 {
		writeJSON(w, 200, map[string]any{"ok": false, "message": strings.TrimSpace(res.Stderr + "\n" + res.Stdout)})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": st["installed"] == true, "version": st["versionstring"], "message": "Nextcloud " + toStr(st["versionstring"]) + " gefunden"})
}

// PUT /api/config/nextcloud
func (s *Server) handleNcConfigSave(w http.ResponseWriter, r *http.Request) {
	var req ncConfigReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := validateOCC(req.OCC); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := s.cfg.Update(func(c *Config) error {
		c.Nextcloud.Enabled, c.Nextcloud.OCC = req.Enabled, req.OCC
		return nil
	}); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	logf("nextcloud config updated (enabled=%v)", req.Enabled)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

type immichConfigReq struct {
	Enabled bool   `json:"enabled"`
	URL     string `json:"url"`
	APIKey  string `json:"apiKey"` // empty = keep the stored key
}

func validateURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return errors.New("URL muss mit http:// oder https:// beginnen")
	}
	return nil
}

func immichGet(baseURL, key, path string) (int, map[string]any, error) {
	req, err := http.NewRequest("GET", strings.TrimRight(baseURL, "/")+"/api"+path, nil)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("x-api-key", key)
	req.Header.Set("Accept", "application/json")
	resp, err := immichClient.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	var m map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&m)
	return resp.StatusCode, m, nil
}

// POST /api/config/immich/test
func (s *Server) handleImmichConfigTest(w http.ResponseWriter, r *http.Request) {
	var req immichConfigReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := validateURL(req.URL); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	key := strings.TrimSpace(req.APIKey)
	if key == "" {
		b, err := os.ReadFile(s.cfg.Get().Immich.APIKeyFile)
		if err != nil {
			writeJSON(w, 200, map[string]any{"ok": false, "message": "Kein API-Schlüssel angegeben oder gespeichert"})
			return
		}
		key = strings.TrimSpace(string(b))
	}
	code, about, err := immichGet(req.URL, key, "/server/about")
	if err != nil {
		writeJSON(w, 200, map[string]any{"ok": false, "message": "Immich nicht erreichbar: " + err.Error()})
		return
	}
	if code != 200 {
		writeJSON(w, 200, map[string]any{"ok": false, "message": fmt.Sprintf("Immich antwortet mit HTTP %d (Schlüssel ungültig oder ohne Rechte)", code)})
		return
	}
	scode, _, _ := immichGet(req.URL, key, "/server/statistics")
	msg := "Immich " + toStr(about["version"]) + " erreichbar"
	if scode != 200 {
		msg += " – Achtung: Schlüssel hat keine Admin-Rechte (Statistik/Benutzer nicht verfügbar)"
	}
	writeJSON(w, 200, map[string]any{"ok": true, "admin": scode == 200, "version": about["version"], "message": msg})
}

// PUT /api/config/immich
func (s *Server) handleImmichConfigSave(w http.ResponseWriter, r *http.Request) {
	var req immichConfigReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := validateURL(req.URL); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	c := s.cfg.Get()
	if k := strings.TrimSpace(req.APIKey); k != "" {
		if err := writeSecret(c.Immich.APIKeyFile, k); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
	}
	if err := s.cfg.Update(func(c *Config) error {
		c.Immich.Enabled, c.Immich.URL = req.Enabled, strings.TrimRight(req.URL, "/")
		return nil
	}); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	logf("immich config updated (enabled=%v)", req.Enabled)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// ======================= Kopia: discover, connect, create =======================

func (s *Server) kopiaDir() string { return filepath.Join(s.cfg.Dir(), "kopia") }

// GET /api/kopia/discover – existing Kopia config files on the server
func (s *Server) handleKopiaDiscover(w http.ResponseWriter, r *http.Request) {
	c := s.cfg.Get()
	known := map[string]string{}
	for _, k := range c.Kopia.Repos {
		known[k.ConfigFile] = k.ID
	}
	var files []string
	for _, pattern := range []string{"/root/.config/kopia/*.config", filepath.Join(s.kopiaDir(), "*.config"), "/home/*/.config/kopia/*.config"} {
		m, _ := filepath.Glob(pattern)
		files = append(files, m...)
	}
	type found struct {
		ConfigFile string `json:"configFile"`
		KnownAs    string `json:"knownAs,omitempty"`
		Connected  bool   `json:"connected"`
		Storage    string `json:"storage,omitempty"`
		Path       string `json:"path,omitempty"`
		Error      string `json:"error,omitempty"`
	}
	out := []found{}
	seen := map[string]bool{}
	for _, f := range files {
		if seen[f] {
			continue
		}
		seen[f] = true
		it := found{ConfigFile: f, KnownAs: known[f]}
		res := run(r.Context(), 30*time.Second, []string{"KOPIA_CHECK_FOR_UPDATES=false"}, c.Kopia.Binary, "--config-file="+f, "repository", "status", "--json")
		var st map[string]any
		if res.ExitCode == 0 && json.Unmarshal([]byte(res.Stdout), &st) == nil {
			it.Connected = true
			if sto, ok := st["storage"].(map[string]any); ok {
				it.Storage = toStr(sto["type"])
				if cfg, ok := sto["config"].(map[string]any); ok {
					it.Path = toStr(cfg["path"])
				}
			}
		} else {
			it.Error = strings.TrimSpace(res.Stderr)
		}
		out = append(out, it)
	}
	writeJSON(w, 200, out)
}

type kopiaConnectReq struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Path          string   `json:"path"`     // filesystem repository location
	Password      string   `json:"password"` // repository password
	Create        bool     `json:"create"`   // true = create a new repository
	StorePassword bool     `json:"storePassword"`
	Sources       []string `json:"sources"`
}

// POST /api/kopia/connect – connect to an existing or create a new filesystem repository
func (s *Server) handleKopiaConnect(w http.ResponseWriter, r *http.Request) {
	var req kopiaConnectReq
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if !filepath.IsAbs(req.Path) {
		writeErr(w, 400, "Repository-Pfad muss absolut sein")
		return
	}
	if len(req.Password) < 8 {
		writeErr(w, 400, "Passwort zu kurz (min. 8 Zeichen)")
		return
	}
	k := KopiaRepo{ID: req.ID, Name: req.Name, ConfigFile: filepath.Join(s.kopiaDir(), req.ID+".config"), Sources: req.Sources}
	if err := validateRepo(&k); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	c := s.cfg.Get()
	for _, e := range c.Kopia.Repos {
		if e.ID == k.ID {
			writeErr(w, 409, "Kennung bereits vergeben")
			return
		}
	}
	if _, err := os.Stat(k.ConfigFile); err == nil {
		writeErr(w, 409, "Konfigurationsdatei existiert bereits: "+k.ConfigFile)
		return
	}
	if err := os.MkdirAll(s.kopiaDir(), 0o700); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	verb := "connect"
	if req.Create {
		verb = "create"
		if err := os.MkdirAll(req.Path, 0o700); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
	} else if _, err := os.Stat(filepath.Join(req.Path, "kopia.repository.f")); err != nil {
		writeErr(w, 400, "Unter "+req.Path+" liegt kein Kopia-Repository (kopia.repository.f fehlt)")
		return
	}
	env := []string{"KOPIA_CHECK_FOR_UPDATES=false", "KOPIA_PASSWORD=" + req.Password}
	logf("kopia repository %s %s (id %s)", verb, req.Path, k.ID)
	res := run(r.Context(), 5*time.Minute, env, c.Kopia.Binary, "--config-file="+k.ConfigFile, "repository", verb, "filesystem", "--path="+req.Path)
	if res.ExitCode != 0 {
		_ = os.Remove(k.ConfigFile)
		writeJSON(w, 502, res)
		return
	}
	if req.StorePassword {
		k.PasswordFile = filepath.Join(s.kopiaDir(), k.ID+".pw")
		if err := writeSecret(k.PasswordFile, req.Password); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
	}
	if err := s.cfg.Update(func(c *Config) error { c.Kopia.Repos = append(c.Kopia.Repos, k); return nil }); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "repo": k, "output": strings.TrimSpace(res.Stdout + res.Stderr)})
}

// POST /api/kopia/repos/{repo}/password – store (or replace) the repository password on the server
func (s *Server) handleKopiaPassword(w http.ResponseWriter, r *http.Request) {
	k, _, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	var req struct {
		Password string `json:"password"`
	}
	if err := readJSON(r, &req); err != nil || req.Password == "" {
		writeErr(w, 400, "Passwort fehlt")
		return
	}
	pf := k.PasswordFile
	if pf == "" {
		pf = filepath.Join(s.kopiaDir(), k.ID+".pw")
	}
	if err := writeSecret(pf, req.Password); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	if err := s.cfg.Update(func(c *Config) error {
		for i := range c.Kopia.Repos {
			if c.Kopia.Repos[i].ID == k.ID {
				c.Kopia.Repos[i].PasswordFile = pf
			}
		}
		return nil
	}); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
