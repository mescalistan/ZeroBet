package com.zerobet.safeguard

import com.zerobet.safeguard.guard.ContentCategory
import com.zerobet.safeguard.guard.SearchQueryClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryClassifierTest {

    private fun category(query: String) = SearchQueryClassifier.classify(query)?.category

    private fun assertAllBlocked(expected: ContentCategory, queries: List<String>) {
        val wrong = queries.filter { category(it) != expected }
        assertTrue("Non bloccate (o categoria sbagliata) come $expected: $wrong", wrong.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Ricerche da bloccare
    // ---------------------------------------------------------------------------------------

    @Test
    fun `blocca le ricerche su scommesse e casino`() = assertAllBlocked(
        ContentCategory.GAMBLING, listOf(
            "scommesse", "scommesse sportive oggi", "siti scommesse", "come scommettere online",
            "casino", "casinò online", "CASINO ONLINE AAMS", "migliori casinò", "starcasino",
            "poker online", "pokerstars", "blackjack regole", "roulette online", "slot machine",
            "slot online gratis", "slot gratis", "bingo online", "gratta e vinci", "superenalotto",
            "estrazioni lotto", "10 e lotto", "lotteria italia", "schedina vincente", "pronostici serie a",
            "quote serie a", "quote calcio oggi", "snai", "sisal", "goldbet", "eurobet", "lottomatica",
            "bet365", "bet 365", "22bet", "1xbet", "betfair", "bwin", "leovegas", "william hill",
            "bonus benvenuto", "bonus senza deposito", "giri gratis", "free spins", "jackpot",
            "gioco d'azzardo online", "betting exchange", "bookmaker non aams", "vincere soldi",
            "videopoker", "888casino", "planetwin365", "sala slot vicino a me", "corse dei cavalli",
        )
    )

    @Test
    fun `blocca le ricerche su trading e investimenti`() = assertAllBlocked(
        ContentCategory.TRADING, listOf(
            "trading", "trading online", "Trading Online Come Iniziare", "investimenti",
            "investimenti sicuri", "migliori investimenti 2026", "come investire", "investire in borsa",
            "investire 10000 euro", "dove investo i risparmi", "forex", "broker forex",
            "bitcoin", "prezzo bitcoin", "comprare bitcoin", "criptovalute", "criptovaluta emergente",
            "crypto", "ethereum", "dogecoin", "binance", "coinbase", "etoro", "plus500", "degiro",
            "trade republic", "tradingview", "metatrader", "comprare azioni", "azioni tesla",
            "azioni da comprare oggi", "borsa italiana", "ftse mib", "piazza affari", "nasdaq",
            "etf", "migliori etf", "btp valore", "fondi comuni", "opzioni binarie", "leva finanziaria",
            "conto demo", "copy trading", "day trading", "daytrading", "nft", "staking",
            "guadagnare online", "soldi facili", "fare soldi online", "rendita passiva",
            "mercato azionario", "trader professionista", "investitori", "cfd trading", "s&p 500",
        )
    )

    @Test
    fun `blocca le ricerche su prestiti e finanziamenti`() = assertAllBlocked(
        ContentCategory.LOANS, listOf(
            "prestito", "prestiti", "prestito personale", "prestiti online veloci",
            "prestito senza busta paga", "prestiti a protestati", "finanziamento", "finanziamenti",
            "finanziamento auto", "mutuo", "mutui", "mutuo prima casa", "calcolo rata mutuo",
            "cessione del quinto", "cessione quinto pensionati", "agos", "findomestic", "younited",
            "compass prestiti", "fiditalia", "prestitionline", "mutuionline", "facile.it",
            "soldi subito", "soldi in prestito", "anticipo stipendio", "carta revolving",
            "consolidamento debiti", "compro oro", "banco dei pegni", "klarna", "scalapay",
            "microcredito", "payday loan", "loan", "fido bancario", "credito immediato",
            "cattivi pagatori", "microprestito",
        )
    )

    // ---------------------------------------------------------------------------------------
    // Falsi positivi: nessuna di queste ricerche deve essere bloccata
    // ---------------------------------------------------------------------------------------

    @Test
    fun `non blocca le ricerche quotidiane`() {
        val innocent = listOf(
            // vita di tutti i giorni
            "meteo roma", "meteo domani milano", "ricetta carbonara", "ricetta tiramisù", "orari treni",
            "trenitalia", "italo biglietti", "farmacia di turno", "pizzeria vicino a me",
            "ristorante giapponese", "notizie oggi", "ultime notizie", "corriere della sera",
            "gazzetta dello sport", "risultati serie a", "classifica serie a", "calendario serie a",
            "juventus inter streaming", "formula 1 gran premio", "moto gp", "tour de france tappa",
            "netflix serie tv", "film da vedere", "programmi tv stasera", "canzoni sanremo",
            "youtube musica", "traduttore inglese italiano", "wikipedia", "google maps",
            "codice fiscale calcolo", "spid poste", "inps accesso", "agenzia delle entrate",
            "730 precompilato", "bolletta enel", "offerte luce e gas", "offerte telefonia mobile",
            "iphone 17 prezzo", "samsung galaxy recensione", "amazon offerte", "ikea catalogo",
            "scarpe nike uomo", "zalando resi", "subito.it auto usate", "immobiliare affitto roma",
            "offerte di lavoro", "curriculum vitae modello", "concorsi pubblici", "università iscrizione",
            "compiti di matematica", "teorema di pitagora", "divisione dividendo e divisore",
            "frasi auguri compleanno", "oroscopo oggi", "santo del giorno", "calendario 2026",
            "voli low cost", "ryanair check in", "booking hotel rimini", "airbnb firenze",
            "sintomi influenza", "medico di base", "prenotazione visita cup", "dieta mediterranea",
            "allenamento a casa", "palestra orari", "cane segugio italiano", "gatto persiano",
            "veterinario", "giardinaggio rose", "bricolage mensole", "leroy merlin",
            "banca intesa accesso", "unicredit home banking", "bonifico istantaneo", "iban poste",
            "carta di credito smarrita", "saldo conto corrente", "pagare bollo auto", "pagopa",
            "assicurazione auto preventivo", "broker assicurativo", "revisione auto scadenza",
            // parole vicine a quelle a rischio
            "investigatore privato", "investigazione scientifica", "investito da un'auto",
            "investitura feudale", "investimento pedone bologna", "investimento stradale oggi",
            "betulla albero", "betoniera prezzo", "alfabeto greco", "tibet viaggio", "bettola osteria",
            "better call saul", "alphabet inc", "sorbetto al limone", "distanza terra luna",
            "tanto tempo fa", "tana libera tutti", "ratatouille film", "pirata dei caraibi",
            "quota 100 pensione", "quote rosa", "quota iscrizione palestra", "famous quotes",
            "slot prenotazione vaccino", "slot pcie scheda madre", "time slot", "slot m.2",
            "puntata grande fratello", "ultima puntata fiction", "vincita premio nobel",
            "azioni legali condominio", "azioni correttive qualità", "piano d'azione", "azione cattolica",
            "le più belle azioni della partita", "borsa di studio", "borsa in pelle", "borsa frigo",
            "borsa dell'acqua calda", "banco di scuola", "banco alimentare", "oro olimpiadi",
            "medaglia d'oro", "tasso di natalità", "tasso animale", "rata condominio",
            "rate universitarie", "pagare a rate le tasse", "credito formativo", "crediti universitari",
            "titoli di coda", "titolo di studio", "leva del cambio", "leva militare", "mercato rionale",
            "mercato del pesce", "calciomercato", "stock di magazzino", "stock foto gratis",
            "spread the word", "opzioni sviluppatore android", "demo gioco ps5", "conto alla rovescia",
            "fondi di caffè", "fondo schiena", "fido cane nome", "token autenticazione",
            "moneta da 2 euro rara", "monete antiche", "coin master", "exchange student programma",
            // omonimi ed espressioni innocue
            "giocatore in prestito", "prestito con diritto di riscatto", "prestito libri biblioteca",
            "prestito interbibliotecario", "libri in prestito", "on loan players",
            "finanziamento pubblico ai partiti", "finanziamenti europei ricerca",
            "società di mutuo soccorso", "mutuo consenso", "gruppi di mutuo aiuto",
            "trading card game pokemon", "pokemon trading cards", "fair trade caffè",
            "world trade center", "trader joe's", "poker face lady gaga", "casino royale film",
            "roulette russa", "lotteria degli scontrini", "che casino significato",
            "poker di haaland", "tappeto in sisal", "corda sisal", "team cofidis",
            "cfd fluidodinamica", "ansys cfd", "legge finanziaria 2026", "manovra finanziaria",
            "guardia di finanza", "educazione finanziaria scuola", "sclerosi multipla sintomi",
            "piatto tipico siciliano", "rabona tutorial", "wolf of wall street film",
            "obbligazioni diritto civile", "immagine speculare", "ripple effect", "kraken mitologia",
            "pitagora filosofo", "bussola compass app", "eth zurigo", "gerolamo cardano",
            "airdrop iphone", "shiba inu cucciolo", "bot telegram", "pac man", "payday 3 gioco",
            "fantacalcio voti", "monopoli regole", "tombola natale", "carte da gioco briscola",
            "scopa regole", "burraco regole", "scacchi online", "sudoku", "cruciverba",
        )
        val blocked = innocent.mapNotNull { q -> SearchQueryClassifier.classify(q)?.let { "$q -> ${it.term}" } }
        assertTrue("Falsi positivi: $blocked", blocked.isEmpty())
    }

    @Test
    fun `non blocca chi cerca aiuto`() {
        val help = listOf(
            "come smettere di giocare d'azzardo", "ludopatia numero verde", "dipendenza da scommesse",
            "giocatori anonimi roma", "autoesclusione casino online", "terapia gioco d'azzardo",
            "come uscire dai debiti di gioco", "dipendenza da trading", "sovraindebitamento prestiti",
            "aiuto per chi gioca alle slot machine", "psicologo dipendenza da gioco",
            "come bloccare i siti di scommesse", "vittima di usura", "telefono verde gioco d'azzardo",
        )
        val blocked = help.filter { SearchQueryClassifier.classify(it) != null }
        assertTrue("Ricerche di aiuto bloccate: $blocked", blocked.isEmpty())
    }

    @Test
    fun `mentre si scrive l'ultima parola incompleta non conta`() {
        // "invest" potrebbe diventare "investigatore", "crypto" "cryptography", "loan" "loano"
        assertNull(SearchQueryClassifier.classify("invest", typing = true))
        assertNull(SearchQueryClassifier.classify("come diventare invest", typing = true))
        assertNull(SearchQueryClassifier.classify("trading", typing = true))
        // la parola è completa quando è seguita da uno spazio o da un'altra parola
        assertEquals(ContentCategory.TRADING, SearchQueryClassifier.classify("trading ", typing = true)?.category)
        assertEquals(ContentCategory.TRADING, SearchQueryClassifier.classify("trading onl", typing = true)?.category)
        assertEquals(ContentCategory.GAMBLING, SearchQueryClassifier.classify("casino online gra", typing = true)?.category)
        assertNull(SearchQueryClassifier.classify("investigatore privato ro", typing = true))
    }

    @Test
    fun `testi troppo corti o troppo lunghi sono ignorati`() {
        assertNull(SearchQueryClassifier.classify(""))
        assertNull(SearchQueryClassifier.classify("ab"))
        assertNull(SearchQueryClassifier.classify("trading ".repeat(100)))
    }

    // ---------------------------------------------------------------------------------------
    // Estrazione della ricerca dall'indirizzo
    // ---------------------------------------------------------------------------------------

    @Test
    fun `estrae la ricerca dagli indirizzi dei motori di ricerca`() {
        assertEquals("trading online", SearchQueryClassifier.queryFromUrl("google.com/search?q=trading+online&hl=it"))
        assertEquals("trading online", SearchQueryClassifier.queryFromUrl("https://www.google.it/search?client=ms-android&q=trading%20online"))
        assertEquals("casinò", SearchQueryClassifier.queryFromUrl("https://www.bing.com/search?q=casin%C3%B2&form=QBLH"))
        assertEquals("prestiti", SearchQueryClassifier.queryFromUrl("duckduckgo.com/?q=prestiti&t=h_"))
        assertEquals("mutuo", SearchQueryClassifier.queryFromUrl("https://it.search.yahoo.com/search?p=mutuo"))
        assertEquals("bitcoin", SearchQueryClassifier.queryFromUrl("https://search.brave.com/search?q=bitcoin"))
        assertEquals("forex", SearchQueryClassifier.queryFromUrl("https://www.startpage.com/do/search?query=forex"))
        assertEquals("slot machine", SearchQueryClassifier.queryFromUrl("m.youtube.com/results?search_query=slot+machine"))
        assertEquals("poker", SearchQueryClassifier.queryFromUrl("https://www.google.com/#q=poker"))
    }

    @Test
    fun `non estrae nulla dagli indirizzi che non sono ricerche`() {
        assertNull(SearchQueryClassifier.queryFromUrl("google.com"))
        assertNull(SearchQueryClassifier.queryFromUrl("https://www.google.com/maps"))
        assertNull(SearchQueryClassifier.queryFromUrl("https://www.repubblica.it/economia?q=trading"))
        assertNull(SearchQueryClassifier.queryFromUrl("wikipedia.org/wiki/Trading"))
        assertNull(SearchQueryClassifier.queryFromUrl("trading online"))
        assertNull(SearchQueryClassifier.queryFromUrl(""))
        // un sito il cui nome contiene "google" non è Google
        assertNull(SearchQueryClassifier.queryFromUrl("https://notgoogle-search.example.com/?x=1"))
    }

    @Test
    fun `riconosce i motori di ricerca dal dominio`() {
        assertTrue(SearchQueryClassifier.isSearchEngine("www.google.it"))
        assertTrue(SearchQueryClassifier.isSearchEngine("google.co.uk"))
        assertTrue(SearchQueryClassifier.isSearchEngine("search.brave.com"))
        assertTrue(SearchQueryClassifier.isSearchEngine("duckduckgo.com"))
        assertFalse(SearchQueryClassifier.isSearchEngine("repubblica.it"))
        assertFalse(SearchQueryClassifier.isSearchEngine("googleadservices-fake.net"))
        assertFalse(SearchQueryClassifier.isSearchEngine("com"))
    }

    @Test
    fun `una ricerca a rischio arrivata dall'indirizzo viene bloccata`() {
        val query = SearchQueryClassifier.queryFromUrl("google.com/search?q=investimenti+sicuri&oq=inv")
        assertEquals(ContentCategory.TRADING, SearchQueryClassifier.classify(query!!)?.category)
        val innocent = SearchQueryClassifier.queryFromUrl("google.com/search?q=investigatore+privato")
        assertNull(SearchQueryClassifier.classify(innocent!!))
    }
}
