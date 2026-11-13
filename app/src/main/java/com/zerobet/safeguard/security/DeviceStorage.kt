package com.zerobet.safeguard.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import java.io.File

/**
 * Storage "device protected" (Direct Boot).
 *
 * Android tiene due aree dati per ogni app: quella cifrata con le credenziali dell'utente,
 * leggibile solo DOPO il primo sblocco dello schermo, e quella cifrata con la sola chiave del
 * dispositivo, leggibile appena il telefono si accende. Tutto ciò che serve per filtrare già
 * dalla schermata di blocco post-riavvio (hash del PIN, stato della protezione, database con
 * whitelist/blacklist/registro, cache compatta delle liste esterne) vive nella seconda.
 */
object DeviceStorage {

    private const val TAG = "SafeGuardStorage"
    const val DB_NAME = "safeguard.db"
    const val SECURE_PREFS = "safeguard_secure_prefs"
    private const val STATE_PREFS = "safeguard_state"
    private const val FILTER_CACHE = "external_domains.bin"

    /**
     * Contesto che scrive nello storage protetto dal dispositivo. Diverse librerie (tra cui
     * EncryptedSharedPreferences) chiamano getApplicationContext() su ciò che ricevono e
     * tornerebbero così allo storage dell'utente: per questo il contesto restituisce se stesso.
     */
    private class DeviceContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    @Volatile private var cached: Context? = null

    fun context(context: Context): Context =
        cached ?: DeviceContext(context.applicationContext.createDeviceProtectedStorageContext()).also { cached = it }

    fun isUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    /**
     * Sposta dall'area "credential protected" i dati creati dalle versioni precedenti dell'app.
     * Va chiamato a ogni avvio: fa qualcosa solo la prima volta, con lo schermo già sbloccato.
     */
    fun migrate(context: Context) {
        if (!isUnlocked(context)) return
        val app = context.applicationContext
        val device = context(context)
        try {
            if (app.getDatabasePath(DB_NAME).exists() && !device.getDatabasePath(DB_NAME).exists()) {
                device.moveDatabaseFrom(app, DB_NAME)
            }
            val legacyPrefs = File(app.dataDir, "shared_prefs/$SECURE_PREFS.xml")
            if (legacyPrefs.exists() && !securePrefsFile(device).exists()) {
                device.moveSharedPreferencesFrom(app, SECURE_PREFS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Spostamento dei dati nello storage protetto dal dispositivo fallito", e)
        }
    }

    /**
     * false solo nel caso limite del primo riavvio dopo l'aggiornamento da una versione senza
     * Direct Boot, a schermo ancora bloccato: i dati non sono ancora stati spostati e non vanno
     * creati vuoti. In quel caso i receiver aspettano lo sblocco.
     */
    fun isReady(context: Context): Boolean =
        isUnlocked(context) || securePrefsFile(context(context)).exists()

    private fun securePrefsFile(device: Context) = File(device.dataDir, "shared_prefs/$SECURE_PREFS.xml")

    /** Preferenze non sensibili (ultimo stato della SIM, ultimo accesso al registro...). */
    fun statePrefs(context: Context): SharedPreferences =
        context(context).getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    /** File con la copia compatta (hash a 64 bit) dei domini delle liste esterne. */
    fun filterCacheFile(context: Context): File = File(context(context).filesDir, FILTER_CACHE)
}
