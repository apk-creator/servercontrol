package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestParentDisk(t *testing.T) {
	for in, want := range map[string]string{"/dev/sdb1": "/dev/sdb 1", "/dev/nvme0n1p2": "/dev/nvme0n1 2", "/dev/sdaa12": "/dev/sdaa 12"} {
		d, n := parentDisk(in)
		if d+" "+n != want {
			t.Errorf("%s → %s %s", in, d, n)
		}
	}
}

func TestSetFstab(t *testing.T) {
	dir := t.TempDir()
	fstabPath = filepath.Join(dir, "fstab")
	defer func() { fstabPath = "/etc/fstab" }()
	_ = os.WriteFile(fstabPath, []byte("# x\nUUID=root / ext4 defaults 0 1\nUUID=abc /old ext4 defaults 0 2\n"), 0o644)
	if err := setFstab("abc", "/mnt/neu", "ext4", "defaults,nofail"); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(fstabPath)
	if strings.Contains(string(b), "/old") || !strings.Contains(string(b), "UUID=abc /mnt/neu ext4 defaults,nofail 0 2") || !strings.Contains(string(b), "UUID=root /") {
		t.Fatal(string(b))
	}
	_ = setFstab("abc", "", "", "")
	b, _ = os.ReadFile(fstabPath)
	if strings.Contains(string(b), "abc") {
		t.Fatal(string(b))
	}
}

func TestMountRe(t *testing.T) {
	if !mountRe.MatchString("/mnt/daten") || mountRe.MatchString("/etc") {
		t.Fatal("mountRe")
	}
}

func TestUntilClock(t *testing.T) {
	now := time.Date(2026, 11, 15, 22, 0, 0, 0, time.Local)
	if d, _ := untilClock("03:30", now); d != 5*time.Hour+30*time.Minute {
		t.Fatal(d)
	}
	if d, _ := untilClock("23:00", now); d != time.Hour {
		t.Fatal(d)
	}
	if _, err := untilClock("25:00", now); err == nil {
		t.Fatal("accepted 25:00")
	}
}
