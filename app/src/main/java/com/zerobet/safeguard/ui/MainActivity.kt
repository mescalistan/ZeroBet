package com.zerobet.safeguard.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zerobet.safeguard.ui.components.PinVerify
import com.zerobet.safeguard.ui.theme.SafeGuardTheme

/**
 * Punto d'ingresso della UI:
 *   wizard non completato -> OnboardingScreen
 *   wizard completato     -> richiesta PIN -> DashboardScreen
 */
class MainActivity : ComponentActivity() {

    /** La dashboard resta sbloccata solo finché l'Activity è visibile. */
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SafeGuardTheme {
                Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
                    val vm: MainViewModel = viewModel()
                    var onboardingDone by remember { mutableStateOf(vm.pinManager.onboardingDone) }

                    when {
                        !onboardingDone -> OnboardingScreen(vm, onFinished = {
                            onboardingDone = true
                            unlocked = true
                        })

                        !unlocked -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PinVerify(
                                pinManager = vm.pinManager,
                                title = "Area riservata al tutore",
                                subtitle = "Inserisci il PIN a 6 cifre per accedere alla configurazione",
                                onSuccess = { unlocked = true }
                            )
                        }

                        else -> DashboardScreen(vm, onLock = { unlocked = false })
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Uscendo dall'app (o aprendo una schermata di sistema) la dashboard si richiude:
        // al rientro verrà richiesto di nuovo il PIN.
        if (!isChangingConfigurations) unlocked = false
    }
}
