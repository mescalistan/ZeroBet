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

/**
 * Tiene in memoria il [DomainFilter] corrente, sincronizzato con il DB Room, per tutto il
 * processo: lo usano sia il VpnService (query DNS) sia il servizio di accessibilità (URL del browser).
 *
 *  - whitelist, blacklist locale e domini auto-appresi: osservati con Flow, ogni modifica dalla
 *    dashboard o dall'auto-apprendimento è attiva subito;
 *  - domini delle fonti esterne (tabella grande): caricati all'avvio e ricaricati esplicitamente
 *    con [reloadExternal] al termine di una sincronizzazione, non a ogni batch inserito.
 */
class FilterStore(private val repository: DomainRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val external = MutableStateFlow<Set<String>>(emptySet())
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
        reloadExternal()
    }

    /** Sospende finché il primo snapshot delle regole è stato caricato dal DB. */
    suspend fun awaitReady() = ready.await()

    fun reloadExternal() {
        scope.launch(Dispatchers.IO) { external.value = repository.loadSyncedDomains() }
    }

    /** Salva un dominio auto-appreso; il filtro si aggiorna da solo tramite il Flow di Room. */
    fun learn(domain: String, origin: String, detail: String) {
        scope.launch(Dispatchers.IO) { repository.learn(domain, origin, detail) }
    }

    fun learnFromHeuristic(domain: String, score: Int) =
        learn(domain.removePrefix("www."), AutoLearnedBlockedDomain.ORIGIN_HEURISTIC, "Punteggio di rischio $score/100")
}
