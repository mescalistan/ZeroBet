package com.zerobet.safeguard.guard

import android.os.SystemClock

/**
 * Stato condiviso (in memoria, stesso processo) tra Guardiano, LockScreenActivity e dashboard.
 *
 * Dopo un PIN corretto il tutore ottiene una "finestra di grazia" durante la quale il Guardiano
 * non interviene, così può modificare le Impostazioni di sistema. Usa l'orologio monotono
 * (elapsedRealtime) così non è aggirabile cambiando data/ora del telefono.
 */
object GuardState {
    const val GRACE_AFTER_PIN_MS = 60_000L
    const val GRACE_FOR_UNINSTALL_MS = 5 * 60_000L

    @Volatile private var graceUntil = 0L

    /** true mentre la LockScreenActivity è visibile: evita di rilanciarla a raffica. */
    @Volatile var lockScreenVisible = false

    fun grant(durationMs: Long) {
        graceUntil = SystemClock.elapsedRealtime() + durationMs
    }

    fun revoke() {
        graceUntil = 0L
    }

    val inGrace: Boolean
        get() = SystemClock.elapsedRealtime() < graceUntil
}
