package com.zerobet.safeguard.vpn

import com.zerobet.safeguard.data.BlockedDomain
import com.zerobet.safeguard.data.WhitelistedBank

/**
 * Snapshot immutabile delle regole, tenuto in memoria dal VpnService e ricostruito
 * ogni volta che Room notifica un cambiamento. Il controllo è O(numero di label) per
 * i domini e O(numero di keyword) per le parole chiave: trascurabile per ogni query DNS.
 */
class DomainFilter(
    private val whitelist: Set<String>,
    private val blockedDomains: Set<String>,
    private val keywords: List<String>
) {
    constructor(whitelisted: List<WhitelistedBank>, blocked: List<BlockedDomain>) : this(
        whitelist = whitelisted.map { it.domain }.toSet(),
        blockedDomains = blocked.filter { !it.isKeyword }.map { it.pattern }.toSet(),
        keywords = blocked.filter { it.isKeyword }.map { it.pattern }
    )

    /**
     * Ordine di valutazione (come da specifica):
     *  1. Whitelist banche  -> consentito, anche se una keyword o la blacklist lo bloccherebbero
     *  2. Blacklist domini  -> bloccato (dominio esatto o qualunque sottodominio)
     *  3. Parole chiave     -> bloccato se il nome contiene la keyword
     */
    fun shouldBlock(rawDomain: String): Boolean {
        val domain = rawDomain.lowercase().trimEnd('.')
        if (domain.isEmpty()) return false
        if (matchesSelfOrParent(whitelist, domain)) return false
        if (matchesSelfOrParent(blockedDomains, domain)) return true
        return keywords.any { domain.contains(it) }
    }

    /** true se `domain` o uno dei suoi domini padre è nel set (es. app.bet365.com -> bet365.com). */
    private fun matchesSelfOrParent(set: Set<String>, domain: String): Boolean {
        var d = domain
        while (true) {
            if (d in set) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    companion object {
        val EMPTY = DomainFilter(emptySet(), emptySet(), emptyList())
    }
}
