package main

import "testing"

func TestDevURL(t *testing.T) {
	for addr, want := range map[string]string{
		":8053":          "http://localhost:8053",
		"127.0.0.1:8099": "http://127.0.0.1:8099",
		"0.0.0.0:8053":   "http://localhost:8053",
		"[::1]:8053":     "http://[::1]:8053",
		"[::]:8053":      "http://localhost:8053",
	} {
		if got := devURL(addr); got != want {
			t.Errorf("devURL(%q) = %q, want %q", addr, got, want)
		}
	}
}
