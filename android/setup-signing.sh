#!/usr/bin/env bash
# One-time setup: creates the key that signs the Android app and stores it as
# GitHub Actions secrets, so every APK release can update the previous one.
# Keep a backup of ~/.config/mobile-chrome-adblock; if it's lost, users will
# have to uninstall and reinstall to get future updates.
set -euo pipefail
REPO="${1:-TYFSADIK/mobile-chrome-adblock}"
DIR="$HOME/.config/mobile-chrome-adblock"
mkdir -p "$DIR" && chmod 700 "$DIR"
if [ ! -f "$DIR/release.jks" ]; then
  PW=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)
  keytool -genkeypair -keystore "$DIR/release.jks" -storetype PKCS12 -alias adblock \
    -keyalg RSA -keysize 4096 -validity 36500 -storepass "$PW" -keypass "$PW" \
    -dname "CN=mobile-chrome-adblock"
  printf '%s' "$PW" > "$DIR/password"
  chmod 600 "$DIR"/*
fi
PW=$(cat "$DIR/password")
base64 < "$DIR/release.jks" | gh secret set ANDROID_KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$PW" | gh secret set ANDROID_KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$PW" | gh secret set ANDROID_KEY_PASSWORD -R "$REPO"
printf 'adblock' | gh secret set ANDROID_KEY_ALIAS -R "$REPO"
echo "Done. Signing key: $DIR/release.jks (back this folder up)."
