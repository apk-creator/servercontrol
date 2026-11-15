package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestKopiaServerClient(t *testing.T) {
	mux := http.NewServeMux()
	auth := func(r *http.Request) bool { u, p, ok := r.BasicAuth(); return ok && u == "admin" && p == "geheim" }
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if !auth(r) {
			w.WriteHeader(401)
			return
		}
		http.SetCookie(w, &http.Cookie{Name: "Kopia-Session-Cookie", Value: "s1"})
		w.Write([]byte(`<html><head><meta name="kopia-csrf-token" content="tok123"></head></html>`))
	})
	check := func(w http.ResponseWriter, r *http.Request) bool {
		if !auth(r) || r.Header.Get("X-Kopia-Csrf-Token") != "tok123" {
			w.WriteHeader(403)
			w.Write([]byte("Invalid or missing CSRF token"))
			return false
		}
		return true
	}
	mux.HandleFunc("/api/v1/sources", func(w http.ResponseWriter, r *http.Request) {
		if check(w, r) {
			w.Write([]byte(`{"sources":[{"source":{"host":"server1","userName":"root","path":"/srv/a"}}]}`))
		}
	})
	mux.HandleFunc("/api/v1/snapshots", func(w http.ResponseWriter, r *http.Request) {
		if check(w, r) && r.URL.Query().Get("path") == "/srv/a" {
			w.Write([]byte(`{"snapshots":[{"id":"abc","startTime":"2026-11-14T01:00:00+01:00","retention":["latest-1"],"summary":{"size":123,"files":4,"numFailed":0}}]}`))
		}
	})
	ts := httptest.NewTLSServer(mux)
	defer ts.Close()
	srv := &kopiaSrv{Unit: "kopia-test", URLs: []string{ts.URL}, User: "admin", Pass: "geheim"}
	snaps, err := srv.serverSnapshots(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(snaps) != 1 || snaps[0]["id"] != "abc" || snaps[0]["totalSize"].(float64) != 123 {
		t.Fatalf("unexpected: %#v", snaps)
	}
	bad := &kopiaSrv{Unit: "x", URLs: []string{ts.URL}, User: "admin", Pass: "falsch"}
	if _, err := bad.serverSnapshots(context.Background()); err == nil {
		t.Fatal("expected auth error")
	}
}
