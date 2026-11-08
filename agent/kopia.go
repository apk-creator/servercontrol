package main

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var idRe = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,31}$`)
var snapIDRe = regexp.MustCompile(`^[a-zA-Z0-9]{8,64}$`)

func (s *Server) kopiaRepo(w http.ResponseWriter, r *http.Request) (KopiaRepo, string, bool) {
	c := s.cfg.Get()
	id := r.PathValue("repo")
	for _, k := range c.Kopia.Repos {
		if k.ID == id {
			return k, c.Kopia.Binary, true
		}
	}
	writeErr(w, 404, "unknown kopia repository")
	return KopiaRepo{}, "", false
}

func kopiaEnv(k KopiaRepo) []string {
	env := []string{"KOPIA_CHECK_FOR_UPDATES=false"}
	if k.PasswordFile != "" {
		if b, err := os.ReadFile(k.PasswordFile); err == nil {
			env = append(env, "KOPIA_PASSWORD="+strings.TrimRight(string(b), "\r\n"))
		}
	}
	return env
}

func kopiaArgs(k KopiaRepo, args ...string) []string {
	return append([]string{"--config-file=" + k.ConfigFile}, args...)
}

// GET /api/kopia/repos – all repos with quick status
func (s *Server) handleKopiaRepos(w http.ResponseWriter, r *http.Request) {
	c := s.cfg.Get()
	type item struct {
		KopiaRepo
		Connected bool   `json:"connected"`
		Storage   any    `json:"storage,omitempty"`
		Error     string `json:"error,omitempty"`
	}
	out := make([]item, len(c.Kopia.Repos))
	done := make(chan struct{})
	for i, k := range c.Kopia.Repos {
		go func(i int, k KopiaRepo) {
			defer func() { done <- struct{}{} }()
			it := item{KopiaRepo: k}
			res := run(r.Context(), 30*time.Second, kopiaEnv(c.Kopia.Repos[i]), c.Kopia.Binary, kopiaArgs(k, "repository", "status", "--json")...)
			if res.ExitCode == 0 {
				var st map[string]any
				if json.Unmarshal([]byte(res.Stdout), &st) == nil {
					it.Connected = true
					it.Storage = st["storage"]
				}
			} else {
				it.Error = strings.TrimSpace(res.Stderr)
			}
			out[i] = it
		}(i, k)
	}
	for range c.Kopia.Repos {
		<-done
	}
	writeJSON(w, 200, out)
}

func validateRepo(k *KopiaRepo) error {
	if !idRe.MatchString(k.ID) {
		return errors.New("id: lowercase letters, digits, - and _ (max 32)")
	}
	if strings.TrimSpace(k.Name) == "" {
		k.Name = k.ID
	}
	if !filepath.IsAbs(k.ConfigFile) {
		return errors.New("configFile must be an absolute path")
	}
	if k.PasswordFile != "" && !filepath.IsAbs(k.PasswordFile) {
		return errors.New("passwordFile must be an absolute path")
	}
	for _, src := range k.Sources {
		if !filepath.IsAbs(src) {
			return errors.New("sources must be absolute paths")
		}
	}
	return nil
}

// POST /api/kopia/repos – add or replace a repo definition
func (s *Server) handleKopiaRepoSave(w http.ResponseWriter, r *http.Request) {
	var k KopiaRepo
	if err := readJSON(r, &k); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := validateRepo(&k); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	err := s.cfg.Update(func(c *Config) error {
		for i := range c.Kopia.Repos {
			if c.Kopia.Repos[i].ID == k.ID {
				c.Kopia.Repos[i] = k
				return nil
			}
		}
		c.Kopia.Repos = append(c.Kopia.Repos, k)
		return nil
	})
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	logf("kopia repo saved: %s", k.ID)
	writeJSON(w, 200, k)
}

// DELETE /api/kopia/repos/{repo} – removes only the definition, never data
func (s *Server) handleKopiaRepoDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("repo")
	err := s.cfg.Update(func(c *Config) error {
		for i := range c.Kopia.Repos {
			if c.Kopia.Repos[i].ID == id {
				c.Kopia.Repos = append(c.Kopia.Repos[:i], c.Kopia.Repos[i+1:]...)
				return nil
			}
		}
		return errors.New("unknown repository")
	})
	if err != nil {
		writeErr(w, 404, err.Error())
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// GET /api/kopia/repos/{repo}/status
func (s *Server) handleKopiaStatus(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	env := kopiaEnv(k)
	st := run(r.Context(), 30*time.Second, env, bin, kopiaArgs(k, "repository", "status", "--json")...)
	mi := run(r.Context(), 30*time.Second, env, bin, kopiaArgs(k, "maintenance", "info", "--json")...)
	var status, maint any
	_ = json.Unmarshal([]byte(st.Stdout), &status)
	_ = json.Unmarshal([]byte(mi.Stdout), &maint)
	resp := map[string]any{"status": status, "maintenance": maint}
	if st.ExitCode != 0 {
		resp["error"] = strings.TrimSpace(st.Stderr)
	}
	writeJSON(w, 200, resp)
}

// GET /api/kopia/repos/{repo}/snapshots
func (s *Server) handleKopiaSnapshots(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	res := run(r.Context(), 90*time.Second, kopiaEnv(k), bin, kopiaArgs(k, "snapshot", "list", "--all", "--json")...)
	if res.ExitCode != 0 {
		sendResult(w, res)
		return
	}
	var snaps []map[string]any
	if err := json.Unmarshal([]byte(res.Stdout), &snaps); err != nil {
		writeErr(w, 502, "cannot parse kopia output")
		return
	}
	// slim the payload down to what the app shows
	type snap struct {
		ID        string   `json:"id"`
		Source    any      `json:"source"`
		Start     string   `json:"startTime"`
		End       string   `json:"endTime"`
		Size      any      `json:"totalSize"`
		Files     any      `json:"files"`
		Errors    any      `json:"errors"`
		Retention []string `json:"retention"`
		Desc      string   `json:"description"`
	}
	out := make([]snap, 0, len(snaps))
	for _, m := range snaps {
		sm := snap{ID: toStr(m["id"]), Source: m["source"], Start: toStr(m["startTime"]), End: toStr(m["endTime"]), Desc: toStr(m["description"])}
		if st, ok := m["stats"].(map[string]any); ok {
			sm.Size, sm.Files, sm.Errors = st["totalSize"], st["fileCount"], st["errorCount"]
		}
		if rr, ok := m["retentionReason"].([]any); ok {
			for _, x := range rr {
				sm.Retention = append(sm.Retention, toStr(x))
			}
		}
		out = append(out, sm)
	}
	writeJSON(w, 200, out)
}

// GET /api/kopia/repos/{repo}/policies
func (s *Server) handleKopiaPolicies(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	res := run(r.Context(), 30*time.Second, kopiaEnv(k), bin, kopiaArgs(k, "policy", "list", "--json")...)
	var p any
	if json.Unmarshal([]byte(res.Stdout), &p) != nil {
		sendResult(w, res)
		return
	}
	writeJSON(w, 200, p)
}

// POST /api/kopia/repos/{repo}/{action}
func (s *Server) handleKopiaAction(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	var req struct {
		Source     string `json:"source"`
		SnapshotID string `json:"snapshotId"`
		Target     string `json:"target"`
		Percent    int    `json:"percent"`
	}
	if r.ContentLength > 0 {
		if err := readJSON(r, &req); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	env := kopiaEnv(k)
	title := "Kopia " + k.Name + ": "
	var args []string
	timeout := 12 * time.Hour
	switch r.PathValue("action") {
	case "snapshot":
		switch {
		case req.Source != "":
			if !filepath.IsAbs(req.Source) {
				writeErr(w, 400, "source must be absolute")
				return
			}
			args = []string{"snapshot", "create", req.Source}
		case len(k.Sources) > 0:
			args = append([]string{"snapshot", "create"}, k.Sources...)
		default:
			args = []string{"snapshot", "create", "--all"}
		}
		title += "Snapshot"
	case "maintenance":
		args, title = []string{"maintenance", "run", "--full"}, title+"Wartung"
	case "verify":
		p := req.Percent
		if p <= 0 || p > 100 {
			p = 1
		}
		args, title = []string{"snapshot", "verify", "--verify-files-percent=" + itoa(p)}, title+"Verifizieren"
	case "restore":
		if !snapIDRe.MatchString(req.SnapshotID) || !filepath.IsAbs(req.Target) {
			writeErr(w, 400, "snapshotId and absolute target required")
			return
		}
		if _, err := os.Stat(req.Target); err == nil {
			entries, _ := os.ReadDir(req.Target)
			if len(entries) > 0 {
				writeErr(w, 409, "target directory is not empty")
				return
			}
		}
		args, title = []string{"snapshot", "restore", req.SnapshotID, req.Target}, title+"Wiederherstellen → "+req.Target
	case "delete-snapshot":
		if !snapIDRe.MatchString(req.SnapshotID) {
			writeErr(w, 400, "snapshotId required")
			return
		}
		args, timeout, title = []string{"snapshot", "delete", req.SnapshotID, "--delete"}, 30*time.Minute, title+"Snapshot löschen"
	default:
		writeErr(w, 400, "unknown action")
		return
	}
	jobStarted(w, s.jobs.Start(title, timeout, env, bin, kopiaArgs(k, args...)...))
}

func itoa(i int) string {
	b, _ := json.Marshal(i)
	return string(b)
}
