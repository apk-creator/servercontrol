package main

import (
	"bufio"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// ======================= Overview =======================

type cpuSample struct{ idle, total uint64 }

func readCPU() cpuSample {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return cpuSample{}
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	if sc.Scan() {
		fields := strings.Fields(sc.Text())[1:]
		var s cpuSample
		for i, v := range fields {
			n, _ := strconv.ParseUint(v, 10, 64)
			s.total += n
			if i == 3 || i == 4 { // idle + iowait
				s.idle += n
			}
		}
		return s
	}
	return cpuSample{}
}

func meminfo() map[string]uint64 {
	m := map[string]uint64{}
	b, _ := os.ReadFile("/proc/meminfo")
	for _, l := range strings.Split(string(b), "\n") {
		f := strings.Fields(l)
		if len(f) >= 2 {
			n, _ := strconv.ParseUint(f[1], 10, 64)
			m[strings.TrimSuffix(f[0], ":")] = n * 1024
		}
	}
	return m
}

func osRelease() string {
	b, _ := os.ReadFile("/etc/os-release")
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "PRETTY_NAME=") {
			return strings.Trim(strings.TrimPrefix(l, "PRETTY_NAME="), `"`)
		}
	}
	return "Linux"
}

type Temp struct {
	Chip  string  `json:"chip"`
	Label string  `json:"label"`
	C     float64 `json:"celsius"`
}

func temperatures() []Temp {
	out := []Temp{}
	inputs, _ := filepath.Glob("/sys/class/hwmon/hwmon*/temp*_input")
	for _, in := range inputs {
		dir := filepath.Dir(in)
		chip, _ := os.ReadFile(filepath.Join(dir, "name"))
		v, err := os.ReadFile(in)
		if err != nil {
			continue
		}
		milli, err := strconv.Atoi(strings.TrimSpace(string(v)))
		if err != nil {
			continue
		}
		label := strings.TrimSuffix(filepath.Base(in), "_input")
		if lb, err := os.ReadFile(strings.TrimSuffix(in, "_input") + "_label"); err == nil {
			label = strings.TrimSpace(string(lb))
		}
		out = append(out, Temp{Chip: strings.TrimSpace(string(chip)), Label: label, C: float64(milli) / 1000})
	}
	return out
}

func handleOverview(w http.ResponseWriter, r *http.Request) {
	a := readCPU()
	time.Sleep(300 * time.Millisecond)
	b := readCPU()
	cpu := 0.0
	if dt := b.total - a.total; dt > 0 {
		cpu = 100 * (1 - float64(b.idle-a.idle)/float64(dt))
	}
	var si syscall.Sysinfo_t
	_ = syscall.Sysinfo(&si)
	load, _ := os.ReadFile("/proc/loadavg")
	lf := strings.Fields(string(load))
	host, _ := os.Hostname()
	kern, _ := os.ReadFile("/proc/sys/kernel/osrelease")
	mi := meminfo()
	_, rebootErr := os.Stat("/var/run/reboot-required")
	failed := run(r.Context(), 10*time.Second, nil, "systemctl", "list-units", "--state=failed", "--no-legend", "--plain")
	nFailed := 0
	for _, l := range strings.Split(strings.TrimSpace(failed.Stdout), "\n") {
		if strings.TrimSpace(l) != "" {
			nFailed++
		}
	}
	model := ""
	if ci, err := os.ReadFile("/proc/cpuinfo"); err == nil {
		for _, l := range strings.Split(string(ci), "\n") {
			if strings.HasPrefix(l, "model name") {
				model = strings.TrimSpace(strings.SplitN(l, ":", 2)[1])
				break
			}
		}
	}
	loads := []string{"0", "0", "0"}
	if len(lf) >= 3 {
		loads = lf[:3]
	}
	writeJSON(w, 200, map[string]any{
		"hostname":       host,
		"os":             osRelease(),
		"kernel":         strings.TrimSpace(string(kern)),
		"cpuModel":       model,
		"cpuCores":       countCPUs(),
		"cpuPercent":     cpu,
		"load":           loads,
		"uptimeSeconds":  si.Uptime,
		"memTotal":       mi["MemTotal"],
		"memAvailable":   mi["MemAvailable"],
		"swapTotal":      mi["SwapTotal"],
		"swapFree":       mi["SwapFree"],
		"temperatures":   temperatures(),
		"rebootRequired": rebootErr == nil,
		"failedUnits":    nFailed,
		"time":           time.Now().Format(time.RFC3339),
	})
}

func countCPUs() int {
	b, _ := os.ReadFile("/proc/cpuinfo")
	return strings.Count(string(b), "processor\t:")
}

// ======================= Services (systemd) =======================

var unitRe = regexp.MustCompile(`^[A-Za-z0-9@._:\\-]+$`)

func handleServices(w http.ResponseWriter, r *http.Request) {
	res := run(r.Context(), 15*time.Second, nil, "systemctl", "list-units", "--type=service", "--all", "--output=json", "--no-pager")
	if res.ExitCode != 0 {
		sendResult(w, res)
		return
	}
	var units []map[string]any
	_ = json.Unmarshal([]byte(res.Stdout), &units)
	// add enablement state
	uf := run(r.Context(), 15*time.Second, nil, "systemctl", "list-unit-files", "--type=service", "--output=json", "--no-pager")
	var files []map[string]any
	_ = json.Unmarshal([]byte(uf.Stdout), &files)
	en := map[string]any{}
	for _, f := range files {
		en[toStr(f["unit_file"])] = f["state"]
	}
	for _, u := range units {
		u["enabled"] = en[toStr(u["unit"])]
	}
	writeJSON(w, 200, units)
}

func toStr(v any) string {
	s, _ := v.(string)
	return s
}

func handleServiceStatus(w http.ResponseWriter, r *http.Request) {
	u := r.PathValue("unit")
	if !unitRe.MatchString(u) {
		writeErr(w, 400, "invalid unit name")
		return
	}
	res := run(r.Context(), 15*time.Second, nil, "systemctl", "show", u, "--no-pager",
		"-p", "Id,Description,ActiveState,SubState,LoadState,UnitFileState,MainPID,ExecMainStartTimestamp,MemoryCurrent,CPUUsageNSec,TasksCurrent,FragmentPath,NRestarts")
	props := map[string]string{}
	for _, l := range strings.Split(res.Stdout, "\n") {
		if k, v, ok := strings.Cut(l, "="); ok {
			props[k] = v
		}
	}
	st := run(r.Context(), 15*time.Second, nil, "systemctl", "status", u, "--no-pager", "-n", "30")
	writeJSON(w, 200, map[string]any{"properties": props, "status": st.Stdout + st.Stderr})
}

var serviceActions = map[string]bool{"start": true, "stop": true, "restart": true, "reload": true, "enable": true, "disable": true, "mask": true, "unmask": true, "reset-failed": true}

func handleServiceAction(w http.ResponseWriter, r *http.Request) {
	u, act := r.PathValue("unit"), r.PathValue("action")
	if !unitRe.MatchString(u) || !serviceActions[act] {
		writeErr(w, 400, "invalid unit or action")
		return
	}
	if u == "servercontrol-agent.service" && (act == "stop" || act == "disable" || act == "mask") {
		writeErr(w, 400, "refusing to stop the agent itself")
		return
	}
	logf("service %s %s", act, u)
	sendResult(w, run(r.Context(), 90*time.Second, nil, "systemctl", act, u))
}

// ======================= Logs (journal) =======================

func handleLogs(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	n, _ := strconv.Atoi(q.Get("lines"))
	if n <= 0 || n > 5000 {
		n = 300
	}
	args := []string{"--no-pager", "-o", "json", "-n", strconv.Itoa(n), "--output-fields=MESSAGE,PRIORITY,_SYSTEMD_UNIT,SYSLOG_IDENTIFIER,__REALTIME_TIMESTAMP,_HOSTNAME"}
	if u := q.Get("unit"); u != "" {
		if !unitRe.MatchString(u) {
			writeErr(w, 400, "invalid unit")
			return
		}
		args = append(args, "-u", u)
	}
	if p := q.Get("priority"); p != "" {
		if _, err := strconv.Atoi(p); err != nil || len(p) != 1 {
			writeErr(w, 400, "invalid priority")
			return
		}
		args = append(args, "-p", p)
	}
	if s := q.Get("since"); s != "" {
		if !regexp.MustCompile(`^[0-9 :\-a-z]+$`).MatchString(s) {
			writeErr(w, 400, "invalid since")
			return
		}
		args = append(args, "--since", s)
	}
	if g := q.Get("grep"); g != "" {
		args = append(args, "--grep", g, "--case-sensitive=false")
	}
	if q.Get("boot") == "1" {
		args = append(args, "-b")
	}
	res := run(r.Context(), 30*time.Second, nil, "journalctl", args...)
	type entry struct {
		Time     int64  `json:"time"`
		Priority string `json:"priority"`
		Unit     string `json:"unit"`
		Ident    string `json:"ident"`
		Message  string `json:"message"`
	}
	out := []entry{}
	for _, l := range strings.Split(res.Stdout, "\n") {
		if l == "" {
			continue
		}
		var m map[string]any
		if json.Unmarshal([]byte(l), &m) != nil {
			continue
		}
		ts, _ := strconv.ParseInt(toStr(m["__REALTIME_TIMESTAMP"]), 10, 64)
		msg := toStr(m["MESSAGE"])
		if msg == "" {
			if arr, ok := m["MESSAGE"].([]any); ok { // binary message as byte array
				b := make([]byte, 0, len(arr))
				for _, x := range arr {
					if f, ok := x.(float64); ok {
						b = append(b, byte(f))
					}
				}
				msg = string(b)
			}
		}
		out = append(out, entry{Time: ts / 1000, Priority: toStr(m["PRIORITY"]), Unit: toStr(m["_SYSTEMD_UNIT"]), Ident: toStr(m["SYSLOG_IDENTIFIER"]), Message: msg})
	}
	writeJSON(w, 200, out)
}

// ======================= Storage =======================

type Mount struct {
	Device string `json:"device"`
	Mount  string `json:"mount"`
	FSType string `json:"fstype"`
	Total  uint64 `json:"total"`
	Used   uint64 `json:"used"`
	Free   uint64 `json:"free"`
}

var skipFS = map[string]bool{"proc": true, "sysfs": true, "devtmpfs": true, "devpts": true, "tmpfs": true, "cgroup2": true, "cgroup": true,
	"securityfs": true, "pstore": true, "bpf": true, "debugfs": true, "tracefs": true, "mqueue": true, "hugetlbfs": true, "configfs": true,
	"fusectl": true, "autofs": true, "efivarfs": true, "binfmt_misc": true, "overlay": true, "nsfs": true, "ramfs": true, "rpc_pipefs": true}

func mounts() []Mount {
	b, _ := os.ReadFile("/proc/mounts")
	seen := map[string]bool{}
	var out []Mount
	for _, l := range strings.Split(string(b), "\n") {
		f := strings.Fields(l)
		if len(f) < 3 || skipFS[f[2]] || seen[f[1]] || strings.HasPrefix(f[1], "/snap/") {
			continue
		}
		seen[f[1]] = true
		var st syscall.Statfs_t
		if syscall.Statfs(f[1], &st) != nil || st.Blocks == 0 {
			continue
		}
		bs := uint64(st.Bsize)
		total, free, avail := st.Blocks*bs, st.Bfree*bs, st.Bavail*bs
		out = append(out, Mount{Device: f[0], Mount: f[1], FSType: f[2], Total: total, Used: total - free, Free: avail})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Mount < out[j].Mount })
	return out
}

func handleStorage(w http.ResponseWriter, r *http.Request) {
	lsblk := run(r.Context(), 15*time.Second, nil, "lsblk", "-J", "-b", "-o", "NAME,PATH,SIZE,TYPE,FSTYPE,MOUNTPOINTS,MODEL,SERIAL,ROTA,TRAN,LABEL,UUID")
	var blk any
	_ = json.Unmarshal([]byte(lsblk.Stdout), &blk)
	writeJSON(w, 200, map[string]any{"mounts": mounts(), "blockdevices": blk})
}

var devRe = regexp.MustCompile(`^/dev/(sd[a-z]+|nvme[0-9]+n[0-9]+|nvme[0-9]+|hd[a-z]+|vd[a-z]+)$`)

func handleSmart(w http.ResponseWriter, r *http.Request) {
	dev := r.URL.Query().Get("device")
	if !devRe.MatchString(dev) {
		writeErr(w, 400, "invalid device")
		return
	}
	res := run(r.Context(), 30*time.Second, nil, "smartctl", "-a", "-j", dev)
	// smartctl uses bit-coded exit codes; JSON is valid as long as bits 0/1 are not set
	var j any
	if err := json.Unmarshal([]byte(res.Stdout), &j); err != nil {
		writeJSON(w, 502, res)
		return
	}
	writeJSON(w, 200, j)
}

func handleSmartTest(w http.ResponseWriter, r *http.Request) {
	dev := r.URL.Query().Get("device")
	typ := r.URL.Query().Get("type")
	if !devRe.MatchString(dev) || (typ != "short" && typ != "long") {
		writeErr(w, 400, "invalid device or type")
		return
	}
	sendResult(w, run(r.Context(), 30*time.Second, nil, "smartctl", "-t", typ, dev))
}

// ======================= Network =======================

func handleNetwork(w http.ResponseWriter, r *http.Request) {
	addr := run(r.Context(), 10*time.Second, nil, "ip", "-j", "-s", "addr")
	var ifaces any
	_ = json.Unmarshal([]byte(addr.Stdout), &ifaces)
	route := run(r.Context(), 10*time.Second, nil, "ip", "-j", "route")
	var routes any
	_ = json.Unmarshal([]byte(route.Stdout), &routes)
	dev, _ := os.ReadFile("/proc/net/dev")
	type counters struct {
		RX uint64 `json:"rxBytes"`
		TX uint64 `json:"txBytes"`
	}
	c := map[string]counters{}
	for _, l := range strings.Split(string(dev), "\n")[2:] {
		name, rest, ok := strings.Cut(l, ":")
		if !ok {
			continue
		}
		f := strings.Fields(rest)
		if len(f) >= 9 {
			rx, _ := strconv.ParseUint(f[0], 10, 64)
			tx, _ := strconv.ParseUint(f[8], 10, 64)
			c[strings.TrimSpace(name)] = counters{rx, tx}
		}
	}
	wg := run(r.Context(), 10*time.Second, nil, "wg", "show", "all", "dump")
	writeJSON(w, 200, map[string]any{"interfaces": ifaces, "routes": routes, "counters": c, "wireguard": wg.Stdout})
}

// ======================= Processes =======================

func handleProcesses(w http.ResponseWriter, r *http.Request) {
	sortBy := "-%cpu"
	if r.URL.Query().Get("sort") == "mem" {
		sortBy = "-rss"
	}
	res := run(r.Context(), 10*time.Second, nil, "ps", "-eo", "pid=,user=,pcpu=,pmem=,rss=,etimes=,comm=,args=", "--sort="+sortBy)
	type proc struct {
		PID     int     `json:"pid"`
		User    string  `json:"user"`
		CPU     float64 `json:"cpu"`
		Mem     float64 `json:"mem"`
		RSS     uint64  `json:"rssKb"`
		Elapsed int64   `json:"elapsedSeconds"`
		Comm    string  `json:"comm"`
		Args    string  `json:"args"`
	}
	out := []proc{}
	for i, l := range strings.Split(res.Stdout, "\n") {
		if i >= 150 {
			break
		}
		f := strings.Fields(l)
		if len(f) < 7 {
			continue
		}
		var p proc
		p.PID, _ = strconv.Atoi(f[0])
		p.User = f[1]
		p.CPU, _ = strconv.ParseFloat(f[2], 64)
		p.Mem, _ = strconv.ParseFloat(f[3], 64)
		p.RSS, _ = strconv.ParseUint(f[4], 10, 64)
		p.Elapsed, _ = strconv.ParseInt(f[5], 10, 64)
		p.Comm = f[6]
		if len(f) > 7 {
			p.Args = strings.Join(f[7:], " ")
		}
		out = append(out, p)
	}
	writeJSON(w, 200, out)
}

func handleKill(w http.ResponseWriter, r *http.Request) {
	var req struct {
		PID    int    `json:"pid"`
		Signal string `json:"signal"`
	}
	if err := readJSON(r, &req); err != nil || req.PID <= 1 {
		writeErr(w, 400, "invalid request")
		return
	}
	sig := map[string]string{"TERM": "-TERM", "KILL": "-KILL", "HUP": "-HUP", "": "-TERM"}[req.Signal]
	if sig == "" {
		writeErr(w, 400, "invalid signal")
		return
	}
	if req.PID == os.Getpid() {
		writeErr(w, 400, "refusing to kill the agent")
		return
	}
	logf("kill %s %d", sig, req.PID)
	sendResult(w, run(r.Context(), 10*time.Second, nil, "kill", sig, strconv.Itoa(req.PID)))
}

// ======================= Updates (apt) =======================

func (s *Server) handleUpdatesList(w http.ResponseWriter, r *http.Request) {
	res := run(r.Context(), 60*time.Second, nil, "apt", "list", "--upgradable")
	type pkg struct {
		Name, NewVersion, OldVersion, Origin string
		Security                             bool
	}
	out := []pkg{}
	re := regexp.MustCompile(`^([^/]+)/(\S+) (\S+) \S+ \[upgradable from: ([^\]]+)\]`)
	for _, l := range strings.Split(res.Stdout, "\n") {
		if m := re.FindStringSubmatch(l); m != nil {
			out = append(out, pkg{Name: m[1], Origin: m[2], NewVersion: m[3], OldVersion: m[4], Security: strings.Contains(m[2], "security")})
		}
	}
	_, reboot := os.Stat("/var/run/reboot-required")
	writeJSON(w, 200, map[string]any{"packages": out, "rebootRequired": reboot == nil})
}

func (s *Server) handleUpdatesRefresh(w http.ResponseWriter, r *http.Request) {
	jobStarted(w, s.jobs.Start("apt update", 15*time.Minute, nil, "apt-get", "update"))
}

func (s *Server) handleUpdatesUpgrade(w http.ResponseWriter, r *http.Request) {
	mode := r.URL.Query().Get("mode")
	args := []string{"-y", "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold"}
	switch mode {
	case "full":
		args = append(args, "full-upgrade")
	default:
		args = append(args, "upgrade")
	}
	jobStarted(w, s.jobs.Start("apt "+args[len(args)-1], 2*time.Hour, nil, "apt-get", args...))
}

// ======================= Power =======================

func handlePower(w http.ResponseWriter, r *http.Request) {
	act := r.PathValue("action")
	if act != "reboot" && act != "poweroff" {
		writeErr(w, 400, "invalid action")
		return
	}
	logf("power %s requested", act)
	// small delay so the HTTP response reaches the client
	sendResult(w, run(r.Context(), 10*time.Second, nil, "systemd-run", "--on-active=5", "systemctl", act))
}

// ======================= Users (local accounts) =======================

func handleAccounts(w http.ResponseWriter, r *http.Request) {
	b, _ := os.ReadFile("/etc/passwd")
	type acct struct {
		Name  string `json:"name"`
		UID   int    `json:"uid"`
		Home  string `json:"home"`
		Shell string `json:"shell"`
		Gecos string `json:"gecos"`
	}
	out := []acct{}
	for _, l := range strings.Split(string(b), "\n") {
		f := strings.Split(l, ":")
		if len(f) < 7 {
			continue
		}
		uid, _ := strconv.Atoi(f[2])
		if uid == 0 || (uid >= 1000 && uid < 65534) {
			out = append(out, acct{Name: f[0], UID: uid, Gecos: f[4], Home: f[5], Shell: f[6]})
		}
	}
	last := run(r.Context(), 10*time.Second, nil, "last", "-n", "20", "-w")
	writeJSON(w, 200, map[string]any{"accounts": out, "lastLogins": last.Stdout})
}

// ======================= Shell (opt-in) =======================

func (s *Server) handleShell(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.Get().AllowShell {
		writeErr(w, 403, "shell disabled in agent config (allowShell)")
		return
	}
	var req struct {
		Command    string `json:"command"`
		Background bool   `json:"background"`
	}
	if err := readJSON(r, &req); err != nil || strings.TrimSpace(req.Command) == "" {
		writeErr(w, 400, "invalid request")
		return
	}
	logf("shell: %s", req.Command)
	if req.Background {
		jobStarted(w, s.jobs.Start("shell: "+req.Command, 6*time.Hour, nil, "/bin/bash", "-lc", req.Command))
		return
	}
	writeJSON(w, 200, run(r.Context(), 120*time.Second, nil, "/bin/bash", "-lc", req.Command))
}
