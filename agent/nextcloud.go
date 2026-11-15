package main

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var ncUserRe = regexp.MustCompile(`^[A-Za-z0-9_.@\- ]{1,64}$`)
var ncAppRe = regexp.MustCompile(`^[a-z0-9_]{1,64}$`)

func (s *Server) occ(r *http.Request, timeout time.Duration, env []string, args ...string) CmdResult {
	c := s.cfg.Get().Nextcloud
	full := append(append([]string{}, c.OCC[1:]...), args...)
	return run(r.Context(), timeout, env, c.OCC[0], full...)
}

func (s *Server) occJob(title string, args ...string) *Job {
	c := s.cfg.Get().Nextcloud
	full := append(append([]string{}, c.OCC[1:]...), args...)
	return s.jobs.Start("Nextcloud: "+title, 6*time.Hour, nil, c.OCC[0], full...)
}

func (s *Server) ncEnabled(w http.ResponseWriter) bool {
	c := s.cfg.Get().Nextcloud
	if !c.Enabled || len(c.OCC) == 0 {
		writeErr(w, 404, "nextcloud module disabled in agent config")
		return false
	}
	return true
}

// occJSON runs an occ command with --output=json and returns parsed JSON.
func (s *Server) occJSON(w http.ResponseWriter, r *http.Request, args ...string) {
	if !s.ncEnabled(w) {
		return
	}
	res := s.occ(r, 120*time.Second, nil, append(args, "--output=json")...)
	var v any
	if err := json.Unmarshal([]byte(strings.TrimSpace(res.Stdout)), &v); err != nil {
		sendResult(w, res)
		return
	}
	writeJSON(w, 200, v)
}

func (s *Server) handleNcStatus(w http.ResponseWriter, r *http.Request) { s.occJSON(w, r, "status") }
func (s *Server) handleNcApps(w http.ResponseWriter, r *http.Request)   { s.occJSON(w, r, "app:list") }
func (s *Server) handleNcUsers(w http.ResponseWriter, r *http.Request) {
	s.occJSON(w, r, "user:list", "--info", "--limit=2000")
}
func (s *Server) handleNcSetupChecks(w http.ResponseWriter, r *http.Request) {
	s.occJSON(w, r, "setupchecks")
}

var ncServerUpdRe = regexp.MustCompile(`(?m)^Nextcloud ([0-9][0-9.]*) is available`)
var ncAppUpdRe = regexp.MustCompile(`(?m)^Update for ([a-z0-9_]+) to version ([0-9][^ ]*) is available`)

// GET /api/nextcloud/updatecheck – raw output plus the parsed server/app updates
func (s *Server) handleNcUpdateCheck(w http.ResponseWriter, r *http.Request) {
	if !s.ncEnabled(w) {
		return
	}
	res := s.occ(r, 120*time.Second, nil, "update:check")
	server := ""
	if m := ncServerUpdRe.FindStringSubmatch(res.Stdout); m != nil {
		server = m[1]
	}
	apps := []map[string]string{}
	for _, m := range ncAppUpdRe.FindAllStringSubmatch(res.Stdout, -1) {
		apps = append(apps, map[string]string{"app": m[1], "version": m[2]})
	}
	installed := ""
	st := s.occ(r, 60*time.Second, nil, "status", "--output=json")
	var sj struct {
		Version string `json:"versionstring"`
	}
	if json.Unmarshal([]byte(st.Stdout), &sj) == nil {
		installed = sj.Version
	}
	writeJSON(w, 200, map[string]any{
		"cmd": res.Cmd, "exitCode": res.ExitCode, "stdout": res.Stdout, "stderr": res.Stderr,
		"server": server, "apps": apps, "updaterAvailable": s.ncUpdater() != nil, "installed": installed,
	})
}

// ncUpdater: command for updater/updater.phar next to occ, run like occ (e.g. sudo -u www-data php …)
func (s *Server) ncUpdater() []string {
	occ := s.cfg.Get().Nextcloud.OCC
	if len(occ) == 0 {
		return nil
	}
	path := occ[len(occ)-1]
	if filepath.Base(path) != "occ" {
		return nil
	}
	phar := filepath.Join(filepath.Dir(path), "updater", "updater.phar")
	if _, err := os.Stat(phar); err != nil {
		return nil
	}
	return append(append([]string{}, occ[:len(occ)-1]...), phar, "--no-interaction")
}

// GET /api/nextcloud/maintenance-window – start hour (UTC) of the 4-hour window for heavy background jobs
func (s *Server) handleNcMaintWindow(w http.ResponseWriter, r *http.Request) {
	if !s.ncEnabled(w) {
		return
	}
	res := s.occ(r, 30*time.Second, nil, "config:system:get", "maintenance_window_start")
	v := strings.TrimSpace(res.Stdout)
	out := map[string]any{"set": false}
	if n, err := strconv.Atoi(v); err == nil && res.ExitCode == 0 {
		out["set"] = true
		out["startUtc"] = n
	}
	writeJSON(w, 200, out)
}

// PUT /api/nextcloud/maintenance-window {"startUtc": 0..23} or {"startUtc": -1} to remove
func (s *Server) handleNcMaintWindowSet(w http.ResponseWriter, r *http.Request) {
	if !s.ncEnabled(w) {
		return
	}
	var req struct {
		StartUtc int `json:"startUtc"`
	}
	// 100 = no window (heavy jobs may run at any time), -1 = remove the setting
	if err := readJSON(r, &req); err != nil || req.StartUtc < -1 || (req.StartUtc > 23 && req.StartUtc != 100) {
		writeErr(w, 400, "startUtc must be 0..23, 100 or -1")
		return
	}
	logf("nextcloud maintenance window → %d", req.StartUtc)
	if req.StartUtc < 0 {
		sendResult(w, s.occ(r, 30*time.Second, nil, "config:system:delete", "maintenance_window_start"))
		return
	}
	sendResult(w, s.occ(r, 30*time.Second, nil, "config:system:set", "maintenance_window_start", "--type=integer", "--value="+strconv.Itoa(req.StartUtc)))
}

func (s *Server) handleNcBackgroundMode(w http.ResponseWriter, r *http.Request) {
	if !s.ncEnabled(w) {
		return
	}
	res := s.occ(r, 30*time.Second, nil, "config:app:get", "core", "backgroundjobs_mode")
	last := s.occ(r, 30*time.Second, nil, "config:app:get", "core", "lastcron")
	writeJSON(w, 200, map[string]string{"mode": strings.TrimSpace(res.Stdout), "lastCron": strings.TrimSpace(last.Stdout)})
}

// POST /api/nextcloud/action/{action}
func (s *Server) handleNcAction(w http.ResponseWriter, r *http.Request) {
	if !s.ncEnabled(w) {
		return
	}
	var req struct {
		User     string `json:"user"`
		App      string `json:"app"`
		Password string `json:"password"`
		Display  string `json:"displayName"`
		Email    string `json:"email"`
		Group    string `json:"group"`
		Mode     string `json:"mode"`
		Quota    string `json:"quota"`
	}
	if r.ContentLength > 0 {
		if err := readJSON(r, &req); err != nil {
			writeErr(w, 400, err.Error())
			return
		}
	}
	needUser := func() bool {
		if !ncUserRe.MatchString(req.User) || strings.HasPrefix(req.User, "-") {
			writeErr(w, 400, "invalid user")
			return false
		}
		return true
	}
	needApp := func() bool {
		if !ncAppRe.MatchString(req.App) {
			writeErr(w, 400, "invalid app id")
			return false
		}
		return true
	}
	act := r.PathValue("action")
	logf("nextcloud action %s user=%q app=%q", act, req.User, req.App)
	short := 120 * time.Second
	switch act {
	// --- maintenance ---
	case "maintenance-on":
		sendResult(w, s.occ(r, short, nil, "maintenance:mode", "--on"))
	case "maintenance-off":
		sendResult(w, s.occ(r, short, nil, "maintenance:mode", "--off"))
	case "repair":
		jobStarted(w, s.occJob("Reparatur", "maintenance:repair"))
	case "repair-expensive":
		jobStarted(w, s.occJob("Reparatur (umfangreich)", "maintenance:repair", "--include-expensive"))
	case "upgrade":
		jobStarted(w, s.occJob("Upgrade", "upgrade"))
	case "server-update":
		cmd := s.ncUpdater()
		if cmd == nil {
			writeErr(w, 404, "updater/updater.phar not found next to occ")
			return
		}
		jobStarted(w, s.jobs.Start("Nextcloud: Server-Update", 2*time.Hour, nil, cmd[0], cmd[1:]...))
	case "add-missing-indices":
		jobStarted(w, s.occJob("Fehlende Indizes", "db:add-missing-indices"))
	case "add-missing-columns":
		jobStarted(w, s.occJob("Fehlende Spalten", "db:add-missing-columns"))
	case "add-missing-primary-keys":
		jobStarted(w, s.occJob("Fehlende Primärschlüssel", "db:add-missing-primary-keys"))
	case "files-scan-all":
		jobStarted(w, s.occJob("Dateiscan (alle)", "files:scan", "--all"))
	case "files-cleanup":
		jobStarted(w, s.occJob("Dateicache aufräumen", "files:cleanup"))
	case "trashbin-cleanup":
		jobStarted(w, s.occJob("Papierkorb leeren (alle)", "trashbin:cleanup", "--all-users"))
	case "versions-cleanup":
		jobStarted(w, s.occJob("Versionen aufräumen", "versions:cleanup"))
	case "background-mode":
		if req.Mode != "cron" && req.Mode != "ajax" && req.Mode != "webcron" {
			writeErr(w, 400, "mode must be cron, ajax or webcron")
			return
		}
		sendResult(w, s.occ(r, short, nil, "background:"+req.Mode))
	// --- apps ---
	case "app-enable":
		if needApp() {
			sendResult(w, s.occ(r, 5*time.Minute, nil, "app:enable", req.App))
		}
	case "app-disable":
		if needApp() {
			sendResult(w, s.occ(r, short, nil, "app:disable", req.App))
		}
	case "app-update":
		if needApp() {
			jobStarted(w, s.occJob("App-Update "+req.App, "app:update", req.App))
		}
	case "app-update-all":
		jobStarted(w, s.occJob("Alle Apps aktualisieren", "app:update", "--all"))
	// --- users ---
	case "user-scan":
		if needUser() {
			jobStarted(w, s.occJob("Dateiscan "+req.User, "files:scan", "--", req.User))
		}
	case "user-enable":
		if needUser() {
			sendResult(w, s.occ(r, short, nil, "user:enable", "--", req.User))
		}
	case "user-disable":
		if needUser() {
			sendResult(w, s.occ(r, short, nil, "user:disable", "--", req.User))
		}
	case "user-quota":
		if needUser() {
			if !regexp.MustCompile(`^(none|default|[0-9]+ ?(B|KB|MB|GB|TB))$`).MatchString(req.Quota) {
				writeErr(w, 400, "invalid quota (e.g. 50 GB, none)")
				return
			}
			sendResult(w, s.occ(r, short, nil, "user:setting", "--", req.User, "files", "quota", req.Quota))
		}
	case "user-resetpassword":
		if needUser() {
			if len(req.Password) < 10 {
				writeErr(w, 400, "password too short")
				return
			}
			sendResult(w, s.occ(r, short, []string{"OC_PASS=" + req.Password}, "user:resetpassword", "--password-from-env", "--", req.User))
		}
	case "user-add":
		if needUser() {
			if len(req.Password) < 10 {
				writeErr(w, 400, "password too short")
				return
			}
			args := []string{"user:add", "--password-from-env"}
			if req.Display != "" {
				args = append(args, "--display-name="+req.Display)
			}
			if req.Group != "" {
				args = append(args, "--group="+req.Group)
			}
			if req.Email != "" {
				args = append(args, "--email="+req.Email)
			}
			args = append(args, "--", req.User)
			sendResult(w, s.occ(r, short, []string{"OC_PASS=" + req.Password}, args...))
		}
	case "user-delete":
		if needUser() {
			sendResult(w, s.occ(r, 10*time.Minute, nil, "user:delete", "--", req.User))
		}
	default:
		writeErr(w, 400, "unknown action")
	}
}
