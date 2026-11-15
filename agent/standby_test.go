package main

import "testing"

func TestHdparmCode(t *testing.T) {
	for _, c := range []struct{ s, want int }{{0, 0}, {5, 1}, {60, 12}, {600, 120}, {1200, 240}, {1800, 241}, {3600, 242}, {99999, 251}} {
		if got := hdparmCode(c.s); got != c.want {
			t.Errorf("hdparmCode(%d)=%d want %d", c.s, got, c.want)
		}
	}
}

func TestVerifyDigest(t *testing.T) {
	// sha256("abc")
	ok := "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
	if err := verifyDigest([]byte("abc"), ok); err != nil {
		t.Fatal(err)
	}
	if verifyDigest([]byte("abd"), ok) == nil || verifyDigest([]byte("abc"), "") == nil {
		t.Fatal("bad digest accepted")
	}
}
