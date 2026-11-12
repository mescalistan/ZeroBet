package com.zerobet.safeguard.guard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zerobet.safeguard.R
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.ui.LockScreenActivity

/**
 * Guardiano Impostazioni.
 *
 * Osserva i cambi di finestra delle sole app di sistema da cui si può disattivare SafeGuard
 * (Impostazioni e package installer). Quando la schermata visibile è una di quelle "sensibili":
 *   - Info app di SafeGuard (Disinstalla / Forza interruzione / Cancella dati)
 *   - Amministratori dispositivo
 *   - Impostazioni VPN
 *   - Accessibilità -> SafeGuard (altrimenti basterebbe spegnere il Guardiano stesso)
 *   - Dialogo di disinstallazione di SafeGuard
 * mostra la LockScreenActivity che richiede il PIN del tutore. Senza PIN l'utente viene
 * riportato alla Home con GLOBAL_ACTION_HOME.
 *
 * Il servizio legge esclusivamente i testi di queste schermate di sistema per riconoscerle:
 * non registra né trasmette nulla.
 */
class SettingsGuardAccessibilityService : AccessibilityService() {

    private lateinit var appLabel: String
    private var lastLockAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        appLabel = getString(R.string.app_name).lowercase()
        // Limita gli eventi ricevuti ai soli package rilevanti (privacy + batteria).
        serviceInfo = serviceInfo.apply { packageNames = WATCHED_PACKAGES.toTypedArray() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in WATCHED_PACKAGES) return

        val pinManager = (application as SafeGuardApp).pinManager
        // Il Guardiano entra in funzione solo a wizard completato (durante l'onboarding il
        // tutore deve poter concedere i permessi) e resta in silenzio nella finestra di grazia.
        if (!pinManager.onboardingDone || GuardState.inGrace || GuardState.lockScreenVisible) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLockAt < RELOCK_THROTTLE_MS) return

        if (isSensitiveScreen(event)) {
            lastLockAt = now
            showLockScreen()
        }
    }

    /** Riconosce le schermate sensibili dal nome della classe della finestra e dai testi visibili. */
    private fun isSensitiveScreen(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString().orEmpty()
        // Nomi di Activity/Fragment AOSP: DeviceAdminSettings, DeviceAdminAdd, VpnSettings...
        if (SENSITIVE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) return true

        val texts = StringBuilder()
        event.text.forEach { texts.append(it).append('\n') }
        collectText(rootInActiveWindow, texts, depth = 0)
        val screen = texts.toString().lowercase()

        // Schermata "Amministratori dispositivo" (titolo localizzato)
        if (DEVICE_ADMIN_TITLES.any { screen.contains(it) }) return true

        // Tutte le altre schermate sensibili mostrano il nome dell'app insieme a un'azione
        // o a un contesto pericoloso. Il solo nome (es. nell'elenco generale delle app) non basta.
        if (screen.contains(appLabel) && DANGEROUS_CONTEXT.any { screen.contains(it) }) return true

        return false
    }

    /** Visita in profondità l'albero dei nodi raccogliendo testi e content description. */
    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        if (node == null || depth > MAX_DEPTH || out.length > MAX_TEXT_CHARS) return
        node.text?.let { out.append(it).append('\n') }
        node.contentDescription?.let { out.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, depth + 1)
        }
    }

    private fun showLockScreen() {
        startActivity(
            Intent(this, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: SettingsGuardAccessibilityService? = null

        private const val RELOCK_THROTTLE_MS = 800L
        private const val MAX_DEPTH = 25
        private const val MAX_TEXT_CHARS = 6000

        private val WATCHED_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",          // non esiste su tutti i Samsung, innocuo
            "com.miui.securitycenter",               // "Info app" su Xiaomi/MIUI
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.android.vpndialogs",                // dialogo di sistema per scollegare la VPN
        )

        private val SENSITIVE_CLASS_HINTS = listOf("DeviceAdmin", "VpnSettings", "UninstallerActivity")

        private val DEVICE_ADMIN_TITLES = listOf(
            "amministratori dispositivo", "amministratore dispositivo", "app di amministrazione",
            "device admin", "device administrators",
        )

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
