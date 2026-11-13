package com.zerobet.safeguard.security

import android.util.Log
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.data.SecurityEventDao
import com.zerobet.safeguard.data.SecurityEventLog
import com.zerobet.safeguard.data.Severity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Raggruppa gli eventi ripetuti con la stessa chiave: dentro una finestra di [windowMs] il primo
 * crea una riga, i successivi ne aumentano solo il contatore. Kotlin puro, coperto da unit test.
 */
class EventGrouper(private val windowMs: Long = 60_000L, private val maxKeys: Int = 500) {

    class Entry(var rowId: Long, val startedAt: Long, var count: Int, var lastAt: Long, var dirty: Boolean)

    private val entries = HashMap<String, Entry>()

    /** @return null se serve una riga nuova (da registrare poi con [opened]), altrimenti la riga da aggiornare. */
    fun hit(key: String, now: Long): Entry? {
        val entry = entries[key]
        if (entry == null || now - entry.startedAt >= windowMs) return null
        entry.count++
        entry.lastAt = now
        entry.dirty = true
        return entry
    }

    fun opened(key: String, rowId: Long, now: Long) {
        if (entries.size >= maxKeys) entries.entries.removeAll { now - it.value.startedAt >= windowMs && !it.value.dirty }
        if (entries.size >= maxKeys) entries.clear()
        entries[key] = Entry(rowId, now, 1, now, false)
    }

    /** Righe con un contatore non ancora scritto sul DB. */
    fun takeDirty(): List<Entry> = entries.values.filter { it.dirty }.onEach { it.dirty = false }
}

/**
 * Registro Allarmi ("Diario di sicurezza"): scrive su Room, su un solo thread in background, così
 * chi segnala un evento (VPN, servizio di accessibilità, receiver) non aspetta mai il disco.
 *
 * Il DB sta nello storage "device protected", quindi il registro funziona anche subito dopo il
 * riavvio, prima che l'utente sblocchi lo schermo.
 */
class SecurityLog(private val daoProvider: () -> SecurityEventDao) {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "safeguard-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val grouper = EventGrouper()
    private var flushScheduled = false
    private var inserts = 0

    fun info(type: String, title: String, detail: String = "") = log(type, Severity.INFO, title, detail)
    fun warning(type: String, title: String, detail: String = "") = log(type, Severity.WARNING, title, detail)
    fun critical(type: String, title: String, detail: String = "") = log(type, Severity.CRITICAL, title, detail)

    fun log(type: String, severity: String, title: String, detail: String = "") {
        val now = System.currentTimeMillis()
        submit { insert(SecurityEventLog(timestamp = now, severity = severity, type = type, title = title, detail = detail.take(300))) }
    }

    /** Come [log] ma attende la scrittura (fino a [timeoutMs]): serve allo spegnimento del telefono. */
    fun logNow(type: String, severity: String, title: String, detail: String = "", timeoutMs: Long = 1500) {
        val now = System.currentTimeMillis()
        try {
            executor.submit {
                insert(SecurityEventLog(timestamp = now, severity = severity, type = type, title = title, detail = detail))
                flush()
            }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Evento non scritto in tempo: $title")
        }
    }

    /**
     * Evento raggruppabile: se [key] si ripete entro un minuto non crea una nuova riga ma aumenta
     * il contatore di quella esistente (scritto sul DB al più ogni [FLUSH_DELAY_S] secondi).
     */
    fun grouped(key: String, type: String, severity: String, title: String, detail: String) {
        val now = System.currentTimeMillis()
        submit {
            if (grouper.hit(key, now) != null) {
                scheduleFlush()
            } else {
                val id = insert(SecurityEventLog(timestamp = now, severity = severity, type = type, title = title, detail = detail.take(300)))
                if (id > 0) grouper.opened(key, id, now)
            }
        }
    }

    /** Dominio bloccato dal filtro DNS (una riga per dominio al minuto, con contatore). */
    fun domainBlocked(domain: String, reason: String) =
        grouped("dns:$domain", EventType.DOMAIN, Severity.INFO, domain, reason)

    fun clear() = submit { daoProvider().clear() }

    private fun insert(event: SecurityEventLog): Long = try {
        val id = daoProvider().insert(event)
        if (++inserts % TRIM_EVERY == 0) daoProvider().trim(MAX_ROWS)
        id
    } catch (e: Exception) {
        Log.w(TAG, "Scrittura del registro fallita: ${e.message}")
        -1
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        executor.schedule({ flushScheduled = false; flush() }, FLUSH_DELAY_S, TimeUnit.SECONDS)
    }

    private fun flush() {
        try {
            val dao = daoProvider()
            grouper.takeDirty().forEach { dao.updateCount(it.rowId, it.count, it.lastAt) }
        } catch (e: Exception) {
            Log.w(TAG, "Aggiornamento dei contatori fallito: ${e.message}")
        }
    }

    private fun submit(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: Exception) {
            // executor chiuso: il processo sta terminando
        }
    }

    private companion object {
        const val TAG = "SafeGuardLog"
        const val FLUSH_DELAY_S = 5L
        const val MAX_ROWS = 5_000
        const val TRIM_EVERY = 200
    }
}
