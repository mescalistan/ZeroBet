package com.zerobet.safeguard

import com.zerobet.safeguard.guard.ContentCategory
import com.zerobet.safeguard.guard.ContentClassifier
import com.zerobet.safeguard.sync.BlocklistParser
import com.zerobet.safeguard.sync.CtKeyword
import com.zerobet.safeguard.sync.CtLogDiscovery
import com.zerobet.safeguard.vpn.BlockReason
import com.zerobet.safeguard.vpn.DomainFilter
import com.zerobet.safeguard.vpn.DomainHeuristics
import com.zerobet.safeguard.vpn.SafeDomains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ThreatIntelTest {

    // ---------------------------------------------------------------------------------------
    // Parser delle blocklist
    // ---------------------------------------------------------------------------------------

    @Test
    fun `parser - formato hosts`() {
        assertEquals("30-day-change.com", BlocklistParser.parseLine("0.0.0.0 30-day-change.com"))
        assertEquals("ads.example.com", BlocklistParser.parseLine("127.0.0.1\tads.example.com   # commento"))
        // www. viene rimosso: il filtro copre comunque i sottodomini
        assertEquals("30-day-change.com", BlocklistParser.parseLine("0.0.0.0 www.30-day-change.com"))
    }

    @Test
    fun `parser - elenco di domini e regole adblock`() {
        assertEquals("2mdn.net", BlocklistParser.parseLine("2mdn.net"))
        assertEquals("0x.bet", BlocklistParser.parseLine("  0X.BET  "))
        assertEquals("tracker.example.org", BlocklistParser.parseLine("||tracker.example.org^"))
        assertEquals("xn--80ak6aa92e.com", BlocklistParser.parseLine("xn--80ak6aa92e.com"))
    }

    @Test
    fun `parser - righe da ignorare`() {
        assertNull(BlocklistParser.parseLine(""))
        assertNull(BlocklistParser.parseLine("# Project home page: https://github.com/StevenBlack/hosts"))
        assertNull(BlocklistParser.parseLine("! Title: lista"))
        assertNull(BlocklistParser.parseLine("[Adblock Plus 2.0]"))
        assertNull(BlocklistParser.parseLine("127.0.0.1 localhost"))
        assertNull(BlocklistParser.parseLine("::1 ip6-localhost"))
        assertNull(BlocklistParser.parseLine("0.0.0.0 0.0.0.0"))
        assertNull(BlocklistParser.parseLine("255.255.255.255 broadcasthost"))
        assertNull(BlocklistParser.parseLine("192.168.1.1"))
        assertNull(BlocklistParser.parseLine("non un dominio"))
        assertNull(BlocklistParser.parseLine("senzapunto"))
    }

    @Test
    fun `parser - estrazione dei domini da una pagina HTML`() {
        val html = """<p><b>- OÜ NIBBLE ITSF</b> (sito <i>internet</i> https://nibble.finance).</p>
            <li>"Zynerix" (sito internet https://zynerix.net e relativa pagina https://client.zynerix.net/login);</li>
            <li>www.xzd316.it</li>
            <link href="https://www.consob.it/o/consob-theme/css/main.css">
            <a href="https://esempio.it/documento.pdf">pdf</a> versione 2.0 del file style.css"""
        val found = BlocklistParser.extractDomains(html)
        assertTrue("nibble.finance" in found)
        assertTrue("zynerix.net" in found)
        assertTrue("client.zynerix.net" in found)
        assertTrue("xzd316.it" in found)
        assertTrue("esempio.it" in found)
        // Il nome di un file senza schema né "www." non è un dominio
        assertFalse(found.any { it.endsWith(".css") || it.endsWith(".pdf") })
        assertTrue(BlocklistParser.extractDomains("testo senza link, versione 2.0").isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Euristica sul nome a dominio
    // ---------------------------------------------------------------------------------------

    @Test
    fun `euristica - TLD dedicati bloccano da soli`() {
        for (d in listOf("qualcosa.bet", "nuovosito.casino", "tavoli.poker", "segnali.forex", "soldi.loan")) {
            assertTrue(d, DomainHeuristics.isRisky(d))
        }
    }

    @Test
    fun `euristica - combinazioni di token`() {
        for (d in listOf(
            "22bet-mirror.com", "bet777.net", "www.royalcasino-online.com", "superbet-bonus.com",
            "migliortrading.eu", "prestitoveloce24.it", "win-bonus.vip", "mega-slot-jackpot.com",
        )) {
            assertTrue(d, DomainHeuristics.isRisky(d))
        }
    }

    @Test
    fun `euristica - nessun falso positivo sui domini comuni`() {
        for (d in listOf(
            "alphabet.com", "betterhelp.com", "superbet.com", "sorbetto.com", "windows.com",
            "investing.com", "timeslot.app", "bonus.it", "repubblica.it", "unicredit.it",
            "wikipedia.org", "google.com", "credito-cooperativo.it", "yahoo.finance",
        )) {
            assertFalse(d, DomainHeuristics.isRisky(d))
        }
        // I domini sicuri hanno sempre punteggio 0, anche con token sospetti in un sottodominio
        assertEquals(0, DomainHeuristics.score("casino.google.com"))
        assertTrue(SafeDomains.contains("it.wikipedia.org"))
        assertFalse(SafeDomains.contains("wikipedia.org.casino-clone.com"))
    }

    @Test
    fun `filtro - liste esterne, auto-appresi ed euristica`() {
        val filter = DomainFilter(
            whitelist = setOf("unicredit.it", "amico-bet365.it"),
            blockedDomains = setOf("snai.it", "dns.google"),
            keywords = listOf("prestit"),
            external = hashSetOf("ads.tracker-network.com", "sito-oscurato.net")
        )
        // Match esatto e sui sottodomini anche per le liste esterne
        assertEquals(BlockReason.LIST, filter.check("sito-oscurato.net").reason)
        assertEquals(BlockReason.LIST, filter.check("www.sito-oscurato.net").reason)
        assertEquals(BlockReason.LIST, filter.check("x.ads.tracker-network.com").reason)
        // Il dominio padre di una voce in lista NON è bloccato
        assertFalse(filter.shouldBlock("tracker-network.com"))
        // Resolver DoH in blacklist fissa
        assertTrue(filter.shouldBlock("dns.google"))
        // Parola chiave ed euristica, con il motivo corretto
        assertEquals(BlockReason.KEYWORD, filter.check("prestiti-facili.org").reason)
        val heuristic = filter.check("nuovo-casino-live.xyz")
        assertEquals(BlockReason.HEURISTIC, heuristic.reason)
        assertTrue(heuristic.score >= DomainHeuristics.THRESHOLD)
        // La whitelist vince anche sull'euristica
        assertFalse(filter.shouldBlock("amico-bet365.it"))
        assertFalse(filter.shouldBlock("trading.unicredit.it"))
        // blockedByRules ignora le liste: serve alla sync per non salvare voci ridondanti
        assertTrue(filter.blockedByRules("0-8-07c.casino"))
        assertFalse(filter.blockedByRules("sito-oscurato.net"))
        assertFalse(filter.shouldBlock("wikipedia.org"))
    }

    // ---------------------------------------------------------------------------------------
    // Certificate Transparency
    // ---------------------------------------------------------------------------------------

    @Test
    fun `crt_sh - parsing in streaming del JSON`() {
        val json = """[{"issuer_ca_id":62123,"issuer_name":"C=US, O=\"DigiCert\" Inc, CN=GeoTrust {TLS} RSA",
            "common_name":"bet.snai.it","name_value":"bet.snai.it\n*.snai-mirror.net","id":30047673593,
            "not_before":"2026-10-18T00:00:00","not_after":"2027-04-18T23:59:59","result_count":2},
            {"issuer_ca_id":1,"common_name":"x","name_value":"scommesse-top.example.org","id":2,"not_before":"2020-01-01T00:00:00"}]"""
        val entries = ArrayList<Pair<String, String>>()
        val count = CtLogDiscovery.parseEntries(StringReader(json)) { name, notBefore -> entries.add(name to notBefore) }
        assertEquals(2, count)
        assertEquals("bet.snai.it\n*.snai-mirror.net", entries[0].first)
        assertEquals("2026-10-18T00:00:00", entries[0].second)
        assertEquals("scommesse-top.example.org", entries[1].first)
        // Risposte vuote o non JSON (es. pagina di errore 502) non producono nulla
        assertEquals(0, CtLogDiscovery.parseEntries(StringReader("[]")) { _, _ -> })
        assertEquals(0, CtLogDiscovery.parseEntries(StringReader("<html><h1>502 Bad Gateway</h1></html>")) { _, _ -> })
    }

    @Test
    fun `crt_sh - filtro dei candidati`() {
        val root = CtKeyword("scommess")
        assertEquals(
            listOf("scommesse-live24.com", "app.scommesse-live24.com"),
            CtLogDiscovery.candidates("*.scommesse-live24.com\napp.scommesse-live24.com\nwww.scommesse-live24.com", root)
        )
        assertTrue(CtLogDiscovery.candidates("esempio.com\nnon valido", root).isEmpty())

        // Marchi "strict": solo come nome di secondo livello, con cifre o affissi tipici
        val agos = CtKeyword("agos", strict = true)
        assertEquals(listOf("agos-prestiti.com"), CtLogDiscovery.candidates("agos-prestiti.com", agos))
        assertEquals(listOf("login.agos24.net"), CtLogDiscovery.candidates("login.agos24.net", agos))
        assertTrue(CtLogDiscovery.candidates("lagos.com\nagosto-vacanze.it\nagos.comune.example.it", agos).isEmpty())
        val snai = CtKeyword("snai", strict = true)
        assertEquals(listOf("snaibet.xyz"), CtLogDiscovery.candidates("snaibet.xyz\nsnail.io\nsnailmail.com", snai))
        val compass = CtKeyword("compass", strict = true)
        assertTrue(CtLogDiscovery.candidates("compass-group.com\nmycompassion.org", compass).isEmpty())
    }

    @Test
    fun `crt_sh - rotazione delle parole`() {
        val today = CtLogDiscovery.keywordsFor(20_000)
        assertEquals(CtLogDiscovery.KEYWORDS_PER_RUN, today.size)
        assertEquals(today.size, today.map { it.term }.distinct().size)
        // In un numero sufficiente di giorni tutte le parole vengono interrogate
        val covered = (0L until 10L).flatMap { CtLogDiscovery.keywordsFor(20_000 + it) }.map { it.term }.toSet()
        assertEquals(CtLogDiscovery.KEYWORDS.size, covered.size)
        assertEquals("https://crt.sh/?q=%25scommess%25&output=json&exclude=expired", CtLogDiscovery.queryUrl(CtKeyword("scommess")))
    }

    // ---------------------------------------------------------------------------------------
    // Classificatore dei contenuti a schermo
    // ---------------------------------------------------------------------------------------

    @Test
    fun `contenuti - combinazioni ad alta confidenza`() {
        val bet = ContentClassifier.classify("Bonus Benvenuto fino a 500€!\nGIOCA ORA\nQuote maggiorate sulla Serie A")
        assertNotNull(bet)
        assertEquals(ContentCategory.GAMBLING, bet!!.category)

        val trading = ContentClassifier.classify("Apri conto demo gratuito. Leva finanziaria fino a 1:30, spread da 0.6 pip")
        assertEquals(ContentCategory.TRADING, trading?.category)

        // Anche dentro il sito della banca: la pagina dei prestiti viene riconosciuta
        val loan = ContentClassifier.classify("Prestito personale\nCalcola la tua rata\nTAN 6,90% - TAEG 7,45%\nRichiedi ora")
        assertEquals(ContentCategory.LOANS, loan?.category)
        assertTrue(loan!!.phrases.size >= ContentClassifier.MIN_DISTINCT)
    }

    @Test
    fun `contenuti - testi innocui non scattano`() {
        // Una sola espressione, anche forte, non basta
        assertNull(ContentClassifier.classify("Gioca ora con i tuoi amici al nuovo puzzle game"))
        // Parole generiche isolate o contenute in altre parole
        assertNull(ContentClassifier.classify("La prossima puntata della serie va in onda domani a quota 1200 metri"))
        assertNull(ContentClassifier.classify("Tanto tempo fa, a grande distanza, un pirata navigava: titanic"))
        assertNull(ContentClassifier.classify("Estratto conto: saldo disponibile, bonifici in uscita, carta di debito"))
        assertNull(ContentClassifier.classify("Meteo di oggi: sole al nord, piogge sparse al sud. Temperature in aumento."))
        assertNull(ContentClassifier.classify(""))
    }
}
