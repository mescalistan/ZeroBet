package com.zerobet.safeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.guard.IntegrityChecker
import com.zerobet.safeguard.security.DeviceStorage
import com.zerobet.safeguard.vpn.DomainBlockerVpnService

/**
 * Riavvia il filtro DNS all'accensione del telefono e dopo un aggiornamento dell'app.
 *
 * È directBootAware: riceve LOCKED_BOOT_COMPLETED appena il sistema è partito, PRIMA che l'utente
 * inserisca il codice di sblocco, così il filtro è attivo già dalla schermata di blocco. Tutto ciò
 * che legge (PIN, stato della protezione, database) sta nello storage protetto dal dispositivo.
 * BOOT_COMPLETED arriva dopo il primo sblocco: lì si completano le parti che richiedono lo storage
 * dell'utente (WorkManager) e si ricontrolla che i servizi siano attivi.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        try {
            val app = context.applicationContext as SafeGuardApp
            if (!DeviceStorage.isReady(context)) {
                Log.w(TAG, "Dati non ancora nello storage protetto: si attende il primo sblocco")
                return
            }
            if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                app.securityLog.warning(EventType.POWER, "Telefono riavviato", "Avvio completato a schermo bloccato")
            }
            if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) app.onUserUnlocked()

            val pinManager = app.pinManager
            if (!pinManager.onboardingDone || !pinManager.protectionEnabled) return
            // prepare() == null significa che il consenso VPN è ancora valido.
            if (VpnService.prepare(context) == null) {
                DomainBlockerVpnService.start(context)
            } else {
                Log.w(TAG, "Consenso VPN revocato: il filtro non può ripartire da solo")
            }
            if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED) IntegrityChecker.check(context, "avvio")
        } catch (e: Exception) {
            Log.e(TAG, "Riavvio del filtro fallito", e)
        }
    }

    private companion object {
        const val TAG = "SafeGuardBoot"
        val HANDLED = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
