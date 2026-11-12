package com.zerobet.safeguard.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsPacketsTest {

    /** Costruisce una query DNS minima: header + una domanda. */
    private fun dnsQuery(name: String, qtype: Int, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        fun short(v: Int) { out.write(v shr 8); out.write(v and 0xFF) }
        short(id); short(0x0100); short(1); short(0); short(0); short(0)
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        short(qtype); short(1)
        return out.toByteArray()
    }

    private val client = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dns = byteArrayOf(10, 111, 222.toByte(), 2)

    @Test
    fun `round trip di un pacchetto IPv4 UDP`() {
        val payload = dnsQuery("www.Bet365.com", DnsPackets.TYPE_A)
        val packet = DnsPackets.buildIpv4Udp(client, dns, 40000, 53, payload)

        val parsed = DnsPackets.parseIpv4Udp(packet, packet.size)
        assertNotNull(parsed)
        assertEquals(40000, parsed!!.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(client, parsed.srcAddr)
        assertArrayEquals(dns, parsed.dstAddr)
        assertArrayEquals(payload, parsed.payload)

        // Il checksum dell'header IP è valido se la somma a complemento a uno dà 0xFFFF.
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun `i pacchetti non UDP o troncati vengono ignorati`() {
        val packet = DnsPackets.buildIpv4Udp(client, dns, 40000, 53, dnsQuery("a.it", 1))
        assertNull(DnsPackets.parseIpv4Udp(packet, 10))
        val tcp = packet.copyOf().also { it[9] = 6 }
        assertNull(DnsPackets.parseIpv4Udp(tcp, tcp.size))
        val ipv6 = packet.copyOf().also { it[0] = 0x60 }
        assertNull(DnsPackets.parseIpv4Udp(ipv6, ipv6.size))
    }

    @Test
    fun `parsing della domanda DNS`() {
        val q = DnsPackets.parseQuestion(dnsQuery("www.Bet365.com", DnsPackets.TYPE_AAAA))
        assertNotNull(q)
        assertEquals("www.bet365.com", q!!.name)
        assertEquals(DnsPackets.TYPE_AAAA, q.qtype)
        assertEquals(1, q.qclass)
    }

    @Test
    fun `le risposte e i messaggi malformati non sono domande`() {
        val response = dnsQuery("a.it", 1).also { it[2] = 0x81.toByte() }
        assertNull(DnsPackets.parseQuestion(response))
        assertNull(DnsPackets.parseQuestion(ByteArray(5)))
        val truncated = dnsQuery("esempio.it", 1).copyOf(16)
        assertNull(DnsPackets.parseQuestion(truncated))
    }

    @Test
    fun `sinkhole A risponde con 0_0_0_0`() {
        val query = dnsQuery("snai.it", DnsPackets.TYPE_A, id = 0xBEEF)
        val question = DnsPackets.parseQuestion(query)!!
        val resp = DnsPackets.buildBlockedResponse(query, question)

        assertEquals(0xBE.toByte(), resp[0]); assertEquals(0xEF.toByte(), resp[1])   // stesso ID
        assertEquals(0x81.toByte(), resp[2]); assertEquals(0x80.toByte(), resp[3])   // QR+RD, RA, NOERROR
        assertEquals(1, resp[7].toInt())                                             // ANCOUNT = 1
        assertEquals(query.size + 16, resp.size)                                     // 12 byte fissi + 4 di RDATA
        assertArrayEquals(ByteArray(4), resp.copyOfRange(resp.size - 4, resp.size))
        // La sezione question è identica a quella della query
        assertArrayEquals(query.copyOfRange(12, query.size), resp.copyOfRange(12, query.size))
    }

    @Test
    fun `sinkhole AAAA e altri tipi`() {
        val q6 = dnsQuery("snai.it", DnsPackets.TYPE_AAAA)
        val r6 = DnsPackets.buildBlockedResponse(q6, DnsPackets.parseQuestion(q6)!!)
        assertEquals(q6.size + 28, r6.size)
        assertArrayEquals(ByteArray(16), r6.copyOfRange(r6.size - 16, r6.size))

        val https = dnsQuery("snai.it", 65)
        val rHttps = DnsPackets.buildBlockedResponse(https, DnsPackets.parseQuestion(https)!!)
        assertEquals(https.size, rHttps.size)
        assertEquals(0, rHttps[7].toInt())

        val nx = DnsPackets.buildBlockedResponse(q6, DnsPackets.parseQuestion(q6)!!, nxDomain = true)
        assertEquals(3, nx[3].toInt() and 0x0F)
    }

    @Test
    fun `ordine whitelist - blacklist - keyword`() {
        val filter = DomainFilter(
            whitelist = setOf("unicredit.it"),
            blockedDomains = setOf("intesasanpaolo.com", "snai.it", "unicredit.it"),
            keywords = listOf("trading", "prestit")
        )
        // La whitelist vince anche su blacklist e keyword
        assertFalse(filter.shouldBlock("unicredit.it"))
        assertFalse(filter.shouldBlock("trading.unicredit.it"))
        // Dominio esatto e sottodomini
        assertTrue(filter.shouldBlock("snai.it"))
        assertTrue(filter.shouldBlock("APP.Snai.it."))
        assertTrue(filter.shouldBlock("www.intesasanpaolo.com"))
        // Un dominio che "finisce" allo stesso modo ma non è un sottodominio non è bloccato
        assertFalse(filter.shouldBlock("nonsnai.it"))
        // Keyword
        assertTrue(filter.shouldBlock("migliori-prestiti-online.com"))
        assertTrue(filter.shouldBlock("tradingview.com"))
        // Tutto il resto passa
        assertFalse(filter.shouldBlock("wikipedia.org"))
    }
}
