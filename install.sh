#!/usr/bin/env bash
# One-line installer for mobile-chrome-adblock on a fresh Linux server.
#
#   curl -fsSL https://raw.githubusercontent.com/TYFSADIK/mobile-chrome-adblock/main/install.sh | sudo bash -s dns.yourdomain.com
#
set -euo pipefail

DOMAIN="${1:-}"
IMAGE="ghcr.io/tyfsadik/mobile-chrome-adblock:latest"
DIR="/opt/mobile-chrome-adblock"

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (use sudo)"
[ -n "$DOMAIN" ] || die "usage: install.sh dns.yourdomain.com"

say "Checking that $DOMAIN points to this server"
SERVER_IP="$(curl -4 -fsS https://api.ipify.org || true)"
DOMAIN_IP="$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)"
if [ -z "$DOMAIN_IP" ]; then
  die "$DOMAIN does not resolve yet. Add an A record pointing to ${SERVER_IP:-this server} and try again."
elif [ -n "$SERVER_IP" ] && [ "$DOMAIN_IP" != "$SERVER_IP" ]; then
  echo "warning: $DOMAIN resolves to $DOMAIN_IP but this server is $SERVER_IP."
  echo "         HTTPS certificates will fail until the A record is fixed."
fi

if ! command -v docker >/dev/null 2>&1; then
  say "Installing Docker"
  curl -fsSL https://get.docker.com | sh
fi

for port in 80 443 853; do
  if ss -ltn "sport = :$port" | grep -q LISTEN; then
    die "port $port is already in use. Stop whatever is using it (e.g. nginx/apache) and re-run."
  fi
done

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  say "Opening firewall ports 80, 443, 853"
  ufw allow 80/tcp >/dev/null && ufw allow 443/tcp >/dev/null && ufw allow 853/tcp >/dev/null
fi

say "Writing $DIR/docker-compose.yml"
mkdir -p "$DIR/certs"
[ -f "$DIR/allowlist.txt" ] || echo "# Domains that must never be blocked, one per line." > "$DIR/allowlist.txt"
cat > "$DIR/docker-compose.yml" <<EOF
services:
  adblock-dns:
    image: $IMAGE
    restart: unless-stopped
    command: ["-domain", "$DOMAIN"]
    ports: ["80:80", "443:443", "853:853"]
    volumes:
      - ./certs:/data/certs
      - ./allowlist.txt:/data/allowlist.txt:ro
EOF

say "Starting"
cd "$DIR"
docker compose pull -q
docker compose up -d

say "Waiting for the HTTPS certificate"
for _ in $(seq 1 30); do
  if curl -fsS -o /dev/null "https://$DOMAIN/"; then
    break
  fi
  sleep 2
done

cat <<EOF

  Ad blocker is running.

  Open this on your phone and follow the steps:
     https://$DOMAIN

  Android → Settings → Private DNS → hostname:  $DOMAIN
  iPhone  → open https://$DOMAIN in Safari → Download profile

  Logs:    cd $DIR && docker compose logs -f
  Update:  cd $DIR && docker compose pull && docker compose up -d

EOF
