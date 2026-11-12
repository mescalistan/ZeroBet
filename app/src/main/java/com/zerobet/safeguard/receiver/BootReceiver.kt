package com.zerobet.safeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.vpn.DomainBlockerVpnService

/**
 * Riavvia il filtro DNS all'accensione del telefono (e dopo un aggiornamento dell'app),
 * a patto che il wizard sia stato completato e che il tutore non abbia messo in pausa la protezione.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }
        try {
            val pinManager = (context.applicationContext as SafeGuardApp).pinManager
            if (!pinManager.onboardingDone || !pinManager.protectionEnabled) return
            // prepare() == null significa che il consenso VPN è ancora valido.
            if (VpnService.prepare(context) == null) {
                DomainBlockerVpnService.start(context)
            } else {
                Log.w("SafeGuardBoot", "Consenso VPN revocato: il filtro non può ripartire da solo")
            }
        } catch (e: Exception) {
            Log.e("SafeGuardBoot", "Riavvio del filtro fallito", e)
        }
    }
}
