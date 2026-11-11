package main

import (
	"bytes"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// The Immich API key stays on the server (apiKeyFile); the app never sees it.
// Only an allowlist of admin endpoints is proxied.

type immichRoute struct {
	method string
	re     *regexp.Regexp
}

var immichAllow = []immichRoute{
	{"GET", regexp.MustCompile(`^/server/(about|statistics|storage|version|version-check|features|config|ping)$`)},
	{"GET", regexp.MustCompile(`^/(jobs|queues)$`)},
	{"PUT", regexp.MustCompile(`^/jobs/[a-zA-Z]+$`)},
	{"POST", regexp.MustCompile(`^/jobs$`)},
	{"GET", regexp.MustCompile(`^/admin/users$`)},
	{"POST", regexp.MustCompile(`^/admin/users$`)},
	{"GET", regexp.MustCompile(`^/admin/users/[0-9a-f-]{36}(/statistics)?$`)},
	{"PUT", regexp.MustCompile(`^/admin/users/[0-9a-f-]{36}$`)},
	{"DELETE", regexp.MustCompile(`^/admin/users/[0-9a-f-]{36}$`)},
	{"POST", regexp.MustCompile(`^/admin/users/[0-9a-f-]{36}/restore$`)},
	{"GET", regexp.MustCompile(`^/libraries$`)},
	{"POST", regexp.MustCompile(`^/libraries/[0-9a-f-]{36}/scan$`)},
	{"GET", regexp.MustCompile(`^/system-config$`)},
}

var immichClient = &http.Client{Timeout: 60 * time.Second}

// ANY /api/immich/{path...}
func (s *Server) handleImmich(w http.ResponseWriter, r *http.Request) {
	c := s.cfg.Get().Immich
	if !c.Enabled {
		writeErr(w, 404, "immich module disabled in agent config")
		return
	}
	path := "/" + r.PathValue("path")
	allowed := false
	for _, rt := range immichAllow {
		if rt.method == r.Method && rt.re.MatchString(path) {
			allowed = true
			break
		}
	}
	if !allowed {
		writeErr(w, 403, "endpoint not allowed: "+r.Method+" "+path)
		return
	}
	key, err := os.ReadFile(c.APIKeyFile)
	if err != nil {
		writeErr(w, 500, "cannot read immich api key file")
		return
	}
	var body io.Reader
	if r.Method != "GET" {
		b, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		body = bytes.NewReader(b)
	}
	target := strings.TrimRight(c.URL, "/") + "/api" + path
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, body)
	if err != nil {
		writeErr(w, 500, err.Error())
		return
	}
	req.Header.Set("x-api-key", strings.TrimSpace(string(key)))
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if r.Method != "GET" {
		logf("immich %s %s", r.Method, path)
	}
	resp, err := immichClient.Do(req)
	if err != nil {
		writeErr(w, 502, "immich unreachable: "+err.Error())
		return
	}
	defer resp.Body.Close()
	w.Header().Set("Content-Type", resp.Header.Get("Content-Type"))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(resp.StatusCode)
	_, _ = io.Copy(w, io.LimitReader(resp.Body, 8<<20))
}

// Immich runs with docker compose; the update pulls the images and recreates the containers.

func immichComposeDir(r *http.Request) (string, string) {
	bin := containerBin()
	if bin == "" {
		return "", ""
	}
	res := run(r.Context(), 20*time.Second, nil, bin, "ps", "-a",
		"--filter", "label=com.docker.compose.service=immich-server",
		"--format", `{{.Label "com.docker.compose.project.working_dir"}}`)
	dir := strings.TrimSpace(strings.SplitN(res.Stdout, "\n", 2)[0])
	return bin, dir
}

// GET /api/immich-compose – compose directory and the pinned IMMICH_VERSION from .env
func (s *Server) handleImmichCompose(w http.ResponseWriter, r *http.Request) {
	_, dir := immichComposeDir(r)
	out := map[string]any{"dir": dir}
	if dir != "" {
		if b, err := os.ReadFile(filepath.Join(dir, ".env")); err == nil {
			for _, l := range strings.Split(string(b), "\n") {
				if v, ok := strings.CutPrefix(strings.TrimSpace(l), "IMMICH_VERSION="); ok {
					out["immichVersion"] = strings.Trim(v, `"' `)
				}
			}
		}
	}
	writeJSON(w, 200, out)
}

// POST /api/immich-compose/update – docker compose pull && up -d (as a job)
func (s *Server) handleImmichComposeUpdate(w http.ResponseWriter, r *http.Request) {
	bin, dir := immichComposeDir(r)
	if dir == "" || !filepath.IsAbs(dir) {
		writeErr(w, 404, "Immich-Compose-Projekt nicht gefunden (Container immich-server)")
		return
	}
	logf("immich compose update in %s", dir)
	jobStarted(w, s.jobs.Start("Immich: Update (compose pull + up)", time.Hour, nil,
		"sh", "-c", `cd "$1" && "$2" compose pull && "$2" compose up -d && "$2" image prune -f`, "sh", dir, bin))
}
