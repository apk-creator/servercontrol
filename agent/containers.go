package main

import (
	"encoding/json"
	"net/http"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

// Docker/Podman containers (e.g. Immich runs in Docker).

func containerBin() string {
	for _, b := range []string{"docker", "podman"} {
		if p, err := exec.LookPath(b); err == nil {
			return p
		}
	}
	return ""
}

func handleContainers(w http.ResponseWriter, r *http.Request) {
	bin := containerBin()
	if bin == "" {
		writeJSON(w, 200, []any{})
		return
	}
	res := run(r.Context(), 20*time.Second, nil, bin, "ps", "-a", "--format", "{{json .}}")
	out := []map[string]any{}
	for _, l := range strings.Split(res.Stdout, "\n") {
		var m map[string]any
		if json.Unmarshal([]byte(l), &m) == nil {
			out = append(out, m)
		}
	}
	stats := run(r.Context(), 20*time.Second, nil, bin, "stats", "--no-stream", "--format", "{{json .}}")
	st := map[string]map[string]any{}
	for _, l := range strings.Split(stats.Stdout, "\n") {
		var m map[string]any
		if json.Unmarshal([]byte(l), &m) == nil {
			st[toStr(m["Name"])] = m
		}
	}
	for _, c := range out {
		if s, ok := st[toStr(c["Names"])]; ok {
			c["CPUPerc"], c["MemUsage"] = s["CPUPerc"], s["MemUsage"]
		}
	}
	writeJSON(w, 200, out)
}

var containerRe = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$`)

func handleContainerAction(w http.ResponseWriter, r *http.Request) {
	name, act := r.PathValue("name"), r.PathValue("action")
	bin := containerBin()
	if bin == "" || !containerRe.MatchString(name) || (act != "start" && act != "stop" && act != "restart") {
		writeErr(w, 400, "invalid container or action")
		return
	}
	logf("container %s %s", act, name)
	sendResult(w, run(r.Context(), 120*time.Second, nil, bin, act, name))
}

func handleContainerLogs(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	bin := containerBin()
	if bin == "" || !containerRe.MatchString(name) {
		writeErr(w, 400, "invalid container")
		return
	}
	res := run(r.Context(), 20*time.Second, nil, bin, "logs", "--tail", "300", "--timestamps", name)
	writeJSON(w, 200, map[string]string{"log": res.Stdout + res.Stderr})
}
