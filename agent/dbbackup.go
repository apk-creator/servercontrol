package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Nextcloud → Wartung → Datenbank-Backup: a nightly, consistent dump of the Nextcloud database
// (mariadb-dump --single-transaction, zstd), run by a systemd timer so it also happens when
// the agent is not running. The dump lands in a folder that the Kopia snapshot of the data
// disk picks up afterwards. Restoring is deliberately left to the shell.

const (
	dbBackupConfig = "/etc/servercontrol/dbbackup.json"
	dbBackupScript = "/usr/local/sbin/servercontrol-dbbackup"
	dbBackupUnit   = "servercontrol-dbbackup"
)

type dbBackupConf struct {
	Enabled bool   `json:"enabled"`
	Time    string `json:"time"` // HH:MM server time
	Keep    int    `json:"keep"`
	Dir     string `json:"dir"`
}

func loadDBBackup() dbBackupConf {
	c := dbBackupConf{Time: "00:45", Keep: 3, Dir: "/srv/nextcloud-data/db-backup"}
	if b, err := os.ReadFile(dbBackupConfig); err == nil {
		_ = json.Unmarshal(b, &c)
	}
	return c
}

var (
	dbNameRe    = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)
	backupDirRe = regexp.MustCompile(`^/(srv|mnt|data|backup|var/backups|home)(/[A-Za-z0-9._-]+)+$`)
	dumpFileRe  = regexp.MustCompile(`^nextcloud-\d{4}-\d{2}-\d{2}_\d{4}\.sql\.(zst|gz)$`)
)

// database of the Nextcloud instance (from its config via occ)
func (s *Server) ncDatabase(r *http.Request) (dbtype, name string) {
	get := func(key string) string {
		return strings.TrimSpace(s.occ(r, 60*time.Second, nil, "config:system:get", key).Stdout)
	}
	dbtype, name = get("dbtype"), get("dbname")
	if name == "" {
		name = "nextcloud"
	}
	return
}

func dumpTool() string {
	for _, t := range []string{"mariadb-dump", "mysqldump"} {
		if has(t) {
			return t
		}
	}
	return ""
}

func dbBackupScriptText(c dbBackupConf, db string) string {
	comp, ext := "zstd -q -T0", "zst"
	if !has("zstd") {
		comp, ext = "gzip -c", "gz"
	}
	return fmt.Sprintf(`#!/bin/bash
# written by ServerControl – Nextcloud → Wartung → Datenbank-Backup
set -euo pipefail
DIR=%q
KEEP=%d
install -d -m 700 "$DIR"
TS=$(date +%%Y-%%m-%%d_%%H%%M)
OUT="$DIR/nextcloud-$TS.sql.%s"
DUMP=$(command -v mariadb-dump || command -v mysqldump)
# consistent without maintenance mode (InnoDB); written under a temporary name first
"$DUMP" --single-transaction --quick --routines --events --default-character-set=utf8mb4 %q | %s > "$OUT.part"
mv "$OUT.part" "$OUT"
# keep the newest $KEEP dumps
ls -1t "$DIR"/nextcloud-*.sql.* 2>/dev/null | grep -v '\.part$' | tail -n +$((KEEP + 1)) | xargs -r rm -f || true
echo "Dump: $OUT ($(du -h "$OUT" | cut -f1))"
`, c.Dir, c.Keep, ext, db, comp)
}

type dumpFile struct {
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	MTime int64  `json:"mtime"` // epoch ms
}

func listDumps(dir string) []dumpFile {
	ents, _ := os.ReadDir(dir)
	var out []dumpFile
	for _, e := range ents {
		if !dumpFileRe.MatchString(e.Name()) {
			continue
		}
		if fi, err := e.Info(); err == nil {
			out = append(out, dumpFile{e.Name(), fi.Size(), fi.ModTime().UnixMilli()})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].MTime > out[j].MTime })
	return out
}

func unitProps(r *http.Request, unit string, props ...string) map[string]string {
	res := run(r.Context(), 10*time.Second, nil, "systemctl", "show", unit, "-p", strings.Join(props, ","))
	m := map[string]string{}
	for _, l := range strings.Split(res.Stdout, "\n") {
		if k, v, ok := strings.Cut(l, "="); ok {
			m[k] = v
		}
	}
	return m
}

// systemd prints timestamps like "Mon 2026-11-16 00:45:03 CET"; empty/"n/a" = never
func systemdTime(v string) int64 {
	v = strings.TrimSpace(v)
	if v == "" || v == "n/a" {
		return 0
	}
	f := strings.Fields(v)
	if len(f) >= 3 {
		if t, err := time.ParseInLocation("2006-01-02 15:04:05", f[1]+" "+f[2], time.Local); err == nil {
			return t.UnixMilli()
		}
	}
	return 0
}

// GET /api/nextcloud/dbbackup
func (s *Server) handleDBBackup(w http.ResponseWriter, r *http.Request) {
	c := loadDBBackup()
	dbtype, db := s.ncDatabase(r)
	svc := unitProps(r, dbBackupUnit+".service", "Result", "ExecMainStatus", "ExecMainExitTimestamp", "ActiveState")
	tim := unitProps(r, dbBackupUnit+".timer", "UnitFileState", "ActiveState", "NextElapseUSecRealtime", "LastTriggerUSec")
	_, scriptErr := os.Stat(dbBackupScript)
	files := listDumps(c.Dir)
	var lastOK int64
	if len(files) > 0 {
		lastOK = files[0].MTime
	}
	writeJSON(w, 200, map[string]any{
		"config": c, "dbtype": dbtype, "database": db, "tool": dumpTool(), "zstd": has("zstd"),
		"installed": scriptErr == nil, "timerEnabled": tim["UnitFileState"] == "enabled",
		"nextRun": systemdTime(tim["NextElapseUSecRealtime"]), "lastRun": systemdTime(svc["ExecMainExitTimestamp"]),
		"lastResult": svc["Result"], "lastExit": svc["ExecMainStatus"], "running": svc["ActiveState"] == "activating",
		"files": files, "lastSuccess": lastOK,
		"stale": c.Enabled && (lastOK == 0 || time.Since(time.UnixMilli(lastOK)) > 26*time.Hour),
	})
}

// PUT /api/nextcloud/dbbackup {"enabled":true,"time":"00:45","keep":3,"dir":"/srv/nextcloud-data/db-backup"}
func (s *Server) handleDBBackupSet(w http.ResponseWriter, r *http.Request) {
	var c dbBackupConf
	if err := readJSON(r, &c); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if !clockRe.MatchString(c.Time) {
		writeErr(w, 400, "Uhrzeit HH:MM erwartet")
		return
	}
	if c.Keep < 1 || c.Keep > 30 {
		writeErr(w, 400, "Anzahl 1 bis 30")
		return
	}
	if !backupDirRe.MatchString(c.Dir) || filepath.Clean(c.Dir) != c.Dir || strings.Contains(c.Dir, "..") {
		writeErr(w, 400, "Ordner muss unter /srv, /mnt, /data, /backup, /var/backups oder /home liegen")
		return
	}
	dbtype, db := s.ncDatabase(r)
	if dbtype != "mysql" {
		writeErr(w, 400, "Nur für MariaDB/MySQL (Nextcloud nutzt „"+dbtype+"“)")
		return
	}
	if !dbNameRe.MatchString(db) {
		writeErr(w, 400, "ungültiger Datenbankname: "+db)
		return
	}
	if dumpTool() == "" {
		writeErr(w, 400, "mariadb-dump ist nicht installiert")
		return
	}
	if err := os.WriteFile(dbBackupScript, []byte(dbBackupScriptText(c, db)), 0o750); err != nil {
		writeErr(w, 500, "Skript: "+err.Error())
		return
	}
	h, m, _ := strings.Cut(c.Time, ":")
	hh, _ := strconv.Atoi(h)
	svc := "[Unit]\nDescription=ServerControl: Nextcloud-Datenbank-Backup\nAfter=mariadb.service mysql.service\n\n" +
		"[Service]\nType=oneshot\nExecStart=" + dbBackupScript + "\nNice=10\nIOSchedulingClass=idle\n"
	tim := fmt.Sprintf("[Unit]\nDescription=ServerControl: Nextcloud-Datenbank-Backup täglich\n\n"+
		"[Timer]\nOnCalendar=*-*-* %02d:%s:00\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n", hh, m)
	for name, content := range map[string]string{dbBackupUnit + ".service": svc, dbBackupUnit + ".timer": tim} {
		if err := os.WriteFile("/etc/systemd/system/"+name, []byte(content), 0o644); err != nil {
			writeErr(w, 500, name+": "+err.Error())
			return
		}
	}
	run(r.Context(), 30*time.Second, nil, "systemctl", "daemon-reload")
	var res CmdResult
	if c.Enabled {
		res = run(r.Context(), 30*time.Second, nil, "systemctl", "enable", "--now", dbBackupUnit+".timer")
	} else {
		res = run(r.Context(), 30*time.Second, nil, "systemctl", "disable", "--now", dbBackupUnit+".timer")
	}
	if res.ExitCode != 0 {
		writeErr(w, 500, strings.TrimSpace(res.Stderr))
		return
	}
	b, _ := json.MarshalIndent(c, "", "  ")
	_ = os.WriteFile(dbBackupConfig, b, 0o600)
	logf("dbbackup: enabled=%v time=%s keep=%d dir=%s", c.Enabled, c.Time, c.Keep, c.Dir)
	writeJSON(w, 200, map[string]any{"ok": true, "stdout": "Gespeichert."})
}

// POST /api/nextcloud/dbbackup/run – dump now (as a job; same service as the timer)
func (s *Server) handleDBBackupRun(w http.ResponseWriter, r *http.Request) {
	if _, err := os.Stat(dbBackupScript); err != nil {
		writeErr(w, 400, "Erst einrichten (Einstellungen speichern)")
		return
	}
	// same service as the timer; afterwards its output from the journal
	jobStarted(w, s.jobs.Start("Nextcloud-Datenbank sichern", 3*time.Hour, nil, "bash", "-c",
		"systemctl start "+dbBackupUnit+".service; rc=$?; journalctl -u "+dbBackupUnit+".service -n 5 --no-pager -o cat; exit $rc"))
}
