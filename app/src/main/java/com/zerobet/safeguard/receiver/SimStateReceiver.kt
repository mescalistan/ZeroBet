package com.zerobet.safeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.security.DeviceStorage

/** Evento da scrivere nel registro in seguito a un cambio di stato della SIM. */
data class SimEvent(val title: String, val detail: String)

/**
 * Logica (Kotlin puro, coperta da unit test) che decide quando un cambio di stato della SIM è
 * un evento da registrare. Gli stati di Android vengono ridotti a "presente" / "assente"; gli
 * stati transitori (NOT_READY, UNKNOWN) non contano, e i passaggi interni a "presente"
 * (LOCKED -> READY -> LOADED a ogni accensione) non generano allarmi.
 */
object SimStateTracker {
    const val PRESENT = "PRESENT"
    const val ABSENT = "ABSENT"

    /** @return [PRESENT], [ABSENT] oppure null per gli stati transitori. */
    fun presence(state: String?): String? = when (state?.uppercase()) {
        "ABSENT", "CARD_IO_ERROR", "CARD_RESTRICTED" -> ABSENT
        "READY", "LOADED", "IMSI", "LOCKED", "PIN_REQUIRED", "PUK_REQUIRED", "NETWORK_LOCKED", "PRESENT" -> PRESENT
        else -> null
    }

    /** Stati numerici di TelephonyManager.getSimState(). */
    fun presence(state: Int): String? = when (state) {
        1 -> ABSENT                    // SIM_STATE_ABSENT
        2, 3, 4, 5, 10 -> PRESENT      // PIN/PUK richiesto, bloccata dalla rete, READY, PRESENT
        8, 9 -> ABSENT                 // CARD_IO_ERROR, CARD_RESTRICTED
        else -> null                   // UNKNOWN, NOT_READY, PERM_DISABLED
    }

    /** @param previous ultimo valore noto per lo slot, null alla prima osservazione (nessun allarme). */
    fun presenceEvent(previous: String?, current: String, slot: Int): SimEvent? {
        if (previous == null || previous == current) return null
        val where = "Slot SIM ${slot + 1}"
        return if (current == ABSENT) SimEvent("SIM rimossa dal dispositivo", where)
        else SimEvent("SIM inserita o riattivata", where)
    }

    fun operatorEvent(previous: String?, current: String?): SimEvent? {
        if (previous.isNullOrBlank() || current.isNullOrBlank() || previous == current) return null
        return SimEvent("Operatore della SIM cambiato", "$previous → $current")
    }
}

/**
 * Riceve i cambi di stato della SIM (anche a schermo bloccato dopo un riavvio: è directBootAware)
 * e li scrive nel Registro Allarmi come eventi critici. Funziona offline: non usa la rete.
 */
class SimStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!DeviceStorage.isReady(context)) return
        val app = context.applicationContext as SafeGuardApp
        try {
            when (intent.action) {
                ACTION_SIM_STATE_CHANGED -> {
                    val slot = intent.getIntExtra("phone", intent.getIntExtra("slot", 0)).coerceIn(0, 3)
                    val presence = SimStateTracker.presence(intent.getStringExtra("ss")) ?: return
                    if (presence == SimStateTracker.ABSENT) {
                        // All'accensione alcuni telefoni segnalano "assente" per un istante prima
                        // di leggere la SIM: si conferma dopo qualche secondo prima di dare l'allarme.
                        Handler(Looper.getMainLooper()).postDelayed({ confirmAbsent(app, slot) }, CONFIRM_DELAY_MS)
                    } else {
                        apply(app, slot, presence)
                    }
                }
                ACTION_CARRIER_CONFIG_CHANGED -> checkOperator(app)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Evento SIM non gestito", e)
        }
    }

    companion object {
        private const val TAG = "SafeGuardSim"
        const val ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED"
        const val ACTION_CARRIER_CONFIG_CHANGED = "android.telephony.action.CARRIER_CONFIG_CHANGED"
        private const val CONFIRM_DELAY_MS = 8_000L

        private fun confirmAbsent(app: SafeGuardApp, slot: Int) {
            val telephony = app.getSystemService(TelephonyManager::class.java)
            val state = try {
                telephony?.getSimState(slot) ?: TelephonyManager.SIM_STATE_ABSENT
            } catch (e: Exception) {
                TelephonyManager.SIM_STATE_ABSENT
            }
            if (SimStateTracker.presence(state) != SimStateTracker.PRESENT) apply(app, slot, SimStateTracker.ABSENT)
        }

        private fun apply(app: SafeGuardApp, slot: Int, presence: String) {
            val prefs = DeviceStorage.statePrefs(app)
            val key = "sim_presence_$slot"
            val previous = prefs.getString(key, null)
            if (previous == presence) return
            prefs.edit().putString(key, presence).apply()
            SimStateTracker.presenceEvent(previous, presence, slot)?.let {
                app.securityLog.critical(EventType.SIM, it.title, it.detail)
            }
            if (presence == SimStateTracker.PRESENT) checkOperator(app)
        }

        /** Confronta l'operatore della SIM con l'ultimo noto (non richiede permessi). */
        fun checkOperator(app: SafeGuardApp) {
            val telephony = app.getSystemService(TelephonyManager::class.java) ?: return
            val code = telephony.simOperator.orEmpty()
            if (code.isBlank()) return
            val current = telephony.simOperatorName.orEmpty().ifBlank { code } + " ($code)"
            val prefs = DeviceStorage.statePrefs(app)
            val previous = prefs.getString("sim_operator", null)
            if (previous == current) return
            prefs.edit().putString("sim_operator", current).apply()
            SimStateTracker.operatorEvent(previous, current)?.let {
                app.securityLog.critical(EventType.SIM, it.title, it.detail)
            }
        }
    }
}
