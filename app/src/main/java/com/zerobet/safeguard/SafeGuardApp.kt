package com.zerobet.safeguard

import android.app.Application
import com.zerobet.safeguard.data.AppDatabase
import com.zerobet.safeguard.data.DomainRepository
import com.zerobet.safeguard.security.PinManager
import com.zerobet.safeguard.sync.SyncScheduler
import com.zerobet.safeguard.vpn.FilterStore

/**
 * Service locator minimale: espone i singleton condivisi da UI, VpnService, AccessibilityService
 * e worker (tutti nello stesso processo) senza introdurre un framework di DI.
 */
class SafeGuardApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: DomainRepository by lazy { DomainRepository(database.domainDao()) }
    val pinManager: PinManager by lazy { PinManager(this) }
    val filterStore: FilterStore by lazy { FilterStore(repository) }

    override fun onCreate() {
        super.onCreate()
        // Aggiornamento quotidiano delle blocklist (no-op se già pianificato).
        SyncScheduler.schedulePeriodic(this)
    }
}
