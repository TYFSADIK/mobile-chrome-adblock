<div align="center">

<img src="docs/img/banner.png" alt="Ad blocking for Chrome on your phone" width="100%">

# Mobile Chrome AdBlock

Block ads in Chrome on Android and iPhone, without extensions, root or jailbreak.

[![CI](https://github.com/TYFSADIK/mobile-chrome-adblock/actions/workflows/ci.yml/badge.svg)](https://github.com/TYFSADIK/mobile-chrome-adblock/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

</div>

Chrome on Android doesn't support extensions, and Chrome on iPhone doesn't either. So the browser most
people use on their phone has no way to run uBlock Origin or anything like it.

What both phones do support is choosing your own encrypted DNS server. Every page and app looks up
domain names before loading anything, so if the DNS server says `doubleclick.net` doesn't exist, the ad
never loads. This project is that DNS server. You run it on a cheap VPS, change one setting on your
phone, and ads stop showing up in Chrome, Safari and most other apps.

It blocks about 250,000 ad and tracker domains using the [StevenBlack](https://github.com/StevenBlack/hosts)
and [AdGuard DNS](https://github.com/AdguardTeam/AdGuardSDNSFilter) lists, refreshed daily. Lookups are
encrypted (DNS-over-TLS for Android, DNS-over-HTTPS for iPhone), and it also catches trackers that hide
behind CNAME records. It's a single Go binary with no database.

<div align="center">
<img src="docs/img/screenshots.png" alt="Setup page in light and dark mode" width="720">
<br>
<sub>The setup page your server hosts. Open it on a phone for instructions and live stats.</sub>
</div>

## How it works

<img src="docs/img/how-it-works.png" alt="The phone asks the DNS server for doubleclick.net and gets 0.0.0.0, while wikipedia.org resolves normally" width="100%">

## Quick start

You need:

1. A small Linux server. The Oracle Cloud free tier works, and so does any $4 to $5 VPS (Hetzner, DigitalOcean, Vultr).
2. A domain pointing at it, like `dns.yourname.com` with an A record set to the server's IP.
   A free [DuckDNS](https://www.duckdns.org) subdomain is fine.

Then on the server:

```bash
curl -fsSL https://raw.githubusercontent.com/TYFSADIK/mobile-chrome-adblock/main/install.sh | sudo bash -s dns.yourname.com
```

The script installs Docker if it's missing, opens ports 80, 443 and 853, starts the server and gets a
Let's Encrypt certificate. It prints your phone settings when it's done.

<details>
<summary>Other ways to run it</summary>

Docker Compose:

```bash
git clone https://github.com/TYFSADIK/mobile-chrome-adblock && cd mobile-chrome-adblock
# replace dns.example.com in docker-compose.yml with your domain
docker compose up -d
```

Docker:

```bash
docker run -d --name adblock --restart unless-stopped \
  -p 80:80 -p 443:443 -p 853:853 -v $PWD/certs:/data/certs \
  ghcr.io/tyfsadik/mobile-chrome-adblock -domain dns.yourname.com
```

Prebuilt binary from [Releases](https://github.com/TYFSADIK/mobile-chrome-adblock/releases):

```bash
sudo ./adblock-dns-linux-amd64 -domain dns.yourname.com
```

From source (Go 1.27 or newer):

```bash
go build -o adblock-dns . && sudo ./adblock-dns -domain dns.yourname.com
```

Port 80 is for the certificate, 443 for DNS-over-HTTPS and the setup page, and 853 for DNS-over-TLS.
</details>

## Phone setup

The easiest way is to open `https://dns.yourname.com` on your phone and follow the page. Or:

**Android (whole phone).** Go to Settings > Network & internet > Private DNS, pick "Private DNS provider
hostname" and enter `dns.yourname.com`. On Samsung it's under Settings > Connections > More connection
settings > Private DNS.

**Android (Chrome only).** In Chrome, go to Settings > Privacy and security > Use secure DNS > Custom and
enter `https://dns.yourname.com/dns-query`.

**iPhone and iPad.** Open `https://dns.yourname.com` in Safari (profiles can only be installed from
Safari, but they apply to Chrome too). Tap "Download profile", then go to Settings > Profile Downloaded >
Install. You can confirm it under Settings > General > VPN, DNS & Device Management > DNS.

To check that it's working, run [this ad block test](https://d3ward.github.io/toolz/adblock) in Chrome before and after.

One server is enough for your whole family. Just send them the link.

## Options

| Flag | Default | Description |
|---|---|---|
| `-domain` | none | Public hostname. Turns on HTTPS, DNS-over-TLS and automatic certificates. |
| `-lists` | StevenBlack + AdGuard DNS | Comma-separated blocklist URLs or files, in hosts or `\|\|domain^` format. |
| `-allowlist` | `allowlist.txt` | Domains that should never be blocked, one per line. Covers subdomains. |
| `-upstream` | `1.1.1.1:53,9.9.9.9:53` | Where allowed lookups get forwarded. |
| `-refresh` | `24h` | How often to re-download the blocklists. |
| `-log-blocks` | off | Log every blocked domain. Useful when a site breaks. |
| `-dns-addr` | off | Extra plain DNS listener, e.g. `:53` on a home network. Don't expose it to the internet. |
| `-dev` | off | Local testing without TLS. |

For stricter blocking, add more lists:

```bash
-lists "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts,https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt,https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt"
```

If a site breaks, run with `-log-blocks`, find the domain in the logs, add it to `allowlist.txt` and restart.

## Running locally

```bash
go run . -dev -dns-addr 127.0.0.1:5354
dig @127.0.0.1 -p 5354 doubleclick.net   # 0.0.0.0
dig @127.0.0.1 -p 5354 wikipedia.org     # real IP
open http://localhost:8053               # setup page
```

## FAQ

**Does it block YouTube ads?**
No. YouTube serves ads from the same servers as the videos, so a DNS blocker can't separate them. Same
goes for sponsored posts on Instagram and Facebook. For YouTube you'll want Brave, or Firefox for Android
with uBlock Origin.

**Why not just use AdGuard DNS or NextDNS?**
You can, and they work well. The difference is that this runs on your own server, so nobody else sees
which sites you visit, and there's no query limit (NextDNS's free tier stops filtering after 300k
queries a month).

**Does it slow the phone down or use more battery?**
No. The phone does DNS lookups anyway, this just changes who answers them. Pages tend to load faster
because the ads and trackers are skipped.

**Does it work on mobile data?**
Yes, both Private DNS and the iOS profile apply on every network.

**How do I turn it off?**
On Android, set Private DNS back to Automatic. On iPhone, remove the profile under Settings > General >
VPN, DNS & Device Management.

**Can I run it at home?**
Yes, on a Raspberry Pi or any machine that's always on, as long as ports 80, 443 and 853 are forwarded
and you have a domain (DuckDNS works). There are arm64 and armv7 builds.

## Contributing

Issues and pull requests are welcome. Run `go test ./...` before sending a PR.

## License

[MIT](LICENSE), Taki Sadik
