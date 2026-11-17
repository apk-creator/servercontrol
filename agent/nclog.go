package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// Nextcloud → Wartung → Protokoll: the newest entries of nextcloud.log (JSON lines).

func (s *Server) ncLogFile(r *http.Request) string {
	get := func(key string) string {
		return strings.TrimSpace(s.occ(r, 60*time.Second, nil, "config:system:get", key).Stdout)
	}
	if f := get("logfile"); f != "" {
		return f
	}
	if d := get("datadirectory"); d != "" {
		return strings.TrimRight(d, "/") + "/nextcloud.log"
	}
	return ""
}

// GET /api/nextcloud/log?n=200&level=0
func (s *Server) handleNcLog(w http.ResponseWriter, r *http.Request) {
	n, _ := strconv.Atoi(r.URL.Query().Get("n"))
	if n <= 0 || n > 1000 {
		n = 200
	}
	minLevel, _ := strconv.Atoi(r.URL.Query().Get("level"))
	path := s.ncLogFile(r)
	if path == "" {
		writeErr(w, 404, "Protokolldatei von Nextcloud nicht gefunden")
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, 404, err.Error())
		return
	}
	defer f.Close()
	// only the end of the file (it can grow to many MB)
	const tail = 2 << 20
	if fi, err := f.Stat(); err == nil && fi.Size() > tail {
		_, _ = f.Seek(fi.Size()-tail, io.SeekStart)
	}
	data, _ := io.ReadAll(f)
	if i := bytes.IndexByte(data, '\n'); i >= 0 && len(data) >= tail {
		data = data[i+1:] // drop the cut first line
	}
	var lines []string
	sc := bufio.NewScanner(bytes.NewReader(data))
	sc.Buffer(make([]byte, 1<<20), 4<<20)
	for sc.Scan() {
		lines = append(lines, sc.Text())
	}
	var out []map[string]any
	for i := len(lines) - 1; i >= 0 && len(out) < n; i-- {
		var e map[string]any
		if json.Unmarshal([]byte(lines[i]), &e) != nil {
			continue
		}
		lvl, _ := e["level"].(float64)
		if int(lvl) < minLevel {
			continue
		}
		msg := ""
		switch m := e["message"].(type) {
		case string:
			msg = m
		case nil:
		default:
			b, _ := json.Marshal(m)
			msg = string(b)
		}
		if ex, ok := e["exception"].(map[string]any); ok && msg == "" {
			msg = fmt.Sprint(ex["Message"])
		}
		out = append(out, map[string]any{
			"time": e["time"], "level": int(lvl), "app": e["app"], "user": e["user"], "message": msg,
			"method": e["method"], "url": e["url"], "remoteAddr": e["remoteAddr"],
		})
	}
	writeJSON(w, 200, map[string]any{"file": path, "entries": out})
}
