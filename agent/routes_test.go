package main

import "testing"

// registering all routes panics on conflicting patterns
func TestRoutesRegister(t *testing.T) {
	s := &Server{cfg: &ConfigStore{}, jobs: NewJobs()}
	if s.routes() == nil {
		t.Fatal("no handler")
	}
}
