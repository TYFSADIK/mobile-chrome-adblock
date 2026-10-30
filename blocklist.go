package main

import (
	"bufio"
	"context"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const hagezi = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"

// Levels maps each blocking level to its lists. Hosts-format, plain domain
// and AdBlock-style (||domain^) lists are all understood. Every level also
// blocks public encrypted-DNS servers so apps can't route around the filter.
var Levels = map[string][]string{
	"strong": {
		"https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
		"https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
		hagezi + "pro.plus-onlydomains.txt",
		hagezi + "doh-onlydomains.txt",
	},
	"ultimate": {
		"https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
		"https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
		hagezi + "ultimate-onlydomains.txt",
		hagezi + "doh-onlydomains.txt",
	},
}

// httpClient downloads blocklists. It resolves names through the upstream
// servers directly, which keeps working on Android (no /etc/resolv.conf) and
// when this process is itself the system resolver.
var httpClient = &http.Client{Timeout: 60 * time.Second}

func useUpstreamForDownloads(upstreams []string) {
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			var err error
			for _, up := range upstreams {
				var c net.Conn
				if c, err = d.DialContext(ctx, network, up); err == nil {
					return c, nil
				}
			}
			return nil, err
		},
	}
	dialer := &net.Dialer{Timeout: 15 * time.Second, Resolver: resolver}
	httpClient.Transport = &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         dialer.DialContext,
		TLSHandshakeTimeout: 15 * time.Second,
	}
}

type ruleSet struct {
	block map[string]struct{}
	allow map[string]struct{}
}

// Blocklist matches a domain and all of its parent domains against the
// loaded rules. Allow rules always win over block rules.
type Blocklist struct {
	sources   []string
	allowFile string
	rules     atomic.Pointer[ruleSet]
}

func NewBlocklist(sources []string, allowFile string) *Blocklist {
	b := &Blocklist{sources: sources, allowFile: allowFile}
	b.rules.Store(&ruleSet{block: map[string]struct{}{}, allow: map[string]struct{}{}})
	return b
}

func (b *Blocklist) Blocked(name string) bool {
	rs := b.rules.Load()
	name = strings.TrimSuffix(strings.ToLower(name), ".")
	blocked := false
	for d := name; d != ""; {
		if _, ok := rs.allow[d]; ok {
			return false
		}
		if _, ok := rs.block[d]; ok {
			blocked = true
		}
		i := strings.IndexByte(d, '.')
		if i < 0 {
			break
		}
		d = d[i+1:]
	}
	return blocked
}

func (b *Blocklist) Size() int { return len(b.rules.Load().block) }

// Reload downloads every source in parallel and atomically swaps in the
// new rule set. If every source fails, the previous rules are kept.
func (b *Blocklist) Reload() {
	rs := &ruleSet{block: map[string]struct{}{}, allow: map[string]struct{}{}}
	var mu sync.Mutex
	var wg sync.WaitGroup
	var ok atomic.Int32
	for _, src := range b.sources {
		wg.Add(1)
		go func(src string) {
			defer wg.Done()
			r, err := open(src)
			if err != nil {
				log.Printf("blocklist %s: %v", src, err)
				return
			}
			defer r.Close()
			local := &ruleSet{block: map[string]struct{}{}, allow: map[string]struct{}{}}
			parse(r, local)
			mu.Lock()
			for d := range local.block {
				rs.block[d] = struct{}{}
			}
			for d := range local.allow {
				rs.allow[d] = struct{}{}
			}
			mu.Unlock()
			ok.Add(1)
			log.Printf("blocklist %s: %d domains", src, len(local.block))
		}(src)
	}
	wg.Wait()
	if b.allowFile != "" {
		if f, err := os.Open(b.allowFile); err == nil {
			sc := bufio.NewScanner(f)
			for sc.Scan() {
				if d := normalize(sc.Text()); d != "" {
					rs.allow[d] = struct{}{}
				}
			}
			f.Close()
		} else if !os.IsNotExist(err) {
			log.Printf("allowlist: %v", err)
		}
	}
	if ok.Load() == 0 && len(b.sources) > 0 {
		log.Printf("blocklist: all sources failed, keeping previous %d rules", b.Size())
		return
	}
	b.rules.Store(rs)
	log.Printf("blocklist: %d blocked domains, %d allowed", len(rs.block), len(rs.allow))
}

func (b *Blocklist) AutoReload(every time.Duration) {
	for range time.Tick(every) {
		b.Reload()
	}
}

func open(src string) (io.ReadCloser, error) {
	if !strings.HasPrefix(src, "http://") && !strings.HasPrefix(src, "https://") {
		return os.Open(src)
	}
	resp, err := httpClient.Get(src)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		return nil, &httpError{resp.Status}
	}
	return resp.Body, nil
}

type httpError struct{ status string }

func (e *httpError) Error() string { return "HTTP " + e.status }

// parse understands:
//
//	0.0.0.0 ads.example.com        (hosts file)
//	ads.example.com                (plain domain list)
//	||ads.example.com^             (AdBlock / AdGuard DNS syntax)
//	@@||good.example.com^          (exception)
func parse(r io.Reader, rs *ruleSet) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || line[0] == '#' || line[0] == '!' || line[0] == '[' {
			continue
		}
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		target := rs.block
		if strings.HasPrefix(line, "@@") {
			target = rs.allow
			line = line[2:]
		}
		if strings.HasPrefix(line, "||") {
			line = line[2:]
			mods := ""
			if i := strings.IndexByte(line, '$'); i >= 0 {
				line, mods = line[:i], line[i+1:]
			}
			// Rules scoped to specific clients or record types don't apply here.
			if mods != "" && mods != "important" {
				continue
			}
			if !strings.HasSuffix(line, "^") {
				continue
			}
			line = strings.TrimSuffix(line, "^")
		} else if f := strings.Fields(line); len(f) >= 2 {
			// hosts format: IP followed by one or more names
			for _, name := range f[1:] {
				if d := normalize(name); d != "" {
					target[d] = struct{}{}
				}
			}
			continue
		}
		if d := normalize(line); d != "" {
			target[d] = struct{}{}
		}
	}
}

func normalize(s string) string {
	d := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(s)), ".")
	if d == "" || !strings.Contains(d, ".") || strings.ContainsAny(d, "*/:|^$ ") {
		return ""
	}
	switch d {
	case "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback":
		return ""
	}
	return d
}
