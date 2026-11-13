package com.zerobet.safeguard

import com.zerobet.safeguard.data.DefaultData
import com.zerobet.safeguard.guard.BankAppRules
import com.zerobet.safeguard.guard.BlockedPackages
import com.zerobet.safeguard.guard.ChatKind
import com.zerobet.safeguard.guard.ContentCategory
import com.zerobet.safeguard.guard.TelegramRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContextRulesTest {

    // ---------------------------------------------------------------------------------------
    // Banche: whitelist web + app
    // ---------------------------------------------------------------------------------------

    @Test
    fun `ogni banca del catalogo ha almeno un'app e i package richiesti sono presenti`() {
        val missing = DefaultData.bankPresets.filter { it.packages.isEmpty() }.map { it.name }
        assertTrue("Banche senza app: $missing", missing.isEmpty())
        val all = DefaultData.bankPresets.flatMap { it.packages }.toSet()
        listOf(
            "com.latuabanca.android", "it.unicredit", "it.gruppobper.ams.android.bper", "com.lynxspa.bancopopolare",
            "com.fineco.it", "it.chebanca.android", "it.credem.mobile", "it.mps.app.digitalbanking", "it.bnl.apps.banking",
            "it.ingdirect.app", "posteitaliane.posteapp.appbpol", "posteitaliane.posteapp.apppostepay",
            "com.revolut.revolut", "de.number26.android", "it.hype.app", "com.bbva.bbvacontigo", "com.illimity.bank",
        ).forEach { assertTrue("Manca $it", it in all) }
    }

    @Test
    fun `l'app della banca in whitelist e consentita, quelle delle altre banche no`() {
        val banks = BlockedPackages.bankAppsFor(listOf("poste.it", "postepay.it"))
        assertNull(BlockedPackages.categoryOf("posteitaliane.posteapp.appbpol", "BancoPosta", banks = banks))
        assertNull(BlockedPackages.categoryOf("posteitaliane.posteapp.apppostepay", "Postepay", banks = banks))
        listOf("it.unicredit", "com.latuabanca.android", "it.ingdirect.app", "com.revolut.revolut", "com.fineco.it").forEach {
            assertEquals(it, ContentCategory.BANK, BlockedPackages.categoryOf(it, banks = banks))
        }
        // con un'altra banca in whitelist i ruoli si invertono
        val unicredit = BlockedPackages.bankAppsFor(listOf("www.unicredit.it"))
        assertNull(BlockedPackages.categoryOf("it.unicredit", banks = unicredit))
        assertEquals(ContentCategory.BANK, BlockedPackages.categoryOf("posteitaliane.posteapp.appbpol", banks = unicredit))
    }

    @Test
    fun `le finanziarie pure sono sempre bloccate`() {
        val banks = BlockedPackages.bankAppsFor(listOf("poste.it"))
        listOf("it.findomestic.mobile", "it.compass.app", "it.agos.agos4you", "com.cofidis.mobile", "it.iblbanca.mobile").forEach {
            assertEquals(it, ContentCategory.LOANS, BlockedPackages.categoryOf(it, banks = banks))
        }
    }

    @Test
    fun `nell'app della banca si chiudono solo le sezioni prestiti e investimenti`() {
        val blocked = listOf(
            listOf("Richiedi prestito", "Importo", "Durata"),
            listOf("Prestito istantaneo", "Fino a 5.000 €"),
            listOf("Indietro", "Cessione del quinto", "Calcola la rata"),
            listOf("Finanziamento", "Scegli l'importo"),
            listOf("Investimenti", "Il tuo portafoglio"),
            listOf("Trading", "Compra e vendi azioni"),
            listOf("Compra azioni", "Cerca un titolo"),
            listOf("Crypto", "Bitcoin", "Ethereum"),
        )
        blocked.forEach { assertNotNull("Dovrebbe chiudere: $it", BankAppRules.findRiskySection(it)) }

        val free = listOf(
            // home con voci di menu e banner più in basso: resta utilizzabile
            listOf("Ciao Mario", "Saldo disponibile", "1.234,56 €", "Bonifico", "Prestiti", "Investimenti", "Richiedi un prestito in 5 minuti"),
            listOf("Movimenti", "Supermercato", "-45,20 €"),
            listOf("Bonifico SEPA", "Beneficiario", "IBAN"),
            listOf("Il mio mutuo", "Rata di novembre", "Pagata"),
            listOf("Carte", "Postepay Evolution"),
        )
        free.forEach { assertNull("Non dovrebbe chiudere: $it", BankAppRules.findRiskySection(it)) }
    }

    // ---------------------------------------------------------------------------------------
    // Telegram
    // ---------------------------------------------------------------------------------------

    @Test
    fun `riconosce chat private canali gruppi e bot dall'intestazione`() {
        assertEquals(ChatKind.PRIVATE, TelegramRules.chatKind(listOf("Marco Rossi", "online", "Ciao!")))
        assertEquals(ChatKind.PRIVATE, TelegramRules.chatKind(listOf("Marco Rossi", "ultimo accesso alle 18:20")))
        assertEquals(ChatKind.PRIVATE, TelegramRules.chatKind(listOf("Anna", "last seen recently")))
        assertEquals(ChatKind.PRIVATE, TelegramRules.chatKind(listOf("Anna", "sta scrivendo…")))
        assertEquals(ChatKind.PUBLIC, TelegramRules.chatKind(listOf("Segnali Trading VIP", "12.345 iscritti")))
        assertEquals(ChatKind.PUBLIC, TelegramRules.chatKind(listOf("Pronostici Calcio", "3,2K subscribers")))
        assertEquals(ChatKind.PUBLIC, TelegramRules.chatKind(listOf("Gruppo Calcetto", "1.204 membri, 56 online")))
        assertEquals(ChatKind.PUBLIC, TelegramRules.chatKind(listOf("Casino Bonus", "bot")))
        assertEquals(ChatKind.PUBLIC, TelegramRules.chatKind(listOf("Canale X", "Messaggio fissato", "Unisciti")))
        assertEquals(ChatKind.UNKNOWN, TelegramRules.chatKind(listOf("Telegram", "Marco Rossi", "Ci vediamo domani")))
    }

    private val blockedDomains = setOf("bet365.com", "snai.it", "etoro.com", "nuovo-casino.bet")
    private fun isBlocked(domain: String) = blockedDomains.any { domain == it || domain.endsWith(".$it") }

    @Test
    fun `nelle chat private le parole discorsive non bastano`() {
        val chat = listOf(
            "Marco Rossi", "online",
            "Ieri ho perso alle scommesse, basta davvero",
            "Anche io ho chiuso con il trading e il forex, mi hanno rovinato",
            "Domani pizza? Ti mando il link del ristorante: www.pizzeriadamario.it",
        )
        assertNull(TelegramRules.findRiskyLink(chat, ::isBlocked))
    }

    @Test
    fun `nelle chat private link domini e inviti a rischio vengono bloccati`() {
        val link = TelegramRules.findRiskyLink(listOf("Marco", "online", "guarda qui https://www.bet365.com/bonus"), ::isBlocked)
        assertEquals("bet365.com", link?.term)
        assertNotNull(TelegramRules.findRiskyLink(listOf("entra su m.snai.it"), ::isBlocked))
        assertEquals("t.me/segnalitradingvip", TelegramRules.findRiskyLink(listOf("unisciti: t.me/SegnaliTradingVIP"), ::isBlocked)?.term)
        assertEquals("@casinobonusbot", TelegramRules.findRiskyLink(listOf("prova il bot @CasinoBonusBot"), ::isBlocked)?.term)
        assertEquals(ContentCategory.LOANS, TelegramRules.findRiskyLink(listOf("scrivi a @prestitiveloci_it"), ::isBlocked)?.category)
        // inviti innocui
        assertNull(TelegramRules.findRiskyLink(listOf("canale del calcetto: t.me/calcettogiovedi", "chiedi a @mario_rossi"), ::isBlocked))
        assertNull(TelegramRules.findRiskyLink(listOf("versione 1.2.3 rilasciata", "ore 18.30"), ::isBlocked))
    }
}

class DeveloperOptionsRulesTest {

    private fun tap(vararg t: String) = com.zerobet.safeguard.guard.DeveloperOptionsRules.isBuildNumberTap(t.toList())

    @Test
    fun `il tocco su Numero build viene riconosciuto`() {
        assertTrue(tap("Build number", "BE4B.251210.005"))
        assertTrue(tap("Numero build", "UP1A.231005.007"))
        assertTrue(tap("Numero di build"))
        assertTrue(tap("Build number\nBE4B.251210.005"))
        assertTrue(tap("Versione MIUI", "MIUI Global 14.0.3"))
        assertTrue(tap("Versione HyperOS"))
        assertTrue(tap("Build\u00a0number"))
    }

    @Test
    fun `le altre voci di Info telefono restano libere`() {
        listOf(
            "Versione Android", "Android version", "Modello", "Model", "IMEI", "Stato SIM", "SIM status",
            "Indirizzo IP", "Nome dispositivo", "Informazioni legali", "Aggiornamento di sistema",
            "Versione banda di base", "Baseband version", "Livello patch di sicurezza Android",
        ).forEach { assertTrue("Falso positivo: $it", !tap(it)) }
        assertTrue(!tap(""))
        assertTrue(!tap("Il numero build si trova in fondo alla pagina Info telefono"))
    }
}
