package com.zerobet.safeguard

import android.app.Application
import com.zerobet.safeguard.data.AppDatabase
import com.zerobet.safeguard.data.DomainRepository
import com.zerobet.safeguard.security.PinManager

/**
 * Service locator minimale: espone i singleton condivisi da UI, VpnService e AccessibilityService
 * (tutti nello stesso processo) senza introdurre un framework di DI.
 */
class SafeGuardApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: DomainRepository by lazy { DomainRepository(database.domainDao()) }
    val pinManager: PinManager by lazy { PinManager(this) }
}
