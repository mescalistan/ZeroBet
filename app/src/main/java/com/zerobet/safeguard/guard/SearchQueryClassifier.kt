package com.zerobet.safeguard.guard

import java.net.URLDecoder
import java.text.Normalizer

/** Ricerca a rischio: [term] è la parola o l'espressione che l'ha fatta scattare. */
class SearchHit(val category: ContentCategory, val term: String)

/**
 * Riconosce le ricerche su scommesse, trading/investimenti e prestiti scritte in un motore di
 * ricerca. Kotlin puro, coperto da unit test.
 *
 * A differenza di [ContentClassifier] qui basta UNA parola ("trading", "investimenti"): chi la
 * scrive in un motore di ricerca la sta cercando. Per non dare falsi positivi:
 *  - il confronto è per parola intera (o per radice all'inizio della parola), mai per sottostringa:
 *    "investigatore" non è "investi", "betulla" non è "bet", "distanza" non è "tan";
 *  - le parole con più significati valgono solo in un'espressione ("slot machine" sì, "slot" no;
 *    "comprare azioni" sì, "azioni" no; "borsa italiana" sì, "borsa" no);
 *  - le espressioni innocue che contengono una parola a rischio vengono tolte prima del confronto
 *    ("giocatore in prestito", "trading card", "poker face", "mutuo soccorso"...);
 *  - le ricerche di aiuto ("come smettere di giocare", "ludopatia numero verde") non sono mai bloccate.
 */
object SearchQueryClassifier {

    // ---------------------------------------------------------------------------------------
    // Vocabolario
    // ---------------------------------------------------------------------------------------

    /** Parole intere. */
    private val WORDS: Map<ContentCategory, Set<String>> = mapOf(
        ContentCategory.GAMBLING to setOf(
            // generiche
            "casino", "casinos", "poker", "blackjack", "roulette", "baccarat", "bingo", "betting",
            "gambling", "azzardo", "jackpot", "schedina", "schedine", "pronostico", "pronostici",
            "bookmaker", "bookmakers", "bookmakers", "allibratore", "allibratori", "tipster", "tipsters",
            "lotteria", "lotterie", "superenalotto", "enalotto", "eurojackpot", "totocalcio", "totogol",
            "millionday", "10elotto", "grattaevinci", "videolottery", "videopoker", "vlt", "slotmachine",
            "slots", "freespin", "freespins", "betslip", "surebet", "surebets", 
            "ippica", "texas holdem", "holdem", "videoslot", "videoslots", "sportsbook", "wager", "wagering",
            // marchi
            "snai", "sisal", "goldbet", "eurobet", "lottomatica", "betfair", "bwin", "pokerstars",
            "planetwin", "planetwin365", "starcasino", "starvegas", "leovegas", "betway", "williamhill",
            "unibet", "admiralbet", "netbet", "betflag", "stanleybet", "betsson", "betclic", "marathonbet",
            "giocodigitale", "novibet", "quigioco", "domusbet", "vincitu", "betaland", "sportbet",
            "eplay24", "codere", "snaitech", "casinomania", "netwin", "betnero", "sportium", 
            "mostbet", "daznbet", "betsolution", "bet365", "888casino", "888poker", "888sport",
            "sportitaliabet", "signorbet", "betitaly", "zonagioco", "admiralyes",
            "gioco digitale", "partypoker", "ggpoker", "winamax", "betano", "bet9ja", "sportpesa",
            "melbet", "betwinner", "megapari", "rollbit", "bcgame", "roobet", "cloudbet",
        ),
        ContentCategory.TRADING to setOf(
            // generiche
            "trading", "trader", "traders", "forex", "crypto", "cripto", "cryptos",
            "bitcoin", "bitcoins", "btc", "ethereum", "altcoin", "altcoins", "memecoin", "memecoins",
            "dogecoin", "shitcoin", "stablecoin", "stablecoins", "litecoin", "nft", "nfts", "usdt", "xrp",
            "solana", "staking", "scalping", "daytrading", "etf", "etfs", "btp", 
            "obbligazionario", "azionario", "nasdaq", "ftse", "investimenti", "investimento", "investire",
            "investi", "investo", "investiamo", "investitore", "investitori", "investing", "investment",
            "investments", "invest", "investor", "investors", "stocks", "borsistico",
            "blockchain", "tokenomics", "exchange crypto", "robotrading",
            "autotrading", "tradare", "tradando", "hodl", "polizze unit linked",
            // marchi
            "binance", "coinbase", "etoro", "plus500", "degiro", "xtb", "avatrade", "bybit", "kucoin",
            "bitpanda", "tradingview", "metatrader", "mt4", "mt5", "bitget", "okx", "iqoption",
            "pocketoption", "moneyfarm", "freedom24", "fineco trading", "capital com", "robinhood",
            "bitfinex", "bitstamp", "coinmarketcap", "coingecko", "metamask", "ledger wallet",
            "investing com", "xm trading", "fpmarkets", "pepperstone", "exness", "libertex", 
            "activtrades", "markets com", "trade republic", "scalable capital", "young platform",
            "crypto com", "ig markets", "interactive brokers", "saxo bank", "fxpro", "fxcm", "oanda",
        ),
        ContentCategory.LOANS to setOf(
            // generiche
            "mutuo", "mutui", "loan", "loans", "microcredito", "microprestito", "microprestiti",
            "revolving", "strozzino", "strozzini", "usuraio", "usurai", 
            "indebitarsi", "rifinanziamento", "surroga", "leasing", "scoperto di conto",
            // marchi
            "agos", "findomestic", "younited", "cofidis", "fiditalia", "prestitionline", "mutuionline",
            "prexta", "avvera", "creditis", "soisy", "scalapay", "klarna", "clearpay",
            "prestiamoci", "smava", "signorprestito", "figenpa", "sigla credit", "conafi", "ibl banca",
            "dynamica retail", "santander consumer", "compass banca", "compass prestiti",
            "compass finanziaria", "deutsche bank easy", "sella personal credit", "facile it",
            "cashper", "moneyfy", "ferratum", "kviku", "creditoitalia",
        ),
    )

    /** Radici: valgono se la parola INIZIA così (es. "scommess" -> scommesse, scommessa). */
    private val STEMS: Map<ContentCategory, List<String>> = mapOf(
        ContentCategory.GAMBLING to listOf("scommess", "scommett", "pokerst", "superenalott"),
        ContentCategory.TRADING to listOf(
            "tradingonline", "criptovalut", "cryptovalut", "cryptocurrenc", "criptomonet", "bitcoin",
            "forextrad", "tradingview",
        ),
        ContentCategory.LOANS to listOf("prestit", "finanziament", "cessionedelquinto"),
    )

    /** Parole che valgono anche in fondo a un nome composto (es. "starcasino", "888poker"). */
    private val SUFFIXES: Map<ContentCategory, List<String>> = mapOf(
        ContentCategory.GAMBLING to listOf("casino", "poker", "scommesse"),
        ContentCategory.TRADING to listOf("trading", "forex", "coin"),
        ContentCategory.LOANS to listOf("prestiti", "prestito"),
    )

    /** Espressioni a rischio che contengono un'espressione innocua ("in prestito"): si controllano per prime. */
    private val PRIORITY_PHRASES = listOf(
        "soldi in prestito" to ContentCategory.LOANS,
        "denaro in prestito" to ContentCategory.LOANS,
        "prendere in prestito soldi" to ContentCategory.LOANS,
    )

    /** Espressioni di più parole: è qui che entrano i termini ambigui. */
    private val PHRASES: Map<ContentCategory, List<String>> = mapOf(
        ContentCategory.GAMBLING to listOf(
            "slot machine", "slot machines", "slot online", "slot gratis", "slot vlt", "giochi slot",
            "slot demo", "slot da bar", "sala slot", "sale slot", 
            "sala scommesse", "sala bingo", "gratta e vinci", "10 e lotto", "dieci e lotto",
            "win for life", "million day", "estrazioni lotto", "estrazione lotto", "estrazioni del lotto",
            "estrazione del lotto", "lotto estrazioni", "lotto estrazione", "numeri lotto", "lotto oggi",
            "numeri ritardatari", "bonus benvenuto", "bonus di benvenuto", "bonus senza deposito",
            "giri gratis", "free spin", "free spins", "quote calcio", "quote serie a", "quote champions",
            "quote partite", "quote oggi", "quote vincente", "quote antepost", "quote live",
            "puntare soldi", "giocare soldi", 
            "william hill", "big casino", "jackpot city", "merkur win", "dazn bet", "e play24",
            "bet 365", "1x bet", "22 bet", "gioco online soldi veri", "giochi soldi veri", "casinoonline", "segugio it", "vincere soldi",
            "vincere al lotto", "vincere alla lotteria", "sistemi lotto",
            "corse cavalli", "corse dei cavalli", "corse ippiche", "live betting", "betting exchange",
            "under over", "over 2 5", "goal no goal", "risultato esatto quote",
        ),
        ContentCategory.TRADING to listOf(
            "comprare azioni", "compra azioni", "comprare titoli", "acquistare azioni", "acquisto azioni",
            "azioni da comprare", "azioni tesla", "azioni nvidia", "azioni apple",
            "azioni amazon", "azioni enel", "azioni eni", "azioni unicredit", "azioni intesa",
            "azioni stellantis", "azioni ferrari", "azioni leonardo", "azioni oggi", "vendere azioni",
            "prezzo azioni", "quotazione azioni", "quotazioni azioni", "azioni in borsa", "azioni borsa",
            "giocare in borsa", "borsa italiana", "borsa di milano", "borsa oggi", "borsa valori",
            "andamento borsa", "piazza affari", "ftse mib", "dow jones", "s p 500",
            "sp500", "mercato azionario", "mercati finanziari", "mercati azionari", "stock market",
            "fondi comuni", "fondi di investimento", "fondo di investimento", "piano di accumulo",
            "opzioni binarie", "leva finanziaria", "conto demo", "day trading", "copy trading",
            "swing trading", "broker online", "broker forex", "broker finanziario", "migliori broker",
            "broker cfd", "cfd azioni", "cfd forex", "cfd trading", "trading cfd", "conto cfd", "migliori azioni da comprare", "pump and dump", "soldi facili", "fare soldi online", "fare soldi velocemente",
            "fare soldi facili", "guadagnare online", "guadagnare soldi", 
            "guadagni facili", "guadagno facile", "raddoppiare i soldi", "raddoppiare soldi",
            "diventare ricchi", "diventare ricco", "arricchirsi velocemente", "rendita passiva",
            "rendite passive", "wallet bitcoin", "exchange criptovalute", 
            
            "oro da investimento",
            "shiba inu coin", "shiba inu prezzo", "valore bitcoin", "ripple xrp",
        ),
        ContentCategory.LOANS to listOf(
            "cessione del quinto", "cessione quinto", "cessione del 5", "fido bancario", "richiesta fido",
            "fido di conto", "soldi subito", "soldi in prestito", "liquidita immediata",
            "liquidita subito", "anticipo stipendio", "anticipo tfs", "anticipo tfr",
            "anticipo sullo stipendio", "carta revolving", "carte revolving", "carta di credito revolving",
            "consolidamento debiti", "consolidamento debito", "compro oro", "banco dei pegni",
            "monte dei pegni", "banco pegni", "payday loan", "cash advance", "paga in 3 rate",
            
            "calcola rata", "calcolo rata", "simulazione rata", "delega di pagamento",
            "credito al consumo", "credito veloce", "credito immediato", "credito subito",
            "credito senza busta paga", "soldi senza garanzie", 
            "cattivi pagatori", "segnalati crif", 
            "farsi prestare soldi", "chiedere soldi in prestito", "linea di credito", "apertura di credito",
        ),
    )

    /**
     * Espressioni innocue che contengono una parola a rischio: vengono cancellate dalla ricerca
     * prima del confronto, così il resto della frase viene comunque valutato.
     */
    private val NEUTRAL_PHRASES = listOf(
        // prestito: calcio e biblioteche
        "in prestito", "prestito con diritto", "prestito con obbligo", "prestito secco",
        "prestito oneroso", "prestito gratuito", "prestito libri", "prestito libro", "prestito bibliotecario",
        "prestito interbibliotecario", "prestito bibliotecari", "prestito digitale", "prestito ebook",
        "prestiti calciomercato", "calciomercato prestiti", "prestito linguistico", "prestiti linguistici",
        "prestito d uso", "prestito opere", "prestito attrezzatura", "prestito bici",
        "on loan", "loan word", "loan words", "loanword",
        // finanziamento pubblico
        "finanziamento pubblico", "finanziamenti pubblici", "finanziamento ai partiti",
        "finanziamento dei partiti", "finanziamenti europei", "finanziamento europeo",
        "finanziamento della ricerca", "finanziamenti alla ricerca", "finanziamento illecito",
        "finanziamento del terrorismo", "finanziamento della scuola", "finanziamento sanita",
        "finanziamento della sanita", "finanziamento pnrr", "finanziamenti pnrr",
        // mutuo
        "mutuo soccorso", "mutuo consenso", "mutuo aiuto", "mutuo rispetto", "mutuo accordo",
        "mutuo riconoscimento", "mutuo appoggio", "mutuo scambio", "mutuo insegnamento",
        // investimento
        "investimento pedone", "investimento pedonale", "investimento stradale", "investimento pedoni",
        "investimento mortale", "investimento ciclista", "investimento di un pedone", "investimento auto",
        "investimento treno", "investimento ferroviario", "investimento animale", "investi un pedone",
        // trading
        "trading card", "trading cards", "trading post", "trade union", "trade unions", "fair trade",
        "trade off", "trade mark", "world trade center", "world trade", "free trade", "trade show",
        "trader joe", "trader joes", "trader joe s",
        // gioco
        "poker face", "pokerface", "casino royale", "roulette russa", "russian roulette",
        "lotteria degli scontrini", "lotteria scontrini", "che casino", "un casino di", "bingo bongo",
        "poker di donne",
        "poker di", "cala il poker", "poker personale", "poker servito",
        // omonimi: la fibra di sisal, la squadra di ciclismo Cofidis
        "in sisal", "di sisal", "corda sisal", "tappeto sisal", "tappeti sisal", "fibra sisal",
        "sisal corda", "sisal tappeto", "team cofidis", "squadra cofidis", "cofidis ciclismo",
        "cofidis cycling", "cofidis tour",
        // varie
        "crypto ag", "trade winds", "exchange student", "revolving door", "porta revolving",
        "revolving doors", "mutui scambi", "leasing sociale", 
        "investire del potere", "investire di un incarico", "investi di autorita",
        
    )

    /** Ricerche di aiuto: chi vuole smettere non va fermato. */
    private val HELP_WORDS = setOf(
        "smettere", "dipendenza", "dipendenze", "ludopatia", "ludopatico", "ludopatici", "ludopata",
        "autoesclusione", "autoescludersi", "autoescludermi", "terapia", "curare", "guarire",
        "psicologo", "psicoterapia", "psichiatra", "sostegno", "sovraindebitamento", "disintossicarsi",
        "serd", "sert", "addiction", "rehab", "gamblers", "patologico", "compulsivo", "compulsiva",
        "prevenzione", "recupero", "riabilitazione", "antiusura", "usura", "denunciare", "denuncia",
        "bloccare", "blocco", "vietare", "divieto",
    )
    private val HELP_PHRASES = listOf(
        "giocatori anonimi", "numero verde", "telefono verde", "come uscire", "uscire dal",
        "uscire dai", "chiedere aiuto", "ho bisogno di aiuto", "aiuto per", "centro di ascolto",
        "gioco d azzardo patologico", "disturbo da gioco", "problemi di gioco", "problema di gioco",
        "gioco responsabile autoesclusione", "stop gambling", "quit gambling", "gambling help",
        "legge sul sovraindebitamento", "vittima di", "vittime di",
    )

    private val BET_BRAND = Regex("^(\\d{1,4}x?bet|bet\\d{1,4}|\\d{2,4}casino|casino\\d{2,4})$")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val DIACRITICS = Regex("\\p{Mn}+")

    private val wordIndex: Map<String, ContentCategory>
    private val phraseIndex: List<Pair<String, ContentCategory>>

    init {
        val words = HashMap<String, ContentCategory>()
        val phrases = ArrayList<Pair<String, ContentCategory>>()
        // Le voci di WORDS con uno spazio (es. "trade republic") sono trattate come espressioni.
        for ((category, set) in WORDS) for (w in set) {
            if (' ' in w) phrases.add(w to category) else words[w] = category
        }
        for ((category, list) in PHRASES) for (p in list) phrases.add(p to category)
        wordIndex = words
        phraseIndex = phrases
    }

    // ---------------------------------------------------------------------------------------
    // Classificazione
    // ---------------------------------------------------------------------------------------

    /** Minuscolo, senza accenti, separatori ridotti a un solo spazio. */
    fun normalize(raw: String): String {
        val noAccents = DIACRITICS.replace(Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD), "")
        return NON_WORD.replace(noAccents, " ").trim()
    }

    /**
     * @param typing true se il testo è ancora in fase di scrittura: l'ultima parola è incompleta
     *   ("invest" potrebbe diventare "investigatore") e viene ignorata finché non è seguita da uno spazio.
     * @return la categoria a rischio, o null se la ricerca è innocua.
     */
    fun classify(rawQuery: String, typing: Boolean = false): SearchHit? {
        if (rawQuery.length < 3 || rawQuery.length > 300) return null
        var text = normalize(rawQuery)
        if (typing && !rawQuery.last().isWhitespace()) text = text.substringBeforeLast(' ', "")
        if (text.length < 3) return null

        var padded = " $text "
        if (HELP_PHRASES.any { padded.contains(" $it ") }) return null
        val allTokens = text.split(' ')
        if (allTokens.any { it in HELP_WORDS }) return null

        for ((phrase, category) in PRIORITY_PHRASES) {
            if (padded.contains(" $phrase ")) return SearchHit(category, phrase)
        }
        for (neutral in NEUTRAL_PHRASES) {
            if (padded.contains(" $neutral ")) padded = padded.replace(" $neutral ", " | ")
        }

        for ((phrase, category) in phraseIndex) {
            if (padded.contains(" $phrase ")) return SearchHit(category, phrase)
        }

        for (token in padded.split(' ')) {
            if (token.length < 3) continue
            wordIndex[token]?.let { return SearchHit(it, token) }
            if (BET_BRAND.matches(token)) return SearchHit(ContentCategory.GAMBLING, token)
            for ((category, stems) in STEMS) {
                if (stems.any { token.startsWith(it) }) return SearchHit(category, token)
            }
            for ((category, suffixes) in SUFFIXES) {
                if (suffixes.any { token.length > it.length && token.endsWith(it) }) {
                    return SearchHit(category, token)
                }
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Estrazione della ricerca dall'indirizzo della pagina
    // ---------------------------------------------------------------------------------------

    /** Motori di ricerca riconosciuti: etichetta del dominio -> parametri che contengono la ricerca. */
    private val ENGINES: Map<String, List<String>> = mapOf(
        "google" to listOf("q"),
        "bing" to listOf("q"),
        "duckduckgo" to listOf("q"),
        "yahoo" to listOf("p", "q"),
        "ecosia" to listOf("q"),
        "startpage" to listOf("query", "q"),
        "qwant" to listOf("q"),
        "brave" to listOf("q"),
        "yandex" to listOf("text"),
        "virgilio" to listOf("qs", "q"),
        "libero" to listOf("query", "q"),
        "youtube" to listOf("search_query"),
        "baidu" to listOf("wd"),
        "ask" to listOf("q"),
        "aol" to listOf("q"),
        "mojeek" to listOf("q"),
        "swisscows" to listOf("query"),
        "perplexity" to listOf("q"),
    )

    /**
     * true se [host] (es. "www.google.it", "search.yahoo.com") è un motore di ricerca noto, cioè
     * un sito le cui caselle di testo sono caselle di ricerca. YouTube è escluso: lì si legge la
     * ricerca solo dall'indirizzo, perché le caselle di testo sono anche commenti.
     */
    fun isSearchEngine(host: String): Boolean =
        engineParams(host) != null && host.lowercase().split('.').none { it == "youtube" }

    private fun engineParams(host: String): List<String>? {
        val labels = host.lowercase().split('.')
        // L'etichetta del motore è una di quelle prima del TLD: google.it, www.google.co.uk, search.brave.com
        for (i in 0 until labels.size - 1) ENGINES[labels[i]]?.let { return it }
        return null
    }

    /**
     * Estrae la ricerca dal testo della barra degli indirizzi, es.
     * "google.com/search?q=trading+online&hl=it" -> "trading online". null se non è la pagina
     * dei risultati di un motore di ricerca.
     */
    fun queryFromUrl(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty() || url.contains(' ')) return null
        val noScheme = url.substringAfter("://")
        val host = noScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
        val params = engineParams(host) ?: return null
        // I parametri possono stare dopo "?" o, in alcuni motori, dopo "#" (es. google.com/#q=...).
        val queryString = noScheme.substringAfter('?', "").substringBefore('#') + "&" +
            noScheme.substringAfter('#', "")
        for (pair in queryString.split('&')) {
            val name = pair.substringBefore('=')
            if (name !in params || '=' !in pair) continue
            val value = try {
                URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            } catch (e: IllegalArgumentException) {
                pair.substringAfter('=').replace('+', ' ')
            }
            if (value.isNotBlank()) return value
        }
        return null
    }
}
