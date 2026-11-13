package com.zerobet.safeguard.vpn

import com.zerobet.safeguard.data.AutoLearnedBlockedDomain
import com.zerobet.safeguard.data.DomainRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/**
 * Tiene in memoria il [DomainFilter] corrente, sincronizzato con il DB Room, per tutto il
 * processo: lo usano sia il VpnService (query DNS) sia il servizio di accessibilità (URL del browser).
 *
 *  - whitelist, blacklist locale e domini auto-appresi: osservati con Flow, ogni modifica dalla
 *    dashboard o dall'auto-apprendimento è attiva subito;
 *  - domini delle fonti esterne (tabella grande): tenuti come [HashedDomainSet] (8 byte per
 *    dominio), letti all'avvio da una copia binaria su file e ricostruiti dal DB con
 *    [reloadExternal] al termine di una sincronizzazione, non a ogni batch inserito.
 */
class FilterStore(private val repository: DomainRepository, private val cacheFile: File? = null) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val external = MutableStateFlow(HashedDomainSet.EMPTY)
    private val ready = CompletableDeferred<Unit>()

    @Volatile
    var current: DomainFilter = DomainFilter.EMPTY
        private set

    init {
        scope.launch {
            combine(repository.whitelist, repository.blocked, repository.learned, external) { wl, bl, learned, ext ->
                DomainFilter(wl, bl, learned, ext)
            }.collect {
                current = it
                ready.complete(Unit)
            }
        }
        // Avvio: la copia compatta su file si legge in pochi millisecondi (anche in Direct Boot);
        // il DB viene riletto solo se il file manca o dopo una sincronizzazione.
        scope.launch(Dispatchers.IO) {
            val cached = cacheFile?.let { HashedDomainSet.readFrom(it) }
            if (cached != null) external.value = cached else loadExternalFromDb()
        }
    }

    /** Sospende finché il primo snapshot delle regole è stato caricato dal DB. */
    suspend fun awaitReady() = ready.await()

    /** Numero di domini esterni caricati e memoria occupata (per diagnostica e dashboard). */
    val externalSize: Int get() = external.value.size
    val externalMemoryBytes: Long get() = external.value.memoryBytes

    fun reloadExternal() {
        scope.launch(Dispatchers.IO) { loadExternalFromDb() }
    }

    private fun loadExternalFromDb() {
        try {
            val set = repository.loadSyncedHashes()
            external.value = set
            cacheFile?.let { set.writeTo(it) }
        } catch (e: Exception) {
            android.util.Log.w("SafeGuardFilter", "Caricamento delle liste esterne fallito: ${e.message}")
        }
    }

    /** Salva un dominio auto-appreso; il filtro si aggiorna da solo tramite il Flow di Room. */
    fun learn(domain: String, origin: String, detail: String) {
        scope.launch(Dispatchers.IO) { repository.learn(domain, origin, detail) }
    }

    fun learnFromHeuristic(domain: String, score: Int) =
        learn(domain.removePrefix("www."), AutoLearnedBlockedDomain.ORIGIN_HEURISTIC, "Punteggio di rischio $score/100")
}
