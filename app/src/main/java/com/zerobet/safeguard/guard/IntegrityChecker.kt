package com.zerobet.safeguard.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.zerobet.safeguard.R
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.security.DeviceStorage
import com.zerobet.safeguard.ui.LockScreenActivity
import com.zerobet.safeguard.vpn.DomainBlockerVpnService
import java.util.concurrent.TimeUnit

/**
 * Controllo di integrità: verifica che i due servizi obbligatori (filtro VPN e Guardiano di
 * accessibilità) siano attivi. Viene eseguito allo sblocco dello schermo, all'avvio del
 * telefono, dal servizio di accessibilità e ogni 15 minuti da WorkManager (per il caso in cui
 * un task killer abbia chiuso tutto il processo).
 *
 *  - VPN spenta con consenso ancora valido: viene riavviata subito, in automatico.
 *  - VPN spenta senza consenso, oppure servizio di accessibilità disattivato: evento CRITICAL
 *    nel registro e schermata di blocco che obbliga a riattivare il servizio (o a inserire il PIN).
 *
 * Nulla scatta se la protezione è in pausa o nella finestra di grazia: sono scelte del tutore.
 */
object IntegrityChecker {

    private const val TAG = "SafeGuardIntegrity"
    private const val CHANNEL_ID = "safeguard_alert"
    private const val NOTIFICATION_ID = 1002
    private const val MIN_INTERVAL_MS = 5_000L
    private const val WORK_NAME = "safeguard-integrity"

    const val PROBLEM_VPN = "vpn"
    const val PROBLEM_ACCESSIBILITY = "accessibility"

    @Volatile private var lastCheckAt = 0L
    @Volatile private var lastReported: String? = null

    /** @return il problema trovato ([PROBLEM_VPN], [PROBLEM_ACCESSIBILITY]) o null se è tutto a posto. */
    fun check(context: Context, trigger: String, showLock: Boolean = true): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckAt < MIN_INTERVAL_MS) return null
        lastCheckAt = now
        if (!DeviceStorage.isReady(context)) return null
        val app = context.applicationContext as SafeGuardApp
        return try {
            val pin = app.pinManager
            if (!pin.onboardingDone || GuardState.inGrace) return null

            var problem: String? = null
            if (pin.protectionEnabled && !DomainBlockerVpnService.running.value) {
                if (VpnService.prepare(app) == null) {
                    DomainBlockerVpnService.start(app)
                    app.securityLog.warning(EventType.INTEGRITY, "Filtro VPN trovato spento e riavviato", "Controllo: $trigger")
                } else {
                    problem = PROBLEM_VPN
                }
            }
            if (problem == null && !SettingsGuardAccessibilityService.isEnabled(app)) problem = PROBLEM_ACCESSIBILITY

            if (problem == null) {
                if (lastReported != null) {
                    lastReported = null
                    app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                    app.securityLog.info(EventType.INTEGRITY, "Protezione di nuovo completa")
                }
                return null
            }
            if (lastReported != problem) {
                lastReported = problem
                app.securityLog.critical(
                    EventType.INTEGRITY,
                    if (problem == PROBLEM_VPN) "Filtro VPN disattivato" else "Guardiano di accessibilità disattivato",
                    "Rilevato senza PIN del tutore. Controllo: $trigger"
                )
            }
            if (showLock && !GuardState.lockScreenVisible) alert(app, problem)
            problem
        } catch (e: Exception) {
            Log.w(TAG, "Controllo di integrità fallito", e)
            null
        }
    }

    /**
     * Mostra la schermata di blocco. Android limita l'avvio di Activity dallo sfondo: quando non
     * lo consente resta comunque la notifica ad alta priorità che apre la stessa schermata.
     */
    private fun alert(app: SafeGuardApp, problem: String) {
        val intent = LockScreenActivity.integrityIntent(app, problem)
        try {
            app.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Avvio della schermata di blocco non consentito dallo sfondo")
        }
        try {
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Allarmi di protezione", NotificationManager.IMPORTANCE_HIGH)
            )
            val pending = PendingIntent.getActivity(
                app, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle("SafeGuard: protezione disattivata")
                    .setContentText("Tocca per riattivare il servizio obbligatorio.")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setContentIntent(pending)
                    .setFullScreenIntent(pending, true)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Notifica di allarme non mostrata: ${e.message}")
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** Controllo periodico: l'intervallo minimo di WorkManager (15 minuti) costa pochissima batteria. */
    fun schedulePeriodic(context: Context) {
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<IntegrityWorker>(15, TimeUnit.MINUTES).build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Controllo periodico non pianificato: ${e.message}")
        }
    }
}

class IntegrityWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // Dallo sfondo non si apre la schermata: basta la notifica (e il riavvio automatico della VPN).
        IntegrityChecker.check(applicationContext, "periodico")
        return Result.success()
    }
}
