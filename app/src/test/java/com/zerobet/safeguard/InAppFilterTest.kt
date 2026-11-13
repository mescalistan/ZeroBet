package com.zerobet.safeguard

import com.zerobet.safeguard.guard.BlockedPackages
import com.zerobet.safeguard.guard.ContentCategory
import com.zerobet.safeguard.guard.InAppContentAndAdFilter
import com.zerobet.safeguard.guard.InAppKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InAppFilterTest {

    // ---------------------------------------------------------------------------------------
    // Contenuti: titoli di video, post, articoli
    // ---------------------------------------------------------------------------------------

    @Test
    fun `titoli e post dedicati a trading scommesse e prestiti sono bloccati`() {
        val risky = mapOf(
            "Trading online: guida completa per principianti 2026" to ContentCategory.TRADING,
            "Come investire in azioni ed ETF partendo da zero" to ContentCategory.TRADING,
            "Migliori piattaforme trading: la classifica aggiornata" to ContentCategory.TRADING,
            "Guadagnare con il forex: la mia strategia" to ContentCategory.TRADING,
            "Investire in bitcoin conviene ancora?" to ContentCategory.TRADING,
            "Prezzo Bitcoin oggi: analisi e previsioni" to ContentCategory.TRADING,
            "Bitcoin ed Ethereum: quale comprare adesso" to ContentCategory.TRADING,
            "Analisi tecnica: supporti e resistenze sul FTSE MIB" to ContentCategory.TRADING,
            "Le 5 criptovalute che esploderanno quest'anno" to ContentCategory.TRADING,
            "Ho trasformato 1000€ in 10.000€ con il day trading" to ContentCategory.TRADING,
            "Pronostici serie A: quote e schedina della giornata" to ContentCategory.GAMBLING,
            "Bonus scommesse: 100€ di benvenuto per i nuovi iscritti" to ContentCategory.GAMBLING,
            "BIG WIN alle slot online! Vincita pazzesca in diretta" to ContentCategory.GAMBLING,
            "Casinò online: metodo roulette che funziona davvero" to ContentCategory.GAMBLING,
            "Scommesse sportive: come vincere con le multiple" to ContentCategory.GAMBLING,
            "Prestito veloce in 24 ore anche senza busta paga" to ContentCategory.LOANS,
            "Cessione del quinto: richiedi un preventivo gratuito" to ContentCategory.LOANS,
            "Prestiti online: confronta le offerte di Agos e Findomestic" to ContentCategory.LOANS,
        )
        val wrong = risky.filter { (text, category) -> InAppContentAndAdFilter.classifyText(text)?.category != category }
        assertTrue("Non bloccati o con categoria sbagliata: ${wrong.keys}", wrong.isEmpty())
        assertEquals(InAppKind.CONTENT, InAppContentAndAdFilter.classifyText("Trading online per principianti")!!.kind)
    }

    @Test
    fun `notizie generiche di economia politica e cronaca non sono bloccate`() {
        val innocent = listOf(
            "Manovra finanziaria del governo: le novità per il 2026",
            "Inflazione ISTAT: a ottobre prezzi in aumento dello 0,9%",
            "Stipendi pubblici, arrivano gli aumenti in busta paga",
            "Pensioni, quota 103 confermata anche per il prossimo anno",
            "Legge di bilancio: scontro in parlamento sulle tasse",
            "La BCE lascia invariati i tassi di interesse",
            "PIL italiano in crescita dello 0,3% nel terzo trimestre",
            "Sciopero dei treni venerdì: orari e fasce garantite",
            "Guardia di Finanza sequestra beni per due milioni",
            "Bonus edilizi, cosa cambia per le ristrutturazioni",
            "Caro bollette: aumenti per luce e gas da gennaio",
            "Calciomercato: l'attaccante arriva in prestito con diritto di riscatto",
            "Serie A, la classifica dopo la dodicesima giornata",
            "Poker di gol per la capolista, avversari travolti",
            "Meteo: weekend di pioggia al nord, sole al sud",
            "Ricetta della carbonara originale romana",
            "Insider trading, manager condannato a due anni",
            "Mutuo soccorso: la storia delle società operaie",
            "Che casino ieri sera in centro per il concerto",
            "Biblioteca comunale: nuovo servizio di prestito libri digitale",
            "Il mercato del lavoro cresce: più occupati tra i giovani",
            "Azioni legali dei consumatori contro la compagnia aerea",
            "Borsa di studio per gli studenti meritevoli: come fare domanda",
            "Finanziamento pubblico ai partiti: la proposta di riforma",
            "Come smettere di giocare d'azzardo: la storia di Marco",
            "Ludopatia, il numero verde per chi cerca aiuto",
            "BAJA 1000 LIVE!! Patrick Wieland, Kyle Jergensen, Chuck Dempsey Race For 1 Bitcoin",
            "Il rapinatore chiedeva il riscatto in bitcoin: arrestato",
            "Iscriviti", "Mi piace", "Condividi", "1.234 visualizzazioni", "3 giorni fa", "Commenti 58",
            "Scopri di più", "Home", "Shorts", "Iscrizioni", "Raccolta",
        )
        val blocked = innocent.mapNotNull { t -> InAppContentAndAdFilter.classifyText(t)?.let { "$t -> ${it.term}" } }
        assertTrue("Falsi positivi: $blocked", blocked.isEmpty())
    }

    @Test
    fun `una sola parola generica in un titolo non basta, due o una frase inequivocabile si`() {
        // una sola parola generica: resta
        assertNull(InAppContentAndAdFilter.classifyText("Mutuo prima casa: le agevolazioni per gli under 36 spiegate"))
        assertNull(InAppContentAndAdFilter.classifyText("Piazza Affari chiude in rialzo dopo i dati americani"))
        // due termini diversi nello stesso titolo
        assertNotNull(InAppContentAndAdFilter.classifyText("Piazza Affari: FTSE MIB ai massimi, cosa comprare"))
        assertNotNull(InAppContentAndAdFilter.classifyText("Mutui e prestiti: come ottenere il tasso migliore"))
        // un'espressione inequivocabile
        assertNotNull(InAppContentAndAdFilter.classifyText("Dove investire 10.000 euro oggi"))
    }

    @Test
    fun `i testi lunghi vengono valutati frase per frase`() {
        val article = "Il consiglio comunale ha approvato il nuovo piano del traffico. " +
            "Previste nuove piste ciclabili e parcheggi di scambio. ".repeat(12) +
            "In fondo alla pagina: scopri come fare trading online con la nostra piattaforma."
        assertEquals(ContentCategory.TRADING, InAppContentAndAdFilter.classifyText(article)?.category)
        val clean = "Il consiglio comunale ha approvato il nuovo piano del traffico. ".repeat(30)
        assertNull(InAppContentAndAdFilter.classifyText(clean))
    }

    // ---------------------------------------------------------------------------------------
    // Pubblicità
    // ---------------------------------------------------------------------------------------

    @Test
    fun `riconosce le etichette di annuncio`() {
        listOf("Sponsorizzato", "Sponsored", "Annuncio", "Annuncio · etoro.com", "Ad", "Ad · 1 di 2", "Promosso",
            "Post sponsorizzato", "Sponsorizzato da Plus500", "Pubblicità").forEach {
            assertTrue("Dovrebbe essere un'etichetta di annuncio: $it", InAppContentAndAdFilter.isAdMarker(it))
        }
        listOf("Addio alle armi", "Adesso in onda", "Admin del gruppo", "Annunciato il nuovo album",
            "Sponsor tecnico della nazionale di calcio presentato ieri in conferenza stampa a Coverciano",
            "Iscriviti", "Mario Rossi").forEach {
            assertFalse("Non è un'etichetta di annuncio: $it", InAppContentAndAdFilter.isAdMarker(it))
        }
    }

    @Test
    fun `pubblicita di trading scommesse e prestiti nei feed sono bloccate`() {
        val feed = listOf(
            "Mario Rossi", "2 ore fa", "Che bella giornata al mare!", "Mi piace", "Commenta",
            "eToro", "Sponsorizzato", "Unisciti a milioni di utenti e scopri gli investimenti", "Scopri di più",
            "Anna Bianchi", "Ieri", "Torta di mele della nonna",
        )
        val ad = InAppContentAndAdFilter.findAd(feed)
        assertNotNull(ad)
        assertEquals(InAppKind.AD, ad!!.kind)
        assertEquals(ContentCategory.TRADING, ad.category)

        assertEquals(
            ContentCategory.GAMBLING,
            InAppContentAndAdFilter.findAd(listOf("Annuncio", "Bonus scommesse fino a 300€", "Registrati"))?.category
        )
        assertEquals(
            ContentCategory.LOANS,
            InAppContentAndAdFilter.findAd(listOf("Video consigliato", "Prestito veloce online", "Richiedi ora"))?.category
        )
        // invito all'azione attaccato a un marchio: "Installa" accanto a un'app di poker
        assertEquals(
            ContentCategory.GAMBLING,
            InAppContentAndAdFilter.findAd(listOf("PokerStars: Texas Hold'em", "Installa"))?.category
        )
    }

    @Test
    fun `pubblicita innocue e termini lontani dall'etichetta non sono bloccati`() {
        // annuncio di scarpe: nessun termine a rischio
        assertNull(InAppContentAndAdFilter.findAd(listOf("Nike", "Sponsorizzato", "Nuova collezione running", "Acquista ora")))
        // etichetta di annuncio in cima, parola a rischio venti elementi più sotto (un altro contenuto)
        val far = listOf("Sponsorizzato", "Scarpe da corsa") + List(20) { "Post numero $it" } + listOf("Ho chiesto un mutuo")
        assertNull(InAppContentAndAdFilter.findAd(far))
        // "Iscriviti" su un video di cucina
        assertNull(InAppContentAndAdFilter.findAd(listOf("Carbonara perfetta in 10 minuti", "Chef Luca", "Iscriviti", "12.000 Mi piace")))
        assertNull(InAppContentAndAdFilter.findAd(emptyList()))
    }

    // ---------------------------------------------------------------------------------------
    // Play Store
    // ---------------------------------------------------------------------------------------

    @Test
    fun `la scheda di un'app a rischio nello store viene bloccata, le altre no`() {
        val trading = listOf("eToro: Investi e fai Trading", "eToro", "Contiene annunci", "4,3", "Installa", "Finanza")
        assertEquals(ContentCategory.TRADING, InAppContentAndAdFilter.classifyStorePage(trading)?.category)
        val casino = listOf("Jackpot Party Casino Slots", "SciPlay", "Installa", "Casinò")
        assertEquals(InAppKind.STORE_PAGE, InAppContentAndAdFilter.classifyStorePage(casino)?.kind)
        val loans = listOf("Findomestic", "Findomestic Banca", "Apri", "Prestiti personali e carte")
        assertEquals(ContentCategory.LOANS, InAppContentAndAdFilter.classifyStorePage(loans)?.category)

        val maps = listOf("Google Maps", "Google LLC", "4,2", "Installa", "Viaggi e info locali", "Naviga ed esplora")
        assertNull(InAppContentAndAdFilter.classifyStorePage(maps))
        // elenco dei risultati dello store senza scheda aperta (nessun pulsante Installa)
        assertNull(InAppContentAndAdFilter.classifyStorePage(listOf("Giochi", "App", "Libri", "Per te", "Classifiche")))
    }

    // ---------------------------------------------------------------------------------------
    // Caselle di ricerca
    // ---------------------------------------------------------------------------------------

    @Test
    fun `riconosce le caselle di ricerca dall'id o dal suggerimento`() {
        assertTrue(InAppContentAndAdFilter.isSearchField("com.google.android.youtube:id/search_edit_text", null, true))
        assertTrue(InAppContentAndAdFilter.isSearchField("com.google.android.youtube:id/search_query", null, false))
        assertTrue(InAppContentAndAdFilter.isSearchField("com.instagram.android:id/action_bar_search_edit_text", null, true))
        assertTrue(InAppContentAndAdFilter.isSearchField("androidx.appcompat:id/search_src_text", null, true))
        assertTrue(InAppContentAndAdFilter.isSearchField(null, "Cerca", true))
        assertTrue(InAppContentAndAdFilter.isSearchField(null, "Search YouTube", true))
        assertTrue(InAppContentAndAdFilter.isSearchField("com.android.vending:id/search_bar_text_input", null, true))

        // messaggi, commenti, risultati e suggerimenti non sono caselle di ricerca
        assertFalse(InAppContentAndAdFilter.isSearchField("org.telegram.messenger:id/message_input", "Messaggio", true))
        assertFalse(InAppContentAndAdFilter.isSearchField(null, "Aggiungi un commento...", true))
        assertFalse(InAppContentAndAdFilter.isSearchField("com.app:id/search_result_title", null, false))
        assertFalse(InAppContentAndAdFilter.isSearchField("com.app:id/search_suggestion_item", null, false))
        assertFalse(InAppContentAndAdFilter.isSearchField(null, "Cerca", false))      // testo non modificabile senza id
        assertFalse(InAppContentAndAdFilter.isSearchField(null, null, true))
    }

    // ---------------------------------------------------------------------------------------
    // App dedicate
    // ---------------------------------------------------------------------------------------

    @Test
    fun `le app dedicate sono bloccate dall'elenco dal nome del package o dal nome visibile`() {
        val trading = listOf(
            "com.fusionmedia.investing", "com.tradingview.tradingviewapp", "net.metaquotes.metatrader4",
            "net.metaquotes.metatrader5", "com.etoro.openbook", "com.plus500", "com.binance.dev", "com.coinbase.android",
            "com.kraken.trade", "com.crypto.exchange", "de.traderepublic.app", "de.scalable.capital",
            "com.bloomberg.android.plus", "com.binance.dev.beta", "io.nuovobroker.forex.app",
        )
        trading.forEach { assertEquals(it, ContentCategory.TRADING, BlockedPackages.categoryOf(it)) }

        val gambling = listOf("it.snai.sport", "com.pokerstars.it", "com.sconosciuto.casino.vegas", "com.dev.slots.jackpot", "it.nuovo.scommesse")
        gambling.forEach { assertEquals(it, ContentCategory.GAMBLING, BlockedPackages.categoryOf(it)) }

        val loans = listOf("it.findomestic.app", "it.compass.app", "it.agos.app", "it.cofidis.app", "com.nuova.prestiti.veloci")
        loans.forEach { assertEquals(it, ContentCategory.LOANS, BlockedPackages.categoryOf(it)) }

        // package anonimo, nome visibile esplicito
        assertEquals(ContentCategory.GAMBLING, BlockedPackages.categoryOf("com.abc.xyz123", "Casinò Vegas Slots"))
        assertEquals(ContentCategory.TRADING, BlockedPackages.categoryOf("com.abc.xyz123", "Trading Facile"))
    }

    @Test
    fun `le app comuni e l'app della banca in whitelist restano libere`() {
        val free = listOf(
            "com.google.android.youtube", "com.instagram.android", "com.facebook.katana", "com.whatsapp",
            "org.telegram.messenger", "com.android.chrome", "com.android.vending", "com.spotify.music",
            "com.netflix.mediaclient", "com.google.android.apps.maps", "com.google.android.apps.walletnfcrel",
            "com.paypal.android.p2pmobile", "com.microsoft.exchange.mowa", "it.poste.postepay", "com.amazon.mShop.android.shopping",
            "com.duolingo", "com.alphabet.learning", "com.tibet.travel", "it.trenitalia.app", "com.unicredit",
            "com.samsung.android.spay", "com.supercell.clashofclans", "com.king.candycrushsaga",
        )
        val blocked = free.filter { BlockedPackages.categoryOf(it) != null }
        assertTrue("App comuni bloccate: $blocked", blocked.isEmpty())
        assertNull(BlockedPackages.categoryOf("com.abc.xyz123", "Investigatore Privato"))
        assertNull(BlockedPackages.categoryOf("com.abc.xyz123", "Borsa di Studio Italia"))

        // la finanziaria o la banca messa in whitelist dal tutore resta utilizzabile
        assertEquals(ContentCategory.LOANS, BlockedPackages.categoryOf("it.findomestic.app"))
        assertNull(BlockedPackages.categoryOf("it.findomestic.app", "Findomestic", setOf("findomestic")))
        assertNull(BlockedPackages.categoryOf("com.fineco.it", "Fineco", setOf("fineco")))
        assertEquals("unicredit", BlockedPackages.labelOfDomain("www.unicredit.it"))
        assertEquals("bancobpm", BlockedPackages.labelOfDomain("bancobpm.it"))
    }
}
