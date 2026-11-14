package main

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFilesRoundtrip(t *testing.T) {
	dir := t.TempDir()
	// upload
	req := httptest.NewRequest("POST", "/api/files/upload?dir="+dir+"&name=a.txt", strings.NewReader("hallo"))
	rec := httptest.NewRecorder()
	handleFilesUpload(rec, req)
	if rec.Code != 200 {
		t.Fatalf("upload: %d %s", rec.Code, rec.Body)
	}
	// second upload without overwrite → 409
	rec = httptest.NewRecorder()
	handleFilesUpload(rec, httptest.NewRequest("POST", "/api/files/upload?dir="+dir+"&name=a.txt", strings.NewReader("x")))
	if rec.Code != 409 {
		t.Fatalf("expected 409, got %d", rec.Code)
	}
	// bad name
	rec = httptest.NewRecorder()
	handleFilesUpload(rec, httptest.NewRequest("POST", "/api/files/upload?dir="+dir+"&name=../x", strings.NewReader("x")))
	if rec.Code != 400 {
		t.Fatalf("expected 400, got %d", rec.Code)
	}
	// list
	rec = httptest.NewRecorder()
	handleFilesList(rec, httptest.NewRequest("GET", "/api/files/list?path="+dir, nil))
	var l struct{ Entries []fileEntry }
	_ = json.Unmarshal(rec.Body.Bytes(), &l)
	if len(l.Entries) != 1 || l.Entries[0].Name != "a.txt" || l.Entries[0].Size != 5 {
		t.Fatalf("list: %s", rec.Body)
	}
	// view
	rec = httptest.NewRecorder()
	handleFilesDownload(rec, httptest.NewRequest("GET", "/api/files/download?view=1&path="+filepath.Join(dir, "a.txt"), nil))
	if !strings.Contains(rec.Body.String(), "hallo") {
		t.Fatalf("view: %s", rec.Body)
	}
	// mkdir, rename, delete
	rec = httptest.NewRecorder()
	handleFilesMkdir(rec, httptest.NewRequest("POST", "/", strings.NewReader(`{"path":"`+dir+`/sub"}`)))
	if rec.Code != 200 {
		t.Fatalf("mkdir: %s", rec.Body)
	}
	rec = httptest.NewRecorder()
	handleFilesRename(rec, httptest.NewRequest("POST", "/", strings.NewReader(`{"from":"`+dir+`/a.txt","to":"`+dir+`/sub/b.txt"}`)))
	if rec.Code != 200 {
		t.Fatalf("rename: %s", rec.Body)
	}
	rec = httptest.NewRecorder()
	handleFilesDelete(rec, httptest.NewRequest("POST", "/", strings.NewReader(`{"path":"`+dir+`/sub","recursive":true}`)))
	if rec.Code != 200 {
		t.Fatalf("delete: %s", rec.Body)
	}
	if _, err := os.Stat(dir + "/sub"); err == nil {
		t.Fatal("sub still exists")
	}
	rec = httptest.NewRecorder()
	handleFilesDelete(rec, httptest.NewRequest("POST", "/", strings.NewReader(`{"path":"/etc"}`)))
	if rec.Code != 403 {
		t.Fatalf("protected: %d", rec.Code)
	}
}
