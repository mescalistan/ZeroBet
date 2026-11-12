package com.zerobet.safeguard.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.DomainDao
import com.zerobet.safeguard.data.SyncStatus
import com.zerobet.safeguard.data.SyncedDomain
import com.zerobet.safeguard.vpn.DomainFilter
import com.zerobet.safeguard.vpn.SafeDomains
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.TimeUnit

enum class SourceFormat {
    /** Una voce per riga: formato hosts, elenco di domini o regole adblock. */
    LINES,
    /** Pagina HTML / testo libero: i domini vengono estratti con regex dai link presenti. */
    HTML_LINKS
}

class BlocklistSource(
    val id: String,
    val label: String,
    val url: String,
    val format: SourceFormat,
    /** Domini (e loro sottodomini) da non importare mai da questa fonte: link di servizio della pagina. */
    val ignore: Set<String> = emptySet()
)

object BlocklistSources {
    const val CRTSH = "CRTSH"
    const val ADM = "ADM"

    /** Pagina ADM da cui ricavare il link del file TXT se l'indirizzo diretto dovesse cambiare. */
    const val ADM_PAGE = "https://www.adm.gov.it/portale/siti-web-inibiti-giochi"

    val ALL: List<BlocklistSource> = listOf(
        BlocklistSource(
            "STEVENBLACK", "StevenBlack (gambling + adware)",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling/hosts",
            SourceFormat.LINES
        ),
        BlocklistSource(
            "BLOCKLISTPROJECT", "Block List Project (gambling)",
            "https://blocklistproject.github.io/Lists/gambling.txt",
            SourceFormat.LINES
        ),
        BlocklistSource(
            "YOYO_ADS", "Peter Lowe (pubblicità e tracker)",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0",
            SourceFormat.LINES
        ),
        BlocklistSource(
            ADM, "ADM – siti di gioco inibiti",
            "https://www.adm.gov.it/portale/documents/20182/114059352/elenco_siti_inibiti_giochi.txt",
            SourceFormat.LINES
        ),
        BlocklistSource(
            "CONSOB", "CONSOB – siti oscurati",
            "https://www.consob.it/web/area-pubblica/oscuramenti",
            SourceFormat.HTML_LINKS,
            ignore = setOf(
                "w3.org", "normattiva.it", "liferay.com", "italia.it", "esma.europa.eu", "iosco.org",
                "ivass.it", "covip.it", "agcm.it", "agcom.it", "garanteprivacy.it", "acf.consob.it",
                "arbitrobancariofinanziario.it", "borsaitaliana.it", "adobe.com",
            )
        ),
    )

    fun label(id: String): String = when (id) {
        CRTSH -> "crt.sh – nuovi certificati"
        else -> ALL.firstOrNull { it.id == id }?.label ?: id
    }
}

/** Stato in memoria della sincronizzazione, osservato dalla dashboard. */
object SyncState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _progress = MutableStateFlow("")
    /** Nome della fonte in corso di download. */
    val progress: StateFlow<String> = _progress

    @Synchronized
    internal fun begin(): Boolean {
        if (_running.value) return false
        _running.value = true
        return true
    }

    internal fun step(label: String) {
        _progress.value = label
    }

    internal fun end() {
        _progress.value = ""
        _running.value = false
    }
}

object SyncScheduler {
    private const val PERIODIC = "blacklist-sync-daily"
    private const val MANUAL = "blacklist-sync-now"

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Pianifica l'aggiornamento ogni 24 ore; se è già pianificato non cambia nulla. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<BlacklistSyncWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** "Forza aggiornamento ora": esecuzione singola, ignorata se una è già in coda. */
    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BlacklistSyncWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * Scarica le fonti esterne e ne salva i domini nella tabella `synced_domains`.
 *
 * Memoria: ogni download è letto in streaming riga per riga (o carattere per carattere per il
 * JSON di crt.sh) e scritto su Room a blocchi di [BATCH_SIZE]; il file non è mai tenuto in RAM.
 * Robustezza: ogni fonte è indipendente. Se un download fallisce, i domini già salvati di quella
 * fonte restano validi e l'errore viene annotato in `sync_status`.
 */
class BlacklistSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!SyncState.begin()) return@withContext Result.success()   // già in corso
        try {
            val app = applicationContext as SafeGuardApp
            val dao = app.database.domainDao()
            app.filterStore.awaitReady()
            val filter = app.filterStore.current
            val batch = System.currentTimeMillis()

            var succeeded = 0
            for (source in BlocklistSources.ALL) {
                SyncState.step(source.label)
                if (syncSource(source, dao, filter, batch)) succeeded++
            }
            SyncState.step(BlocklistSources.label(BlocklistSources.CRTSH))
            if (discoverFromCtLogs(dao, filter, batch)) succeeded++

            app.filterStore.reloadExternal()
            // Nessuna fonte raggiunta (es. rete assente a metà): WorkManager riproverà con backoff.
            if (succeeded == 0) Result.retry() else Result.success()
        } finally {
            SyncState.end()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Blocklist, ADM, CONSOB
    // ---------------------------------------------------------------------------------------

    private suspend fun syncSource(source: BlocklistSource, dao: DomainDao, filter: DomainFilter, batch: Long): Boolean {
        return try {
            // Dalle pagine HTML si scartano anche i sottodomini dei siti "sicuri" (es. eur-lex.europa.eu):
            // sono link di servizio della pagina, non siti oscurati.
            val sink = DomainSink(
                source.id, batch, dao, filter, source.ignore,
                skipSafeSubdomains = source.format == SourceFormat.HTML_LINKS
            )
            try {
                download(source, source.url, sink)
            } catch (e: IOException) {
                // L'indirizzo del file ADM contiene un identificativo di documento che può
                // cambiare: in caso di errore lo si ricava dalla pagina ufficiale.
                val fallback = if (source.id == BlocklistSources.ADM) findAdmListUrl() else null
                if (fallback == null) throw e
                download(source, fallback, sink)
            }
            sink.flush()
            if (sink.accepted == 0) throw IOException("nessun dominio trovato")
            // Download completo: si eliminano le voci della fonte non più presenti nella lista.
            dao.deleteStaleSynced(source.id, batch)
            dao.upsertSyncStatus(SyncStatus(source.id, System.currentTimeMillis(), dao.countSynced(source.id), null))
            Log.i(TAG, "${source.id}: ${sink.accepted} letti, ${sink.stored} salvati")
            true
        } catch (e: CancellationException) {
            throw e   // lavoro fermato da WorkManager (es. rete persa): non è un errore della fonte
        } catch (e: Exception) {
            Log.w(TAG, "${source.id}: sincronizzazione fallita: ${e.message}")
            recordFailure(dao, source.id, e)
            false
        }
    }

    private suspend fun download(source: BlocklistSource, url: String, sink: DomainSink) {
        httpGet(url) { reader ->
            var lines = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (++lines > MAX_LINES) break
                when (source.format) {
                    SourceFormat.LINES -> BlocklistParser.parseLine(line)?.let { sink.add(it) }
                    SourceFormat.HTML_LINKS -> BlocklistParser.extractDomains(line).forEach { sink.add(it) }
                }
            }
        }
    }

    /** Cerca nella pagina ADM il link al file "elenco_siti_inibiti_giochi.txt". */
    private suspend fun findAdmListUrl(): String? = try {
        val href = Regex("href=\"([^\"]*elenco_siti_inibiti_giochi\\.txt[^\"]*)\"")
        var found: String? = null
        httpGet(BlocklistSources.ADM_PAGE) { reader ->
            while (found == null) {
                val line = reader.readLine() ?: break
                found = href.find(line)?.groupValues?.get(1)?.replace("&amp;", "&")
            }
        }
        found?.let { if (it.startsWith("http")) it else "https://www.adm.gov.it$it" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ---------------------------------------------------------------------------------------
    // Certificate Transparency (crt.sh)
    // ---------------------------------------------------------------------------------------

    /**
     * Interroga crt.sh per le parole del giorno. I domini trovati si accumulano di esecuzione in
     * esecuzione (ogni giorno si cercano parole diverse), quindi qui non si eliminano le voci vecchie.
     * crt.sh risponde spesso con errori 502 o timeout: ogni parola è indipendente dalle altre.
     */
    private suspend fun discoverFromCtLogs(dao: DomainDao, filter: DomainFilter, batch: Long): Boolean {
        val sink = DomainSink(BlocklistSources.CRTSH, batch, dao, filter, emptySet(), skipSafeSubdomains = true)
        val cutoff = LocalDate.now().minusDays(CtLogDiscovery.RECENT_DAYS).toString()   // "yyyy-MM-dd"
        var answered = 0
        var lastError: Exception? = null

        for (keyword in CtLogDiscovery.keywordsFor(LocalDate.now().toEpochDay())) {
            try {
                httpGet(CtLogDiscovery.queryUrl(keyword), readTimeoutMs = 90_000) { reader ->
                    CtLogDiscovery.parseEntries(reader) { nameValue, notBefore ->
                        // Solo certificati recenti: le date ISO si confrontano come stringhe.
                        if (notBefore.take(10) >= cutoff) {
                            CtLogDiscovery.candidates(nameValue, keyword).forEach { sink.addBlocking(it) }
                        }
                    }
                }
                answered++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "crt.sh '${keyword.term}': ${e.message}")
            }
            delay(CT_PAUSE_MS)   // cortesia verso un servizio pubblico gratuito
        }
        sink.flush()

        val previous = dao.syncStatus(BlocklistSources.CRTSH)
        dao.upsertSyncStatus(
            SyncStatus(
                source = BlocklistSources.CRTSH,
                lastSuccessAt = if (answered > 0) System.currentTimeMillis() else previous?.lastSuccessAt ?: 0L,
                domainCount = dao.countSynced(BlocklistSources.CRTSH),
                lastError = if (answered > 0) null else describe(lastError)
            )
        )
        Log.i(TAG, "crt.sh: $answered parole con risposta, ${sink.stored} nuovi domini")
        return answered > 0
    }

    // ---------------------------------------------------------------------------------------
    // Supporto
    // ---------------------------------------------------------------------------------------

    private suspend fun recordFailure(dao: DomainDao, sourceId: String, error: Exception) {
        val previous = dao.syncStatus(sourceId)
        dao.upsertSyncStatus(
            SyncStatus(sourceId, previous?.lastSuccessAt ?: 0L, previous?.domainCount ?: 0, describe(error))
        )
    }

    private fun describe(error: Exception?): String =
        (error?.message ?: error?.javaClass?.simpleName ?: "errore sconosciuto").take(120)

    /** GET in streaming: il corpo viene passato a [block] come reader e chiuso al termine. */
    private inline fun httpGet(url: String, readTimeoutMs: Int = 60_000, block: (BufferedReader) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "SafeGuard-BlocklistSync/1.0 (Android)")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use(block)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Accumula i domini validi e li scrive su Room a blocchi. Scarta:
     *  - i domini "sicuri" esatti (una lista corrotta non deve poter bloccare google.com);
     *  - i link di servizio della pagina sorgente ([ignore]);
     *  - le voci già coperte da parole chiave o euristica: non serve salvarle, e per alcune
     *    liste (centinaia di migliaia di sottodomini "*.casino") è la gran parte del file.
     */
    private class DomainSink(
        private val source: String,
        private val batch: Long,
        private val dao: DomainDao,
        private val filter: DomainFilter,
        private val ignore: Set<String>,
        private val skipSafeSubdomains: Boolean = false
    ) {
        private val buffer = ArrayList<SyncedDomain>(BATCH_SIZE)
        var accepted = 0
            private set
        var stored = 0
            private set

        suspend fun add(domain: String) {
            if (SafeDomains.isExact(domain) || isIgnored(domain)) return
            if (skipSafeSubdomains && SafeDomains.contains(domain)) return
            accepted++
            if (filter.blockedByRules(domain)) return
            buffer.add(SyncedDomain(domain, source, batch))
            stored++
            if (buffer.size >= BATCH_SIZE) flush()
        }

        /** Come [add], ma utilizzabile da callback non sospendenti (parser JSON): scrive solo a fine giro. */
        fun addBlocking(domain: String) {
            if (SafeDomains.contains(domain) || filter.isWhitelisted(domain) || filter.check(domain).blocked) return
            if (buffer.size >= MAX_CT_DOMAINS_PER_RUN) return
            accepted++
            stored++
            buffer.add(SyncedDomain(domain, source, batch))
        }

        suspend fun flush() {
            if (buffer.isEmpty()) return
            dao.insertSynced(ArrayList(buffer))
            buffer.clear()
        }

        private fun isIgnored(domain: String): Boolean =
            ignore.any { domain == it || domain.endsWith(".$it") }
    }

    companion object {
        private const val TAG = "SafeGuardSync"
        private const val BATCH_SIZE = 2_000
        private const val MAX_LINES = 600_000
        private const val MAX_CT_DOMAINS_PER_RUN = 5_000
        private const val CT_PAUSE_MS = 2_000L
    }
}
