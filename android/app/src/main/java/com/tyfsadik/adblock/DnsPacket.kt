package com.tyfsadik.adblock

/** Just enough IPv4, UDP and DNS handling to answer queries arriving on the VPN interface. */
object DnsPacket {

    class Question(val name: String, val type: Int, val end: Int)

    /** A DNS query pulled out of an IPv4/UDP packet, with what's needed to address the reply. */
    class Query(val dns: ByteArray, private val srcIp: ByteArray, private val dstIp: ByteArray, private val srcPort: Int) {
        /** Wraps a DNS response in an IPv4/UDP packet going back to the asking app. */
        fun reply(payload: ByteArray): ByteArray {
            val total = 20 + 8 + payload.size
            val p = ByteArray(total)
            p[0] = 0x45
            put16(p, 2, total)
            p[6] = 0x40 // don't fragment
            p[8] = 64 // TTL
            p[9] = 17 // UDP
            dstIp.copyInto(p, 12)
            srcIp.copyInto(p, 16)
            put16(p, 10, checksum(p, 0, 20))
            put16(p, 20, 53)
            put16(p, 22, srcPort)
            put16(p, 24, 8 + payload.size)
            // UDP checksum 0 means "not computed", which IPv4 allows.
            payload.copyInto(p, 28)
            return p
        }
    }

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28

    /** Returns the DNS query in [buf] if it's an IPv4 UDP packet to port 53, else null. */
    fun parse(buf: ByteArray, len: Int): Query? {
        if (len < 28 || (buf[0].toInt() ushr 4) != 4 || buf[9].toInt() != 17) return null
        val ihl = (buf[0].toInt() and 0x0f) * 4
        val total = u16(buf, 2)
        if (total > len || ihl + 8 + 12 > total || u16(buf, ihl + 2) != 53) return null
        return Query(
            dns = buf.copyOfRange(ihl + 8, total),
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
        )
    }

    fun question(msg: ByteArray): Question? {
        if (msg.size < 12 || u16(msg, 4) != 1) return null
        val (name, end) = readName(msg, 12) ?: return null
        if (end + 4 > msg.size) return null
        return Question(name, u16(msg, end), end + 4)
    }

    /** Answers A with 0.0.0.0, AAAA with ::, and anything else with an empty NOERROR. */
    fun blockedAnswer(query: ByteArray, q: Question): ByteArray {
        val rdLen = when (q.type) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> -1
        }
        val answer = if (rdLen < 0) 0 else 12 + rdLen
        val out = ByteArray(q.end + answer)
        query.copyInto(out, 0, 0, q.end)
        out[2] = (0x80 or (query[2].toInt() and 0x79)).toByte() // response, keep opcode + RD
        out[3] = 0x80.toByte() // recursion available, NOERROR
        put16(out, 6, if (rdLen < 0) 0 else 1)
        put16(out, 8, 0)
        put16(out, 10, 0)
        if (rdLen >= 0) {
            var i = q.end
            out[i++] = 0xc0.toByte(); out[i++] = 0x0c // pointer to the question name
            put16(out, i, q.type); i += 2
            put16(out, i, 1); i += 2 // class IN
            put16(out, i, 0); put16(out, i + 2, 300); i += 4 // TTL
            put16(out, i, rdLen) // address bytes are already zero
        }
        return out
    }

    /** CNAME targets in a response, used to catch trackers hiding behind first-party names. */
    fun cnameTargets(msg: ByteArray): List<String> {
        val q = question(msg) ?: return emptyList()
        val count = u16(msg, 6)
        val out = ArrayList<String>()
        var off = q.end
        repeat(count) {
            val (_, nameEnd) = readName(msg, off) ?: return out
            if (nameEnd + 10 > msg.size) return out
            val type = u16(msg, nameEnd)
            val rdLen = u16(msg, nameEnd + 8)
            val rdata = nameEnd + 10
            if (type == TYPE_CNAME) readName(msg, rdata)?.let { out.add(it.first) }
            off = rdata + rdLen
        }
        return out
    }

    /** Reads a possibly compressed name; returns it and the offset just past it. */
    private fun readName(msg: ByteArray, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var off = start
        var end = -1
        var jumps = 0
        while (true) {
            if (off >= msg.size) return null
            val len = msg[off].toInt() and 0xff
            when {
                len == 0 -> {
                    if (end < 0) end = off + 1
                    return sb.toString() to end
                }
                len and 0xc0 == 0xc0 -> {
                    if (off + 1 >= msg.size || ++jumps > 20) return null
                    if (end < 0) end = off + 2
                    off = ((len and 0x3f) shl 8) or (msg[off + 1].toInt() and 0xff)
                }
                else -> {
                    if (off + 1 + len > msg.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 1..len) sb.append((msg[off + i].toInt() and 0xff).toChar())
                    off += 1 + len
                }
            }
        }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        while (i < off + len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }
}
