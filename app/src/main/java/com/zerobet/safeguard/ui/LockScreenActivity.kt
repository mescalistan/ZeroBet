package com.zerobet.safeguard.ui

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pinManager = (application as SafeGuardApp).pinManager

        onBackPressedDispatcher.addCallback(this) { leaveToHome() }

        setContent {
            SafeGuardTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PinVerify(
                            pinManager = pinManager,
                            title = "Impostazione protetta",
                            subtitle = "Questa sezione può disattivare SafeGuard.\nÈ richiesto il PIN del tutore.",
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
}
