package com.zerobet.safeguard.data

import kotlinx.coroutines.flow.Flow

class DomainRepository(private val dao: DomainDao) {

    val blocked: Flow<List<BlockedDomain>> = dao.observeBlocked()
    val whitelist: Flow<List<WhitelistedBank>> = dao.observeWhitelist()

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
