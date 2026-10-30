package main

import (
	"strings"
	"testing"
)

func TestParseAndMatch(t *testing.T) {
	src := `# hosts comment
0.0.0.0 ads.example.com
127.0.0.1 localhost
0.0.0.0 tracker.net # inline comment
plain-list.org
! adblock comment
||doubleclick.net^
||important.io^$important
||scoped.io^$client=1.2.3.4
||*.wild.com^
@@||safe.doubleclick.net^
`
	rs := &ruleSet{block: map[string]struct{}{}, allow: map[string]struct{}{}}
	parse(strings.NewReader(src), rs)
	b := NewBlocklist(nil, "")
	b.rules.Store(rs)

	cases := map[string]bool{
		"ads.example.com.":         true,
		"sub.ads.example.com":      true,
		"example.com":              false,
		"tracker.net":              true,
		"plain-list.org":           true,
		"doubleclick.net":          true,
		"stats.g.doubleclick.net.": true,
		"safe.doubleclick.net":     false,
		"cdn.safe.doubleclick.net": false,
		"important.io":             true,
		"scoped.io":                false,
		"wild.com":                 false,
		"localhost":                false,
		"ADS.Example.COM":          true,
	}
	for name, want := range cases {
		if got := b.Blocked(name); got != want {
			t.Errorf("Blocked(%q) = %v, want %v", name, got, want)
		}
	}
}
