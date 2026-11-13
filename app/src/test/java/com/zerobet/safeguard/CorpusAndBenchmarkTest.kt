package com.zerobet.safeguard

import com.zerobet.safeguard.data.DefaultData
import com.zerobet.safeguard.guard.ContentCategory
import com.zerobet.safeguard.guard.SearchQueryClassifier
import com.zerobet.safeguard.receiver.SimStateTracker
import com.zerobet.safeguard.security.EventGrouper
import com.zerobet.safeguard.vpn.DomainFilter
import com.zerobet.safeguard.vpn.HashedDomainSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Corpus esteso contro i falsi positivi (oltre 1.000 casi fra ricerche e domini), test delle
 * strutture compatte e benchmark di memoria e velocità. I numeri del benchmark vengono stampati
 * (righe che iniziano con BENCHMARK) e salvati in build/reports/safeguard-benchmark.txt.
 */
class CorpusAndBenchmarkTest {

    private fun resourceLines(name: String): List<String> =
        javaClass.getResourceAsStream("/corpus/$name")!!.bufferedReader(Charsets.UTF_8).readLines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    /** Filtro con le regole predefinite dell'app (domini, parole chiave, euristica) e nessuna lista esterna. */
    private fun defaultFilter(external: HashedDomainSet = HashedDomainSet.EMPTY) =
        DomainFilter(emptyList(), DefaultData.seedEntries() + DefaultData.seedEntriesV2(), emptyList(), external)

    // ---------------------------------------------------------------------------------------
    // Ricerche
    // ---------------------------------------------------------------------------------------

    @Test
    fun `nessuna delle oltre 500 ricerche innocue viene bloccata`() {
        val innocent = resourceLines("innocent_queries.txt")
        assertTrue("Il corpus deve avere almeno 500 frasi, ne ha ${innocent.size}", innocent.size >= 500)
        val blocked = innocent.mapNotNull { q -> SearchQueryClassifier.classify(q)?.let { "$q -> ${it.term}" } }
        assertTrue("Falsi positivi (${blocked.size}): $blocked", blocked.isEmpty())
    }

    @Test
    fun `le ricerche innocue restano tali anche in maiuscolo e con parole di contorno`() {
        val innocent = resourceLines("innocent_queries.txt")
        val variants = innocent.flatMap { listOf(it.uppercase(), "$it significato", "cos'è $it", "$it wikipedia") }
        val blocked = variants.mapNotNull { q -> SearchQueryClassifier.classify(q)?.let { "$q -> ${it.term}" } }
        assertTrue("Falsi positivi nelle varianti (${blocked.size}): ${blocked.take(40)}", blocked.isEmpty())
    }

    private val riskyTerms = mapOf(
        ContentCategory.GAMBLING to listOf(
            "scommesse", "casino", "casinò online", "poker", "slot machine", "roulette", "blackjack", "bingo",
            "superenalotto", "gratta e vinci", "snai", "sisal", "goldbet", "eurobet", "lottomatica", "bet365",
            "betfair", "bwin", "pokerstars", "leovegas", "starcasino", "william hill", "betway", "unibet",
            "planetwin365", "1xbet", "22bet", "scommessa sportiva", "pronostici", "schedina", "jackpot",
            "bookmaker", "betting", "gambling", "videopoker", "888casino", "admiralbet", "netbet", "betflag",
        ),
        ContentCategory.TRADING to listOf(
            "trading", "trading online", "forex", "bitcoin", "criptovalute", "crypto", "ethereum", "binance",
            "coinbase", "etoro", "plus500", "degiro", "investimenti", "investire", "investimento", "etf",
            "borsa italiana", "ftse mib", "comprare azioni", "trader", "tradingview", "metatrader", "nasdaq",
            "copy trading", "day trading", "opzioni binarie", "leva finanziaria", "dogecoin", "nft", "staking",
            "piazza affari", "mercato azionario", "fondi comuni", "bybit", "kucoin", "xtb", "btp", "altcoin",
        ),
        ContentCategory.LOANS to listOf(
            "prestito", "prestiti", "prestito personale", "finanziamento", "finanziamenti", "mutuo", "mutui",
            "cessione del quinto", "agos", "findomestic", "younited", "fiditalia", "prestitionline",
            "mutuionline", "compass prestiti", "soldi subito", "anticipo stipendio", "carta revolving",
            "consolidamento debiti", "compro oro", "banco dei pegni", "klarna", "scalapay", "microcredito",
            "payday loan", "fido bancario", "credito immediato", "cattivi pagatori", "microprestito",
        ),
    )

    @Test
    fun `le ricerche a rischio sono bloccate in tutte le varianti`() {
        val templates = listOf("%s", "%s online", "migliori %s", "%s opinioni", "come funziona %s", "%s app")
        var total = 0
        val missed = ArrayList<String>()
        for ((category, terms) in riskyTerms) for (term in terms) for (template in templates) {
            val query = template.format(term)
            total++
            if (SearchQueryClassifier.classify(query)?.category != category) missed.add(query)
            if (SearchQueryClassifier.classify(query.uppercase())?.category != category) missed.add(query.uppercase())
        }
        assertTrue("Servono almeno 500 casi, sono $total", total >= 500)
        assertTrue("Ricerche a rischio non bloccate (${missed.size}): ${missed.take(40)}", missed.isEmpty())
    }

    @Test
    fun `la ricerca bloccata viene riportata per il registro`() {
        val hit = SearchQueryClassifier.classify("  Trading Online  ")
        assertNotNull(hit)
        assertEquals("Trading Online", hit!!.query)
    }

    // ---------------------------------------------------------------------------------------
    // Domini
    // ---------------------------------------------------------------------------------------

    private val innocentDomains = """
        google.com www.google.it wikipedia.org it.wikipedia.org youtube.com m.youtube.com facebook.com instagram.com
        whatsapp.com web.whatsapp.com amazon.it amazon.com ebay.it subito.it repubblica.it corriere.it
        gazzetta.it ansa.it ilsole24ore.com lastampa.it ilpost.it rainews.it raiplay.it mediasetinfinity.mediaset.it
        netflix.com spotify.com primevideo.com disneyplus.com twitch.tv tiktok.com linkedin.com x.com reddit.com
        github.com stackoverflow.com microsoft.com apple.com icloud.com samsung.com xiaomi.com android.com
        mozilla.org openstreetmap.org maps.google.com mail.google.com drive.google.com docs.google.com
        outlook.live.com libero.it virgilio.it tiscali.it alice.it aruba.it inps.it agenziaentrate.gov.it
        governo.it salute.gov.it istruzione.it miur.gov.it comune.roma.it comune.milano.it regione.lombardia.it
        trenitalia.com italotreno.it atac.roma.it atm.it ryanair.com easyjet.com ita-airways.com booking.com
        airbnb.it tripadvisor.it skyscanner.it enel.it eni.com a2a.it iren.it tim.it vodafone.it windtre.it
        iliad.it fastweb.it sky.it dazn.com esselunga.it coop.it conad.it lidl.it carrefour.it ikea.com
        leroymerlin.it mediaworld.it unieuro.it zalando.it decathlon.it nike.com adidas.it hm.com zara.com
        giallozafferano.it cookaround.com my-personaltrainer.it paginemediche.it humanitas.it ospedalebambinogesu.it
        treccani.it garzantilinguistica.it wordreference.com deepl.com duolingo.com khanacademy.org coursera.org
        unibo.it uniroma1.it polimi.it unimi.it unina.it cineca.it almalaurea.it indeed.com infojobs.it
        immobiliare.it idealista.it casa.it autoscout24.it quattroruote.it moto.it meteo.it ilmeteo.it 3bmeteo.com
        legambiente.it wwf.it emergency.it caritas.it croce-rossa.it vatican.va chiesacattolica.it
        betulla-vivai.it alphabet.com tibet-viaggi.it sorbetteria.it distanza.org cantante.it pirata.it
        biblioteca.comune.bologna.it bibliotechediroma.it opac.sbn.it mutuosoccorso.it azionecattolica.it
        investigatoreprivato.roma.it borsadistudio.it scuolabook.it mondadoristore.it lafeltrinelli.it ibs.it
        cdn.jsdelivr.net fonts.googleapis.com ajax.googleapis.com cloudflare.com akamaihd.net s3.amazonaws.com
        play.google.com apps.apple.com update.microsoft.com connectivitycheck.gstatic.com time.android.com
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }

    private val riskyDomains = """
        bet365.com www.bet365.it snai.it sisal.it goldbet.it eurobet.it lottomatica.it betfair.it bwin.it
        pokerstars.it leovegas.it starcasino.it williamhill.it 888casino.it unibet.it betway.it admiralbet.it
        netbet.it betflag.it planetwin365.it 1xbet.com 22bet.com nuovo-casino-online.com migliori-scommesse.net
        slot-gratis-bonus.com casino-live.bet poker-online.casino scommesse-sportive.info superbet-bonus.net
        win-bonus.vip mega-jackpot.casino binance.com coinbase.com etoro.com plus500.com degiro.it
        tradingview.com forex-broker.trade trading-online-facile.com crypto-exchange.io bitcoin-profit.app
        investimenti-sicuri.forex agos.it findomestic.it younited-credit.com compass.it fiditalia.it
        prestitionline.it mutuionline.it facile.it segugio.it prestiti-veloci.loan prestito-subito.loans
        cessione-del-quinto-online.it taboola.com outbrain.com propellerads.com adsterra.com incomeaccess.com
        dns.google cloudflare-dns.com dns.quad9.net app.bet365.com m.snai.it
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }

    @Test
    fun `nessun dominio innocuo viene bloccato dalle regole predefinite`() {
        val filter = defaultFilter()
        assertTrue("Servono almeno 150 domini, sono ${innocentDomains.size}", innocentDomains.size >= 150)
        val blocked = innocentDomains.filter { filter.shouldBlock(it) }
        assertTrue("Domini innocui bloccati: $blocked", blocked.isEmpty())
    }

    @Test
    fun `i domini a rischio sono bloccati dalle regole predefinite`() {
        val filter = defaultFilter()
        val allowed = riskyDomains.filter { !filter.shouldBlock(it) }
        assertTrue("Domini a rischio non bloccati: $allowed", allowed.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Struttura compatta
    // ---------------------------------------------------------------------------------------

    private fun syntheticDomains(count: Int): List<String> {
        val random = Random(42)
        val tlds = listOf("com", "net", "org", "it", "info", "xyz", "io", "co.uk")
        val letters = "abcdefghijklmnopqrstuvwxyz0123456789-"
        return List(count) { i ->
            val name = buildString { repeat(6 + random.nextInt(14)) { append(letters[random.nextInt(letters.length)]) } }
            "$name$i.${tlds[random.nextInt(tlds.size)]}"
        }
    }

    @Test
    fun `l'insieme compatto trova domini esatti e sottodomini e non confonde i suffissi`() {
        val set = HashedDomainSet.of("bet365.com", "ads.tracker-network.com", "sito-oscurato.net", "bet365.com")
        assertEquals(3, set.size)                                  // duplicati eliminati
        assertTrue(set.matches("bet365.com"))
        assertTrue(set.matches("app.mobile.bet365.com"))           // sottodominio
        assertTrue(set.matches("ads.tracker-network.com"))
        assertFalse(set.matches("tracker-network.com"))            // il padre di una voce non è in lista
        assertFalse(set.matches("notbet365.com"))                  // suffisso di stringa, non di dominio
        assertFalse(set.matches("bet365.com.example.org"))
        assertFalse(set.matches("com"))
        assertFalse(HashedDomainSet.EMPTY.matches("bet365.com"))
    }

    @Test
    fun `l'insieme compatto sopravvive al salvataggio su file`() {
        val domains = syntheticDomains(5_000)
        val set = HashedDomainSet.of(domains)
        val file = File.createTempFile("safeguard", ".bin")
        try {
            set.writeTo(file)
            val loaded = HashedDomainSet.readFrom(file)!!
            assertEquals(set.size, loaded.size)
            assertTrue(domains.all { loaded.matches(it) })
            assertFalse(loaded.matches("dominio-mai-visto.example"))
            file.writeText("file corrotto")
            assertNull(HashedDomainSet.readFrom(file))
        } finally {
            file.delete()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Registro e SIM
    // ---------------------------------------------------------------------------------------

    @Test
    fun `50 richieste allo stesso dominio in un minuto diventano una riga con contatore`() {
        val grouper = EventGrouper(windowMs = 60_000)
        var rows = 0
        var last: EventGrouper.Entry? = null
        for (i in 0 until 50) {
            val now = 1_000L + i * 1_000L                          // una richiesta al secondo
            val entry = grouper.hit("dns:bet365.com", now)
            if (entry == null) {
                rows++
                grouper.opened("dns:bet365.com", rows.toLong(), now)
            } else {
                last = entry
            }
        }
        assertEquals(1, rows)
        assertEquals(50, last!!.count)
        assertEquals(1, grouper.takeDirty().size)
        assertTrue(grouper.takeDirty().isEmpty())                  // già scritto
        // oltre la finestra di un minuto si apre una riga nuova; un altro dominio ha la sua riga
        assertNull(grouper.hit("dns:bet365.com", 1_000L + 61_000L))
        assertNull(grouper.hit("dns:snai.it", 2_000L))
    }

    @Test
    fun `la rimozione e l'inserimento della SIM generano un evento, i passaggi normali no`() {
        // riduzione degli stati di Android a presente / assente
        assertEquals(SimStateTracker.ABSENT, SimStateTracker.presence("ABSENT"))
        assertEquals(SimStateTracker.PRESENT, SimStateTracker.presence("READY"))
        assertEquals(SimStateTracker.PRESENT, SimStateTracker.presence("LOADED"))
        assertEquals(SimStateTracker.PRESENT, SimStateTracker.presence("LOCKED"))
        assertNull(SimStateTracker.presence("NOT_READY"))
        assertNull(SimStateTracker.presence("UNKNOWN"))
        assertNull(SimStateTracker.presence(null as String?))
        assertEquals(SimStateTracker.ABSENT, SimStateTracker.presence(1))
        assertEquals(SimStateTracker.PRESENT, SimStateTracker.presence(5))
        assertNull(SimStateTracker.presence(6))

        // prima osservazione: nessun allarme, è solo lo stato di partenza
        assertNull(SimStateTracker.presenceEvent(null, SimStateTracker.PRESENT, 0))
        // READY -> LOADED a ogni accensione: stessa presenza, nessun allarme
        assertNull(SimStateTracker.presenceEvent(SimStateTracker.PRESENT, SimStateTracker.PRESENT, 0))
        assertEquals(
            "SIM rimossa dal dispositivo",
            SimStateTracker.presenceEvent(SimStateTracker.PRESENT, SimStateTracker.ABSENT, 0)!!.title
        )
        assertEquals(
            "SIM inserita o riattivata",
            SimStateTracker.presenceEvent(SimStateTracker.ABSENT, SimStateTracker.PRESENT, 1)!!.title
        )
        assertEquals("Slot SIM 2", SimStateTracker.presenceEvent(SimStateTracker.ABSENT, SimStateTracker.PRESENT, 1)!!.detail)

        assertNull(SimStateTracker.operatorEvent(null, "TIM (22201)"))
        assertNull(SimStateTracker.operatorEvent("TIM (22201)", "TIM (22201)"))
        assertEquals("TIM (22201) → Iliad (22250)", SimStateTracker.operatorEvent("TIM (22201)", "Iliad (22250)")!!.detail)
    }

    // ---------------------------------------------------------------------------------------
    // Benchmark
    // ---------------------------------------------------------------------------------------

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(4) { System.gc(); Thread.sleep(40) }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun `benchmark di memoria e velocita con 211000 domini`() {
        val count = 211_000
        val report = StringBuilder()
        fun line(text: String) {
            println("BENCHMARK $text")
            report.appendLine(text)
        }

        // --- memoria: struttura compatta contro HashSet<String>
        var domains: List<String>? = syntheticDomains(count)
        val before = usedHeap()
        var legacy: HashSet<String>? = HashSet(domains!!)
        val legacyBytes = usedHeap() - before + domains.sumOf { 40L + it.length }   // set + stringhe
        assertTrue(legacy!!.size == count)
        legacy = null

        val compact = HashedDomainSet.of(domains)
        val sample = domains.filterIndexed { i, _ -> i % 21 == 0 }                 // ~10.000 domini in lista
        domains = null
        val beforeCompact = usedHeap()
        val filter = defaultFilter(compact)
        val measured = (usedHeap() - beforeCompact).coerceAtLeast(0)

        line("domini caricati: ${compact.size}")
        line("memoria struttura compatta (array di hash a 64 bit): %.2f MB".format(compact.memoryBytes / 1048576.0))
        line("memoria stimata con HashSet<String>: %.1f MB".format(legacyBytes / 1048576.0))
        assertEquals(count, compact.size)
        assertTrue("La struttura compatta deve stare sotto i 3 MB", compact.memoryBytes < 3L * 1024 * 1024)
        assertTrue("Il filtro non deve trattenere altra memoria significativa: $measured", measured < 3L * 1024 * 1024)

        // --- velocità: 10.000 query DNS (metà in lista, metà no, con sottodomini)
        val queries = ArrayList<String>(10_000)
        for (i in 0 until 5_000) queries.add(if (i % 2 == 0) sample[i % sample.size] else "cdn.www." + sample[i % sample.size])
        val innocent = innocentDomains
        for (i in 0 until 5_000) queries.add(innocent[i % innocent.size])
        repeat(5) { queries.forEach { filter.check(it) } }                         // riscaldamento JIT
        var blocked = 0
        val startDns = System.nanoTime()
        for (q in queries) if (filter.check(q).blocked) blocked++
        val dnsMicros = (System.nanoTime() - startDns) / 1000.0 / queries.size
        line("10.000 query DNS: media %.2f microsecondi per query (bloccate %d)".format(dnsMicros, blocked))
        assertEquals(5_000, blocked)
        assertTrue("Una query DNS deve costare meno di 1 ms: $dnsMicros µs", dnsMicros < 1000.0)

        // --- velocità: 1.000 ricerche
        val innocentQueries = resourceLines("innocent_queries.txt")
        val risky = riskyTerms.values.flatten()
        val searches = List(1_000) { i -> if (i % 2 == 0) innocentQueries[i % innocentQueries.size] else risky[i % risky.size] }
        repeat(5) { searches.forEach { SearchQueryClassifier.classify(it) } }
        val startSearch = System.nanoTime()
        var hits = 0
        for (q in searches) if (SearchQueryClassifier.classify(q) != null) hits++
        val searchMicros = (System.nanoTime() - startSearch) / 1000.0 / searches.size
        line("1.000 ricerche: media %.1f microsecondi per ricerca (bloccate %d)".format(searchMicros, hits))
        assertEquals(500, hits)
        assertTrue("Una ricerca deve costare meno di 2 ms: $searchMicros µs", searchMicros < 2000.0)

        File("build/reports").mkdirs()
        File("build/reports/safeguard-benchmark.txt").writeText(report.toString())
    }
}
