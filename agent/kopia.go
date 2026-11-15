package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
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
		Via       string `json:"via"`
	}
	out := make([]item, len(c.Kopia.Repos))
	done := make(chan struct{})
	for i, k := range c.Kopia.Repos {
		go func(i int, k KopiaRepo) {
			defer func() { done <- struct{}{} }()
			it := item{KopiaRepo: k}
			// ask the running Kopia server first – a CLI call would wake the backup disk
			if srv := kopiaServerFor(r.Context(), k); srv != nil {
				var st map[string]any
				if err := srv.call(r.Context(), "GET", "/api/v1/repo/status", nil, &st); err == nil {
					it.Connected = st["connected"] == true
					it.Storage = storageFromConfig(k)
					it.Via = "server"
					out[i] = it
					return
				}
			}
			res := run(r.Context(), 30*time.Second, kopiaEnv(c.Kopia.Repos[i]), c.Kopia.Binary, kopiaArgs(k, "repository", "status", "--json")...)
			it.Via = "cli"
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
	fresh := r.URL.Query().Get("fresh") == "1"
	var status any
	var errMsg, via string
	if srv := kopiaServerFor(r.Context(), k); srv != nil {
		var st map[string]any
		if err := srv.call(r.Context(), "GET", "/api/v1/repo/status", nil, &st); err == nil {
			st["storage"] = storageFromConfig(k)
			status, via = st, "server"
		} else {
			errMsg = err.Error()
		}
	}
	if status == nil {
		st := run(r.Context(), 30*time.Second, env, bin, kopiaArgs(k, "repository", "status", "--json")...)
		_ = json.Unmarshal([]byte(st.Stdout), &status)
		via = "cli"
		if st.ExitCode != 0 {
			errMsg = strings.TrimSpace(st.Stderr)
		} else {
			errMsg = ""
		}
	}
	// maintenance info exists only in the CLI: cached for 12 h (reading it wakes the disk)
	maint, maintAt := cachedCLI(k.ID+"|maint", fresh, func() (any, bool) {
		mi := run(r.Context(), 30*time.Second, env, bin, kopiaArgs(k, "maintenance", "info", "--json")...)
		var m any
		return m, json.Unmarshal([]byte(mi.Stdout), &m) == nil && mi.ExitCode == 0
	})
	resp := map[string]any{"status": status, "maintenance": maint, "maintenanceAt": maintAt.UnixMilli(), "via": via}
	if m, ok := status.(map[string]any); ok {
		if stg, ok := m["storage"].(map[string]any); ok {
			if c, ok := stg["config"].(map[string]any); ok {
				if p, ok := c["path"].(string); ok && filepath.IsAbs(p) {
					resp["drive"] = driveOf(r, p)
				}
			}
		}
	}
	if errMsg != "" && via == "cli" {
		resp["error"] = errMsg
	}
	writeJSON(w, 200, resp)
}

// GET /api/kopia/repos/{repo}/snapshots
func (s *Server) handleKopiaSnapshots(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	if srv := kopiaServerFor(r.Context(), k); srv != nil {
		if snaps, err := srv.serverSnapshots(r.Context()); err == nil {
			writeJSON(w, 200, snaps)
			return
		}
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
	dropCLICache(k.ID)
	// snapshot and delete through the running Kopia server: it sees the change at once
	if srv := kopiaServerFor(r.Context(), k); srv != nil {
		if msg, done := kopiaServerAction(r, srv, k, r.PathValue("action"), req.Source, req.SnapshotID); done {
			writeJSON(w, 200, map[string]any{"ok": true, "stdout": msg})
			return
		}
	}
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
	case "quick-maintenance":
		args, title = []string{"maintenance", "run"}, title+"Kurzwartung"
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

// driveOf finds the mount and the physical disk (model, HDD/SSD) a path lives on.
func driveOf(r *http.Request, path string) map[string]any {
	var best Mount
	for _, m := range mounts() {
		if (path == m.Mount || strings.HasPrefix(path, strings.TrimSuffix(m.Mount, "/")+"/")) && len(m.Mount) > len(best.Mount) {
			best = m
		}
	}
	out := map[string]any{"mount": best.Mount, "device": best.Device, "total": best.Total, "used": best.Used, "free": best.Free}
	if strings.HasPrefix(best.Device, "/dev/") {
		// -s: walk up from the partition / LUKS mapping to the disk (sysfs only, does not wake the disk)
		res := run(r.Context(), 10*time.Second, nil, "lsblk", "-s", "-n", "-P", "-o", "NAME,TYPE,MODEL,ROTA", best.Device)
		for _, l := range strings.Split(res.Stdout, "\n") {
			if strings.Contains(l, `TYPE="disk"`) {
				kv := parsePairs(l)
				out["disk"] = "/dev/" + kv["NAME"]
				out["model"] = strings.TrimSpace(kv["MODEL"])
				out["rotational"] = kv["ROTA"] == "1"
				break
			}
		}
	}
	return out
}

func parsePairs(l string) map[string]string {
	out := map[string]string{}
	for _, m := range regexp.MustCompile(`([A-Z]+)="([^"]*)"`).FindAllStringSubmatch(l, -1) {
		out[m[1]] = m[2]
	}
	return out
}

var sourceRe = regexp.MustCompile(`^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:/[^\x00]*$`)
var timesRe = regexp.MustCompile(`^([0-2]?[0-9]:[0-5][0-9])(,[0-2]?[0-9]:[0-5][0-9])*$`)
var cronRe = regexp.MustCompile(`^[0-9*/,\- ;A-Za-z]{1,200}$`)

// GET /api/kopia/repos/{repo}/schedule?source=user@host:/path – effective policy of a source
func (s *Server) handleKopiaSchedule(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	src := r.URL.Query().Get("source")
	if !sourceRe.MatchString(src) {
		writeErr(w, 400, "invalid source")
		return
	}
	// effective policy from the running server (with the next snapshot times)
	if srv := kopiaServerFor(r.Context(), k); srv != nil {
		user, rest, _ := strings.Cut(src, "@")
		host, path, _ := strings.Cut(rest, ":")
		var res struct {
			Effective map[string]any `json:"effective"`
			Upcoming  []string       `json:"upcomingSnapshotTimes"`
		}
		q := srcQuery(kopiaSource{UserName: user, Host: host, Path: path})
		if err := srv.call(r.Context(), "POST", "/api/v1/policy/resolve?"+q, map[string]any{"updates": map[string]any{}, "numUpcomingSnapshotTimes": 3}, &res); err == nil && res.Effective != nil {
			res.Effective["upcomingSnapshotTimes"] = res.Upcoming
			writeJSON(w, 200, res.Effective)
			return
		}
	}
	p, _ := cachedCLI(k.ID+"|policy|"+src, r.URL.Query().Get("fresh") == "1", func() (any, bool) {
		res := run(r.Context(), 30*time.Second, kopiaEnv(k), bin, kopiaArgs(k, "policy", "show", "--json", src)...)
		var p any
		return p, json.Unmarshal([]byte(res.Stdout), &p) == nil
	})
	if p == nil {
		writeErr(w, 502, "Richtlinie nicht lesbar")
		return
	}
	writeJSON(w, 200, p)
}

// POST /api/kopia/repos/{repo}/schedule – snapshot times/interval and retention of a source
func (s *Server) handleKopiaScheduleSet(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	var req struct {
		Source        string            `json:"source"`
		Times         string            `json:"times"`   // "01:00,13:00", "" = inherit
		Crontab       string            `json:"crontab"` // "" = inherit
		IntervalHours *int              `json:"intervalHours"`
		Keep          map[string]string `json:"keep"` // latest/hourly/daily/weekly/monthly/annual → number or "inherit"
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if !sourceRe.MatchString(req.Source) {
		writeErr(w, 400, "invalid source")
		return
	}
	args := []string{"policy", "set", req.Source}
	switch t := strings.ReplaceAll(req.Times, " ", ""); {
	case t == "":
		args = append(args, "--snapshot-time=inherit")
	case timesRe.MatchString(t):
		args = append(args, "--snapshot-time="+t)
	default:
		writeErr(w, 400, "times: HH:MM, comma separated")
		return
	}
	switch c := strings.TrimSpace(req.Crontab); {
	case c == "":
		args = append(args, "--snapshot-time-crontab=inherit")
	case cronRe.MatchString(c):
		args = append(args, "--snapshot-time-crontab="+c)
	default:
		writeErr(w, 400, "invalid crontab expression")
		return
	}
	if req.IntervalHours != nil {
		h := *req.IntervalHours
		if h < 0 || h > 24*31 {
			writeErr(w, 400, "invalid interval")
			return
		}
		args = append(args, "--snapshot-interval="+strconv.Itoa(h)+"h")
	}
	for _, key := range []string{"latest", "hourly", "daily", "weekly", "monthly", "annual"} {
		v, ok := req.Keep[key]
		if !ok {
			continue
		}
		v = strings.TrimSpace(v)
		if v == "" {
			v = "inherit"
		}
		if v != "inherit" {
			if n, err := strconv.Atoi(v); err != nil || n < 0 || n > 10000 {
				writeErr(w, 400, "keep-"+key+": number or empty")
				return
			}
		}
		args = append(args, "--keep-"+key+"="+v)
	}
	logf("kopia %s: policy set %v", k.ID, args[3:])
	dropCLICache(k.ID)
	sendResult(w, run(r.Context(), 60*time.Second, kopiaEnv(k), bin, kopiaArgs(k, args...)...))
}

// POST /api/kopia/repos/{repo}/maintenance-schedule
func (s *Server) handleKopiaMaintSet(w http.ResponseWriter, r *http.Request) {
	k, bin, ok := s.kopiaRepo(w, r)
	if !ok {
		return
	}
	var req struct {
		QuickEnabled bool   `json:"quickEnabled"`
		FullEnabled  bool   `json:"fullEnabled"`
		QuickHours   int    `json:"quickHours"`
		FullHours    int    `json:"fullHours"`
		QuickTime    string `json:"quickTime"` // "HH:MM" server time of the next run (empty = keep)
		FullTime     string `json:"fullTime"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	args := []string{"maintenance", "set",
		"--enable-quick=" + strconv.FormatBool(req.QuickEnabled), "--enable-full=" + strconv.FormatBool(req.FullEnabled)}
	if req.QuickHours > 0 && req.QuickHours <= 24*31 {
		args = append(args, "--quick-interval="+strconv.Itoa(req.QuickHours)+"h")
	}
	if req.FullHours > 0 && req.FullHours <= 24*366 {
		args = append(args, "--full-interval="+strconv.Itoa(req.FullHours)+"h")
	}
	// a clock time: postpone the next run to that time; with 24-h intervals it stays there
	for _, t := range []struct{ val, flag string }{{req.QuickTime, "--pause-quick="}, {req.FullTime, "--pause-full="}} {
		if t.val == "" {
			continue
		}
		d, err := untilClock(t.val, time.Now())
		if err != nil {
			writeErr(w, 400, err.Error())
			return
		}
		args = append(args, t.flag+strconv.Itoa(int(d.Minutes()))+"m")
	}
	logf("kopia %s: %v", k.ID, args)
	dropCLICache(k.ID)
	sendResult(w, run(r.Context(), 60*time.Second, kopiaEnv(k), bin, kopiaArgs(k, args...)...))
}

// kopiaServerAction runs snapshot/delete via the server API; done=false → fall back to the CLI.
func kopiaServerAction(r *http.Request, srv *kopiaSrv, k KopiaRepo, action, source, snapID string) (string, bool) {
	ctx := r.Context()
	switch action {
	case "snapshot":
		var src struct {
			Sources []struct {
				Source kopiaSource `json:"source"`
			} `json:"sources"`
		}
		if srv.call(ctx, "GET", "/api/v1/sources", nil, &src) != nil {
			return "", false
		}
		want := map[string]bool{}
		switch {
		case source != "":
			want[source] = true
		case len(k.Sources) > 0:
			for _, p := range k.Sources {
				want[p] = true
			}
		}
		var started []string
		for _, so := range src.Sources {
			if len(want) > 0 && !want[so.Source.Path] {
				continue
			}
			var res any
			if err := srv.call(ctx, "POST", "/api/v1/sources/upload?"+srcQuery(so.Source), map[string]any{}, &res); err != nil {
				return "", false
			}
			started = append(started, so.Source.Path)
			delete(want, so.Source.Path)
		}
		if len(started) == 0 || len(want) > 0 {
			return "", false // a source the server does not manage → CLI
		}
		logf("kopia %s: snapshot via server: %v", k.ID, started)
		return "Snapshot gestartet (läuft im Kopia-Server " + srv.Unit + "): " + strings.Join(started, ", "), true
	case "delete-snapshot":
		snaps, err := srv.serverSnapshots(ctx)
		if err != nil {
			return "", false
		}
		for _, m := range snaps {
			if m["id"] == snapID {
				so, _ := m["source"].(kopiaSource)
				var res any
				body := map[string]any{"source": so, "snapshotManifestIds": []string{snapID}, "deleteSourceAndPolicy": false}
				if err := srv.call(ctx, "POST", "/api/v1/snapshots/delete", body, &res); err != nil {
					return "", false
				}
				logf("kopia %s: snapshot %s deleted via server", k.ID, snapID)
				return "Snapshot gelöscht. Der Speicher wird bei der nächsten vollständigen Wartung freigegeben.", true
			}
		}
	}
	return "", false
}

var clockRe = regexp.MustCompile(`^([01]?[0-9]|2[0-3]):([0-5][0-9])$`)

// untilClock: time from now until the next HH:MM (local time of the server)
func untilClock(hhmm string, now time.Time) (time.Duration, error) {
	m := clockRe.FindStringSubmatch(hhmm)
	if m == nil {
		return 0, fmt.Errorf("Uhrzeit HH:MM erwartet")
	}
	h, _ := strconv.Atoi(m[1])
	mi, _ := strconv.Atoi(m[2])
	next := time.Date(now.Year(), now.Month(), now.Day(), h, mi, 0, 0, now.Location())
	if !next.After(now.Add(time.Minute)) {
		next = next.Add(24 * time.Hour)
	}
	return next.Sub(now), nil
}
