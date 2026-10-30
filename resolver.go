package main

import (
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/miekg/dns"
)

// Resolver answers blocked names with 0.0.0.0 / :: and forwards everything
// else upstream, caching responses for their TTL.
type Resolver struct {
	bl        *Blocklist
	upstreams []string
	client    *dns.Client
	tcpClient *dns.Client
	logBlocks bool

	mu    sync.Mutex
	cache map[cacheKey]cacheEntry

	Queries atomic.Int64
	Blocks  atomic.Int64
}

type cacheKey struct {
	name  string
	qtype uint16
}

type cacheEntry struct {
	msg     *dns.Msg
	expires time.Time
}

const maxCacheEntries = 50000

func NewResolver(bl *Blocklist, upstreams []string, logBlocks bool) *Resolver {
	r := &Resolver{
		bl:        bl,
		upstreams: upstreams,
		client:    &dns.Client{Net: "udp", Timeout: 3 * time.Second},
		tcpClient: &dns.Client{Net: "tcp", Timeout: 5 * time.Second},
		logBlocks: logBlocks,
		cache:     map[cacheKey]cacheEntry{},
	}
	go r.sweep()
	return r
}

func (r *Resolver) Resolve(req *dns.Msg) *dns.Msg {
	r.Queries.Add(1)
	if len(req.Question) != 1 {
		m := new(dns.Msg)
		m.SetRcode(req, dns.RcodeFormatError)
		return m
	}
	q := req.Question[0]

	if r.bl.Blocked(q.Name) {
		r.Blocks.Add(1)
		if r.logBlocks {
			log.Printf("blocked %s", q.Name)
		}
		return blockedReply(req)
	}

	key := cacheKey{dns.CanonicalName(q.Name), q.Qtype}
	if resp := r.fromCache(key); resp != nil {
		resp.Id = req.Id
		return resp
	}

	resp := r.forward(req)
	if resp == nil {
		m := new(dns.Msg)
		m.SetRcode(req, dns.RcodeServerFailure)
		return m
	}
	// CNAME cloaking: an innocent-looking name may alias an ad domain.
	for _, rr := range resp.Answer {
		if c, ok := rr.(*dns.CNAME); ok && r.bl.Blocked(c.Target) {
			r.Blocks.Add(1)
			if r.logBlocks {
				log.Printf("blocked %s (CNAME %s)", q.Name, c.Target)
			}
			return blockedReply(req)
		}
	}
	r.store(key, resp)
	return resp
}

func blockedReply(req *dns.Msg) *dns.Msg {
	m := new(dns.Msg)
	m.SetReply(req)
	m.RecursionAvailable = true
	q := req.Question[0]
	hdr := dns.RR_Header{Name: q.Name, Class: dns.ClassINET, Ttl: 300}
	switch q.Qtype {
	case dns.TypeA:
		hdr.Rrtype = dns.TypeA
		m.Answer = []dns.RR{&dns.A{Hdr: hdr, A: net.IPv4zero}}
	case dns.TypeAAAA:
		hdr.Rrtype = dns.TypeAAAA
		m.Answer = []dns.RR{&dns.AAAA{Hdr: hdr, AAAA: net.IPv6zero}}
	}
	// Other types (HTTPS, SVCB, TXT…) get an empty NOERROR answer so
	// browsers can't learn alternate endpoints for blocked names.
	return m
}

func (r *Resolver) forward(req *dns.Msg) *dns.Msg {
	out := req.Copy()
	out.RecursionDesired = true
	for _, up := range r.upstreams {
		resp, _, err := r.client.Exchange(out, up)
		if err == nil && resp.Truncated {
			resp, _, err = r.tcpClient.Exchange(out, up)
		}
		if err == nil {
			return resp
		}
		log.Printf("upstream %s: %v", up, err)
	}
	return nil
}

func (r *Resolver) fromCache(key cacheKey) *dns.Msg {
	r.mu.Lock()
	e, ok := r.cache[key]
	r.mu.Unlock()
	if !ok {
		return nil
	}
	left := time.Until(e.expires)
	if left <= 0 {
		return nil
	}
	m := e.msg.Copy()
	ttl := uint32(left.Seconds())
	for _, sec := range [][]dns.RR{m.Answer, m.Ns, m.Extra} {
		for _, rr := range sec {
			if rr.Header().Rrtype != dns.TypeOPT {
				rr.Header().Ttl = ttl
			}
		}
	}
	return m
}

func (r *Resolver) store(key cacheKey, resp *dns.Msg) {
	if resp.Rcode != dns.RcodeSuccess && resp.Rcode != dns.RcodeNameError {
		return
	}
	ttl := uint32(300)
	for _, rr := range append(resp.Answer, resp.Ns...) {
		if t := rr.Header().Ttl; t < ttl {
			ttl = t
		}
	}
	if ttl == 0 {
		return
	}
	r.mu.Lock()
	if len(r.cache) < maxCacheEntries {
		r.cache[key] = cacheEntry{resp.Copy(), time.Now().Add(time.Duration(ttl) * time.Second)}
	}
	r.mu.Unlock()
}

func (r *Resolver) sweep() {
	for range time.Tick(time.Minute) {
		now := time.Now()
		r.mu.Lock()
		for k, e := range r.cache {
			if now.After(e.expires) {
				delete(r.cache, k)
			}
		}
		r.mu.Unlock()
	}
}

// ServeDNS lets the resolver back plain DNS and DNS-over-TLS listeners.
func (r *Resolver) ServeDNS(w dns.ResponseWriter, req *dns.Msg) {
	resp := r.Resolve(req)
	if _, udp := w.RemoteAddr().(*net.UDPAddr); udp {
		size := 512
		if opt := req.IsEdns0(); opt != nil {
			size = int(opt.UDPSize())
		}
		resp.Truncate(size)
	}
	w.WriteMsg(resp)
}
