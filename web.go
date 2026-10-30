package main

import (
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"html/template"
	"io"
	"net/http"
	"strings"

	"github.com/miekg/dns"
)

const dohPath = "/dns-query"

func newMux(res *Resolver, bl *Blocklist, publicBase, dotHost string) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc(dohPath, func(w http.ResponseWriter, r *http.Request) { serveDoH(w, r, res) })
	mux.HandleFunc("/ios.mobileconfig", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/x-apple-aspen-config")
		w.Header().Set("Content-Disposition", `attachment; filename="adblock-dns.mobileconfig"`)
		io.WriteString(w, mobileconfig(publicBase+dohPath, dotHost))
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		page.Execute(w, map[string]any{
			"DoH":     publicBase + dohPath,
			"DoT":     dotHost,
			"Domains": int64(bl.Size()),
			"Queries": res.Queries.Load(),
			"Blocks":  res.Blocks.Load(),
		})
	})
	return mux
}

// serveDoH implements RFC 8484 (GET ?dns=base64url and POST application/dns-message).
func serveDoH(w http.ResponseWriter, r *http.Request, res *Resolver) {
	var raw []byte
	var err error
	switch r.Method {
	case http.MethodGet:
		raw, err = base64.RawURLEncoding.DecodeString(strings.TrimRight(r.URL.Query().Get("dns"), "="))
	case http.MethodPost:
		if ct := r.Header.Get("Content-Type"); ct != "application/dns-message" {
			http.Error(w, "unsupported content type", http.StatusUnsupportedMediaType)
			return
		}
		raw, err = io.ReadAll(io.LimitReader(r.Body, 65535))
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if err != nil || len(raw) == 0 {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	req := new(dns.Msg)
	if err := req.Unpack(raw); err != nil {
		http.Error(w, "malformed DNS message", http.StatusBadRequest)
		return
	}
	// RFC 8484 recommends ID 0 for cacheability; echo whatever the client sent.
	resp := res.Resolve(req)
	resp.Id = req.Id
	out, err := resp.Pack()
	if err != nil {
		http.Error(w, "server error", http.StatusInternalServerError)
		return
	}
	minTTL := uint32(0)
	for i, rr := range resp.Answer {
		if t := rr.Header().Ttl; i == 0 || t < minTTL {
			minTTL = t
		}
	}
	w.Header().Set("Content-Type", "application/dns-message")
	w.Header().Set("Cache-Control", fmt.Sprintf("max-age=%d", minTTL))
	w.Write(out)
}

// mobileconfig builds an iOS/iPadOS/macOS profile that switches the whole
// device to encrypted DNS through this server. UUIDs are derived from the
// URL so reinstalling replaces the old profile instead of duplicating it.
func mobileconfig(dohURL, dotHost string) string {
	id := func(salt string) string {
		h := sha1.Sum([]byte(salt + dohURL))
		return fmt.Sprintf("%X-%X-%X-%X-%X", h[0:4], h[4:6], h[6:8], h[8:10], h[10:16])
	}
	ident := "dns.adblock." + strings.NewReplacer(".", "-", ":", "-").Replace(dotHost)
	return fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>PayloadContent</key>
	<array>
		<dict>
			<key>DNSSettings</key>
			<dict>
				<key>DNSProtocol</key>
				<string>HTTPS</string>
				<key>ServerURL</key>
				<string>%[1]s</string>
			</dict>
			<key>PayloadDisplayName</key>
			<string>Ad-blocking DNS</string>
			<key>PayloadIdentifier</key>
			<string>%[2]s.dns</string>
			<key>PayloadType</key>
			<string>com.apple.dnsSettings.managed</string>
			<key>PayloadUUID</key>
			<string>%[3]s</string>
			<key>PayloadVersion</key>
			<integer>1</integer>
		</dict>
	</array>
	<key>PayloadDescription</key>
	<string>Routes DNS through %[4]s, which blocks ad and tracker domains in Chrome, Safari and all apps.</string>
	<key>PayloadDisplayName</key>
	<string>Ad Blocker (%[4]s)</string>
	<key>PayloadIdentifier</key>
	<string>%[2]s</string>
	<key>PayloadRemovalDisallowed</key>
	<false/>
	<key>PayloadType</key>
	<string>Configuration</string>
	<key>PayloadUUID</key>
	<string>%[5]s</string>
	<key>PayloadVersion</key>
	<integer>1</integer>
</dict>
</plist>
`, template.HTMLEscapeString(dohURL), ident, id("payload"), template.HTMLEscapeString(dotHost), id("profile"))
}

var page = template.Must(template.New("page").Funcs(template.FuncMap{"commas": commas}).Parse(`<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ad Blocker Setup</title>
<style>
:root{--bg:#f6f7f9;--card:#fff;--fg:#16181d;--mute:#5d6470;--acc:#0b6bcb;--line:#e1e4e8;--code:#eef1f5}
@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#181b21;--fg:#e8eaee;--mute:#9aa1ad;--acc:#5aa9ff;--line:#2a2f38;--code:#22262e}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 system-ui,-apple-system,sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 16px 48px}
h1{font-size:1.6rem;margin:0 0 4px}h2{font-size:1.15rem;margin:0 0 10px}
.sub{color:var(--mute);margin:0 0 20px}
.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:20px}
.stat{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px;text-align:center}
.stat b{display:block;font-size:1.2rem;font-variant-numeric:tabular-nums}.stat span{font-size:.8rem;color:var(--mute)}
section{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px;margin-bottom:14px}
ol{padding-left:20px;margin:8px 0}li{margin:6px 0}
code{background:var(--code);padding:2px 6px;border-radius:5px;word-break:break-all;font-size:.92em}
.btn{display:inline-block;background:var(--acc);color:#fff;text-decoration:none;padding:10px 16px;border-radius:8px;font-weight:600}
.note{color:var(--mute);font-size:.9rem}
</style></head><body><main>
<h1>Ad Blocker</h1>
<p class="sub">Blocks ads &amp; trackers in Chrome (and every other app) on iPhone and Android.</p>
<div class="stats">
<div class="stat"><b>{{commas .Domains}}</b><span>domains blocked</span></div>
<div class="stat"><b>{{commas .Queries}}</b><span>queries</span></div>
<div class="stat"><b>{{commas .Blocks}}</b><span>ads stopped</span></div>
</div>

<section><h2>iPhone / iPad</h2>
<ol>
<li>Open this page in <b>Safari</b> (profiles can't be installed from Chrome) and tap:<br><br>
<a class="btn" href="/ios.mobileconfig">Download profile</a></li>
<li>Open <b>Settings → Profile Downloaded → Install</b>.</li>
<li>Then go to <b>Settings → General → VPN, DNS &amp; Device Management → DNS</b> and make sure <b>Ad Blocker</b> is selected.</li>
</ol>
<p class="note">Chrome on iOS will now use it automatically.</p></section>

<section><h2>Android (whole phone, recommended)</h2>
<ol>
<li><b>Settings → Network &amp; internet → Private DNS</b> (Samsung: <b>Connections → More connection settings → Private DNS</b>).</li>
<li>Choose <b>Private DNS provider hostname</b> and enter:<br><code>{{.DoT}}</code></li>
<li>Save. Done.</li>
</ol></section>

<section><h2>Android Chrome only</h2>
<ol>
<li>Chrome → <b>⋮ → Settings → Privacy and security → Use secure DNS</b>.</li>
<li>Choose <b>Custom</b> and enter:<br><code>{{.DoH}}</code></li>
</ol></section>

<p class="note">Test it: <code>doubleclick.net</code> should fail to load once you're set up.</p>
</main></body></html>`))

// commas formats 256320 as "256,320".
func commas(n int64) string {
	s := fmt.Sprint(n)
	for i := len(s) - 3; i > 0; i -= 3 {
		s = s[:i] + "," + s[i:]
	}
	return s
}
