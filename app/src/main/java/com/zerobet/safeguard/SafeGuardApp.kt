package com.zerobet.safeguard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.zerobet.safeguard.data.AppDatabase
import com.zerobet.safeguard.data.DomainRepository
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.data.Severity
import com.zerobet.safeguard.guard.IntegrityChecker
import com.zerobet.safeguard.receiver.SimStateReceiver
import com.zerobet.safeguard.security.DeviceStorage
import com.zerobet.safeguard.security.PinManager
import com.zerobet.safeguard.security.SecurityLog
import com.zerobet.safeguard.sync.SyncScheduler
import com.zerobet.safeguard.vpn.FilterStore

/**
 * Service locator minimale: espone i singleton condivisi da UI, VpnService, AccessibilityService
 * e worker (tutti nello stesso processo) senza introdurre un framework di DI.
 *
 * Il processo può partire in modalità Direct Boot (telefono appena acceso, schermo ancora
 * bloccato): per questo tutti i dati stanno nello storage protetto dal dispositivo e ciò che
 * richiede lo storage dell'utente (WorkManager) viene rimandato a [onUserUnlocked].
 */
class SafeGuardApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: DomainRepository by lazy { DomainRepository(database.domainDao()) }
    val securityLog: SecurityLog by lazy { SecurityLog { database.securityEventDao() } }
    val pinManager: PinManager by lazy {
        PinManager(this).also { pin ->
            pin.onWrongPin = { attempt, locked ->
                securityLog.log(
                    EventType.PIN, if (locked) Severity.CRITICAL else Severity.WARNING,
                    "PIN errato (tentativo n. $attempt)",
                    if (locked) "Troppi errori: inserimento del PIN sospeso temporaneamente" else ""
                )
            }
        }
    }
    val filterStore: FilterStore by lazy { FilterStore(repository, DeviceStorage.filterCacheFile(this)) }

    @Volatile private var unlockedSetupDone = false

    override fun onCreate() {
        super.onCreate()
        DeviceStorage.migrate(this)
        registerSystemEvents()
        if (DeviceStorage.isUnlocked(this)) onUserUnlocked()
    }

    /** Parti che richiedono lo storage dell'utente: eseguite al primo sblocco dopo l'avvio. */
    fun onUserUnlocked() {
        if (unlockedSetupDone || !DeviceStorage.isUnlocked(this)) return
        unlockedSetupDone = true
        DeviceStorage.migrate(this)
        try {
            SyncScheduler.schedulePeriodic(this)      // aggiornamento quotidiano delle blocklist
            IntegrityChecker.schedulePeriodic(this)   // controllo dei servizi ogni 15 minuti
        } catch (e: Exception) {
            Log.w("SafeGuardApp", "Pianificazione dei lavori periodici fallita: ${e.message}")
        }
    }

    /**
     * Eventi che Android consegna solo ai receiver registrati a runtime (non nel manifest):
     * sblocco dello schermo, spegnimento, primo sblocco dopo il riavvio. Il processo è tenuto in
     * vita dal servizio VPN in foreground e dal servizio di accessibilità.
     */
    private fun registerSystemEvents() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction(Intent.ACTION_REBOOT)
            addAction(SimStateReceiver.ACTION_SIM_STATE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            private val sim = SimStateReceiver()
            override fun onReceive(context: Context, intent: Intent) {
                if (!DeviceStorage.isReady(context)) return
                when (intent.action) {
                    Intent.ACTION_USER_UNLOCKED -> onUserUnlocked()
                    Intent.ACTION_USER_PRESENT -> {
                        onUserUnlocked()
                        IntegrityChecker.check(context, "sblocco schermo")
                    }
                    Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT -> {
                        // Scrittura sincrona: tra pochi istanti il processo verrà chiuso.
                        if (pinManager.onboardingDone) {
                            securityLog.logNow(EventType.POWER, Severity.WARNING, "Telefono spento o riavviato", "Spegnimento richiesto")
                        }
                    }
                    SimStateReceiver.ACTION_SIM_STATE_CHANGED -> sim.onReceive(context, intent)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }
}
