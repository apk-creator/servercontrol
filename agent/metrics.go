package main

import (
	"bufio"
	"context"
	"net/http"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Background sampler for the overview: CPU (total and per core), memory, disk I/O and
// network throughput every few seconds, kept for one hour (charts like in Cockpit).
// HDD temperatures come from smartctl with "-n standby", so sleeping disks stay asleep.

const (
	sampleEvery   = 5 * time.Second
	historyPoints = 720 // 1 hour
	hddTempEvery  = 2 * time.Minute
)

type cpuTimes struct{ idle, total uint64 }

type rawSample struct {
	at        time.Time
	cpu       []cpuTimes // [0] = all cores
	diskRead  uint64     // bytes
	diskWrite uint64
	diskBusy  uint64 // ms (max of all disks is computed per interval)
	busyByDev map[string]uint64
	netRx     uint64
	netTx     uint64
}

type Point struct {
	CPU       float64 `json:"cpu"`
	Mem       float64 `json:"mem"`
	DiskRead  float64 `json:"diskRead"`
	DiskWrite float64 `json:"diskWrite"`
	DiskBusy  float64 `json:"diskBusy"`
	NetRx     float64 `json:"netRx"`
	NetTx     float64 `json:"netTx"`
}

type DiskTemp struct {
	Device  string  `json:"device"`
	Model   string  `json:"model"`
	C       float64 `json:"celsius"`
	Standby bool    `json:"standby"`
}

type Metrics struct {
	mu       sync.Mutex
	last     *rawSample
	cores    []float64
	history  []Point
	hddTemps []DiskTemp
	hddAt    time.Time
}

var metrics = &Metrics{}

var physDiskRe = regexp.MustCompile(`^(sd[a-z]+|nvme[0-9]+n[0-9]+|vd[a-z]+|hd[a-z]+)$`)

func readCPUAll() []cpuTimes {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return nil
	}
	defer f.Close()
	var out []cpuTimes
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		l := sc.Text()
		if !strings.HasPrefix(l, "cpu") {
			break
		}
		fields := strings.Fields(l)[1:]
		var t cpuTimes
		for i, v := range fields {
			n, _ := strconv.ParseUint(v, 10, 64)
			if i >= 8 { // guest times are already part of user/nice
				break
			}
			t.total += n
			if i == 3 || i == 4 {
				t.idle += n
			}
		}
		out = append(out, t)
	}
	return out
}

func readDiskStats() (rd, wr uint64, busy map[string]uint64) {
	busy = map[string]uint64{}
	b, _ := os.ReadFile("/proc/diskstats")
	for _, l := range strings.Split(string(b), "\n") {
		f := strings.Fields(l)
		if len(f) < 14 || !physDiskRe.MatchString(f[2]) {
			continue
		}
		r, _ := strconv.ParseUint(f[5], 10, 64)
		w, _ := strconv.ParseUint(f[9], 10, 64)
		t, _ := strconv.ParseUint(f[12], 10, 64)
		rd += r * 512
		wr += w * 512
		busy[f[2]] = t
	}
	return
}

func readNetDev() (rx, tx uint64) {
	b, _ := os.ReadFile("/proc/net/dev")
	for _, l := range strings.Split(string(b), "\n") {
		name, rest, ok := strings.Cut(l, ":")
		if !ok {
			continue
		}
		name = strings.TrimSpace(name)
		// physical interfaces only (no loopback, docker bridges, veth, wireguard tunnels)
		if name == "lo" || strings.HasPrefix(name, "veth") || strings.HasPrefix(name, "docker") || strings.HasPrefix(name, "br-") || strings.HasPrefix(name, "wg") {
			continue
		}
		f := strings.Fields(rest)
		if len(f) < 9 {
			continue
		}
		r, _ := strconv.ParseUint(f[0], 10, 64)
		t, _ := strconv.ParseUint(f[8], 10, 64)
		rx += r
		tx += t
	}
	return
}

func takeSample() *rawSample {
	s := &rawSample{at: time.Now(), cpu: readCPUAll()}
	s.diskRead, s.diskWrite, s.busyByDev = readDiskStats()
	s.netRx, s.netTx = readNetDev()
	return s
}

func pctCPU(a, b cpuTimes) float64 {
	dt := b.total - a.total
	if b.total <= a.total || dt == 0 {
		return 0
	}
	return 100 * (1 - float64(b.idle-a.idle)/float64(dt))
}

func rate(a, b uint64, secs float64) float64 {
	if b < a || secs <= 0 {
		return 0
	}
	return float64(b-a) / secs
}

func (m *Metrics) step() {
	s := takeSample()
	mi := meminfo()
	m.mu.Lock()
	defer m.mu.Unlock()
	if p := m.last; p != nil {
		secs := s.at.Sub(p.at).Seconds()
		pt := Point{}
		if len(s.cpu) > 0 && len(p.cpu) == len(s.cpu) {
			pt.CPU = pctCPU(p.cpu[0], s.cpu[0])
			cores := make([]float64, len(s.cpu)-1)
			for i := 1; i < len(s.cpu); i++ {
				cores[i-1] = pctCPU(p.cpu[i], s.cpu[i])
			}
			m.cores = cores
		}
		if t := mi["MemTotal"]; t > 0 {
			pt.Mem = 100 * float64(t-mi["MemAvailable"]) / float64(t)
		}
		pt.DiskRead = rate(p.diskRead, s.diskRead, secs)
		pt.DiskWrite = rate(p.diskWrite, s.diskWrite, secs)
		for dev, t := range s.busyByDev {
			if o, ok := p.busyByDev[dev]; ok && t >= o && secs > 0 {
				if u := float64(t-o) / (secs * 10); u > pt.DiskBusy { // ms → %
					pt.DiskBusy = u
				}
			}
		}
		if pt.DiskBusy > 100 {
			pt.DiskBusy = 100
		}
		pt.NetRx = rate(p.netRx, s.netRx, secs)
		pt.NetTx = rate(p.netTx, s.netTx, secs)
		m.history = append(m.history, pt)
		if len(m.history) > historyPoints {
			m.history = m.history[len(m.history)-historyPoints:]
		}
	}
	m.last = s
}

// rotating disks (HDDs) from sysfs – reading sysfs never wakes a disk
func rotatingDisks() []string {
	ents, _ := os.ReadDir("/sys/block")
	var out []string
	for _, e := range ents {
		n := e.Name()
		if !physDiskRe.MatchString(n) {
			continue
		}
		if b, err := os.ReadFile("/sys/block/" + n + "/queue/rotational"); err == nil && strings.TrimSpace(string(b)) == "1" {
			out = append(out, n)
		}
	}
	sort.Strings(out)
	return out
}

var smartTempRe = regexp.MustCompile(`(?m)^\s*(194|190)\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\d+)`)
var smartModelRe = regexp.MustCompile(`(?m)^(Device Model|Model Number|Product):\s*(.+)$`)

func (m *Metrics) readHddTemps() {
	var out []DiskTemp
	for _, d := range rotatingDisks() {
		model := ""
		if b, err := os.ReadFile("/sys/block/" + d + "/device/model"); err == nil {
			model = strings.TrimSpace(string(b))
		}
		// -n standby: smartctl exits with code 2 without touching a sleeping disk
		res := run(context.Background(), 30*time.Second, nil, "smartctl", "-n", "standby", "-A", "/dev/"+d)
		dt := DiskTemp{Device: "/dev/" + d, Model: model}
		if up := strings.ToUpper(res.Stdout); res.ExitCode == 2 && (strings.Contains(up, "STANDBY") || strings.Contains(up, "SLEEP")) {
			dt.Standby = true
		} else if mm := smartTempRe.FindStringSubmatch(res.Stdout); mm != nil {
			dt.C, _ = strconv.ParseFloat(mm[2], 64)
		} else {
			continue
		}
		if dt.Model == "" {
			if mm := smartModelRe.FindStringSubmatch(res.Stdout); mm != nil {
				dt.Model = strings.TrimSpace(mm[2])
			}
		}
		out = append(out, dt)
	}
	m.mu.Lock()
	// keep the last known temperature of a disk that went to sleep
	prev := map[string]float64{}
	for _, t := range m.hddTemps {
		if t.C > 0 {
			prev[t.Device] = t.C
		}
	}
	for i := range out {
		if out[i].Standby {
			out[i].C = prev[out[i].Device]
		}
	}
	m.hddTemps = out
	m.hddAt = time.Now()
	m.mu.Unlock()
}

func (m *Metrics) Start() {
	go func() {
		m.step()
		t := time.NewTicker(sampleEvery)
		for range t.C {
			m.step()
		}
	}()
	go func() {
		for {
			m.readHddTemps()
			time.Sleep(hddTempEvery)
		}
	}()
}

func (m *Metrics) snapshot() (cur Point, cores []float64, hdd []DiskTemp) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if n := len(m.history); n > 0 {
		cur = m.history[n-1]
	}
	cores = append([]float64(nil), m.cores...)
	hdd = append([]DiskTemp(nil), m.hddTemps...)
	return
}

type tempStats struct {
	Min   float64 `json:"min"`
	Avg   float64 `json:"avg"`
	Max   float64 `json:"max"`
	Count int     `json:"count"`
}

func statsOf(v []float64) tempStats {
	if len(v) == 0 {
		return tempStats{}
	}
	s := tempStats{Min: v[0], Max: v[0], Count: len(v)}
	sum := 0.0
	for _, x := range v {
		sum += x
		if x < s.Min {
			s.Min = x
		}
		if x > s.Max {
			s.Max = x
		}
	}
	s.Avg = sum / float64(len(v))
	return s
}

// board temperatures: all hwmon sensors except drives (drivetemp, nvme)
func boardTemps() []float64 {
	var v []float64
	for _, t := range temperatures() {
		if t.Chip == "drivetemp" || t.Chip == "nvme" || t.C <= 0 || t.C >= 125 {
			continue
		}
		v = append(v, t.C)
	}
	return v
}

// GET /api/system/history
func handleHistory(w http.ResponseWriter, r *http.Request) {
	metrics.mu.Lock()
	h := append([]Point(nil), metrics.history...)
	metrics.mu.Unlock()
	writeJSON(w, 200, map[string]any{"intervalSeconds": int(sampleEvery.Seconds()), "points": h})
}

// scheduled shutdown/reboot (shutdown -r +N writes /run/systemd/shutdown/scheduled)
func scheduledPower() map[string]any {
	b, err := os.ReadFile("/run/systemd/shutdown/scheduled")
	if err != nil {
		return nil
	}
	out := map[string]any{}
	for _, l := range strings.Split(string(b), "\n") {
		k, v, ok := strings.Cut(l, "=")
		if !ok {
			continue
		}
		switch k {
		case "USEC":
			if us, err := strconv.ParseInt(v, 10, 64); err == nil {
				out["at"] = us / 1000 // epoch ms
			}
		case "MODE":
			out["mode"] = v
		}
	}
	if out["at"] == nil {
		return nil
	}
	return out
}
