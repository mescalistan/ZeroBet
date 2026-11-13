package com.zerobet.safeguard.guard

/**
 * App dedicate a trading, finanza speculativa, crypto, scommesse e prestiti: vengono chiuse
 * all'apertura. Kotlin puro, coperto da unit test.
 *
 * Tre livelli: (1) elenco di package noti; (2) euristica sul nome del package, per le app non in
 * elenco; (3) euristica sul nome visibile dell'app ("Casinò Vegas Slots"). L'app della banca
 * (o finanziaria) che il tutore ha messo in whitelist resta sempre utilizzabile.
 */
object BlockedPackages {

    private val KNOWN: Map<String, ContentCategory> = HashMap<String, ContentCategory>().apply {
        listOf(
            // trading, borsa, news finanziarie
            "com.fusionmedia.investing", "com.tradingview.tradingviewapp", "net.metaquotes.metatrader4",
            "net.metaquotes.metatrader5", "com.etoro.openbook", "com.plus500", "de.traderepublic.app",
            "de.scalable.capital", "com.bloomberg.android.plus", "com.bloomberg.android", "nl.degiro.trader",
            "com.xtb.xmobile2", "com.xtb.xmobile", "com.avatrade.mobile", "com.iggroup.android.cfd",
            "com.robinhood.android", "com.interactivebrokers.atws", "com.saxobank.goapp", "com.libertex.mobile",
            "com.exness.android.pa", "com.fineco.it", "it.fineco.app", "com.directa.darwin", "com.moneyfarm.app",
            "com.freedom24.app", "com.capital.trading", "com.iqoption", "com.pocketoption.app", "com.yahoo.mobile.client.android.finance",
            "com.google.android.apps.finance", "com.cnbc.client", "com.marketwatch", "com.ilsole24ore.finanza",
            "it.borsaitaliana.android", "com.webull.trade", "com.naga.trader", "com.trading212.android",
            // crypto
            "com.binance.dev", "com.coinbase.android", "com.kraken.trade", "com.kraken.invest.app",
            "com.crypto.exchange", "co.mona.android", "com.bybit.app", "com.kubi.kucoin", "com.bitpanda.bitpanda",
            "com.okinc.okex.gp", "com.bitget.exchange", "io.metamask", "com.wallet.crypto.trustapp",
            "com.youngplatform.app", "com.coinmarketcap.android", "com.coingecko.coingeckoapp", "com.bitfinex.mobileapp",
            "com.ledger.live", "piuk.blockchain.android", "com.revolut.revolut.trading",
        ).forEach { put(it, ContentCategory.TRADING) }
        listOf(
            // scommesse, poker, casinò, lotterie
            "it.snai.sport", "it.snai.casino", "com.snai.snaisport", "it.sisal.matchpoint", "it.sisal.casino",
            "it.sisal.sisalpay.superenalotto", "it.goldbet.app", "it.eurobet.sport", "it.lottomatica.better",
            "it.lottomatica.scommesse", "com.bet365Wrapper.Bet365_Application", "com.bet365.bet365SportsApp",
            "com.betfair.sportsbook", "com.betfair.exchange", "com.bwin.sports.it", "com.pokerstars.it",
            "com.pyrsoftware.pokerstars.it", "com.leovegas.it", "it.starcasino.app", "com.williamhill.sports.android",
            "com.unibet.sport", "com.betway.sports", "it.admiralbet.app", "it.netbet.sport", "it.betflag.sport",
            "com.planetwin365.app", "com.xbet.client", "org.xbet.client1", "com.stake.casino", "it.gratta.vinci",
            "it.lottomatica.lotto", "it.giocodigitale.app", "com.betsson.sportsbook", "com.tipico.sports",
            "com.winamax.android", "com.partypoker.it", "com.ggpoker.android", "it.dazn.bet",
        ).forEach { put(it, ContentCategory.GAMBLING) }
        listOf(
            // finanziarie e prestiti
            "it.findomestic.mobile", "it.agos.agos4you", "com.cofidis.mobile", "it.iblbanca.mobile",
            "it.findomestic.app", "com.findomestic.mobile", "it.compass.app", "it.compass.easy", "com.compass.compasseasy",
            "it.agos.app", "it.agos.agosapp", "it.cofidis.app", "com.younited.credit", "it.younited.app",
            "it.fiditalia.app", "it.santanderconsumer.app", "com.klarna.android", "it.scalapay.app", "com.scalapay.android",
            "com.clearpay.android", "it.prestitionline.app", "it.mutuionline.app", "it.facile.app", "com.soisy.app",
            "it.sella.personalcredit", "com.cashper.android", "com.ferratum.mobile", "it.deutschebank.easy",
        ).forEach { put(it, ContentCategory.LOANS) }
    }

    /** Parole che, come parte intera del nome del package (separata da punti o trattini bassi), lo qualificano. */
    private val PACKAGE_TOKENS: Map<String, ContentCategory> = HashMap<String, ContentCategory>().apply {
        listOf(
            "trading", "trader", "forex", "metatrader", "tradingview", "investing", "invest", "stocks", "broker",
            "brokers", "crypto", "bitcoin", "binance", "coinbase", "kraken", "bybit", "kucoin", "bitpanda", "etoro",
            "plus500", "degiro", "traderepublic", "cfd", "exchange", "coinmarketcap", "wallet",
        ).forEach { put(it, ContentCategory.TRADING) }
        listOf(
            "casino", "poker", "slots", "slot", "scommesse", "betting", "sportsbook", "bet365", "snai", "sisal",
            "goldbet", "eurobet", "lottomatica", "betfair", "bwin", "pokerstars", "leovegas", "starcasino",
            "williamhill", "unibet", "betway", "admiralbet", "netbet", "betflag", "planetwin365", "xbet", "roulette",
            "blackjack", "bingo", "jackpot", "gambling", "superenalotto", "lotto", "lottery",
        ).forEach { put(it, ContentCategory.GAMBLING) }
        listOf(
            "findomestic", "agos", "cofidis", "younited", "fiditalia", "prestiti", "prestito", "loan", "loans",
            "klarna", "scalapay", "clearpay", "prestitionline", "mutuionline", "cessionedelquinto", "payday",
        ).forEach { put(it, ContentCategory.LOANS) }
    }

    /** "wallet" ed "exchange" da soli sono parole generiche: valgono solo insieme a un altro termine. */
    private val WEAK_TOKENS = setOf("wallet", "exchange", "invest", "slot", "lotto")

    /** App di sistema e portafogli di pagamento che contengono per caso una parola dell'elenco. */
    private val NEVER_BLOCKED_PREFIXES = listOf(
        "com.google.android.apps.walletnfcrel", "com.google.android.gms", "com.android.", "com.samsung.android.spay",
        "com.samsung.android.samsungpay", "com.zerobet.safeguard", "com.microsoft.exchange", "com.paypal.",
    )

    private val TOKEN_SPLIT = Regex("[._\\-]")

    /**
     * @param label nome dell'app mostrato all'utente (può essere null)
     * @param allowedLabels etichette dei domini in whitelist ("unicredit" per unicredit.it): un'app
     *   il cui package o nome le contiene è quella della banca autorizzata e non viene mai bloccata.
     * @return la categoria per cui l'app è bloccata, o null se è consentita.
     */
    fun categoryOf(
        packageName: String,
        label: String? = null,
        allowedLabels: Set<String> = emptySet(),
        banks: BankApps = BankApps.NONE,
    ): ContentCategory? {
        val pkg = packageName.lowercase()
        if (NEVER_BLOCKED_PREFIXES.any { pkg.startsWith(it) }) return null
        // App bancarie del catalogo: libera quella della banca in whitelist, bloccate tutte le altre.
        if (banks.isAllowed(pkg)) return null
        if (banks.isKnownBank(pkg)) return ContentCategory.BANK
        val name = label?.lowercase().orEmpty()
        if (allowedLabels.any { it.length >= 4 && (pkg.contains(it) || name.replace(" ", "").contains(it)) }) return null

        KNOWN[pkg]?.let { return it }
        // Varianti dello stesso sviluppatore: "com.binance.dev.beta" è coperto da "com.binance.dev".
        KNOWN.entries.firstOrNull { pkg.startsWith(it.key + ".") }?.let { return it.value }

        var weak: ContentCategory? = null
        var weakCount = 0
        for (token in pkg.split(TOKEN_SPLIT)) {
            val category = PACKAGE_TOKENS[token] ?: continue
            if (token !in WEAK_TOKENS) return category
            weak = category
            weakCount++
        }
        if (weakCount >= 2) return weak

        // Nome visibile dell'app: stesse regole delle ricerche (parola intera, espressioni innocue escluse).
        if (name.length >= 3) SearchQueryClassifier.classify(name)?.let { return it.category }
        return null
    }

    /** App bancarie: quelle della banca autorizzata dal tutore e tutte quelle del catalogo. */
    class BankApps(private val allowed: Set<String>, private val all: Set<String>) {
        fun isAllowed(pkg: String) = pkg in allowed || allowed.any { pkg.startsWith("$it.") }
        fun isKnownBank(pkg: String) = pkg in all || all.any { pkg.startsWith("$it.") }

        companion object {
            val NONE = BankApps(emptySet(), emptySet())
        }
    }

    /** Ricava le app bancarie consentite dai domini in whitelist (catalogo in DefaultData). */
    fun bankAppsFor(whitelistedDomains: Collection<String>): BankApps {
        val whitelisted = whitelistedDomains.map { it.lowercase().removePrefix("www.") }.toHashSet()
        val allowed = HashSet<String>()
        val all = HashSet<String>()
        for (preset in com.zerobet.safeguard.data.DefaultData.bankPresets) {
            all.addAll(preset.packages)
            if (preset.domains.any { it in whitelisted }) allowed.addAll(preset.packages)
        }
        return BankApps(allowed, all)
    }

    /** "www.unicredit.it" -> "unicredit"; "bancobpm.it" -> "bancobpm". */
    fun labelOfDomain(domain: String): String {
        val parts = domain.lowercase().removePrefix("www.").split('.')
        return if (parts.size >= 2) parts[parts.size - 2] else parts[0]
    }
}
