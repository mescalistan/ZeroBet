package com.zerobet.safeguard.vpn

import java.nio.ByteBuffer

/**
 * Parsing e costruzione dei pacchetti che transitano sull'interfaccia TUN.
 * Solo Kotlin/JVM puro (nessuna dipendenza Android) così è testabile con unit test.
 *
 * Sulla TUN viaggiano pacchetti IP "nudi" (senza header Ethernet):
 *
 *   +--------------------+----------------+---------------------------+
 *   | Header IPv4 (20+B) | Header UDP (8) | Messaggio DNS (payload)   |
 *   +--------------------+----------------+---------------------------+
 */

/** Datagramma UDP estratto da un pacchetto IPv4. */
class UdpDatagram(
    val srcAddr: ByteArray,   // 4 byte
    val dstAddr: ByteArray,   // 4 byte
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray
)

/** Prima domanda contenuta in una query DNS. */
class DnsQuestion(
    val name: String,         // es. "www.bet365.com" (minuscolo, senza punto finale)
    val qtype: Int,           // 1 = A, 28 = AAAA, 65 = HTTPS, ...
    val qclass: Int,          // 1 = IN
    val questionEnd: Int      // offset (nel messaggio DNS) del primo byte dopo la domanda
)

object DnsPackets {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    private const val PROTO_UDP = 17
    private const val IPV4_HEADER_LEN = 20
    private const val UDP_HEADER_LEN = 8
    private const val DNS_HEADER_LEN = 12

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    // ---------------------------------------------------------------------------------------
    // IPv4 + UDP
    // ---------------------------------------------------------------------------------------

    /**
     * Estrae il datagramma UDP da un pacchetto IPv4 letto dalla TUN.
     * Restituisce null per tutto ciò che non ci interessa (non IPv4, non UDP, frammenti,
     * pacchetti troncati): il chiamante li scarta semplicemente.
     *
     * Header IPv4 (RFC 791) - campi usati:
     *   byte 0      : versione (4 bit alti) + IHL = lunghezza header in parole da 32 bit (4 bit bassi)
     *   byte 2-3    : lunghezza totale del pacchetto
     *   byte 6-7    : flag (3 bit) + fragment offset (13 bit)
     *   byte 9      : protocollo (17 = UDP)
     *   byte 12-15  : indirizzo sorgente
     *   byte 16-19  : indirizzo destinazione
     */
    fun parseIpv4Udp(packet: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV4_HEADER_LEN + UDP_HEADER_LEN) return null
        if (u8(packet, 0) shr 4 != 4) return null                    // solo IPv4
        val ihl = (u8(packet, 0) and 0x0F) * 4                       // l'header può avere opzioni
        if (ihl < IPV4_HEADER_LEN) return null
        val totalLength = u16(packet, 2)
        if (totalLength > length || totalLength < ihl + UDP_HEADER_LEN) return null
        if (u8(packet, 9) != PROTO_UDP) return null
        // Flag "More Fragments" (0x2000) o offset != 0 => frammento: una query DNS non lo è mai.
        if (u16(packet, 6) and 0x3FFF != 0) return null

        // Header UDP (RFC 768): porta sorgente, porta destinazione, lunghezza, checksum (2 byte l'uno)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val udpLength = u16(packet, ihl + 4)
        if (udpLength < UDP_HEADER_LEN || ihl + udpLength > totalLength) return null

        return UdpDatagram(
            srcAddr = packet.copyOfRange(12, 16),
            dstAddr = packet.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            payload = packet.copyOfRange(ihl + UDP_HEADER_LEN, ihl + udpLength)
        )
    }

    /**
     * Incapsula un payload in UDP + IPv4, pronto per essere scritto sulla TUN.
     * Per le risposte si invocano con sorgente/destinazione scambiate rispetto alla query,
     * così l'app che ha fatto la richiesta vede la risposta arrivare dal "suo" server DNS.
     */
    fun buildIpv4Udp(
        srcAddr: ByteArray, dstAddr: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLength = UDP_HEADER_LEN + payload.size
        val totalLength = IPV4_HEADER_LEN + udpLength
        val buf = ByteBuffer.allocate(totalLength)       // big-endian = network byte order

        // --- IPv4 ---
        buf.put(0x45.toByte())                 // versione 4, IHL 5 (20 byte, nessuna opzione)
        buf.put(0)                             // DSCP/ECN
        buf.putShort(totalLength.toShort())
        buf.putShort(0)                        // identification
        buf.putShort(0x4000.toShort())         // flag Don't Fragment, offset 0
        buf.put(64)                            // TTL
        buf.put(PROTO_UDP.toByte())
        buf.putShort(0)                        // checksum, calcolato sotto
        buf.put(srcAddr)
        buf.put(dstAddr)

        // --- UDP ---
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLength.toShort())
        buf.putShort(0)                        // checksum UDP: 0 = "non calcolato", lecito su IPv4
        buf.put(payload)

        val out = buf.array()
        val checksum = ipChecksum(out, 0, IPV4_HEADER_LEN)
        out[10] = (checksum shr 8).toByte()
        out[11] = checksum.toByte()
        return out
    }

    /** Checksum Internet (RFC 1071): complemento a uno della somma a 16 bit con riporto. */
    private fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += u16(data, i)
            i += 2
        }
        if ((length and 1) == 1) sum += u8(data, offset + length - 1) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    // ---------------------------------------------------------------------------------------
    // DNS (RFC 1035)
    // ---------------------------------------------------------------------------------------

    /**
     * Legge la prima domanda di una query DNS. Restituisce null se il messaggio non è una
     * query standard ben formata (in tal caso il chiamante lo inoltra così com'è).
     *
     * Header DNS (12 byte):
     *   0-1  ID          identificativo scelto dal client, va ricopiato nella risposta
     *   2-3  FLAGS       QR(1) OPCODE(4) AA TC RD | RA Z(3) RCODE(4)
     *   4-5  QDCOUNT     numero di domande (in pratica sempre 1)
     *   6-7  ANCOUNT     8-9 NSCOUNT     10-11 ARCOUNT
     *
     * Domanda: QNAME + QTYPE(2) + QCLASS(2).
     * QNAME è una sequenza di label: [lunghezza][caratteri]... terminata da un byte 0.
     *   es. "bet365.com" -> 06 'b' 'e' 't' '3' '6' '5' 03 'c' 'o' 'm' 00
     */
    fun parseQuestion(dns: ByteArray): DnsQuestion? {
        if (dns.size < DNS_HEADER_LEN + 5) return null
        val flags = u16(dns, 2)
        if (flags and 0x8000 != 0) return null            // QR=1: è una risposta, non una query
        if ((flags shr 11) and 0xF != 0) return null      // OPCODE != 0: non è una query standard
        if (u16(dns, 4) < 1) return null                  // nessuna domanda

        val name = StringBuilder()
        var pos = DNS_HEADER_LEN
        while (true) {
            if (pos >= dns.size) return null
            val labelLen = u8(dns, pos)
            if (labelLen == 0) {                          // byte 0 = fine del nome
                pos++
                break
            }
            // I due bit alti a 1 indicano un puntatore di compressione: non valido in una domanda.
            if (labelLen and 0xC0 != 0) return null
            pos++
            if (pos + labelLen > dns.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until labelLen) name.append((u8(dns, pos + i)).toChar())
            pos += labelLen
            if (name.length > 253) return null
        }
        if (pos + 4 > dns.size) return null
        return DnsQuestion(
            name = name.toString().lowercase(),
            qtype = u16(dns, pos),
            qclass = u16(dns, pos + 2),
            questionEnd = pos + 4
        )
    }

    /**
     * Costruisce la risposta "sinkhole" per un dominio bloccato:
     *   - query A     -> un record A con 0.0.0.0
     *   - query AAAA  -> un record AAAA con ::
     *   - altri tipi  -> risposta NOERROR senza record (es. HTTPS/SVCB, MX...)
     * L'indirizzo nullo non è instradabile, quindi la connessione fallisce all'istante.
     *
     * Con [nxDomain] = true risponde invece NXDOMAIN (usato per il dominio "canary"
     * che segnala ai browser di non attivare il DNS-over-HTTPS).
     */
    fun buildBlockedResponse(query: ByteArray, question: DnsQuestion, nxDomain: Boolean = false): ByteArray {
        val rdata: ByteArray? = when {
            nxDomain -> null
            question.qtype == TYPE_A -> ByteArray(4)       // 0.0.0.0
            question.qtype == TYPE_AAAA -> ByteArray(16)   // ::
            else -> null
        }
        val questionBytes = question.questionEnd - DNS_HEADER_LEN
        val answerBytes = if (rdata != null) 12 + rdata.size else 0
        val buf = ByteBuffer.allocate(DNS_HEADER_LEN + questionBytes + answerBytes)

        // --- Header ---
        val queryFlags = u16(query, 2)
        var flags = 0x8000                    // QR = 1 (risposta)
        flags = flags or (queryFlags and 0x0100)   // RD: ricopiato dalla query
        flags = flags or 0x0080               // RA = 1 (ricorsione disponibile)
        if (nxDomain) flags = flags or 0x0003 // RCODE 3 = NXDOMAIN
        buf.put(query, 0, 2)                  // stesso ID della query
        buf.putShort(flags.toShort())
        buf.putShort(1)                       // QDCOUNT
        buf.putShort(if (rdata != null) 1 else 0) // ANCOUNT
        buf.putShort(0)                       // NSCOUNT
        buf.putShort(0)                       // ARCOUNT (eventuale OPT/EDNS della query non viene ricopiato)

        // --- Domanda: ricopiata identica dalla query ---
        buf.put(query, DNS_HEADER_LEN, questionBytes)

        // --- Risposta ---
        if (rdata != null) {
            buf.putShort(0xC00C.toShort())    // NAME: puntatore compresso all'offset 12 (il QNAME)
            buf.putShort(question.qtype.toShort())
            buf.putShort(1)                   // CLASS IN
            buf.putInt(BLOCK_TTL_SECONDS)     // TTL breve: uno sblocco dalla dashboard ha effetto rapido
            buf.putShort(rdata.size.toShort())// RDLENGTH
            buf.put(rdata)                    // RDATA
        }
        return buf.array()
    }

    private const val BLOCK_TTL_SECONDS = 30
}
