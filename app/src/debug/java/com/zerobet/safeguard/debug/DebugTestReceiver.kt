package com.zerobet.safeguard.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.receiver.SimStateReceiver
import com.zerobet.safeguard.receiver.SimStateTracker

/**
 * Hook per le prove strumentate sull'emulatore. Esiste SOLO nelle build di debug (source set
 * `src/debug`): la build di rilascio non lo contiene.
 *
 * È protetto dal permesso `android.permission.DUMP`, che hanno la shell adb e il sistema ma non
 * le app installate: un'altra app sul telefono non può usarlo.
 *
 *   adb shell am broadcast -a com.zerobet.safeguard.debug.SET_PIN --es pin <6 cifre> -n com.zerobet.safeguard/.debug.DebugTestReceiver
 *   adb shell am broadcast -a com.zerobet.safeguard.debug.SIM --es state ABSENT|PRESENT --ei slot 0 -n com.zerobet.safeguard/.debug.DebugTestReceiver
 *
 * La SIM dell'emulatore non si può togliere: SIM simula lo stato riportato da SIM_STATE_CHANGED
 * (broadcast protetto, che la shell non può inviare) passando per la stessa logica del receiver.
 */
class DebugTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SafeGuardApp
        when (intent.action) {
            ACTION_SET_PIN -> {
                val pin = intent.getStringExtra("pin").orEmpty()
                val ok = runCatching { app.pinManager.setPin(pin) }.isSuccess
                Log.i(TAG, if (ok) "PIN di test impostato" else "PIN di test non valido")
                resultData = if (ok) "ok" else "invalid"
            }
            ACTION_SIM -> {
                val state = intent.getStringExtra("state").orEmpty().uppercase()
                val presence = SimStateTracker.presence(state)
                if (presence == null) {
                    resultData = "invalid"
                    return
                }
                SimStateReceiver.applyPresence(app, intent.getIntExtra("slot", 0).coerceIn(0, 3), presence)
                Log.i(TAG, "Stato SIM simulato: $presence")
                resultData = "ok"
            }
        }
    }

    private companion object {
        const val TAG = "SafeGuardDebug"
        const val ACTION_SET_PIN = "com.zerobet.safeguard.debug.SET_PIN"
        const val ACTION_SIM = "com.zerobet.safeguard.debug.SIM"
    }
}
