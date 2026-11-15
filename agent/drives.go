package main

import (
	"context"
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

// Drives (Speicher → Laufwerke): list with usage, power state and SMART health, and a detail
// view with partitions and filesystems that can be partitioned, formatted, labelled and mounted.

type blkDev struct {
	Name        string    `json:"name"`
	Path        string    `json:"path"`
	Size        int64     `json:"size"`
	Type        string    `json:"type"`
	FSType      string    `json:"fstype"`
	FSVer       string    `json:"fsver"`
	Label       string    `json:"label"`
	UUID        string    `json:"uuid"`
	PartLabel   string    `json:"partlabel"`
	PartType    string    `json:"parttypename"`
	PTType      string    `json:"pttype"`
	Mountpoints []string  `json:"mountpoints"`
	FSUsed      *int64    `json:"fsused"`
	FSSize      *int64    `json:"fssize"`
	Model       string    `json:"model"`
	Serial      string    `json:"serial"`
	Tran        string    `json:"tran"`
	Rota        bool      `json:"rota"`
	Children    []*blkDev `json:"children"`
}

func lsblkTree(r *http.Request, dev string) ([]*blkDev, error) {
	args := []string{"-J", "-b", "-o", "NAME,PATH,SIZE,TYPE,FSTYPE,FSVER,LABEL,UUID,PARTLABEL,PARTTYPENAME,PTTYPE,MOUNTPOINTS,FSUSED,FSSIZE,MODEL,SERIAL,TRAN,ROTA"}
	if dev != "" {
		args = append(args, dev)
	}
	res := run(r.Context(), 15*time.Second, nil, "lsblk", args...)
	var out struct {
		BlockDevices []*blkDev `json:"blockdevices"`
	}
	if err := json.Unmarshal([]byte(res.Stdout), &out); err != nil {
		return nil, fmt.Errorf("lsblk: %s", strings.TrimSpace(res.Stderr))
	}
	for _, d := range out.BlockDevices {
		cleanMounts(d)
	}
	return out.BlockDevices, nil
}

func cleanMounts(d *blkDev) {
	mp := d.Mountpoints[:0]
	for _, m := range d.Mountpoints {
		if m != "" {
			mp = append(mp, m)
		}
	}
	d.Mountpoints = mp
	for _, c := range d.Children {
		cleanMounts(c)
	}
}

// walk calls f for d and everything below it
func walk(d *blkDev, f func(*blkDev)) {
	f(d)
	for _, c := range d.Children {
		walk(c, f)
	}
}

// usage of all mounted filesystems on a disk (statfs, the same numbers as df)
func diskUsage(d *blkDev) (used, total uint64, mps []string) {
	byMount := map[string]Mount{}
	for _, m := range mounts() {
		byMount[m.Mount] = m
	}
	seen := map[string]bool{}
	walk(d, func(b *blkDev) {
		for _, mp := range b.Mountpoints {
			m, ok := byMount[mp]
			if !ok || seen[b.Path] {
				continue
			}
			seen[b.Path] = true
			used += m.Used
			total += m.Used + m.Free
			mps = append(mps, mp)
		}
	})
	return
}

func driveState(name string, st DiskState) string {
	if st.Standby {
		return "standby"
	}
	if u, ok := metrics.utilOf(name); ok && u > 0.5 {
		return "active"
	}
	return "idle"
}

// GET /api/storage/drives
func handleDrives(w http.ResponseWriter, r *http.Request) {
	tree, err := lsblkTree(r, "")
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	states := metrics.diskStates()
	var out []map[string]any
	for _, d := range tree {
		if d.Type != "disk" || !physDiskRe.MatchString(d.Name) {
			continue
		}
		used, total, mps := diskUsage(d)
		st := states[d.Path]
		e := map[string]any{
			"device": d.Path, "name": d.Name, "model": strings.TrimSpace(d.Model), "serial": d.Serial,
			"size": d.Size, "tran": d.Tran, "rotational": d.Rota, "nvme": strings.HasPrefix(d.Name, "nvme"),
			"state": driveState(d.Name, st), "used": used, "total": total, "mounts": mps,
			"smartPassed": st.Passed, "smartCached": st.Cached || st.Standby, "celsius": st.C, "smartAt": st.At,
		}
		if u, ok := metrics.utilOf(d.Name); ok {
			e["io"] = u
		}
		out = append(out, e)
	}
	sort.SliceStable(out, func(i, j int) bool {
		ni, nj := out[i]["nvme"].(bool), out[j]["nvme"].(bool)
		if ni != nj {
			return ni
		}
		return natLess(out[i]["name"].(string), out[j]["name"].(string))
	})
	writeJSON(w, 200, out)
}

// natLess: sda < sdb < … < sdz < sdaa
func natLess(a, b string) bool {
	if len(a) != len(b) {
		return len(a) < len(b)
	}
	return a < b
}

var fstabPath = "/etc/fstab"

type fstabEntry struct {
	Spec, File, Type, Opts string
}

func readFstab() []fstabEntry {
	b, _ := os.ReadFile(fstabPath)
	var out []fstabEntry
	for _, l := range strings.Split(string(b), "\n") {
		f := strings.Fields(l)
		if len(f) < 4 || strings.HasPrefix(f[0], "#") {
			continue
		}
		out = append(out, fstabEntry{f[0], f[1], f[2], f[3]})
	}
	return out
}

// GET /api/storage/drive?device=/dev/sda
func handleDrive(w http.ResponseWriter, r *http.Request) {
	dev := r.URL.Query().Get("device")
	if !devRe.MatchString(dev) {
		writeErr(w, 400, "invalid device")
		return
	}
	tree, err := lsblkTree(r, dev)
	if err != nil || len(tree) == 0 {
		writeErr(w, 404, "Laufwerk nicht gefunden")
		return
	}
	d := tree[0]
	used, total, _ := diskUsage(d)
	st := metrics.diskStates()[d.Path]
	fstab := map[string]fstabEntry{}
	for _, e := range readFstab() {
		fstab[e.Spec] = e
	}
	// fstab entries of this disk's filesystems (by UUID, LABEL or device path)
	var parts []map[string]any
	walk(d, func(b *blkDev) {
		if b == d && len(d.Children) > 0 {
			return
		}
		p := map[string]any{
			"path": b.Path, "name": b.Name, "type": b.Type, "size": b.Size, "fstype": b.FSType, "fsver": b.FSVer,
			"label": b.Label, "uuid": b.UUID, "partlabel": b.PartLabel, "parttype": b.PartType, "mounts": b.Mountpoints,
			"fsused": b.FSUsed, "fssize": b.FSSize, "system": isSystem(b),
		}
		for _, spec := range []string{"UUID=" + b.UUID, "LABEL=" + b.Label, b.Path} {
			if e, ok := fstab[spec]; ok && spec != "UUID=" && spec != "LABEL=" {
				p["fstab"] = map[string]any{"spec": e.Spec, "mount": e.File, "type": e.Type, "options": e.Opts}
				break
			}
		}
		parts = append(parts, p)
	})
	free := freeRegions(r, d.Path)
	writeJSON(w, 200, map[string]any{
		"device": d.Path, "name": d.Name, "model": strings.TrimSpace(d.Model), "serial": d.Serial, "size": d.Size,
		"tran": d.Tran, "rotational": d.Rota, "pttype": d.PTType, "state": driveState(d.Name, st),
		"smartPassed": st.Passed, "smartCached": st.Cached || st.Standby, "celsius": st.C,
		"used": used, "total": total, "partitions": parts, "free": free, "system": isSystem(d),
		"tools": map[string]bool{"parted": has("parted"), "mkfs.ext4": has("mkfs.ext4"), "mkfs.xfs": has("mkfs.xfs"),
			"mkfs.btrfs": has("mkfs.btrfs"), "mkfs.vfat": has("mkfs.vfat"), "mkfs.exfat": has("mkfs.exfat"), "mkfs.ntfs": has("mkfs.ntfs")},
	})
}

// isSystem: the device (or something on it) holds the running system – never touch it destructively
func isSystem(d *blkDev) bool {
	sys := false
	walk(d, func(b *blkDev) {
		for _, m := range b.Mountpoints {
			if m == "/" || m == "/boot" || m == "/boot/efi" || m == "/usr" || m == "/var" || m == "[SWAP]" {
				sys = true
			}
		}
	})
	return sys
}

func anyMounted(d *blkDev) bool {
	m := false
	walk(d, func(b *blkDev) {
		if len(b.Mountpoints) > 0 {
			m = true
		}
	})
	return m
}

// free regions of a partitioned disk in bytes (parted machine output)
func freeRegions(r *http.Request, dev string) []map[string]int64 {
	if !has("parted") {
		return nil
	}
	res := run(r.Context(), 15*time.Second, nil, "parted", "-m", "-s", dev, "unit", "B", "print", "free")
	var out []map[string]int64
	for _, l := range strings.Split(res.Stdout, "\n") {
		f := strings.Split(strings.TrimSuffix(l, ";"), ":")
		if len(f) < 5 || f[4] != "free" {
			continue
		}
		a, _ := strconv.ParseInt(strings.TrimSuffix(f[1], "B"), 10, 64)
		b, _ := strconv.ParseInt(strings.TrimSuffix(f[2], "B"), 10, 64)
		if b-a >= 16<<20 { // ignore alignment gaps
			out = append(out, map[string]int64{"start": a, "end": b})
		}
	}
	return out
}

var (
	labelRe   = regexp.MustCompile(`^[A-Za-z0-9 ._-]{0,32}$`)
	mountRe   = regexp.MustCompile(`^/(mnt|media|srv|data|backup|home)(/[A-Za-z0-9._-]+)+$`)
	mountOpts = regexp.MustCompile(`^[a-z0-9_=,.-]{1,200}$`)
	partRe    = regexp.MustCompile(`^/dev/(sd[a-z]+[0-9]+|nvme[0-9]+n[0-9]+p[0-9]+|vd[a-z]+[0-9]+)$`)
	fsTypes   = map[string]string{"ext4": "mkfs.ext4", "xfs": "mkfs.xfs", "btrfs": "mkfs.btrfs", "vfat": "mkfs.vfat", "exfat": "mkfs.exfat", "ntfs": "mkfs.ntfs"}
)

// parentDisk: /dev/sdb1 → /dev/sdb, /dev/nvme0n1p2 → /dev/nvme0n1
func parentDisk(part string) (disk string, num string) {
	m := regexp.MustCompile(`^(/dev/nvme[0-9]+n[0-9]+)p([0-9]+)$`).FindStringSubmatch(part)
	if m == nil {
		m = regexp.MustCompile(`^(/dev/[a-z]+)([0-9]+)$`).FindStringSubmatch(part)
	}
	if m == nil {
		return "", ""
	}
	return m[1], m[2]
}

func findDev(tree []*blkDev, path string) *blkDev {
	var hit *blkDev
	for _, d := range tree {
		walk(d, func(b *blkDev) {
			if b.Path == path {
				hit = b
			}
		})
	}
	return hit
}

// POST /api/storage/drive/action
//
//	{"action":"mount","path":"/dev/sdb1","mountpoint":"/mnt/daten","fstab":true,"options":"defaults,nofail"}
//	{"action":"unmount","path":"/dev/sdb1","fstab":true}
//	{"action":"label","path":"/dev/sdb1","label":"Daten"}
//	{"action":"format","path":"/dev/sdb1","fstype":"ext4","label":"Daten","confirm":"/dev/sdb1"}
//	{"action":"mklabel","path":"/dev/sdb","table":"gpt","confirm":"/dev/sdb"}
//	{"action":"mkpart","path":"/dev/sdb","start":1048576,"size":0}   (size 0 = whole free region)
//	{"action":"rmpart","path":"/dev/sdb1","confirm":"/dev/sdb1"}
func (s *Server) handleDriveAction(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Action     string `json:"action"`
		Path       string `json:"path"`
		Mountpoint string `json:"mountpoint"`
		Fstab      bool   `json:"fstab"`
		Options    string `json:"options"`
		Label      string `json:"label"`
		FSType     string `json:"fstype"`
		Table      string `json:"table"`
		Start      int64  `json:"start"`
		Size       int64  `json:"size"`
		Confirm    string `json:"confirm"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if !partRe.MatchString(req.Path) && !devRe.MatchString(req.Path) {
		writeErr(w, 400, "ungültiges Gerät")
		return
	}
	tree, err := lsblkTree(r, "")
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	target := findDev(tree, req.Path)
	if target == nil {
		writeErr(w, 404, "Gerät nicht gefunden")
		return
	}
	var disk *blkDev
	for _, d := range tree {
		if findDev([]*blkDev{d}, req.Path) != nil {
			disk = d
		}
	}
	destructive := req.Action == "format" || req.Action == "mklabel" || req.Action == "rmpart"
	if destructive {
		if req.Confirm != req.Path {
			writeErr(w, 400, "Bestätigung fehlt: Gerätename muss eingegeben werden")
			return
		}
		if isSystem(disk) && (req.Action == "mklabel" || isSystem(target)) {
			writeErr(w, 403, "Das Laufwerk enthält das laufende System – nicht erlaubt")
			return
		}
		if anyMounted(target) || (req.Action == "mklabel" && anyMounted(disk)) {
			writeErr(w, 409, "Erst aushängen")
			return
		}
	}
	ctx := r.Context()
	switch req.Action {
	case "mount":
		if !mountRe.MatchString(req.Mountpoint) || filepath.Clean(req.Mountpoint) != req.Mountpoint || strings.Contains(req.Mountpoint, "..") {
			writeErr(w, 400, "Einhängepunkt muss unter /mnt, /media, /srv, /data, /backup oder /home liegen")
			return
		}
		if req.Options == "" {
			req.Options = "defaults,nofail"
		}
		if !mountOpts.MatchString(req.Options) {
			writeErr(w, 400, "ungültige Optionen")
			return
		}
		if target.FSType == "" || target.UUID == "" {
			writeErr(w, 400, "Kein Dateisystem")
			return
		}
		if err := os.MkdirAll(req.Mountpoint, 0o755); err != nil {
			writeErr(w, 500, err.Error())
			return
		}
		if req.Fstab {
			if err := setFstab(target.UUID, req.Mountpoint, target.FSType, req.Options); err != nil {
				writeErr(w, 500, err.Error())
				return
			}
			run(ctx, 30*time.Second, nil, "systemctl", "daemon-reload")
		}
		if len(target.Mountpoints) == 0 {
			res := run(ctx, 60*time.Second, nil, "mount", "-o", req.Options, req.Path, req.Mountpoint)
			if res.ExitCode != 0 {
				writeErr(w, 500, strings.TrimSpace(res.Stderr))
				return
			}
		}
		logf("drive: mount %s on %s (fstab=%v)", req.Path, req.Mountpoint, req.Fstab)
		writeJSON(w, 200, map[string]any{"ok": true, "stdout": "Eingehängt unter " + req.Mountpoint})
	case "unmount":
		if isSystem(target) {
			writeErr(w, 403, "Systemdateisystem – nicht erlaubt")
			return
		}
		for _, mp := range target.Mountpoints {
			if res := run(ctx, 60*time.Second, nil, "umount", mp); res.ExitCode != 0 {
				writeErr(w, 409, strings.TrimSpace(res.Stderr))
				return
			}
		}
		if req.Fstab && target.UUID != "" {
			if err := setFstab(target.UUID, "", "", ""); err != nil {
				writeErr(w, 500, err.Error())
				return
			}
			run(ctx, 30*time.Second, nil, "systemctl", "daemon-reload")
		}
		logf("drive: unmount %s (fstab=%v)", req.Path, req.Fstab)
		writeJSON(w, 200, map[string]any{"ok": true, "stdout": "Ausgehängt."})
	case "label":
		if !labelRe.MatchString(req.Label) {
			writeErr(w, 400, "Name: bis 32 Zeichen, Buchstaben, Ziffern, Leerzeichen, . _ -")
			return
		}
		var name string
		var args []string
		switch target.FSType {
		case "ext2", "ext3", "ext4":
			name, args = "e2label", []string{req.Path, req.Label}
		case "xfs":
			name, args = "xfs_admin", []string{"-L", orDash(req.Label), req.Path}
		case "btrfs":
			if len(target.Mountpoints) > 0 {
				name, args = "btrfs", []string{"filesystem", "label", target.Mountpoints[0], req.Label}
			} else {
				name, args = "btrfs", []string{"filesystem", "label", req.Path, req.Label}
			}
		case "vfat":
			name, args = "fatlabel", []string{req.Path, req.Label}
		case "exfat":
			name, args = "exfatlabel", []string{req.Path, req.Label}
		case "ntfs":
			name, args = "ntfslabel", []string{req.Path, req.Label}
		default:
			writeErr(w, 400, "Umbenennen für "+target.FSType+" nicht möglich")
			return
		}
		res := run(ctx, 60*time.Second, nil, name, args...)
		logf("drive: label %s = %q", req.Path, req.Label)
		sendResult(w, res)
	case "format":
		bin, ok := fsTypes[req.FSType]
		if !ok || !has(bin) {
			writeErr(w, 400, "Dateisystem nicht verfügbar: "+req.FSType)
			return
		}
		if !labelRe.MatchString(req.Label) {
			writeErr(w, 400, "ungültiger Name")
			return
		}
		args := []string{}
		switch req.FSType {
		case "ext4":
			args = append(args, "-F")
			if req.Label != "" {
				args = append(args, "-L", req.Label)
			}
		case "xfs", "btrfs":
			args = append(args, "-f")
			if req.Label != "" {
				args = append(args, "-L", req.Label)
			}
		case "vfat":
			args = append(args, "-F", "32")
			if req.Label != "" {
				args = append(args, "-n", strings.ToUpper(req.Label))
			}
		case "exfat":
			if req.Label != "" {
				args = append(args, "-n", req.Label)
			}
		case "ntfs":
			args = append(args, "-f", "-F")
			if req.Label != "" {
				args = append(args, "-L", req.Label)
			}
		}
		args = append(args, req.Path)
		logf("drive: format %s as %s", req.Path, req.FSType)
		jobStarted(w, s.jobs.Start("Formatieren "+req.Path+" ("+req.FSType+")", 6*time.Hour, nil, bin, args...))
	case "mklabel":
		if !devRe.MatchString(req.Path) || (req.Table != "gpt" && req.Table != "msdos") || !has("parted") {
			writeErr(w, 400, "Partitionstabelle: gpt oder msdos (parted nötig)")
			return
		}
		res := run(ctx, 60*time.Second, nil, "parted", "-s", req.Path, "mklabel", req.Table)
		settle(ctx, req.Path)
		logf("drive: mklabel %s %s", req.Path, req.Table)
		sendResult(w, res)
	case "mkpart":
		if !devRe.MatchString(req.Path) || !has("parted") || target.PTType == "" {
			writeErr(w, 400, "Erst eine Partitionstabelle anlegen (parted nötig)")
			return
		}
		var region map[string]int64
		for _, f := range freeRegions(r, req.Path) {
			if req.Start >= f["start"] && req.Start < f["end"] {
				region = f
			}
		}
		if region == nil {
			writeErr(w, 400, "Kein freier Bereich an dieser Stelle")
			return
		}
		start := alignUp(region["start"], 1<<20)
		end := region["end"]
		if req.Size > 0 && start+req.Size-1 < end {
			end = start + req.Size - 1
		}
		res := run(ctx, 60*time.Second, nil, "parted", "-s", "-a", "optimal", req.Path, "unit", "B", "mkpart", "primary", strconv.FormatInt(start, 10)+"B", strconv.FormatInt(end, 10)+"B")
		settle(ctx, req.Path)
		logf("drive: mkpart %s %d-%d", req.Path, start, end)
		sendResult(w, res)
	case "rmpart":
		d, num := parentDisk(req.Path)
		if d == "" || !has("parted") {
			writeErr(w, 400, "keine Partition")
			return
		}
		if target.UUID != "" {
			_ = setFstab(target.UUID, "", "", "")
		}
		res := run(ctx, 60*time.Second, nil, "parted", "-s", d, "rm", num)
		settle(ctx, d)
		logf("drive: rmpart %s", req.Path)
		sendResult(w, res)
	default:
		writeErr(w, 400, "unbekannte Aktion")
	}
}

func orDash(s string) string {
	if s == "" {
		return "--"
	}
	return s
}

func alignUp(v, a int64) int64 { return (v + a - 1) / a * a }

func settle(ctx context.Context, dev string) {
	run(ctx, 30*time.Second, nil, "partprobe", dev)
	run(ctx, 30*time.Second, nil, "udevadm", "settle", "-t", "20")
}

// setFstab replaces the entry for UUID=<uuid>; empty mountpoint removes it.
func setFstab(uuid, mountpoint, fstype, opts string) error {
	b, err := os.ReadFile(fstabPath)
	if err != nil {
		return err
	}
	spec := "UUID=" + uuid
	var lines []string
	for _, l := range strings.Split(strings.TrimRight(string(b), "\n"), "\n") {
		f := strings.Fields(l)
		if len(f) >= 2 && f[0] == spec {
			continue
		}
		lines = append(lines, l)
	}
	if mountpoint != "" {
		pass := "2"
		if fstype == "vfat" || fstype == "exfat" || fstype == "ntfs" || fstype == "btrfs" || fstype == "xfs" {
			pass = "0"
		}
		lines = append(lines, fmt.Sprintf("%s %s %s %s 0 %s  # ServerControl", spec, mountpoint, fstype, opts, pass))
	}
	_ = os.WriteFile(fstabPath+".servercontrol.bak", b, 0o644)
	tmp := filepath.Join(filepath.Dir(fstabPath), ".fstab.tmp")
	if err := os.WriteFile(tmp, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, fstabPath)
}
