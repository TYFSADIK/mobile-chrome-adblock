#!/usr/bin/env python3
"""Download the blocklists and write them as sorted 64-bit FNV-1a hashes.

The iOS tunnel extension memory-maps these files instead of parsing text at
startup, which keeps it fast and well under the extension memory limit.
Parsing rules match blocklist.go and the Android app.

usage: build-blocklist.py OUTPUT_DIR
"""
import struct
import sys
import urllib.request

HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"
LISTS = [
    "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    HAGEZI + "pro.plus-onlydomains.txt",
    HAGEZI + "doh-onlydomains.txt",
]
IGNORED = {"localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback"}
MASK = (1 << 64) - 1


def fnv1a(s):
    h = 0xCBF29CE484222325
    for ch in s:
        h ^= ord(ch)
        h = (h * 0x100000001B3) & MASK
    return h


def normalize(s):
    d = s.strip().lower().rstrip(".")
    if not d or "." not in d or d in IGNORED or any(c in d for c in "*/:|^$ "):
        return None
    return d


def parse(text, block, allow):
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line[0] in "#![":
            continue
        line = line.split("#", 1)[0].strip()
        target = block
        if line.startswith("@@"):
            target, line = allow, line[2:]
        if line.startswith("||"):
            line, _, mods = line[2:].partition("$")
            if mods and mods != "important":
                continue
            if not line.endswith("^"):
                continue
            names = [line[:-1]]
        else:
            fields = line.split()
            names = fields[1:] if len(fields) >= 2 else fields
        for name in names:
            d = normalize(name)
            if d:
                target.add(fnv1a(d))


def write(path, hashes):
    with open(path, "wb") as f:
        for h in sorted(hashes):
            f.write(struct.pack("<Q", h))


def main():
    out = sys.argv[1]
    block, allow = set(), set()
    for url in LISTS:
        with urllib.request.urlopen(url, timeout=60) as r:
            parse(r.read().decode("utf-8", "replace"), block, allow)
        print(f"{url}: {len(block)} total")
    write(f"{out}/blocklist.bin", block)
    write(f"{out}/allowlist.bin", allow)
    print(f"wrote {len(block)} blocked, {len(allow)} allowed")


if __name__ == "__main__":
    main()
