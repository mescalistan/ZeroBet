package com.zerobet.safeguard.guard

/** Tipo di conversazione aperta in un'app di messaggi con canali (Telegram). */
enum class ChatKind { PRIVATE, PUBLIC, UNKNOWN }

/**
 * Telegram ospita sia chat private sia canali e gruppi pubblici (sale segnali, canali pronostici,
 * bot di casinò). Kotlin puro, coperto da unit test.
 *
 *  - Canali, gruppi e bot (riconosciuti dal sottotitolo dell'intestazione: "12.345 iscritti",
 *    "membri", "bot", o dai pulsanti "Unisciti", "Silenzia"): filtro standard su contenuti e annunci.
 *  - Chat private 1-a-1 ("online", "ultimo accesso...", "sta scrivendo") ed elenco delle chat:
 *    le parole discorsive non contano; si blocca solo se compare un link o un dominio a rischio,
 *    oppure un invito a un bot o a un canale il cui nome è a rischio (@CasinoBonusBot, t.me/segnalitrading).
 *  - La casella di ricerca globale è controllata sempre, come in ogni altra app.
 */
object TelegramRules {

    val PACKAGES = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
        "org.thunderdog.challegram", "nekox.messenger", "tw.nekomimi.nekogram",
    )

    /** Quanti elementi dall'alto della schermata considerare "intestazione". */
    private const val HEADER_SEGMENTS = 15

    private val PUBLIC_COUNT = Regex(
        "\\b\\d[\\d.,]*\\s*(k|mila|mln|m)?\\s*(iscritti|iscritto|subscribers?|membri|membro|members?|partecipanti|" +
            "participants?|visualizzazioni|views|utenti mensili|monthly users)\\b"
    )
    private val PUBLIC_WORDS = setOf(
        "bot", "unisciti", "join", "join channel", "join group", "unisciti al canale", "unisciti al gruppo",
        "silenzia", "mute", "riattiva audio", "unmute", "avvia", "start", "canale", "channel", "gruppo", "group",
        "canale pubblico", "public channel", "gruppo pubblico", "public group",
    )
    private val PRIVATE_PREFIXES = listOf(
        "ultimo accesso", "ultimo accesso il", "last seen", "visto di recente", "visto ultimamente",
        "seen recently", "visto questa settimana", "visto questo mese",
    )
    private val PRIVATE_CONTAINS = listOf("sta scrivendo", "is typing", "typing", "sta registrando", "recording")

    fun chatKind(segments: List<String>): ChatKind {
        var private = false
        for (i in 0 until minOf(segments.size, HEADER_SEGMENTS)) {
            val s = segments[i].lowercase().trim()
            if (s.isEmpty() || s.length > 60) continue
            if (s in PUBLIC_WORDS || PUBLIC_COUNT.containsMatchIn(s)) return ChatKind.PUBLIC
            if (s == "online" || PRIVATE_PREFIXES.any { s.startsWith(it) } || PRIVATE_CONTAINS.any { s.contains(it) }) {
                private = true
            }
        }
        return if (private) ChatKind.PRIVATE else ChatKind.UNKNOWN
    }

    private val URL = Regex("(?:https?://)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(/[^\\s]*)?")
    private val HANDLE = Regex("(?:^|[\\s(])@([a-z0-9_]{4,32})")
    private val TELEGRAM_LINK_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")

    /** Radici che rendono a rischio il nome di un bot o di un canale (che non ha spazi tra le parole). */
    private val HANDLE_STEMS = listOf(
        "trading", "trader", "forex", "crypto", "cripto", "bitcoin", "btc", "binance", "pumpsignal", "signals",
        "segnali", "casino", "slot", "poker", "roulette", "blackjack", "jackpot", "scommess", "pronostic",
        "betting", "bet365", "1xbet", "22bet", "schedin", "bookmaker", "prestit", "loan", "finanziament",
    )

    fun isRiskyHandle(handle: String): Boolean {
        val h = handle.lowercase()
        return HANDLE_STEMS.any { h.contains(it) }
    }

    /**
     * Link, dominio o invito a rischio nei testi visibili.
     * @param isBlockedDomain decide se un dominio è bloccato (nell'app: il filtro DNS con liste,
     *   parole chiave ed euristica).
     */
    fun findRiskyLink(segments: List<String>, isBlockedDomain: (String) -> Boolean): InAppHit? {
        for (segment in segments) {
            if (segment.length < 4) continue
            val text = segment.lowercase()
            for (m in URL.findAll(text)) {
                val host = m.groupValues[1].removePrefix("www.")
                if (host in TELEGRAM_LINK_HOSTS) {
                    val name = m.groupValues[2].trim('/').substringBefore('/').substringBefore('?')
                    if (name.isNotEmpty() && isRiskyHandle(name)) {
                        return InAppHit(InAppKind.CONTENT, categoryOfHandle(name), "t.me/$name", segment.take(80))
                    }
                } else if (host.contains('.') && !host.first().isDigit() && isBlockedDomain(host)) {
                    return InAppHit(InAppKind.CONTENT, categoryOfHandle(host), host, segment.take(80))
                }
            }
            for (m in HANDLE.findAll(text)) {
                val name = m.groupValues[1]
                if (isRiskyHandle(name)) return InAppHit(InAppKind.CONTENT, categoryOfHandle(name), "@$name", segment.take(80))
            }
        }
        return null
    }

    private fun categoryOfHandle(name: String): ContentCategory = when {
        listOf("prestit", "loan", "finanziament").any { name.contains(it) } -> ContentCategory.LOANS
        listOf("trading", "trader", "forex", "crypto", "cripto", "bitcoin", "btc", "binance", "signals", "segnali", "pumpsignal")
            .any { name.contains(it) } -> ContentCategory.TRADING
        else -> ContentCategory.GAMBLING
    }
}

/**
 * Dentro l'app della banca autorizzata dal tutore: saldo, movimenti e bonifici restano liberi,
 * ma le sezioni dedicate a prestiti, finanziamenti, investimenti, trading e crypto vengono chiuse.
 * Kotlin puro, coperto da unit test.
 *
 * Si guarda solo l'INTESTAZIONE della schermata (i primi elementi in alto, dove le app mostrano il
 * titolo della sezione): così una voce "Prestiti" nel menu o un banner promozionale nella home non
 * rendono inutilizzabile l'app, mentre entrare nella sezione "Richiedi prestito" sì.
 */
object BankAppRules {

    private const val HEADER_SEGMENTS = 3
    private const val MAX_TITLE_CHARS = 60

    /** Termini che in un'app bancaria indicano servizi esistenti da consultare, non nuove richieste. */
    private val ALLOWED_TERMS = setOf("mutuo", "mutui")

    fun findRiskySection(segments: List<String>): InAppHit? {
        for (i in 0 until minOf(segments.size, HEADER_SEGMENTS)) {
            val title = segments[i].trim()
            if (title.length < 3 || title.length > MAX_TITLE_CHARS) continue
            val hit = SearchQueryClassifier.classify(title) ?: continue
            if (hit.term in ALLOWED_TERMS) continue
            return InAppHit(InAppKind.CONTENT, hit.category, hit.term, title)
        }
        return null
    }
}

/**
 * Attivazione delle Opzioni sviluppatore: si ottiene toccando 7 volte "Numero build" (in
 * Info telefono o, su Samsung, in Informazioni software; su Xiaomi è "Versione MIUI/HyperOS").
 * Il Guardiano chiede il PIN al PRIMO tocco: la schermata di blocco interrompe la pagina e
 * le Impostazioni azzerano il conteggio dei tocchi quando la pagina riprende.
 * Kotlin puro, coperto da unit test.
 */
object DeveloperOptionsRules {

    private val BUILD_LABELS = listOf(
        "numero build", "numero di build", "numero della build", "build number", "build-nummer",
        "versione miui", "miui version", "versione hyperos", "hyperos version",
        "versione os", "os version",
    )

    /**
     * true se il tocco è sulla voce "Numero build". [texts] sono i testi dell'evento di clic
     * (titolo e riepilogo della voce): conta il titolo, cioè la prima riga non vuota, così un
     * testo che menziona la build in mezzo a una frase non attiva il blocco.
     */
    fun isBuildNumberTap(texts: List<CharSequence?>): Boolean {
        val title = texts.asSequence()
            .mapNotNull { it?.toString() }
            .flatMap { it.lineSequence() }
            .map { it.lowercase().replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim() }
            .firstOrNull { it.isNotEmpty() } ?: return false
        if (title.length > 40) return false
        return BUILD_LABELS.any { title == it || title.startsWith("$it ") || title.startsWith("$it:") }
    }
}
