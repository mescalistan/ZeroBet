package com.zerobet.safeguard.vpn

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Insieme compatto di domini: al posto di centinaia di migliaia di oggetti String tiene un solo
 * array ordinato di hash a 64 bit, cercato con ricerca binaria.
 *
 *  - Memoria: 8 byte per dominio (211.000 domini = 1,7 MB) contro i ~30 MB di un HashSet<String>.
 *  - Ricerca: O(log n), circa 18 confronti su 211.000 voci, senza allocazioni.
 *  - Collisioni: con hash a 64 bit la probabilità che un dominio qualsiasi coincida con una delle
 *    211.000 voci è circa 1 su 10^14, quindi trascurabile per un filtro.
 *
 * L'insieme non conserva i nomi: per mostrarli (dashboard) si legge il DB a pagine.
 */
class HashedDomainSet private constructor(private val hashes: LongArray, val size: Int) {

    /** Byte occupati in memoria dai dati. */
    val memoryBytes: Long get() = hashes.size.toLong() * Long.SIZE_BYTES

    fun isEmpty(): Boolean = size == 0

    /** Match esatto sul dominio già normalizzato (minuscolo, senza punto finale). */
    operator fun contains(domain: String): Boolean = containsHash(hash(domain, 0))

    /**
     * Match esatto, poi sui domini padre: "app.bet365.com" è coperto dalla voce "bet365.com".
     * Il solo TLD non viene mai usato come padre.
     */
    fun matches(domain: String): Boolean {
        if (size == 0) return false
        if (containsHash(hash(domain, 0))) return true
        var from = domain.indexOf('.')
        while (from >= 0) {
            val start = from + 1
            if (domain.indexOf('.', start) < 0) return false      // rimasto solo il TLD
            if (containsHash(hash(domain, start))) return true
            from = domain.indexOf('.', start)
        }
        return false
    }

    private fun containsHash(h: Long): Boolean {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = hashes[mid]
            if (value < h) low = mid + 1 else if (value > h) high = mid - 1 else return true
        }
        return false
    }

    /** Salva l'insieme in un file binario (scrittura atomica: file temporaneo + rinomina). */
    fun writeTo(file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(size)
            for (i in 0 until size) out.writeLong(hashes[i])
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Costruttore incrementale: i domini si aggiungono uno alla volta, senza tenerli in memoria. */
    class Builder(initialCapacity: Int = 1024) {
        private var data = LongArray(initialCapacity.coerceAtLeast(16))
        private var count = 0

        fun add(domain: String) {
            if (count == data.size) data = data.copyOf(data.size * 2)
            data[count++] = hash(domain, 0)
        }

        fun build(): HashedDomainSet {
            val sorted = data.copyOf(count)
            sorted.sort()
            // rimozione dei duplicati sul posto
            var unique = 0
            for (i in sorted.indices) {
                if (i == 0 || sorted[i] != sorted[i - 1]) sorted[unique++] = sorted[i]
            }
            return HashedDomainSet(if (unique == sorted.size) sorted else sorted.copyOf(unique), unique)
        }
    }

    companion object {
        private const val MAGIC = 0x53474831   // "SGH1"

        val EMPTY = HashedDomainSet(LongArray(0), 0)

        fun of(domains: Iterable<String>): HashedDomainSet =
            Builder().apply { domains.forEach { add(it) } }.build()

        fun of(vararg domains: String): HashedDomainSet = of(domains.asIterable())

        /** null se il file non esiste o non è valido. */
        fun readFrom(file: File): HashedDomainSet? {
            if (!file.isFile) return null
            return try {
                DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { input ->
                    if (input.readInt() != MAGIC) return null
                    val count = input.readInt()
                    if (count < 0 || count.toLong() * 8 > file.length()) return null
                    val data = LongArray(count)
                    for (i in 0 until count) data[i] = input.readLong()
                    HashedDomainSet(data, count)
                }
            } catch (e: Exception) {
                null
            }
        }

        /** FNV-1a a 64 bit sui caratteri da [from] in poi, con rimescolamento finale (murmur3). */
        internal fun hash(s: String, from: Int): Long {
            var h = -0x340d631b7bdddcdbL          // offset basis FNV-1a 64
            for (i in from until s.length) {
                h = h xor s[i].code.toLong()
                h *= 0x100000001b3L
            }
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            h = h xor (h ushr 33)
            return h
        }
    }
}
