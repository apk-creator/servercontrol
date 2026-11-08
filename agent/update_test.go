package main

import "testing"

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"v1.0.0", "1.0.0", 0},
		{"v1.1.0", "1.0.9", 1},
		{"1.0.0", "1.0.1", -1},
		{"v2.0.0", "0.0.0-abc1234", 1},
		{"1.10.0", "1.9.3", 1},
	}
	for _, c := range cases {
		if got := compareVersions(c.a, c.b); got != c.want {
			t.Errorf("compareVersions(%q,%q)=%d want %d", c.a, c.b, got, c.want)
		}
	}
}

func TestPasswordHash(t *testing.T) {
	h, err := HashPassword("correct horse battery")
	if err != nil {
		t.Fatal(err)
	}
	if !VerifyPassword(h, "correct horse battery") || VerifyPassword(h, "wrong") {
		t.Fatal("password verification broken")
	}
}
