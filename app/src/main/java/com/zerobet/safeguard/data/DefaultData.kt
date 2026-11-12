package com.zerobet.safeguard.data

/** Banca proposta nel wizard. Tutti i suoi domini finiscono in whitelist se selezionata. */
data class BankPreset(val name: String, val domains: List<String>)

/**
 * Dati con cui viene pre-popolato il database al primo avvio.
 *
 * Criterio: i marchi con nome "ambiguo" (es. snai -> "snail", sisal -> fibra tessile) sono
 * inseriti come DOMINI per evitare falsi positivi; i termini inequivocabili sono inseriti
 * come PAROLE CHIAVE così da coprire anche domini mirror/cloni (es. bet365-xyz.com).
 */
object DefaultData {

    val bankPresets: List<BankPreset> = listOf(
        BankPreset("Intesa Sanpaolo", listOf("intesasanpaolo.com", "intesa.it")),
        BankPreset("UniCredit", listOf("unicredit.it", "unicreditgroup.eu")),
        BankPreset("Banco BPM", listOf("bancobpm.it")),
        BankPreset("BPER Banca", listOf("bper.it")),
        BankPreset("Monte dei Paschi di Siena", listOf("mps.it")),
        BankPreset("Poste Italiane / BancoPosta", listOf("poste.it", "postepay.it")),
        BankPreset("BNL", listOf("bnl.it")),
        BankPreset("Crédit Agricole Italia", listOf("credit-agricole.it")),
        BankPreset("Credem", listOf("credem.it")),
        BankPreset("ING Italia", listOf("ing.it")),
        BankPreset("Fineco", listOf("finecobank.com", "fineco.it")),
        BankPreset("Banca Mediolanum", listOf("bancamediolanum.it")),
        BankPreset("Banca Sella", listOf("sella.it")),
        BankPreset("Banca Widiba", listOf("widiba.it")),
        BankPreset("Banca Popolare di Sondrio", listOf("popso.it")),
        BankPreset("Mediobanca Premier (CheBanca!)", listOf("mediobancapremier.com", "chebanca.it")),
        BankPreset("Deutsche Bank Italia", listOf("deutsche-bank.it", "db.com")),
        BankPreset("Banca Ifis", listOf("bancaifis.it")),
        BankPreset("illimity", listOf("illimity.com", "illimitybank.com")),
        BankPreset("Hype", listOf("hype.it")),
        BankPreset("N26", listOf("n26.com")),
        BankPreset("Revolut", listOf("revolut.com")),
        BankPreset("BBVA Italia", listOf("bbva.it")),
        BankPreset("Isybank", listOf("isybank.com")),
    )

    private val gamblingDomains = listOf(
        "bet365.com", "bet365.it", "snai.it", "sisal.it", "sisal.com", "lottomatica.it",
        "pokerstars.it", "pokerstars.com", "eurobet.it", "goldbet.it", "betfair.it", "betfair.com",
        "williamhill.it", "williamhill.com", "bwin.it", "bwin.com", "planetwin365.it",
        "betflag.it", "leovegas.it", "leovegas.com", "888.it", "888casino.it", "888sport.it",
        "888poker.it", "starcasino.it", "betway.it", "betway.com", "unibet.it", "unibet.com",
        "netbet.it", "admiralbet.it", "starvegas.it", "betclic.it", "domusbet.it", "stanleybet.it",
        "marathonbet.it", "better.it", "giocodigitale.it", "merkur-win.it",
        "quigioco.it", "betsson.it", "betsson.com", "vincitu.it", "sportbet.it", "casinomania.it",
        "bigcasino.it", "fantasyteam.it", "1xbet.com", "22bet.com", "stake.com", "roobet.com",
        "bc.game", "betano.com", "pinnacle.com", "draftkings.com", "fanduel.com",
        "gratta-e-vinci.it", "grattaevinci.com", "10elotto.it", "superenalotto.it",
        "lotto-italia.it", "win2day.at", "tipico.com", "tipico.it", "codere.it", "eplay24.it",
    )

    private val gamblingKeywords = listOf(
        "bet365", "pokerstars", "lottomatica", "casino", "scommess", "poker", "betting",
        "slotmachine", "slot-", "-slot", "slots", "roulette", "blackjack", "jackpot",
        "sportsbook", "gambling", "eurobet", "goldbet", "planetwin", "1xbet",
    )

    private val tradingDomains = listOf(
        "etoro.com", "plus500.com", "plus500.it", "binance.com", "binance.us", "coinbase.com",
        "kraken.com", "crypto.com", "bybit.com", "okx.com", "kucoin.com", "bitget.com",
        "gate.io", "mexc.com", "htx.com", "bitfinex.com", "bitstamp.net", "gemini.com",
        "bitpanda.com", "youngplatform.com", "coinmarketcap.com", "coingecko.com",
        "xtb.com", "ig.com", "avatrade.it", "avatrade.com", "degiro.it", "degiro.com",
        "trading212.com", "traderepublic.com", "scalable.capital", "directa.it",
        "interactivebrokers.com", "interactivebrokers.ie", "ibkr.com", "saxobank.com",
        "home.saxo", "robinhood.com", "webull.com", "capital.com", "libertex.com",
        "iqoption.com", "pocketoption.com", "olymptrade.com", "exness.com", "xm.com",
        "fxpro.com", "pepperstone.com", "fpmarkets.com", "icmarkets.com", "oanda.com",
        "fxcm.com", "markets.com", "admiralmarkets.com", "activtrades.com",
        "metatrader4.com", "metatrader5.com", "mql5.com", "uniswap.org", "pancakeswap.finance",
        "metamask.io", "phantom.app", "ledger.com", "trezor.io", "pump.fun", "dexscreener.com",
    )

    private val tradingKeywords = listOf(
        "etoro", "plus500", "binance", "trading", "forex", "crypto", "bitcoin", "coinbase",
        "cfd-", "opzionibinarie", "binaryoption", "metatrader",
    )

    private val loanDomains = listOf(
        "findomestic.it", "compass.it", "agos.it", "iblbanca.it", "younited-credit.com",
        "younited-credit.it", "cofidis.it", "santanderconsumer.it", "fiditalia.it",
        "sellapersonalcredit.it", "prestitalia.it", "pitagoraspa.it", "dynamica-retail.it",
        "bancaprogetto.it", "avvera.it", "creditis.it", "figenpa.it", "smava.it",
        "prestiamoci.it", "soisy.it", "klarna.com", "scalapay.com", "clearpay.com",
        "prestitionline.it", "mutuionline.it",
        "signorprestito.it", "prestitisupermarket.it", "credipass.it", "kiron.it",
        "viva-finance.it", "vivus.it", "cashper.it", "ferratum.it", "dunaprestiti.it",
        "deutsche-bank-easy.it", "bnlfinance.it", "consel.it", "banca5.com",
    )

    private val loanKeywords = listOf(
        "findomestic", "agostini", "ibian", "prestit", "finanziament", "cessionedelquinto",
        "cessione-del-quinto", "cessionequinto", "microcredito", "payday", "loan",
        "compass-", "compassonline",
    )

    /** Banche aggiuntive (oltre ai preset) bloccate di default. */
    private val extraBankDomains = listOf(
        "bancagenerali.it", "bancodesio.it", "creval.it", "volksbank.it", "sparkasse.it",
        "bancaetica.it", "bcc.it", "iccreabanca.it", "cassacentrale.it", "inbank.it",
        "relaxbanking.it", "credit-suisse.com", "ubs.com", "santander.it",
        "bunq.com", "tinaba.it", "buddybank.com", "flowe.com",
        "bancaprofilo.it", "bancapassadore.it", "civibank.it", "bancadiasti.it",
        "lacassa.com", "carige.it", "ubibanca.com", "bancobpmspa.com", "bancaaletti.it",
        "webank.it", "youbanking.it", "banking4you.it", "csebo.it",
    )

    /**
     * Reti di affiliazione e pubblicità che veicolano annunci di scommesse, trading e prestiti,
     * più i comparatori di prestiti/mutui.
     */
    private val affiliateDomains = listOf(
        "incomeaccess.com", "btagmedia.com", "affilka.com", "netrefer.com", "myaffiliates.com",
        "cellxpert.com", "propellerads.com", "adsterra.com", "taboola.com", "outbrain.com",
        "exoclick.com", "popads.net", "mgid.com", "revcontent.com", "clickadu.com",
        "hilltopads.com", "trafficjunky.com",
        "facile.it", "segugio.it", "mutuionline.it", "prestitionline.it",
    )

    /**
     * Resolver DNS-over-HTTPS noti. Sinkholando il loro hostname, i browser (Chrome, Brave,
     * Firefox...) non riescono ad avviare il "DNS sicuro" e ricadono sul DNS di sistema, cioè
     * sul nostro filtro. Non ferma un DoH configurato direttamente per indirizzo IP.
     */
    private val dohResolvers = listOf(
        "dns.google", "dns.google.com", "cloudflare-dns.com", "1dot1dot1dot1.cloudflare-dns.com",
        "one.one.one.one", "dns.quad9.net", "dns.adguard.com", "dns.adguard-dns.com",
        "doh.opendns.com", "dns.nextdns.io", "doh.cleanbrowsing.org", "doh.dns.sb",
        "dns.mullvad.net", "freedns.controld.com",
    )

    /** Voci aggiunte con la versione 2 del database (inserite anche dalla migrazione 1 -> 2). */
    fun seedEntriesV2(): List<BlockedDomain> = buildList {
        affiliateDomains.forEach { add(BlockedDomain(pattern = it, category = Category.ADS)) }
        dohResolvers.forEach { add(BlockedDomain(pattern = it, category = Category.DOH)) }
    }

    /** Elenco completo delle voci (pattern, categoria, isKeyword) da inserire alla creazione del DB. */
    fun seedEntries(): List<BlockedDomain> = buildList {
        gamblingDomains.forEach { add(BlockedDomain(pattern = it, category = Category.GAMBLING)) }
        gamblingKeywords.forEach { add(BlockedDomain(pattern = it, category = Category.GAMBLING, isKeyword = true)) }
        tradingDomains.forEach { add(BlockedDomain(pattern = it, category = Category.TRADING)) }
        tradingKeywords.forEach { add(BlockedDomain(pattern = it, category = Category.TRADING, isKeyword = true)) }
        loanDomains.forEach { add(BlockedDomain(pattern = it, category = Category.LOANS)) }
        loanKeywords.forEach { add(BlockedDomain(pattern = it, category = Category.LOANS, isKeyword = true)) }
        bankPresets.flatMap { it.domains }.forEach { add(BlockedDomain(pattern = it, category = Category.BANK)) }
        extraBankDomains.forEach { add(BlockedDomain(pattern = it, category = Category.BANK)) }
        addAll(seedEntriesV2())
    }
}
