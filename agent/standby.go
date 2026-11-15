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

// Auto standby of the HDDs, managed either with hdparm (timer inside the disk, set at boot
// by our own unit) or with hd-idle (daemon that watches the I/O). Disks are addressed by
// their /dev/disk/by-id name, so changing sdX names after a reboot does not matter.

const (
	standbyConfig = "/etc/servercontrol/standby.json"
	standbyScript = "/usr/local/sbin/servercontrol-standby"
	standbyUnit   = "servercontrol-standby.service"
	hdIdleDefault = "/etc/default/hd-idle"
)

type standbyConf struct {
	Method string         `json:"method"` // "hdparm", "hdidle" or "" (off)
	Disks  map[string]int `json:"disks"`  // by-id name → seconds (0 = never)
}

func loadStandby() standbyConf {
	c := standbyConf{Disks: map[string]int{}}
	if b, err := os.ReadFile(standbyConfig); err == nil {
		_ = json.Unmarshal(b, &c)
	}
	if c.Disks == nil {
		c.Disks = map[string]int{}
	}
	return c
}

// byIDNames maps sdX → its best /dev/disk/by-id name (ata-… preferred, no partitions).
func byIDNames() map[string]string {
	out := map[string]string{}
	ents, _ := os.ReadDir("/dev/disk/by-id")
	names := []string{}
	for _, e := range ents {
		names = append(names, e.Name())
	}
	sort.Slice(names, func(i, j int) bool { return rankID(names[i]) < rankID(names[j]) })
	for _, n := range names {
		if strings.Contains(n, "-part") {
			continue
		}
		t, err := filepath.EvalSymlinks("/dev/disk/by-id/" + n)
		if err != nil {
			continue
		}
		dev := filepath.Base(t)
		if _, ok := out[dev]; !ok {
			out[dev] = n
		}
	}
	return out
}

func rankID(n string) int {
	switch {
	case strings.HasPrefix(n, "ata-"):
		return 0
	case strings.HasPrefix(n, "scsi-"), strings.HasPrefix(n, "usb-"):
		return 1
	case strings.HasPrefix(n, "wwn-"):
		return 2
	}
	return 3
}

// hdparmCode turns seconds into the value for hdparm -S (1–240: ×5 s, 241–251: ×30 min).
func hdparmCode(secs int) int {
	switch {
	case secs <= 0:
		return 0
	case secs <= 1200:
		return (secs + 4) / 5
	default:
		n := (secs + 1799) / 1800
		if n > 11 {
			n = 11
		}
		return 240 + n
	}
}

var hdparmUnitRe = regexp.MustCompile(`hdparm[^\n]*\s-S\s*[0-9]`)
var unitNameRe = regexp.MustCompile(`^[A-Za-z0-9@._-]+\.service$`)

// foreignStandbyUnits: own systemd units of the admin that set hdparm -S (e.g. hdd-spindown.service).
func foreignStandbyUnits(r *http.Request) []map[string]any {
	var out []map[string]any
	files, _ := filepath.Glob("/etc/systemd/system/*.service")
	for _, f := range files {
		name := filepath.Base(f)
		if name == standbyUnit {
			continue
		}
		b, err := os.ReadFile(f)
		if err != nil || !hdparmUnitRe.Match(b) {
			continue
		}
		act := strings.TrimSpace(run(r.Context(), 5*time.Second, nil, "systemctl", "is-enabled", name).Stdout)
		out = append(out, map[string]any{"unit": name, "enabled": act == "enabled"})
	}
	return out
}

func hdparmState(r *http.Request, dev string) string {
	res := run(r.Context(), 10*time.Second, nil, "hdparm", "-C", "/dev/"+dev)
	switch {
	case strings.Contains(res.Stdout, "standby"):
		return "standby"
	case strings.Contains(res.Stdout, "sleeping"):
		return "sleeping"
	case strings.Contains(res.Stdout, "active"):
		return "active"
	}
	return "unknown"
}

func has(bin string) bool {
	for _, d := range []string{"/usr/sbin/", "/usr/bin/", "/sbin/", "/bin/", "/usr/local/sbin/"} {
		if _, err := os.Stat(d + bin); err == nil {
			return true
		}
	}
	return false
}

// GET /api/storage/standby
func handleStandby(w http.ResponseWriter, r *http.Request) {
	c := loadStandby()
	ids := byIDNames()
	disks := []map[string]any{}
	for _, d := range rotatingDisks() {
		id := ids[d]
		model, _ := os.ReadFile("/sys/block/" + d + "/device/model")
		size := int64(0)
		if b, err := os.ReadFile("/sys/block/" + d + "/size"); err == nil {
			n, _ := strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
			size = n * 512
		}
		e := map[string]any{"device": "/dev/" + d, "id": id, "model": strings.TrimSpace(string(model)), "size": size, "state": hdparmState(r, d)}
		if v, ok := c.Disks[id]; ok && id != "" {
			e["timeout"] = v
		}
		disks = append(disks, e)
	}
	hdIdleActive := strings.TrimSpace(run(r.Context(), 5*time.Second, nil, "systemctl", "is-active", "hd-idle").Stdout) == "active"
	writeJSON(w, 200, map[string]any{
		"method": c.Method, "disks": disks,
		"hdparm": has("hdparm"), "hdidle": has("hd-idle"), "hdidleActive": hdIdleActive,
		"foreignUnits": foreignStandbyUnits(r),
	})
}

// PUT /api/storage/standby {"method":"hdparm","disks":{"ata-…":1200},"disableUnits":["hdd-spindown.service"]}
func handleStandbySet(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Method       string         `json:"method"`
		Disks        map[string]int `json:"disks"`
		DisableUnits []string       `json:"disableUnits"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if req.Method != "hdparm" && req.Method != "hdidle" && req.Method != "" {
		writeErr(w, 400, "method: hdparm, hdidle or empty")
		return
	}
	known := map[string]bool{}
	for _, id := range byIDNames() {
		known[id] = true
	}
	for id, secs := range req.Disks {
		if !known[id] || strings.ContainsAny(id, "/ ") {
			writeErr(w, 400, "unbekanntes Laufwerk: "+id)
			return
		}
		if secs < 0 || secs > 6*3600 {
			writeErr(w, 400, "Zeit 0 bis 6 Stunden")
			return
		}
	}
	if req.Method == "hdparm" && !has("hdparm") {
		writeErr(w, 400, "hdparm ist nicht installiert")
		return
	}
	if req.Method == "hdidle" && !has("hd-idle") {
		writeErr(w, 400, "hd-idle ist nicht installiert")
		return
	}
	foreign := map[string]bool{}
	for _, u := range foreignStandbyUnits(r) {
		foreign[u["unit"].(string)] = true
	}
	ctx := r.Context()
	var log []string
	step := func(name string, args ...string) {
		res := run(ctx, 60*time.Second, nil, name, args...)
		if res.ExitCode != 0 {
			log = append(log, strings.TrimSpace(name+" "+strings.Join(args, " ")+": "+res.Stderr))
		}
	}
	for _, u := range req.DisableUnits {
		if !unitNameRe.MatchString(u) || !foreign[u] {
			writeErr(w, 400, "Dienst nicht erlaubt: "+u)
			return
		}
		step("systemctl", "disable", "--now", u)
	}
	ids := make([]string, 0, len(req.Disks))
	for id := range req.Disks {
		ids = append(ids, id)
	}
	sort.Strings(ids)

	// hdparm: script + unit (each line on its own, so one missing disk does not stop the rest)
	var sb strings.Builder
	sb.WriteString("#!/bin/sh\n# written by ServerControl – Speicher → Auto-Standby\nudevadm settle -t 30 2>/dev/null || true\n")
	for _, id := range ids {
		code := hdparmCode(req.Disks[id])
		if req.Method != "hdparm" {
			code = 0 // hd-idle (or off) decides – disable the disk's own timer
		}
		fmt.Fprintf(&sb, "hdparm -S %d /dev/disk/by-id/%s >/dev/null 2>&1 || true\n", code, id)
	}
	if err := os.WriteFile(standbyScript, []byte(sb.String()), 0o755); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	unit := "[Unit]\nDescription=ServerControl: HDD auto standby (hdparm)\nAfter=local-fs.target systemd-udev-settle.service\n\n" +
		"[Service]\nType=oneshot\nRemainAfterExit=yes\nExecStart=" + standbyScript + "\n\n[Install]\nWantedBy=multi-user.target\n"
	if err := os.WriteFile("/etc/systemd/system/"+standbyUnit, []byte(unit), 0o644); err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	step("systemctl", "daemon-reload")
	step(standbyScript) // apply now

	switch req.Method {
	case "hdparm":
		step("systemctl", "enable", standbyUnit)
		if has("hd-idle") {
			step("systemctl", "disable", "--now", "hd-idle")
		}
	case "hdidle":
		step("systemctl", "enable", standbyUnit) // keeps the disks' own timers off after reboots
		opts := "-i 0"
		for _, id := range ids {
			if s := req.Disks[id]; s > 0 {
				opts += fmt.Sprintf(" -a /dev/disk/by-id/%s -i %d", id, s)
			}
		}
		conf := "# written by ServerControl – Speicher → Auto-Standby\nSTART_HD_IDLE=true\nHD_IDLE_OPTS=\"" + opts + "\"\n"
		if err := os.WriteFile(hdIdleDefault, []byte(conf), 0o644); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		step("systemctl", "enable", "hd-idle")
		step("systemctl", "restart", "hd-idle")
	default:
		step("systemctl", "disable", standbyUnit)
		if has("hd-idle") {
			step("systemctl", "disable", "--now", "hd-idle")
		}
	}
	b, _ := json.MarshalIndent(standbyConf{Method: req.Method, Disks: req.Disks}, "", "  ")
	_ = os.WriteFile(standbyConfig, b, 0o600)
	logf("standby: method=%q disks=%d disabled=%v", req.Method, len(req.Disks), req.DisableUnits)
	msg := "Auto-Standby gespeichert."
	if len(log) > 0 {
		msg += " Hinweise: " + strings.Join(log, "; ")
	}
	writeJSON(w, 200, map[string]any{"ok": true, "stdout": msg})
}

// POST /api/storage/standby/sleep {"device":"/dev/sdh"} – send a disk to standby now
func handleStandbySleep(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Device string `json:"device"`
	}
	if err := readJSON(r, &req); err != nil || !physDiskRe.MatchString(strings.TrimPrefix(req.Device, "/dev/")) {
		writeErr(w, 400, "invalid device")
		return
	}
	sendResult(w, run(r.Context(), 30*time.Second, nil, "hdparm", "-y", req.Device))
}

// POST /api/storage/standby/install-hdidle
func (s *Server) handleStandbyInstall(w http.ResponseWriter, r *http.Request) {
	jobStarted(w, s.jobs.Start("hd-idle installieren", 10*time.Minute, []string{"DEBIAN_FRONTEND=noninteractive"},
		"apt-get", "install", "-y", "--no-install-recommends", "hd-idle", "hdparm"))
}
