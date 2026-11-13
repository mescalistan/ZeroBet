package com.zerobet.safeguard.guard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.zerobet.safeguard.R
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.AutoLearnedBlockedDomain
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.data.Severity
import com.zerobet.safeguard.data.DomainRepository
import com.zerobet.safeguard.ui.LockScreenActivity
import com.zerobet.safeguard.vpn.SafeDomains

/**
 * Servizio di accessibilità di SafeGuard. Ha due compiti distinti.
 *
 * 1) GUARDIANO IMPOSTAZIONI
 *    Quando la schermata visibile è una di quelle da cui si può disattivare la protezione:
 *      - Info app di SafeGuard (Disinstalla / Forza interruzione / Cancella dati)
 *      - Amministratori dispositivo
 *      - Impostazioni VPN
 *      - Accessibilità -> SafeGuard
 *      - DNS privato (un DNS-over-TLS di sistema scavalcherebbe il filtro)
 *      - Dialogo di disinstallazione di SafeGuard
 *    mostra la LockScreenActivity che richiede il PIN del tutore; senza PIN si torna alla Home.
 *
 * 2) BLOCCO DEI CONTENUTI A SCHERMO E AUTO-APPRENDIMENTO
 *    Nei browser e nelle app di ricerca legge i testi visibili e, se riconosce una combinazione ad
 *    alta confidenza di espressioni su scommesse, trading o prestiti (vedi ContentClassifier),
 *    torna indietro. Se la pagina è un sito esterno aperto nel browser, il suo dominio viene
 *    aggiunto ai "domini auto-appresi" e da quel momento è bloccato anche a livello DNS.
 *
 * 4) TUTTE LE ALTRE APP (social, video, news, store)
 *    Blocco delle ricerche nelle caselle di ricerca delle app, delle pubblicità e dei contenuti
 *    su scommesse/trading/prestiti (vedi InAppContentAndAdFilter), delle schede del Play Store e
 *    delle app dedicate (vedi BlockedPackages). La schermata viene letta in un solo passaggio e
 *    non viene analizzata se il suo hash è uguale a quello del controllo precedente.
 *
 * 3) BLOCCO DELLE RICERCHE
 *    Se in un motore di ricerca (barra degli indirizzi del browser, casella di ricerca della
 *    pagina, app Google) viene scritta una ricerca su scommesse, trading/investimenti o prestiti
 *    (vedi SearchQueryClassifier), si esce dal motore di ricerca e si torna alla Home.
 *
 * Privacy: i testi letti vengono analizzati in memoria sul dispositivo e subito scartati.
 * Non vengono salvati né trasmessi; l'unico dato registrato è il dominio bloccato con il motivo.
 */
class SettingsGuardAccessibilityService : AccessibilityService() {

    private lateinit var app: SafeGuardApp
    private lateinit var appLabel: String
    private var ignoredPackages: Set<String> = emptySet()

    private var lastLockAt = 0L
    private var lastIntegrityAt = 0L
    private var pendingPackage: String? = null
    private var pendingSince = 0L

    // Cache dell'ultima schermata analizzata: se la barra degli indirizzi non è cambiata e la
    // pagina ha finito di caricarsi, l'albero dei nodi non viene più attraversato.
    private var lastScanKey: String? = null
    private var lastScanKeyAt = 0L
    private var lastContentBlockAt = 0L

    // Stato del filtro in-app
    private var lastScreenHash = 0
    private var lastSearchBlockAt = 0L
    private var lastInAppBlockAt = 0L
    private var lastAppBlockAt = 0L
    private var inAppLevel = 0
    private val appNames = HashMap<String, String>()
    private val packageVerdicts = HashMap<String, java.util.Optional<ContentCategory>>()
    private var packageVerdictsFilter: Any? = null
    private val handler = Handler(Looper.getMainLooper())

    // Le preferenze sono cifrate: leggerle a ogni evento sarebbe costoso, quindi si tengono
    // in cache per qualche secondo.
    private var flagsReadAt = 0L
    private var onboardingDone = false
    private var protectionEnabled = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        app = application as SafeGuardApp
        appLabel = getString(R.string.app_name).lowercase()
        ignoredPackages = buildIgnoredPackages()
        // Nessun filtro di sistema: il servizio segue tutte le app (social, video, news, store).
        // I pacchetti innocui (interfaccia di sistema, launcher, tastiere, telefono, fotocamera)
        // vengono scartati in O(1) all'inizio di onAccessibilityEvent.
        serviceInfo = serviceInfo.apply { packageNames = null }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val type = event.eventType
        if (type and HANDLED_EVENTS == 0) return
        val pkg = event.packageName?.toString() ?: return
        // Scarto in O(1): una ricerca in HashSet, nessuna lettura dello schermo.
        if (pkg in ignoredPackages) return
        // Cambio di finestra e testo digitato si valutano subito; scorrimento e aggiornamenti del
        // contenuto (decine al secondo) solo quando la schermata si è fermata.
        val immediate = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED

        val now = SystemClock.elapsedRealtime()
        refreshFlags(now)
        if (onboardingDone && now - lastIntegrityAt >= INTEGRITY_INTERVAL_MS) {
            lastIntegrityAt = now
            IntegrityChecker.check(this, "guardiano")
        }
        // Attivo solo a wizard completato (durante l'onboarding il tutore deve poter concedere
        // i permessi) e in silenzio nella finestra di grazia concessa dal PIN.
        if (!onboardingDone || GuardState.inGrace || GuardState.lockScreenVisible) return

        if (pkg in SETTINGS_PACKAGES) {
            if (now - lastLockAt < RELOCK_THROTTLE_MS) return
            val screen = sensitiveScreen(event) ?: return
            lastLockAt = now
            // Registro Allarmi: i tentativi ripetuti sulla stessa schermata entro un minuto sono una riga sola.
            app.securityLog.grouped(
                "tamper:$screen", EventType.TAMPER, Severity.CRITICAL,
                "Tentativo di aprire un'impostazione protetta", screen
            )
            showLockScreen()
        } else if (protectionEnabled) {
            // App dedicate a trading, scommesse e prestiti: chiuse appena si aprono.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                blockedAppCategory(pkg)?.let { category ->
                    blockApp(pkg, category, now)
                    return
                }
            }
            scheduleScan(pkg, now, immediate)
        }
    }

    /**
     * Debounce: l'albero dei nodi viene letto solo quando la schermata è ferma da
     * [SCAN_DEBOUNCE_MS] (una pagina che si carica genera decine di eventi al secondo), ma mai
     * più tardi di [SCAN_MAX_WAIT_MS] dal primo evento in attesa.
     */
    private fun scheduleScan(pkg: String, now: Long, immediate: Boolean = false) {
        pendingPackage = pkg
        if (pendingSince == 0L) pendingSince = now
        handler.removeCallbacks(scanRunnable)
        val waited = now - pendingSince
        val delay = when {
            waited >= SCAN_MAX_WAIT_MS -> 0L
            immediate -> SCAN_IMMEDIATE_MS      // pochi ms: raggruppa gli eventi dello stesso istante
            else -> SCAN_DEBOUNCE_MS
        }
        handler.postDelayed(scanRunnable, delay)
    }

    private val scanRunnable = Runnable {
        val pkg = pendingPackage
        pendingSince = 0L
        if (pkg != null && !GuardState.inGrace && !GuardState.lockScreenVisible) {
            try {
                val now = SystemClock.elapsedRealtime()
                // Browser e app di ricerca hanno un percorso dedicato (barra degli indirizzi,
                // auto-apprendimento del dominio); tutte le altre app passano dal filtro in-app.
                if (pkg in BROWSER_URL_BARS || pkg in SEARCH_APPS) inspectContent(pkg, now) else inspectApp(pkg, now)
            } catch (e: Exception) {
                android.util.Log.w("SafeGuardGuard", "Analisi della schermata fallita: ${e.message}")
            }
        }
    }

    private fun refreshFlags(now: Long) {
        if (flagsReadAt != 0L && now - flagsReadAt < FLAGS_CACHE_MS) return
        flagsReadAt = now
        onboardingDone = app.pinManager.onboardingDone
        protectionEnabled = app.pinManager.protectionEnabled
    }

    // ---------------------------------------------------------------------------------------
    // 1) Guardiano Impostazioni
    // ---------------------------------------------------------------------------------------

    /** Riconosce le schermate sensibili dal nome della classe della finestra e dai testi visibili. */
    private fun sensitiveScreen(event: AccessibilityEvent): String? {
        val className = event.className?.toString().orEmpty()
        // Nomi di Activity/Fragment AOSP: DeviceAdminSettings, DeviceAdminAdd, VpnSettings...
        SENSITIVE_CLASS_HINTS.firstOrNull { className.contains(it, ignoreCase = true) }?.let {
            return when (it) {
                "DeviceAdmin" -> "Amministratore dispositivo"
                "VpnSettings" -> "Impostazioni VPN"
                else -> "Disinstallazione di SafeGuard"
            }
        }

        val texts = StringBuilder()
        event.text.forEach { texts.append(it).append('\n') }
        collectText(rootInActiveWindow, texts, 0, SETTINGS_TEXT_CHARS)
        val screen = texts.toString().lowercase()

        // Titoli che identificano da soli la schermata: "Amministratori dispositivo" e il
        // dialogo di scelta del DNS privato (non la semplice voce "DNS privato" nell'elenco Rete).
        STANDALONE_TITLES.firstOrNull { screen.contains(it) }?.let {
            return if (it.contains("dns")) "DNS privato" else "Amministratori dispositivo"
        }

        // Tutte le altre schermate sensibili mostrano il nome dell'app insieme a un'azione
        // o a un contesto pericoloso. Il solo nome (es. nell'elenco generale delle app) non basta.
        if (!screen.contains(appLabel)) return null
        // La pagina generale "Rete e Internet" mostra la riga "VPN · SafeGuard" accanto a Wi-Fi,
        // hotspot, ecc.: lì "vpn" non conta, altrimenti l'utente non potrebbe più cambiare rete.
        // Restano protetti la pagina VPN vera e propria e il dialogo del DNS privato.
        val isNetworkDashboard = NETWORK_DASHBOARD_HINTS.any { screen.contains(it) }
        val context = DANGEROUS_CONTEXT.firstOrNull { context ->
            screen.contains(context) && !(context == "vpn" && isNetworkDashboard)
        } ?: return null
        return when {
            context == "vpn" -> "Impostazioni VPN"
            context.startsWith("accessibilit") -> "Accessibilità di SafeGuard"
            context == "disattiva" || context == "deactivate" -> "Amministratore dispositivo"
            context.contains("install") -> "Disinstallazione di SafeGuard"
            else -> "Info app di SafeGuard ($context)"
        }
    }

    private fun showLockScreen() {
        startActivity(
            Intent(this, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    // ---------------------------------------------------------------------------------------
    // 2) Contenuti a schermo
    // ---------------------------------------------------------------------------------------

    private fun inspectContent(pkg: String, now: Long) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        // Cache: nei browser la schermata è identificata dal testo della barra degli indirizzi.
        // Dopo [RESCAN_WINDOW_MS] (pagina caricata) la stessa schermata non viene più riletta.
        BROWSER_URL_BARS[pkg]?.let { barId ->
            val bar = root.findAccessibilityNodeInfosByViewId(barId)?.firstOrNull()
            val key = pkg + '|' + bar?.text + '|' + (bar?.isFocused == true)
            release(bar)
            if (key != lastScanKey) {
                lastScanKey = key
                lastScanKeyAt = now
            } else if (now - lastScanKeyAt > RESCAN_WINDOW_MS) {
                return
            }
        }

        // Ricerche a rischio: si esce dal motore di ricerca prima ancora di guardare i risultati.
        findRiskySearch(pkg, root)?.let { hit ->
            blockSearch(hit, now, pkg)
            return
        }

        // Nei browser noti si legge il dominio dalla barra degli indirizzi.
        val host = BROWSER_URL_BARS[pkg]?.let { readBrowserHost(root, it) }
        val filter = app.filterStore.current

        // a) Il browser è su un dominio già in blacklist: può succedere se la pagina è arrivata
        //    aggirando il DNS (DoH per indirizzo IP, cache, proxy). Si esce comunque.
        if (host != null && filter.check(host).blocked) {
            blockContent("sito in blacklist", now, host)
            return
        }

        // Nelle pagine dei motori di ricerca decide solo la ricerca scritta dall'utente (già
        // controllata sopra): i risultati di una ricerca innocua come "scommessa vinta con gli
        // amici" contengono per forza parole a rischio e non devono far chiudere la pagina.
        // I siti aperti da lì restano coperti dal filtro DNS e dall'analisi dei contenuti.
        if (pkg in SEARCH_APPS || (host != null && SearchQueryClassifier.isSearchEngine(host))) return

        // b) Analisi dei testi visibili (pagina web, banner pubblicitario, schermata di un'app).
        val text = StringBuilder()
        collectText(root, text, 0, CONTENT_TEXT_CHARS)
        val hit = ContentClassifier.classify(text) ?: return
        blockContent(hit.category.label, now, host.orEmpty())

        // c) Auto-apprendimento: solo per siti esterni. Mai per la banca in whitelist (lì si esce
        //    dalla pagina dei prestiti ma l'home banking resta raggiungibile) né per motori di
        //    ricerca e grandi piattaforme, dove il testo incriminato è un risultato o un annuncio.
        if (host != null && !filter.isWhitelisted(host) && !SafeDomains.contains(host)) {
            app.filterStore.learn(
                host, AutoLearnedBlockedDomain.ORIGIN_SCREEN,
                "${hit.category.label}: ${hit.phrases.take(4).joinToString(", ")}"
            )
        }
    }

    /**
     * Cerca una ricerca a rischio in due punti:
     *  - l'indirizzo della pagina dei risultati (es. google.com/search?q=...);
     *  - le caselle di testo della pagina di un motore di ricerca o di un'app di ricerca.
     * Mentre si scrive, l'ultima parola incompleta non viene valutata (vedi SearchQueryClassifier).
     */
    private fun findRiskySearch(pkg: String, root: AccessibilityNodeInfo): SearchHit? {
        val urlBarId = BROWSER_URL_BARS[pkg]
        var onSearchEngine = pkg in SEARCH_APPS

        if (urlBarId != null) {
            val bar = root.findAccessibilityNodeInfosByViewId(urlBarId)?.firstOrNull()
            val text = bar?.text?.toString()?.trim().orEmpty()
            if (bar != null && text.isNotEmpty() && !isHint(bar)) {
                val looksLikeUrl = !text.contains(' ') && text.contains('.')
                if (looksLikeUrl) {
                    SearchQueryClassifier.queryFromUrl(text)?.let { query ->
                        SearchQueryClassifier.classify(query)?.let { return it }
                    }
                    val host = DomainRepository.normalizeDomain(text)
                    if (host != null && SearchQueryClassifier.isSearchEngine(host)) onSearchEngine = true
                } else if (!bar.isFocused) {
                    // Mentre l'utente scrive nella barra NON si valuta nulla: il browser completa da
                    // solo il testo con le ricerche precedenti ("inv" -> "investimenti sicuri") e
                    // bloccherebbe chi sta scrivendo "investigatore". Si blocca all'invio della ricerca.
                    // Alcuni browser mostrano nella barra i termini cercati al posto dell'indirizzo.
                    SearchQueryClassifier.classify(text)?.let { return it }
                }
            }
        }

        // App di ricerca (App Google, widget della Home): nella pagina dei risultati la ricerca è
        // mostrata in una casella di sola lettura con un id noto, e nel titolo della pagina web.
        SEARCH_BOX_IDS[pkg]?.forEach { id ->
            val box = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
            val text = box?.text?.toString()?.trim().orEmpty()
            val focused = box?.isFocused == true
            release(box)
            if (text.isNotEmpty()) SearchQueryClassifier.classify(text, typing = focused)?.let { return it }
        }
        if (pkg in SEARCH_APPS) {
            findWebViewTitle(root, 0)?.let { title ->
                val query = SEARCH_TITLE_SUFFIXES.firstOrNull { title.endsWith(it) }?.let { title.removeSuffix(it) }
                if (query != null) SearchQueryClassifier.classify(query)?.let { return it }
            }
        }

        if (!onSearchEngine) return null
        val fields = ArrayList<AccessibilityNodeInfo>(4)
        collectEditable(root, fields, 0, urlBarId)
        for (field in fields) {
            val text = field.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() || isHint(field)) continue
            SearchQueryClassifier.classify(text, typing = field.isFocused)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // 4) App: ricerche, pubblicità, contenuti, schede del Play Store, app dedicate
    // ---------------------------------------------------------------------------------------

    private class Screen(val segments: ArrayList<String>, val searches: ArrayList<Pair<String, Boolean>>, val hash: Int)

    /**
     * Un solo passaggio sull'albero dei nodi visibili: raccoglie i testi (text e contentDescription)
     * come segmenti, individua le caselle di ricerca e calcola l'hash della schermata. I testi che
     * l'utente sta scrivendo in una casella che non è di ricerca (un messaggio, un commento) non
     * vengono raccolti.
     */
    private fun readScreen(root: AccessibilityNodeInfo): Screen {
        val segments = ArrayList<String>(64)
        val searches = ArrayList<Pair<String, Boolean>>(2)
        var hash = 17
        var visited = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (!node.isVisibleToUser) {
                release(node)
                continue
            }
            val editable = node.isEditable
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && !(editable && isHint(node))) {
                hash = hash * 31 + text.hashCode()
                val hint = if (android.os.Build.VERSION.SDK_INT >= 26) node.hintText?.toString() else null
                if (text.length <= MAX_QUERY_CHARS &&
                    InAppContentAndAdFilter.isSearchField(node.viewIdResourceName, hint, editable)
                ) {
                    searches.add(text to node.isFocused)
                } else if (!editable && segments.size < MAX_SEGMENTS) {
                    segments.add(if (text.length > MAX_SEGMENT_CHARS) text.substring(0, MAX_SEGMENT_CHARS) else text)
                }
            }
            if (!editable) {
                val description = node.contentDescription?.toString()?.trim()
                if (!description.isNullOrEmpty() && description != text && segments.size < MAX_SEGMENTS) {
                    hash = hash * 31 + description.hashCode()
                    segments.add(if (description.length > MAX_SEGMENT_CHARS) description.substring(0, MAX_SEGMENT_CHARS) else description)
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
            if (node !== root) release(node)
        }
        return Screen(segments, searches, hash)
    }

    private fun inspectApp(pkg: String, now: Long) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val screen = readScreen(root)

        // Stessa schermata del controllo precedente: nessuna analisi.
        val hash = screen.hash * 31 + pkg.hashCode()
        if (hash == lastScreenHash) return
        lastScreenHash = hash

        // a) Ricerca scritta in una casella di ricerca dell'app (o mostrata nei risultati).
        for ((text, focused) in screen.searches) {
            val hit = SearchQueryClassifier.classify(text, typing = focused) ?: continue
            blockAppSearch(pkg, hit, now)
            return
        }

        // b) Play Store: scheda di un'app di trading, scommesse o prestiti.
        if (pkg in STORE_PACKAGES) {
            InAppContentAndAdFilter.classifyStorePage(screen.segments)?.let {
                blockInApp(pkg, it, now)
                return
            }
        }

        // c) Pubblicità a rischio, poi contenuti dedicati (titoli di video, post, articoli).
        val hit = InAppContentAndAdFilter.findAd(screen.segments)
            ?: InAppContentAndAdFilter.findContent(screen.segments)
            ?: return
        blockInApp(pkg, hit, now)
    }

    /** Indietro; se dopo 400 ms la ricerca è ancora a schermo (si era solo chiusa la tastiera), Home. */
    private fun blockAppSearch(pkg: String, hit: SearchHit, now: Long) {
        val stillThere = now - lastSearchBlockAt < SEARCH_ESCALATION_MS
        lastSearchBlockAt = now
        lastScreenHash = 0
        app.securityLog.grouped(
            "search:$pkg:${hit.query}", EventType.SEARCH, Severity.WARNING,
            "Ricerca bloccata su ${appName(pkg)}: \"${hit.query}\"",
            "Categoria: ${hit.category.label} · termine: ${hit.term}"
        )
        performGlobalAction(if (stillThere) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        if (!stillThere) handler.postDelayed({ scheduleScan(pkg, SystemClock.elapsedRealtime(), immediate = true) }, 400)
        Toast.makeText(this, "SafeGuard: ricerca bloccata (${hit.category.label})", Toast.LENGTH_SHORT).show()
    }

    /**
     * Scala di azioni: la prima volta si prova la più leggera (scorrere oltre un annuncio o un
     * post nei feed, Indietro negli altri casi); se entro pochi secondi il contenuto è ancora lì
     * si passa a Indietro e infine alla Home.
     */
    private fun blockInApp(pkg: String, hit: InAppHit, now: Long) {
        val level = if (now - lastInAppBlockAt < IN_APP_ESCALATION_MS) inAppLevel + 1 else 0
        inAppLevel = level
        lastInAppBlockAt = now
        lastScreenHash = 0
        val name = appName(pkg)
        val title = when (hit.kind) {
            InAppKind.AD -> "Pubblicità bloccata su $name"
            InAppKind.STORE_PAGE -> "Scheda di un'app bloccata su $name"
            InAppKind.CONTENT -> "Contenuto bloccato su $name"
        }
        app.securityLog.grouped(
            "inapp:$pkg:${hit.kind}:${hit.term}", EventType.CONTENT, Severity.WARNING,
            title, "${hit.category.label} · \"${hit.excerpt}\""
        )
        val scrollable = hit.kind == InAppKind.AD || (hit.kind == InAppKind.CONTENT && pkg in FEED_PACKAGES)
        when {
            level >= 2 -> performGlobalAction(GLOBAL_ACTION_HOME)
            level == 0 && scrollable && swipeForward() -> Unit
            else -> performGlobalAction(GLOBAL_ACTION_BACK)
        }
        handler.postDelayed({ scheduleScan(pkg, SystemClock.elapsedRealtime(), immediate = true) }, 600)
        Toast.makeText(this, "SafeGuard: $title", Toast.LENGTH_SHORT).show()
    }

    /** Scorrimento rapido verso l'alto: fa uscire dallo schermo l'annuncio o il video del feed. */
    private fun swipeForward(): Boolean = try {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.74f)
            lineTo(x, metrics.heightPixels * 0.22f)
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 120)).build(), null, null
        )
    } catch (e: Exception) {
        false
    }

    /** Categoria per cui l'app è bloccata, o null. Il risultato è in cache finché la whitelist non cambia. */
    private fun blockedAppCategory(pkg: String): ContentCategory? {
        val filter = app.filterStore.current
        if (filter !== packageVerdictsFilter) {
            packageVerdictsFilter = filter
            packageVerdicts.clear()
        }
        packageVerdicts[pkg]?.let { return it.orElse(null) }
        val allowed = filter.whitelistedDomains.mapTo(HashSet()) { BlockedPackages.labelOfDomain(it) }
        val category = BlockedPackages.categoryOf(pkg, appName(pkg).takeIf { it != pkg }, allowed)
        packageVerdicts[pkg] = java.util.Optional.ofNullable(category)
        return category
    }

    private fun blockApp(pkg: String, category: ContentCategory, now: Long) {
        if (now - lastAppBlockAt < RELOCK_THROTTLE_MS) return
        lastAppBlockAt = now
        val name = appName(pkg)
        app.securityLog.grouped(
            "app:$pkg", EventType.APP, Severity.CRITICAL,
            "App bloccata all'apertura: $name", "Categoria: ${category.label} · $pkg"
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(LockScreenActivity.blockedAppIntent(this, name))
    }

    /** Nome dell'app mostrato all'utente ("YouTube"), con cache; il nome del package se non è leggibile. */
    private fun appName(pkg: String): String = appNames.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    /** Sui vecchi Android i nodi vanno restituiti al pool; da Android 13 è un'operazione vuota. */
    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo?) {
        if (node != null && android.os.Build.VERSION.SDK_INT < 33) {
            try {
                node.recycle()
            } catch (e: IllegalStateException) {
                // già restituito
            }
        }
    }

    /** Titolo della prima WebView della schermata (es. "trading online - Google Search"). */
    private fun findWebViewTitle(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 12) return null
        if (node.className == "android.webkit.WebView") return node.text?.toString()
        for (i in 0 until node.childCount) findWebViewTitle(node.getChild(i), depth + 1)?.let { return it }
        return null
    }

    private fun isHint(node: AccessibilityNodeInfo): Boolean =
        android.os.Build.VERSION.SDK_INT >= 26 && node.isShowingHintText

    private fun collectEditable(
        node: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int, skipId: String?
    ) {
        if (node == null || depth > MAX_DEPTH || out.size >= MAX_EDITABLE_FIELDS) return
        if (node.isEditable && node.viewIdResourceName != skipId) out.add(node)
        for (i in 0 until node.childCount) collectEditable(node.getChild(i), out, depth + 1, skipId)
    }

    /** Esce dal motore di ricerca: Indietro (chiude la tastiera o la pagina dei risultati), poi Home. */
    private fun blockSearch(hit: SearchHit, now: Long, pkg: String) {
        lastContentBlockAt = now
        // Nel log solo la categoria: il testo cercato non viene mai registrato.
        android.util.Log.i("SafeGuardSearch", "ricerca bloccata: ${hit.category}")
        lastScanKey = null
        app.securityLog.grouped(
            "search:${hit.query}", EventType.SEARCH, Severity.WARNING,
            "Ricerca bloccata su ${appName(pkg)}: \"${hit.query}\"", "Categoria: ${hit.category.label} · termine: ${hit.term}"
        )
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 250)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 500)
        Toast.makeText(this, "SafeGuard: ricerca bloccata (${hit.category.label})", Toast.LENGTH_SHORT).show()
    }

    /** Dominio mostrato nella barra degli indirizzi, o null se non leggibile o in fase di digitazione. */
    private fun readBrowserHost(root: AccessibilityNodeInfo, urlBarId: String): String? {
        val node = root.findAccessibilityNodeInfosByViewId(urlBarId)?.firstOrNull() ?: return null
        if (node.isFocused) return null                    // l'utente sta scrivendo: non è ancora un URL
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.contains(' ')) return null
        return DomainRepository.normalizeDomain(text)
    }

    /**
     * Esce dal contenuto: prima con Indietro; se entro pochi secondi si ricade su un altro
     * contenuto da bloccare (es. la pagina precedente è dello stesso sito) si va alla Home.
     */
    private fun blockContent(reason: String, now: Long, where: String = "") {
        val repeated = now - lastContentBlockAt < REPEAT_BLOCK_WINDOW_MS
        lastContentBlockAt = now
        lastScanKey = null
        app.securityLog.grouped(
            "content:$reason:$where", EventType.CONTENT, Severity.WARNING,
            "Pagina bloccata per i contenuti", if (where.isEmpty()) reason else "$where · $reason"
        )
        performGlobalAction(if (repeated) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        Toast.makeText(this, "SafeGuard: contenuto bloccato ($reason)", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------------------
    // Supporto
    // ---------------------------------------------------------------------------------------

    /** Visita in profondità l'albero dei nodi raccogliendo testi e content description. */
    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int, maxChars: Int) {
        if (node == null || depth > MAX_DEPTH || out.length > maxChars) return
        node.text?.let { out.append(it).append('\n') }
        node.contentDescription?.let { out.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1, maxChars)
        }
    }

    /** Package su cui non si analizzano mai i contenuti. */
    private fun buildIgnoredPackages(): Set<String> {
        val ignored = HashSet(ALWAYS_IGNORED)
        ignored.add(packageName)
        // Ogni lettura è protetta: un'eccezione qui impedirebbe al servizio di partire.
        // Launcher
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0).forEach { ignored.add(it.activityInfo.packageName) }
        } catch (e: Exception) {
            android.util.Log.w("SafeGuardGuard", "Elenco dei launcher non leggibile: ${e.message}")
        }
        // Tastiere: tutte quelle abilitate (l'impostazione ENABLED_INPUT_METHODS non è più
        // leggibile dalle app che puntano ad Android 14+, si usa InputMethodManager).
        try {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.enabledInputMethodList?.forEach { ignored.add(it.packageName) }
        } catch (e: Exception) {
            android.util.Log.w("SafeGuardGuard", "Elenco delle tastiere non leggibile: ${e.message}")
        }
        try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.let { ignored.add(it) }
        } catch (e: Exception) {
            // la tastiera di Google e le più diffuse sono comunque nell'elenco fisso
        }
        ignored.removeAll(SETTINGS_PACKAGES)
        return ignored
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: SettingsGuardAccessibilityService? = null

        private const val RELOCK_THROTTLE_MS = 800L
        private const val SCAN_DEBOUNCE_MS = 350L
        private const val SCAN_IMMEDIATE_MS = 40L
        private const val SEARCH_ESCALATION_MS = 1_500L
        private const val IN_APP_ESCALATION_MS = 5_000L
        private const val MAX_NODES = 600
        private const val MAX_SEGMENTS = 400
        private const val MAX_SEGMENT_CHARS = 1_200
        private const val MAX_QUERY_CHARS = 120
        private const val SCAN_MAX_WAIT_MS = 1_200L
        private const val RESCAN_WINDOW_MS = 6_000L
        private const val INTEGRITY_INTERVAL_MS = 15_000L
        private const val REPEAT_BLOCK_WINDOW_MS = 4_000L
        private const val FLAGS_CACHE_MS = 3_000L
        private const val MAX_DEPTH = 40
        private const val SETTINGS_TEXT_CHARS = 6_000
        private const val CONTENT_TEXT_CHARS = 12_000
        private const val MAX_EDITABLE_FIELDS = 6

        /** App di ricerca: qui si controllano le caselle di testo anche senza barra degli indirizzi. */
        private val SEARCH_APPS = setOf(
            "com.google.android.googlequicksearchbox",   // app Google / widget di ricerca
            "com.google.android.apps.searchlite",        // Google Go
            "com.microsoft.bing",
            "com.ecosia.android",
            "com.qwant.liberty",
        )

        /** App di sistema da cui si può disattivare SafeGuard: qui agisce il Guardiano. */
        private val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",          // non esiste su tutti i Samsung, innocuo
            "com.miui.securitycenter",               // "Info app" su Xiaomi/MIUI
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.android.vpndialogs",                // dialogo di sistema per scollegare la VPN
        )

        /**
         * Barra degli indirizzi dei browser supportati (resource-id della vista).
         * Negli altri browser il blocco dei contenuti funziona comunque, ma senza auto-apprendimento.
         */
        private val BROWSER_URL_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.focus" to "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.opera.mini.native" to "com.opera.mini.native:id/url_field",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
        )

        /**
         * Sistema e app di comunicazione personale: una chat in cui un amico scrive "schedina"
         * non deve far uscire l'utente dalla conversazione.
         */
        private val ALWAYS_IGNORED = setOf(
            "android", "com.android.systemui", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller", "com.google.android.dialer", "com.android.dialer",
            "com.google.android.contacts", "com.samsung.android.dialer", "com.android.phone",
            "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms",
            "com.google.android.gm", "com.microsoft.office.outlook",
            // tastiere, fotocamera, galleria, orologio, calcolatrice
            "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard", "com.touchtype.swiftkey",
            "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera", "com.sec.android.app.camera",
            "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.google.android.deskclock",
            "com.google.android.calculator", "com.android.documentsui", "com.google.android.apps.nexuslauncher",
            "com.google.android.gms", "com.android.vpndialogs.unused",
        )

        /** Store di app: qui si controlla anche la scheda dell'app prima che venga installata. */
        private val STORE_PACKAGES = setOf(
            "com.android.vending", "com.huawei.appmarket", "com.sec.android.app.samsungapps",
            "com.xiaomi.mipicks", "com.amazon.venezia", "com.aurora.store", "org.fdroid.fdroid",
        )

        /** Feed a scorrimento: annunci e post a rischio vengono prima scavalcati con uno scorrimento. */
        private val FEED_PACKAGES = setOf(
            "com.google.android.youtube", "com.instagram.android", "com.instagram.lite", "com.facebook.katana",
            "com.facebook.lite", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.twitter.android",
            "com.reddit.frontpage", "com.snapchat.android", "com.pinterest", "com.linkedin.android",
            "com.google.android.apps.magazines", "flipboard.app", "tv.twitch.android.app", "com.vkontakte.android",
            "org.telegram.messenger", "com.threads.android", "com.instagram.barcelona",
        )

        private const val HANDLED_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED

        /** Caselle (anche di sola lettura) in cui le app di ricerca mostrano la ricerca in corso. */
        private val SEARCH_BOX_IDS = mapOf(
            "com.google.android.googlequicksearchbox" to listOf(
                "com.google.android.googlequicksearchbox:id/googleapp_srp_search_box_text",
                "com.google.android.googlequicksearchbox:id/googleapp_search_box",
                "com.google.android.googlequicksearchbox:id/search_box",
            ),
        )

        /** Suffissi del titolo della pagina dei risultati di Google nelle varie lingue. */
        private val SEARCH_TITLE_SUFFIXES = listOf(
            " - Google Search", " - Cerca con Google", " - Ricerca Google", " - Google Suche",
            " - Recherche Google", " - Buscar con Google",
        )

        /** Tutti e soli i pacchetti di cui il servizio riceve gli eventi. */
        private val RELEVANT_PACKAGES: Set<String> =
            HashSet<String>().apply {
                addAll(SETTINGS_PACKAGES)
                addAll(BROWSER_URL_BARS.keys)
                addAll(SEARCH_APPS)
            }

        private val SENSITIVE_CLASS_HINTS = listOf("DeviceAdmin", "VpnSettings", "UninstallerActivity")

        private val STANDALONE_TITLES = listOf(
            // Amministratori dispositivo
            "amministratori dispositivo", "amministratore dispositivo", "app di amministrazione",
            "device admin", "device administrators",
            // Dialogo "DNS privato"
            "private dns provider hostname", "select private dns mode",
            "nome host del provider dns privato", "nome host del provider di dns privato",
            "hostname del provider dns privato", "seleziona modalità dns privato",
            "seleziona la modalità dns privato",
        )

        /** Voci presenti solo nella pagina generale "Rete e Internet". */
        private val NETWORK_DASHBOARD_HINTS = listOf("hotspot", "airplane mode", "modalità aereo")

        private val DANGEROUS_CONTEXT = listOf(
            // Info app
            "disinstalla", "uninstall", "forza interruzione", "forza arresto", "termina", "force stop",
            "cancella dati", "clear data", "clear storage", "elimina dati",
            // VPN
            "vpn",
            // Accessibilità
            "accessibilit", "accessibility",
            // Amministratore dispositivo
            "disattiva", "deactivate",
        )

        /** Riporta l'utente alla Home; con fallback se il servizio non è connesso. */
        fun goHome(context: Context) {
            val service = instance
            if (service == null || !service.performGlobalAction(GLOBAL_ACTION_HOME)) {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        /** true se l'utente ha attivato questo servizio in Impostazioni -> Accessibilità. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, SettingsGuardAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
