package com.zerobet.safeguard.data

import kotlinx.coroutines.flow.Flow

class DomainRepository(private val dao: DomainDao) {

    val blocked: Flow<List<BlockedDomain>> = dao.observeBlocked()
    val whitelist: Flow<List<WhitelistedBank>> = dao.observeWhitelist()
    val learned: Flow<List<AutoLearnedBlockedDomain>> = dao.observeLearned()
    val syncStatus: Flow<List<SyncStatus>> = dao.observeSyncStatus()

    val syncedCount: Flow<Int> = dao.observeSyncedCount()

    fun synced(query: String, limit: Int): Flow<List<SyncedDomain>> = dao.observeSynced(query, limit)

    fun syncedMatches(query: String): Flow<Int> = dao.observeSyncedMatches(query)

    /** Falso positivo di una lista esterna: la whitelist ha la precedenza anche dopo i prossimi aggiornamenti. */
    suspend fun allowSynced(domain: SyncedDomain) {
        dao.insertWhitelisted(WhitelistedBank(name = "Consentito dal tutore", domain = domain.domain))
    }

    /** Tutti i domini delle fonti esterne, come set per lookup O(1). */
    suspend fun loadSyncedDomains(): Set<String> = dao.allSyncedDomains().toHashSet()

    /** Registra un dominio auto-appreso (ignorato se già presente). */
    suspend fun learn(rawDomain: String, origin: String, detail: String) {
        val domain = normalizeDomain(rawDomain) ?: return
        dao.insertLearned(
            AutoLearnedBlockedDomain(domain, origin, detail.take(200), System.currentTimeMillis())
        )
    }

    suspend fun removeLearned(domain: AutoLearnedBlockedDomain) = dao.deleteLearned(domain)

    /** Falso positivo: toglie il dominio dagli auto-appresi e lo autorizza in modo permanente. */
    suspend fun allowLearned(domain: AutoLearnedBlockedDomain) {
        dao.insertWhitelisted(WhitelistedBank(name = "Consentito dal tutore", domain = domain.domain))
        dao.deleteLearned(domain)
    }

    /** @return false se l'input non è un dominio valido. */
    suspend fun addWhitelisted(name: String, rawDomain: String): Boolean {
        val domain = normalizeDomain(rawDomain) ?: return false
        dao.insertWhitelisted(WhitelistedBank(name = name.ifBlank { domain }, domain = domain))
        return true
    }

    suspend fun addBankPreset(preset: BankPreset) {
        preset.domains.forEach { dao.insertWhitelisted(WhitelistedBank(name = preset.name, domain = it)) }
    }

    suspend fun removeWhitelisted(bank: WhitelistedBank) = dao.deleteWhitelisted(bank)

    /** @return false se l'input non è valido (dominio malformato o parola chiave troppo corta). */
    suspend fun addBlocked(raw: String, isKeyword: Boolean): Boolean {
        val pattern = if (isKeyword) normalizeKeyword(raw) else normalizeDomain(raw)
        pattern ?: return false
        dao.insertBlocked(
            BlockedDomain(pattern = pattern, category = Category.CUSTOM, isKeyword = isKeyword, userAdded = true)
        )
        return true
    }

    suspend fun removeBlocked(domain: BlockedDomain) = dao.deleteBlocked(domain)

    companion object {
        private val DOMAIN_REGEX = Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}$")

        /**
         * Accetta input "sporchi" (URL completi, www., maiuscole) e restituisce il dominio nudo,
         * es. "https://www.UniCredit.it/privati" -> "unicredit.it". null se non valido.
         */
        fun normalizeDomain(raw: String): String? {
            val d = raw.trim().lowercase()
                .substringAfter("://")
                .substringBefore('/')
                .substringBefore(':')
                .removePrefix("www.")
                .trimEnd('.')
            return d.takeIf { DOMAIN_REGEX.matches(it) }
        }

        /** Le parole chiave devono avere almeno 4 caratteri per limitare i falsi positivi. */
        fun normalizeKeyword(raw: String): String? {
            val k = raw.trim().lowercase()
            return k.takeIf { it.length >= 4 && it.all { c -> c.isLetterOrDigit() || c == '-' } }
        }
    }
}
