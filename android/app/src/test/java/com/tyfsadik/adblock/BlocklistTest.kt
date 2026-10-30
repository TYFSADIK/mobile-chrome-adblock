package com.tyfsadik.adblock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BlocklistTest {

    private val list = Blocklist.Builder().apply {
        """
        # hosts comment
        0.0.0.0 ads.example.com
        127.0.0.1 localhost
        0.0.0.0 tracker.net # inline comment
        plain-list.org
        ! adblock comment
        ||doubleclick.net^
        ||important.io^${'$'}important
        ||scoped.io^${'$'}client=1.2.3.4
        ||*.wild.com^
        @@||safe.doubleclick.net^
        """.trimIndent().lines().forEach(::addLine)
    }.build(setOf("user-allowed.tracker.net"))

    @Test
    fun matching() {
        val cases = mapOf(
            "ads.example.com." to true,
            "sub.ads.example.com" to true,
            "example.com" to false,
            "tracker.net" to true,
            "user-allowed.tracker.net" to false,
            "x.user-allowed.tracker.net" to false,
            "plain-list.org" to true,
            "stats.g.doubleclick.net." to true,
            "safe.doubleclick.net" to false,
            "cdn.safe.doubleclick.net" to false,
            "important.io" to true,
            "scoped.io" to false,
            "wild.com" to false,
            "localhost" to false,
            "ADS.Example.COM" to true,
        )
        for ((name, want) in cases) assertEquals(name, want, list.isBlocked(name))
        assertEquals(5, list.size)
    }

    // dig-style query for "ads.example.com" type A, id 0x1234, RD set
    private val query = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
        3, 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(),
        7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0,
        0, 1, 0, 1,
    )

    @Test
    fun blockedAnswer() {
        val q = DnsPacket.question(query)!!
        assertEquals("ads.example.com", q.name)
        assertEquals(DnsPacket.TYPE_A, q.type)
        val a = DnsPacket.blockedAnswer(query, q)
        assertEquals(0x12, a[0].toInt())
        assertEquals(0x34, a[1].toInt())
        assertEquals(0x81, a[2].toInt() and 0xff) // QR + RD
        assertEquals(1, a[7].toInt()) // one answer
        assertArrayEquals(byteArrayOf(0, 4, 0, 0, 0, 0), a.copyOfRange(a.size - 6, a.size)) // rdlen 4, 0.0.0.0
    }

    @Test
    fun ipv4RoundTrip() {
        val ip = ByteArray(28 + query.size)
        ip[0] = 0x45; ip[3] = ip.size.toByte(); ip[9] = 17
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(ip, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(ip, 16)
        ip[20] = 0xa4.toByte(); ip[21] = 0x10 // src port 42000
        ip[23] = 53
        query.copyInto(ip, 28)
        val parsed = DnsPacket.parse(ip, ip.size)
        assertNotNull(parsed)
        assertArrayEquals(query, parsed!!.dns)
        val reply = parsed.reply(byteArrayOf(9, 9))
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), reply.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), reply.copyOfRange(16, 20))
        assertEquals(0xa4.toByte(), reply[22]); assertEquals(0x10.toByte(), reply[23])
        ip[23] = 80
        assertNull(DnsPacket.parse(ip, ip.size))
    }

    @Test
    fun cnameTargets() {
        val resp = query.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = 1 } +
            byteArrayOf(0xc0.toByte(), 0x0c, 0, 5, 0, 1, 0, 0, 0, 60, 0, 6, 3, 'c'.code.toByte(), 'd'.code.toByte(), 'n'.code.toByte(), 0xc0.toByte(), 0x10)
        assertEquals(listOf("cdn.example.com"), DnsPacket.cnameTargets(resp))
    }
}
