// Command adblock-dns is an ad-blocking DNS server for phones.
//
// Mobile Chrome (Android and iOS) has no extension support, but both
// operating systems can send all DNS through an encrypted resolver:
// Android via Private DNS (DNS-over-TLS) and iOS via a configuration
// profile (DNS-over-HTTPS). This server answers ad and tracker domains with
// 0.0.0.0, so those ads never load in Chrome or any other app.
package main

import (
	"crypto/tls"
	"flag"
	"log"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/miekg/dns"
	"golang.org/x/crypto/acme/autocert"
)

func main() {
	var (
		domain    = flag.String("domain", "", "public hostname, e.g. dns.example.com (enables HTTPS + DNS-over-TLS with Let's Encrypt)")
		email     = flag.String("email", "", "contact email for Let's Encrypt (optional)")
		certDir   = flag.String("cert-dir", "certs", "where Let's Encrypt certificates are cached")
		dev       = flag.Bool("dev", false, "local testing: plain HTTP DoH on -http-addr and plain DNS on -dns-addr, no TLS")
		httpAddr  = flag.String("http-addr", ":8053", "listen address for DoH in -dev mode")
		dnsAddr   = flag.String("dns-addr", "", "optional plain DNS listener, e.g. :53 (keep firewalled to your own network)")
		level     = flag.String("level", "strong", "blocking level: strong, or ultimate (blocks more, may break some sites)")
		lists     = flag.String("lists", "", "comma-separated blocklist URLs or files, replacing the -level lists")
		allowFile = flag.String("allowlist", "allowlist.txt", "file of domains that must never be blocked, one per line")
		upstream  = flag.String("upstream", "1.1.1.1:53,9.9.9.9:53", "comma-separated upstream DNS servers")
		refresh   = flag.Duration("refresh", 24*time.Hour, "how often to re-download blocklists")
		logBlocks = flag.Bool("log-blocks", false, "log every blocked domain")
	)
	flag.Parse()
	if *domain == "" && !*dev && *dnsAddr == "" {
		log.Fatal("set -domain dns.example.com for production, -dns-addr for a local resolver, or -dev for testing")
	}
	sources := splitList(*lists)
	if len(sources) == 0 {
		var ok bool
		if sources, ok = Levels[*level]; !ok {
			log.Fatalf("unknown -level %q (use strong or ultimate)", *level)
		}
	}
	useUpstreamForDownloads(splitList(*upstream))

	bl := NewBlocklist(sources, *allowFile)
	bl.Reload()
	go bl.AutoReload(*refresh)

	res := NewResolver(bl, splitList(*upstream), *logBlocks)

	if *dnsAddr != "" {
		for _, n := range []string{"udp", "tcp"} {
			srv := &dns.Server{Addr: *dnsAddr, Net: n, Handler: res}
			go func() { log.Fatal(srv.ListenAndServe()) }()
		}
		log.Printf("plain DNS on %s", *dnsAddr)
	}

	if *domain == "" && !*dev {
		select {} // local resolver only (e.g. Termux on a rooted phone)
	}

	if *dev {
		local := devURL(*httpAddr)
		base, host := local, "localhost"
		if *domain != "" {
			// Preview the setup page as it will look in production.
			base, host = "https://"+*domain, *domain
		}
		log.Printf("dev mode: DoH at %s%s, setup page at %s/", local, dohPath, local)
		log.Fatal(http.ListenAndServe(*httpAddr, newMux(res, bl, base, host)))
	}

	m := &autocert.Manager{
		Prompt:     autocert.AcceptTOS,
		HostPolicy: autocert.HostWhitelist(*domain),
		Cache:      autocert.DirCache(*certDir),
		Email:      *email,
	}

	// :80 answers Let's Encrypt challenges and redirects everything else to HTTPS.
	go func() { log.Fatal(http.ListenAndServe(":80", m.HTTPHandler(nil))) }()

	// :853 DNS-over-TLS for Android Private DNS. ALPN is left unset so
	// clients that offer "dot" or nothing at all both connect.
	dotTLS := &tls.Config{GetCertificate: m.GetCertificate, MinVersion: tls.VersionTLS12}
	dot := &dns.Server{Addr: ":853", Net: "tcp-tls", TLSConfig: dotTLS, Handler: res}
	go func() { log.Fatal(dot.ListenAndServe()) }()

	// :443 DNS-over-HTTPS for iOS profiles and Chrome's Secure DNS, plus the setup page.
	https := &http.Server{
		Addr:              ":443",
		Handler:           newMux(res, bl, "https://"+*domain, *domain),
		TLSConfig:         m.TLSConfig(),
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("serving https://%s (DoH %s) and DoT %s:853", *domain, dohPath, *domain)
	log.Fatal(https.ListenAndServeTLS("", ""))
}

// devURL turns a listen address such as ":8053", "127.0.0.1:8053" or
// "[::1]:8053" into a URL a browser can open, using localhost when the
// address has no host.
func devURL(addr string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "http://" + addr
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "localhost"
	}
	return "http://" + net.JoinHostPort(host, port)
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
