package com.zerobet.safeguard.guard

/** Che cosa è stato riconosciuto in una schermata di un'app. */
enum class InAppKind { AD, CONTENT, STORE_PAGE }

class InAppHit(val kind: InAppKind, val category: ContentCategory, val term: String, val excerpt: String)

/**
 * "Ad & Content Sniper": riconosce, nei testi visibili di una qualsiasi app (YouTube, social, app
 * di notizie, Play Store), pubblicità e contenuti su scommesse, trading/investimenti e prestiti.
 * Kotlin puro, coperto da unit test.
 *
 * Lavora su SEGMENTI: ogni segmento è il testo di un elemento a schermo (un titolo, una
 * didascalia, un pulsante), nell'ordine in cui compaiono. Questo permette due cose:
 *  - valutare ogni titolo o post per conto suo, senza sommare parole di contenuti diversi che
 *    si trovano per caso nella stessa schermata di un feed;
 *  - usare la vicinanza tra segmenti come approssimazione dello "stesso contenitore": l'etichetta
 *    "Sponsorizzato" e il marchio pubblicizzato stanno a pochi elementi di distanza.
 *
 * Regole (pensate per non dare falsi positivi sulle notizie generiche):
 *  - PUBBLICITÀ: un indicatore di annuncio con un termine a rischio entro pochi segmenti;
 *  - CONTENUTO: un singolo segmento che contiene un'espressione inequivocabile ("trading online",
 *    "come investire", "pronostici", "prestito veloce"...) oppure almeno DUE termini a rischio
 *    diversi. Una sola parola generica in un titolo ("mutuo", "borsa italiana") non basta, e
 *    "manovra finanziaria", "inflazione ISTAT", "stipendi pubblici" non contengono alcun termine.
 */
object InAppContentAndAdFilter {

    /** Indicatori forti: da soli identificano un annuncio. */
    private val AD_MARKERS = setOf(
        "sponsorizzato", "sponsorizzata", "sponsorizzati", "sponsored", "sponsor", "annuncio",
        "annunci", "pubblicita", "promosso", "promossa", "promoted", "ad", "ads", "adv", "inserzione",
        "contenuto sponsorizzato", "post sponsorizzato", "paid partnership", "partnership retribuita",
    )

    /** Inviti all'azione: contano come annuncio solo attaccati a un termine a rischio. */
    private val AD_CALLS = setOf(
        "iscriviti", "installa ora", "installa", "scopri di piu", "install now", "install", "learn more",
        "sign up", "registrati", "registrati ora", "apri conto", "apri un conto", "gioca ora", "scarica",
        "scarica ora", "download", "richiedi ora", "inizia ora", "prova ora", "shop now", "visita il sito",
    )

    /** Espressioni che da sole indicano un contenuto dedicato (video, post, articolo). */
    private val INTENT_PHRASES = listOf(
        "come investire", "dove investire", "investire in", "investire oggi", "investire nel", "investire sui",
        "migliori piattaforme", "piattaforme di trading", "piattaforma di trading", "migliori broker",
        "fare trading", "trading online", "trading per principianti", "corso di trading", "corso trading",
        "segnali trading", "segnali forex", "analisi tecnica", "strategia di trading", "strategie di trading",
        "guadagnare con", "guadagnare online", "guadagnare soldi", "soldi facili", "rendita passiva",
        "diventare ricchi", "comprare bitcoin", "comprare azioni", "azioni da comprare", "comprare crypto",
        "migliori criptovalute", "migliori etf", "prezzo bitcoin", "bitcoin oggi", "analisi bitcoin",
        "previsioni bitcoin", "previsione bitcoin", "bitcoin news", "bitcoin price", "buy bitcoin", "crypto news",
        "mercato crypto", "investire in crypto", "comprare ethereum", "bitcoin crolla", "bitcoin vola",
        "bitcoin analisi", "bull run", "crypto trading", "how to invest", "stock market", "make money online", "portafoglio di investimento", "portafoglio investimenti",
        "bonus scommesse", "bonus benvenuto", "bonus di benvenuto", "bonus senza deposito", "giri gratis",
        "free spin", "free spins", "slot online", "slot machine", "casino online", "casino live",
        "migliori casino", "scommesse sportive", "siti scommesse", "siti di scommesse", "quote serie a",
        "quote calcio", "schedina vincente", "pronostici serie a", "pronostici calcio", "pronostici oggi",
        "metodo roulette", "sistema lotto", "come vincere alle", "vincere alle slot",
        "prestito veloce", "prestiti veloci", "prestito online", "prestiti online", "prestito personale",
        "prestito subito", "richiedi prestito", "richiedi un prestito", "richiedi il prestito",
        "cessione del quinto", "soldi subito", "finanziamento veloce", "prestito senza busta paga",
        "prestiti senza busta paga", "credito immediato", "mutuo online", "calcola la rata", "calcola rata",
    )

    /**
     * Parole singole inequivocabili: basta una in un titolo. I nomi delle monete ("bitcoin",
     * "ethereum", "crypto") non sono qui: compaiono anche in titoli che parlano d'altro (una gara
     * con "1 Bitcoin" in palio), quindi servono un secondo termine o un'espressione di [INTENT_PHRASES].
     */
    private val STRONG_WORDS = setOf(
        "trading", "trader", "forex", "criptovalute", "criptovaluta", "altcoin", "memecoin", "scommesse", "pronostici", "schedina", "schedine", "betting",
        "gambling", "superenalotto", "daytrading", "scalping", "tradingview", "metatrader",
    )

    private const val MARKER_WINDOW = 6
    private const val CALL_WINDOW = 3
    private const val MAX_MARKER_LENGTH = 70
    private const val MAX_SEGMENTS = 400

    /** Marchi e parole chiave della scheda di un'app nel Play Store (pulsanti che la identificano). */
    private val STORE_BUTTONS = setOf("installa", "install", "aggiorna", "update", "apri", "open", "disinstalla", "uninstall")

    // ---------------------------------------------------------------------------------------
    // Pubblicità
    // ---------------------------------------------------------------------------------------

    /** true se il segmento è un'etichetta di annuncio ("Sponsorizzato", "Annuncio · etoro.com", "Ad"). */
    fun isAdMarker(segment: String): Boolean {
        if (segment.length > MAX_MARKER_LENGTH) return false
        val text = SearchQueryClassifier.normalize(segment)
        if (text.isEmpty()) return false
        if (text in AD_MARKERS) return true
        val first = text.substringBefore(' ')
        if (first in AD_MARKERS) return true
        return " $text ".let { it.contains(" sponsorizzato ") || it.contains(" sponsored ") || it.contains(" annuncio ") }
    }

    fun isAdCall(segment: String): Boolean =
        segment.length <= 40 && SearchQueryClassifier.normalize(segment) in AD_CALLS

    /** Annuncio a rischio: indicatore di pubblicità con un termine a rischio nei segmenti vicini. */
    fun findAd(segments: List<String>): InAppHit? {
        val limit = minOf(segments.size, MAX_SEGMENTS)
        for (i in 0 until limit) {
            val window = when {
                isAdMarker(segments[i]) -> MARKER_WINDOW
                isAdCall(segments[i]) -> CALL_WINDOW
                else -> continue
            }
            for (j in maxOf(0, i - window)..minOf(limit - 1, i + window)) {
                if (j == i) continue
                val hit = firstHit(segments[j]) ?: continue
                return InAppHit(InAppKind.AD, hit.category, hit.term, segments[j].take(80))
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Contenuti (titoli di video, post, articoli)
    // ---------------------------------------------------------------------------------------

    /** Valuta un solo titolo o testo. */
    fun classifyText(segment: String): InAppHit? {
        if (segment.length < 8) return null
        for (chunk in chunks(segment)) {
            val hits = SearchQueryClassifier.matchAll(chunk)
            if (hits.isEmpty()) continue            // comprende le ricerche di aiuto, mai bloccate
            val padded = " " + SearchQueryClassifier.normalize(chunk) + " "
            val intent = INTENT_PHRASES.firstOrNull { padded.contains(" $it ") }
            val strong = hits.firstOrNull { it.term in STRONG_WORDS }
            val distinct = hits.map { it.term }.distinct().size
            if (intent != null || strong != null || distinct >= 2) {
                val best = strong ?: hits.first()
                return InAppHit(InAppKind.CONTENT, best.category, intent ?: best.term, chunk.take(80))
            }
        }
        return null
    }

    fun findContent(segments: List<String>): InAppHit? {
        for (i in 0 until minOf(segments.size, MAX_SEGMENTS)) classifyText(segments[i])?.let { return it }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Scheda di un'app nel Play Store
    // ---------------------------------------------------------------------------------------

    /**
     * Scheda di un'app di trading, scommesse o prestiti: la schermata ha il pulsante "Installa"
     * (o Apri/Aggiorna) e un termine a rischio nel titolo, nella categoria o nella descrizione.
     */
    fun classifyStorePage(segments: List<String>): InAppHit? {
        val limit = minOf(segments.size, MAX_SEGMENTS)
        var isAppPage = false
        for (i in 0 until limit) {
            if (segments[i].length <= 14 && SearchQueryClassifier.normalize(segments[i]) in STORE_BUTTONS) {
                isAppPage = true
                break
            }
        }
        if (!isAppPage) return null
        for (i in 0 until limit) {
            val hit = firstHit(segments[i]) ?: continue
            return InAppHit(InAppKind.STORE_PAGE, hit.category, hit.term, segments[i].take(80))
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Campi di ricerca
    // ---------------------------------------------------------------------------------------

    private val SEARCH_ID = Regex("(search|query|ricerca)", RegexOption.IGNORE_CASE)
    private val NOT_SEARCH_BOX = Regex("(result|suggest|item|history|recent|chip|tab|filter|title|button|btn|icon|container|layout|list)", RegexOption.IGNORE_CASE)
    private val SEARCH_HINT = Regex("\\b(cerca|search|ricerca|trova|find|suche|buscar|chercher)\\b", RegexOption.IGNORE_CASE)

    /**
     * true se l'elemento è una casella di ricerca: lo dice l'id della vista ("search_src_text",
     * "search_query", "search_edit_text"...) oppure, per le caselle di testo, il suggerimento
     * ("Cerca", "Search YouTube"). Gli elementi dei risultati e dei suggerimenti sono esclusi.
     */
    fun isSearchField(viewId: String?, hint: String?, editable: Boolean): Boolean {
        val id = viewId?.substringAfter(":id/", "").orEmpty()
        if (id.isNotEmpty() && SEARCH_ID.containsMatchIn(id) && !NOT_SEARCH_BOX.containsMatchIn(id)) return true
        return editable && !hint.isNullOrBlank() && hint.length <= 60 && SEARCH_HINT.containsMatchIn(hint)
    }

    // ---------------------------------------------------------------------------------------

    private fun firstHit(segment: String): SearchHit? {
        if (segment.length < 3) return null
        for (chunk in chunks(segment)) SearchQueryClassifier.matchAll(chunk).firstOrNull()?.let { return it }
        return null
    }

    /** Il riconoscitore lavora su testi brevi: i testi lunghi (corpo di un articolo) si dividono in frasi. */
    private fun chunks(text: String): List<String> {
        if (text.length <= 280) return listOf(text)
        val out = ArrayList<String>()
        for (sentence in text.take(4_000).split('.', '\n', '!', '?', ';')) {
            var s = sentence.trim()
            while (s.length > 280) {
                val cut = s.lastIndexOf(' ', 280).takeIf { it > 0 } ?: 280
                out.add(s.substring(0, cut))
                s = s.substring(cut).trim()
            }
            if (s.length >= 8) out.add(s)
        }
        return out
    }
}
