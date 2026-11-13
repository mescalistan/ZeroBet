package com.zerobet.safeguard.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zerobet.safeguard.guard.IntegrityChecker
import com.zerobet.safeguard.vpn.DomainBlockerVpnService
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.guard.GuardState
import com.zerobet.safeguard.guard.SettingsGuardAccessibilityService
import com.zerobet.safeguard.ui.components.PinVerify
import com.zerobet.safeguard.ui.theme.SafeGuardTheme

/**
 * Schermata a tutto schermo lanciata dal Guardiano sopra le Impostazioni di sistema.
 *  - PIN corretto: concede una finestra di grazia e si chiude, lasciando proseguire il tutore.
 *  - Qualsiasi altra uscita (Indietro, Annulla, Home, Recenti): l'utente viene riportato alla Home.
 */
class LockScreenActivity : ComponentActivity() {

    private var pinAccepted = false

    /** Non null quando la schermata è stata aperta dal controllo di integrità. */
    private var problem by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pinManager = (application as SafeGuardApp).pinManager
        problem = intent.getStringExtra(EXTRA_PROBLEM)

        val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pinManager.protectionEnabled = true
                DomainBlockerVpnService.start(this)
                pinAccepted = true
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(this) { leaveToHome() }

        setContent {
            SafeGuardTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val blockedApp = problem?.takeIf { it.startsWith(APP_PREFIX) }?.removePrefix(APP_PREFIX)
                        val issue = problem?.takeIf { blockedApp == null }
                        if (issue != null) {
                            Text(
                                if (issue == IntegrityChecker.PROBLEM_VPN) "Il filtro di rete di SafeGuard è stato spento."
                                else "Il Guardiano di SafeGuard è stato disattivato.",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(onClick = {
                                // Riattivare la protezione non richiede il PIN: serve solo per lasciarla spenta.
                                GuardState.grant(GuardState.GRACE_AFTER_PIN_MS)
                                if (issue == IntegrityChecker.PROBLEM_VPN) {
                                    val consent = VpnService.prepare(this@LockScreenActivity)
                                    if (consent != null) vpnLauncher.launch(consent) else {
                                        DomainBlockerVpnService.start(this@LockScreenActivity)
                                        pinAccepted = true
                                        finish()
                                    }
                                } else {
                                    pinAccepted = true
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    finish()
                                }
                            }) { Text("Riattiva ora la protezione") }
                        }
                        PinVerify(
                            pinManager = pinManager,
                            title = if (blockedApp != null) "App non consentita" else if (issue != null) "Protezione disattivata" else "Impostazione protetta",
                            subtitle = if (blockedApp != null) "$blockedApp è bloccata da SafeGuard.\nSolo il tutore può aprirla, con il PIN."
                            else if (issue != null) "Riattiva il servizio qui sopra per continuare a usare il telefono,\noppure inserisci il PIN del tutore."
                            else "Questa sezione può disattivare SafeGuard.\nÈ richiesto il PIN del tutore.",
                            onSuccess = {
                                pinAccepted = true
                                GuardState.grant(GuardState.GRACE_AFTER_PIN_MS)
                                finish()
                            }
                        )
                        TextButton(onClick = { leaveToHome() }) { Text("Annulla e torna alla Home") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        problem = intent.getStringExtra(EXTRA_PROBLEM)
    }

    override fun onStart() {
        super.onStart()
        GuardState.lockScreenVisible = true
    }

    override fun onStop() {
        super.onStop()
        GuardState.lockScreenVisible = false
        // Se la schermata sparisce senza PIN (es. tasto Recenti) non si resta sulle Impostazioni.
        if (!pinAccepted && !isChangingConfigurations) leaveToHome()
    }

    private fun leaveToHome() {
        SettingsGuardAccessibilityService.goHome(this)
        if (!isFinishing) finish()
    }

    companion object {
        private const val EXTRA_PROBLEM = "problem"
        private const val APP_PREFIX = "app:"

        /** Avviso mostrato quando viene aperta un'app di trading, scommesse o prestiti. */
        fun blockedAppIntent(context: Context, appLabel: String): Intent = integrityIntent(context, APP_PREFIX + appLabel)

        /** Intent per la modalità "servizio obbligatorio spento" (vedi IntegrityChecker). */
        fun integrityIntent(context: Context, problem: String): Intent =
            Intent(context, LockScreenActivity::class.java)
                .putExtra(EXTRA_PROBLEM, problem)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
