package com.zerobet.safeguard.vpn

/**
 * Domini che non devono MAI essere bloccati o appresi in automatico (euristica DNS, scoperta
 * crt.sh, auto-apprendimento da schermo): motori di ricerca, grandi piattaforme, siti
 * istituzionali e le fonti stesse delle blocklist. Una pagina di risultati di ricerca o un post
 * social possono mostrare parole come "bonus benvenuto" senza che il sito sia di gioco.
 *
 * Non ha effetto sulle liste esplicite (blacklist del tutore, blocklist esterne).
 */
object SafeDomains {
    private val suffixes = hashSetOf(
        // Ricerca
        "google.com", "google.it", "bing.com", "duckduckgo.com", "yahoo.com", "ecosia.org",
        "qwant.com", "startpage.com", "brave.com",
        // Piattaforme e servizi diffusi
        "youtube.com", "gstatic.com", "googleapis.com", "googleusercontent.com", "android.com",
        "facebook.com", "instagram.com", "whatsapp.com", "whatsapp.net", "x.com", "twitter.com",
        "tiktok.com", "linkedin.com", "reddit.com", "telegram.org", "t.me", "wikipedia.org",
        "amazon.com", "amazon.it", "apple.com", "icloud.com", "microsoft.com", "live.com",
        "office.com", "outlook.com", "netflix.com", "spotify.com", "paypal.com",
        "github.com", "githubusercontent.com", "github.io", "mozilla.org", "samsung.com",
        "cloudflare.com", "akamaiedge.net", "akamai.net",
        // Istituzionali e fonti delle liste
        "gov.it", "europa.eu", "consob.it", "bancaditalia.it", "crt.sh", "yoyo.org",
    )

    /** true se `domain` è uno dei domini sicuri o un loro sottodominio. */
    fun contains(domain: String): Boolean {
        var d = domain.lowercase().trimEnd('.')
        while (true) {
            if (d in suffixes) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
        }
    }

    /** true solo per il dominio sicuro esatto (usato per scartare voci anomale dalle liste esterne). */
    fun isExact(domain: String): Boolean = domain.lowercase().trimEnd('.') in suffixes
}

/**
 * Punteggio di rischio calcolato sul solo NOME a dominio, per i domini non ancora presenti
 * in nessuna lista. Kotlin puro e senza stato: una manciata di regex precompilate per query.
 *
 * Il punteggio è la somma (max 100) di:
 *   - TLD dedicato al settore (.bet, .casino, .forex...)  -> da solo supera la soglia
 *   - TLD "debole" spesso usato da siti di gioco (.win, .vip, .finance, .credit)
 *   - token nel resto del nome (bet365-style, casino, trading, prestito, bonus...)
 * Si blocca quando il punteggio raggiunge [THRESHOLD].
 */
object DomainHeuristics {

    const val THRESHOLD = 60

    private val DEDICATED_TLDS = hashSetOf(
        "bet", "casino", "poker", "bingo", "trading", "forex", "loan", "loans", "crypto",
    )
    private val WEAK_TLDS = hashSetOf("finance", "credit", "win", "vip")

    private const val DEDICATED_TLD_SCORE = 70
    private const val WEAK_TLD_SCORE = 30

    /** Regex applicate alla parte del nome che precede il TLD, con relativo punteggio. */
    private val TOKEN_RULES: List<Pair<Regex, Int>> = listOf(
        // --- Scommesse / casinò ---
        Regex("\\d{2,3}bet|bet\\d{2,3}") to 70,          // 22bet, bet365, 1xbet777...
        Regex("casin[o0]") to 70,
        Regex("scommess") to 70,
        Regex("poker") to 70,
        Regex("betting|bookmaker|freespin|sportsbook") to 70,
        Regex("(^|[.-])bet([.-]|$)") to 60,              // "bet" come parola a sé: my-bet.com
        Regex("roulette|jackpot|blackjack") to 60,
        Regex("slot|lotto|bingo") to 40,
        Regex("(^|[.-])bet[a-z]|[a-z]bet([.-]|$)") to 30, // "bet" a inizio/fine parola: superbet
        Regex("bonus") to 35,
        Regex("vinci|vincite|gioc[oh]") to 25,
        Regex("win|spin") to 20,
        // --- Trading / investimenti ---
        Regex("trading|forex|copytrad|binaryoption|opzionibinarie") to 70,
        Regex("crypto|bitcoin") to 60,
        Regex("invest|broker|cfd") to 25,
        // --- Prestiti / finanziamenti ---
        Regex("prestit|finanziament|cessionedelquinto|cessione-del-quinto") to 70,
        Regex("loan|credito") to 30,
    )

    /** Punteggio 0..100 del dominio (0 per i domini in [SafeDomains]). */
    fun score(rawDomain: String): Int {
        val domain = rawDomain.lowercase().trimEnd('.').removePrefix("www.")
        val lastDot = domain.lastIndexOf('.')
        if (lastDot <= 0 || SafeDomains.contains(domain)) return 0
        val tld = domain.substring(lastDot + 1)
        val body = domain.substring(0, lastDot)

        var score = when (tld) {
            in DEDICATED_TLDS -> DEDICATED_TLD_SCORE
            in WEAK_TLDS -> WEAK_TLD_SCORE
            else -> 0
        }
        for ((regex, points) in TOKEN_RULES) {
            if (regex.containsMatchIn(body)) score += points
            if (score >= 100) return 100
        }
        return score
    }

    fun isRisky(domain: String): Boolean = score(domain) >= THRESHOLD
}
