package com.zerobet.safeguard.vpn

import com.zerobet.safeguard.data.AutoLearnedBlockedDomain
import com.zerobet.safeguard.data.BlockedDomain
import com.zerobet.safeguard.data.WhitelistedBank

/** Perché un dominio è stato bloccato. */
enum class BlockReason {
    LIST,       // blacklist del tutore, voci predefinite, domini auto-appresi o fonti esterne
    KEYWORD,    // contiene una parola chiave della blacklist
    HEURISTIC   // punteggio di rischio del nome sopra soglia (dominio non ancora in lista)
}

class Verdict(val reason: BlockReason?, val score: Int = 0) {
    val blocked: Boolean get() = reason != null

    companion object {
        val ALLOW = Verdict(null)
    }
}

/**
 * Snapshot immutabile delle regole, tenuto in memoria e ricostruito quando il DB cambia
 * (vedi [FilterStore]).
 *
 * Costo per query DNS: lookup in HashSet, quindi O(1) per il match esatto e O(numero di label)
 * per il match sui domini padre (un nome ha 2-5 label), indipendentemente dal fatto che le
 * liste contengano cento o centinaia di migliaia di domini. Parole chiave ed euristica vengono
 * valutate solo per i nomi che non sono già in lista.
 */
class DomainFilter(
    private val whitelist: Set<String>,
    /** Blacklist "locale": voci predefinite, aggiunte dal tutore e domini auto-appresi. */
    private val blockedDomains: Set<String>,
    private val keywords: List<String>,
    /** Domini delle fonti esterne (set condiviso, non copiato: può essere molto grande). */
    private val external: Set<String> = emptySet(),
    private val heuristicsEnabled: Boolean = true
) {
    constructor(
        whitelisted: List<WhitelistedBank>,
        blocked: List<BlockedDomain>,
        learned: List<AutoLearnedBlockedDomain> = emptyList(),
        external: Set<String> = emptySet()
    ) : this(
        whitelist = whitelisted.mapTo(HashSet()) { it.domain },
        blockedDomains = HashSet<String>().apply {
            blocked.forEach { if (!it.isKeyword) add(it.pattern) }
            learned.forEach { add(it.domain) }
        },
        keywords = blocked.filter { it.isKeyword }.map { it.pattern },
        external = external
    )

    /**
     * Ordine di valutazione:
     *  1. Whitelist del tutore -> consentito, qualunque altra regola lo bloccherebbe
     *  2. Liste (locale + fonti esterne): match esatto, poi match sui domini padre
     *  3. Parole chiave
     *  4. Euristica sul nome
     */
    fun check(rawDomain: String): Verdict {
        val domain = normalize(rawDomain)
        if (domain.isEmpty()) return Verdict.ALLOW
        if (matches(whitelist, domain)) return Verdict.ALLOW
        if (matches(blockedDomains, domain) || matches(external, domain)) return Verdict(BlockReason.LIST)
        return checkRules(domain)
    }

    fun shouldBlock(rawDomain: String): Boolean = check(rawDomain).blocked

    fun isWhitelisted(rawDomain: String): Boolean = matches(whitelist, normalize(rawDomain))

    /**
     * true se il dominio sarebbe bloccato dalle sole regole dinamiche (parole chiave o
     * euristica), anche senza essere in lista. La sincronizzazione lo usa per non salvare
     * voci ridondanti: es. migliaia di sottodomini "*.casino" già coperti dall'euristica.
     */
    fun blockedByRules(rawDomain: String): Boolean = checkRules(normalize(rawDomain)).blocked

    private fun checkRules(domain: String): Verdict {
        if (keywords.any { domain.contains(it) }) return Verdict(BlockReason.KEYWORD)
        if (heuristicsEnabled) {
            val score = DomainHeuristics.score(domain)
            if (score >= DomainHeuristics.THRESHOLD) return Verdict(BlockReason.HEURISTIC, score)
        }
        return Verdict.ALLOW
    }

    /**
     * Match esatto, poi sui sottodomini: "app.bet365.com" è coperto dalla voce "bet365.com".
     * Le voci senza punto (un TLD intero) non vengono mai usate per il match sui padri.
     */
    private fun matches(set: Set<String>, domain: String): Boolean {
        if (set.isEmpty()) return false
        if (domain in set) return true                 // match esatto
        var d = domain
        while (true) {                                 // match sui domini padre
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
            if (d.indexOf('.') < 0) return false       // rimasto solo il TLD
            if (d in set) return true
        }
    }

    private fun normalize(raw: String): String = raw.lowercase().trimEnd('.')

    companion object {
        val EMPTY = DomainFilter(emptySet(), emptySet(), emptyList(), heuristicsEnabled = false)
    }
}
