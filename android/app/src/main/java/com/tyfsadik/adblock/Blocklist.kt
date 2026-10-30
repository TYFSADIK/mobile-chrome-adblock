package com.tyfsadik.adblock

import java.io.BufferedReader

/**
 * Domain matcher. Domains are stored as sorted 64-bit hashes (8 bytes each),
 * so half a million domains fit in about 4 MB. A name is blocked when it or
 * any parent domain is listed, unless it or a parent is allowed.
 */
class Blocklist private constructor(
    private val block: LongArray,
    private val allow: LongArray,
    private val userAllow: Set<String>,
) {
    val size: Int get() = block.size

    fun isBlocked(name: String): Boolean {
        var d = name.trimEnd('.').lowercase()
        var hit = false
        while (true) {
            if (d in userAllow) return false
            val h = hash(d)
            if (allow.binarySearch(h) >= 0) return false
            if (!hit && block.binarySearch(h) >= 0) hit = true
            val i = d.indexOf('.')
            if (i < 0) break
            d = d.substring(i + 1)
        }
        return hit
    }

    fun withUserAllow(allowed: Set<String>) = Blocklist(block, allow, allowed)

    class Builder {
        private var block = LongArray(1 shl 16)
        private var blockCount = 0
        private var allow = LongArray(256)
        private var allowCount = 0

        fun addList(reader: BufferedReader) {
            reader.useLines { lines -> lines.forEach(::addLine) }
        }

        /**
         * Understands hosts files (`0.0.0.0 ads.example.com`), plain domain
         * lists, and AdBlock-style DNS rules (`||ads.example.com^`,
         * `@@||good.example.com^`).
         */
        fun addLine(raw: String) {
            var line = raw.trim()
            if (line.isEmpty() || line[0] == '#' || line[0] == '!' || line[0] == '[') return
            line.indexOf('#').let { if (it >= 0) line = line.substring(0, it).trim() }
            var isAllow = false
            if (line.startsWith("@@")) {
                isAllow = true
                line = line.substring(2)
            }
            if (line.startsWith("||")) {
                line = line.substring(2)
                var mods = ""
                val i = line.indexOf('$')
                if (i >= 0) {
                    mods = line.substring(i + 1)
                    line = line.substring(0, i)
                }
                // Rules scoped to specific clients or record types don't apply here.
                if (mods.isNotEmpty() && mods != "important") return
                if (!line.endsWith('^')) return
                add(line.dropLast(1), isAllow)
                return
            }
            val fields = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (fields.size >= 2) {
                for (name in fields.drop(1)) add(name, isAllow)
                return
            }
            add(line, isAllow)
        }

        private fun add(name: String, isAllow: Boolean) {
            val d = normalize(name) ?: return
            val h = hash(d)
            if (isAllow) {
                if (allowCount == allow.size) allow = allow.copyOf(allow.size * 2)
                allow[allowCount++] = h
            } else {
                if (blockCount == block.size) block = block.copyOf(block.size * 2)
                block[blockCount++] = h
            }
        }

        fun build(userAllow: Set<String> = emptySet()) =
            Blocklist(sortedUnique(block, blockCount), sortedUnique(allow, allowCount), userAllow)

        private fun sortedUnique(a: LongArray, n: Int): LongArray {
            val s = a.copyOf(n)
            s.sort()
            var w = 0
            for (i in s.indices) if (i == 0 || s[i] != s[i - 1]) s[w++] = s[i]
            return s.copyOf(w)
        }
    }

    companion object {
        private val ignored = setOf("localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")

        fun normalize(s: String): String? {
            val d = s.trim().lowercase().trimEnd('.')
            if (d.isEmpty() || '.' !in d || d in ignored) return null
            if (d.any { it in "*/:|^$ " }) return null
            return d
        }

        /** 64-bit FNV-1a. */
        fun hash(s: String): Long {
            var h = 0xcbf29ce484222325uL.toLong()
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
