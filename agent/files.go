package main

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"os/user"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// File manager (like Cockpit's): list, view, download, upload, new folder, rename, delete.
// The agent runs as root, so every path is reachable; uploads take owner and group of
// the target folder (e.g. www-data inside the Nextcloud data directory).

const maxUpload = 8 << 30 // 8 GiB
const maxView = 512 << 10 // text preview

type fileEntry struct {
	Name    string `json:"name"`
	Type    string `json:"type"` // dir, file, link, other
	Target  string `json:"target,omitempty"`
	Size    int64  `json:"size"`
	Mode    string `json:"mode"`
	ModTime int64  `json:"mtime"` // epoch ms
	Owner   string `json:"owner"`
	Group   string `json:"group"`
}

var (
	nameMu    sync.Mutex
	nameCache = map[string]string{}
)

func lookupName(id uint32, group bool) string {
	nameMu.Lock()
	defer nameMu.Unlock()
	key := fmt.Sprintf("%v:%d", group, id)
	if n, ok := nameCache[key]; ok {
		return n
	}
	n := strconv.Itoa(int(id))
	if group {
		if g, err := user.LookupGroupId(n); err == nil {
			n = g.Name
		}
	} else if u, err := user.LookupId(n); err == nil {
		n = u.Username
	}
	nameCache[key] = n
	return n
}

func cleanAbs(p string) (string, error) {
	if p == "" || !filepath.IsAbs(p) || strings.ContainsRune(p, 0) {
		return "", errors.New("absoluter Pfad erforderlich")
	}
	return filepath.Clean(p), nil
}

func entryOf(dir string, fi fs.FileInfo) fileEntry {
	e := fileEntry{Name: fi.Name(), Size: fi.Size(), Mode: fi.Mode().Perm().String(), ModTime: fi.ModTime().UnixMilli(), Type: "other"}
	switch {
	case fi.Mode()&fs.ModeSymlink != 0:
		e.Type = "link"
		e.Target, _ = os.Readlink(filepath.Join(dir, fi.Name()))
		// a link to a folder can be opened like a folder
		if st, err := os.Stat(filepath.Join(dir, fi.Name())); err == nil && st.IsDir() {
			e.Type = "dirlink"
		}
	case fi.IsDir():
		e.Type = "dir"
	case fi.Mode().IsRegular():
		e.Type = "file"
	}
	if st, ok := fi.Sys().(*syscall.Stat_t); ok {
		e.Owner = lookupName(st.Uid, false)
		e.Group = lookupName(st.Gid, true)
	}
	return e
}

// GET /api/files/list?path=/srv
func handleFilesList(w http.ResponseWriter, r *http.Request) {
	dir, err := cleanAbs(r.URL.Query().Get("path"))
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	out := make([]fileEntry, 0, len(ents))
	for _, de := range ents {
		fi, err := de.Info()
		if err != nil {
			continue
		}
		out = append(out, entryOf(dir, fi))
	}
	// folders first, then by name
	sort.Slice(out, func(i, j int) bool {
		di, dj := strings.HasPrefix(out[i].Type, "dir"), strings.HasPrefix(out[j].Type, "dir")
		if di != dj {
			return di
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	writeJSON(w, 200, map[string]any{"path": dir, "entries": out})
}

// GET /api/files/download?path=/etc/hosts  (?view=1: text preview, limited)
func handleFilesDownload(w http.ResponseWriter, r *http.Request) {
	p, err := cleanAbs(r.URL.Query().Get("path"))
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	f, err := os.Open(p)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil || !fi.Mode().IsRegular() {
		writeErr(w, 400, "keine normale Datei")
		return
	}
	if r.URL.Query().Get("view") == "1" {
		b, _ := io.ReadAll(io.LimitReader(f, maxView))
		writeJSON(w, 200, map[string]any{"text": string(b), "size": fi.Size(), "truncated": fi.Size() > maxView})
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(fi.Size(), 10))
	w.Header().Set("Content-Disposition", "attachment; filename=\""+strings.ReplaceAll(fi.Name(), "\"", "")+"\"")
	_, _ = io.Copy(w, f)
}

// POST /api/files/upload?dir=/srv/x&name=a.txt&overwrite=0  (body = file content)
func handleFilesUpload(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	dir, err := cleanAbs(q.Get("dir"))
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	name := q.Get("name")
	if name == "" || name == "." || name == ".." || strings.ContainsAny(name, "/\x00") {
		writeErr(w, 400, "ungültiger Dateiname")
		return
	}
	dst := filepath.Join(dir, name)
	dfi, err := os.Stat(dir)
	if err != nil || !dfi.IsDir() {
		writeErr(w, 400, "Zielordner existiert nicht")
		return
	}
	if _, err := os.Lstat(dst); err == nil && q.Get("overwrite") != "1" {
		writeErr(w, 409, "Datei existiert bereits")
		return
	}
	tmp, err := os.CreateTemp(dir, ".upload-*")
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	n, err := io.Copy(tmp, io.LimitReader(r.Body, maxUpload+1))
	cerr := tmp.Close()
	if err == nil {
		err = cerr
	}
	if err == nil && n > maxUpload {
		err = errors.New("Datei zu groß")
	}
	if err != nil {
		_ = os.Remove(tmp.Name())
		writeErr(w, 500, err.Error())
		return
	}
	_ = os.Chmod(tmp.Name(), 0o644)
	if st, ok := dfi.Sys().(*syscall.Stat_t); ok {
		_ = os.Chown(tmp.Name(), int(st.Uid), int(st.Gid))
	}
	if err := os.Rename(tmp.Name(), dst); err != nil {
		_ = os.Remove(tmp.Name())
		writeErr(w, 500, err.Error())
		return
	}
	logf("files: uploaded %s (%d bytes)", dst, n)
	writeJSON(w, 200, map[string]any{"ok": true, "path": dst, "size": n})
}

// POST /api/files/mkdir {"path": "/srv/new"}
func handleFilesMkdir(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path string `json:"path"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	p, err := cleanAbs(req.Path)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if err := os.Mkdir(p, 0o755); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if pfi, err := os.Stat(filepath.Dir(p)); err == nil {
		if st, ok := pfi.Sys().(*syscall.Stat_t); ok {
			_ = os.Chown(p, int(st.Uid), int(st.Gid))
		}
	}
	logf("files: mkdir %s", p)
	writeJSON(w, 200, map[string]any{"ok": true})
}

// POST /api/files/rename {"from": "/a", "to": "/b"}
func handleFilesRename(w http.ResponseWriter, r *http.Request) {
	var req struct {
		From string `json:"from"`
		To   string `json:"to"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	from, err1 := cleanAbs(req.From)
	to, err2 := cleanAbs(req.To)
	if err1 != nil || err2 != nil || from == "/" {
		writeErr(w, 400, "ungültiger Pfad")
		return
	}
	if _, err := os.Lstat(to); err == nil {
		writeErr(w, 409, "Ziel existiert bereits")
		return
	}
	if err := os.Rename(from, to); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	logf("files: rename %s -> %s", from, to)
	writeJSON(w, 200, map[string]any{"ok": true})
}

// protected paths that must never be deleted as a whole
var protectedPaths = map[string]bool{
	"/": true, "/bin": true, "/boot": true, "/dev": true, "/etc": true, "/home": true, "/lib": true, "/lib64": true,
	"/proc": true, "/root": true, "/run": true, "/sbin": true, "/srv": true, "/sys": true, "/usr": true, "/var": true,
	"/mnt": true, "/media": true, "/opt": true, "/tmp": true,
}

// POST /api/files/delete {"path": "/srv/old", "recursive": true}
func handleFilesDelete(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path      string `json:"path"`
		Recursive bool   `json:"recursive"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	p, err := cleanAbs(req.Path)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if protectedPaths[p] {
		writeErr(w, 403, p+" wird nicht gelöscht")
		return
	}
	fi, err := os.Lstat(p)
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	if fi.IsDir() && req.Recursive {
		err = os.RemoveAll(p)
	} else {
		err = os.Remove(p)
	}
	if err != nil {
		writeErr(w, 400, err.Error())
		return
	}
	logf("files: deleted %s", p)
	writeJSON(w, 200, map[string]any{"ok": true, "at": time.Now().Unix()})
}
