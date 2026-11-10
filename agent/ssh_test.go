package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestParseKeyLine(t *testing.T) {
	files, _ := filepath.Glob("/tmp/kg/*.pub")
	for _, f := range files {
		b, _ := os.ReadFile(f)
		fp, _ := os.ReadFile(f[:len(f)-4] + ".fp")
		k, err := parseKeyLine(string(b))
		if err != nil {
			t.Fatalf("%s: %v", f, err)
		}
		if k.Fingerprint != string(fp) || k.Comment != "servercontrol-test" {
			t.Fatalf("%s: got %+v want %s", f, k, fp)
		}
	}
	if _, err := parseKeyLine(`from="10.0.0.1" ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAyPu4JgHq40IxCLDZUGIgdBkuYWTbRzm43lhzfm+Yz8 x`); err != nil {
		t.Fatal("options line should parse:", err)
	}
	for _, bad := range []string{"", "hello world", "ssh-ed25519 !!!", "ssh-rsa AAAAC3NzaC1lZDI1NTE5AAAAIAyPu4JgHq40IxCLDZUGIgdBkuYWTbRzm43lhzfm+Yz8"} {
		if _, err := parseKeyLine(bad); err == nil {
			t.Fatalf("should reject %q", bad)
		}
	}
}
