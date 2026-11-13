package com.zerobet.safeguard.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Gestione del PIN del tutore e dei flag di configurazione protetti.
 *
 * Il PIN non viene mai salvato in chiaro: si memorizza solo SHA-256(salt || pin), con un salt
 * casuale di 16 byte, dentro EncryptedSharedPreferences (file cifrato AES-256 con chiave
 * custodita nell'Android Keystore).
 *
 * Nota di sicurezza: un PIN a 6 cifre ha solo 10^6 combinazioni, quindi l'hash da solo non
 * resisterebbe a un brute-force offline. La protezione reale viene dalla cifratura Keystore
 * del file e dal blocco temporaneo dopo tentativi errati implementato qui sotto.
 */
class PinManager(context: Context) {

    private val prefs: SharedPreferences = run {
        // Storage "device protected": il PIN e lo stato della protezione devono essere leggibili
        // subito dopo l'accensione, prima dello sblocco dello schermo (Direct Boot). La chiave
        // nel Keystore non richiede l'autenticazione dell'utente, quindi è disponibile anch'essa.
        val appContext = DeviceStorage.context(context)
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Chiamata a ogni PIN errato con il numero del tentativo e true se è scattato il blocco temporaneo. */
    var onWrongPin: ((attempt: Int, locked: Boolean) -> Unit)? = null

    val isPinSet: Boolean
        get() = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    /** Salva (o sostituisce) il PIN. Lancia IllegalArgumentException se non è di 6 cifre. */
    fun setPin(pin: String) {
        require(isValidFormat(pin)) { "Il PIN deve essere composto da $PIN_LENGTH cifre" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hash(salt, pin), Base64.NO_WRAP))
            .putInt(KEY_FAILED, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
    }

    /** Millisecondi residui di blocco per troppi tentativi errati (0 = nessun blocco). */
    fun lockoutRemainingMs(): Long =
        (prefs.getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Verifica il PIN con confronto a tempo costante.
     * Dopo [MAX_ATTEMPTS] errori consecutivi applica un blocco crescente (30s, 60s, 120s... max 15 min).
     */
    fun verifyPin(pin: String): Boolean {
        if (!isPinSet || lockoutRemainingMs() > 0) return false
        val salt = Base64.decode(prefs.getString(KEY_SALT, ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString(KEY_HASH, ""), Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(expected, hash(salt, pin))
        if (ok) {
            prefs.edit().putInt(KEY_FAILED, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
        } else {
            val failed = prefs.getInt(KEY_FAILED, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED, failed)
            if (failed >= MAX_ATTEMPTS) {
                val step = (failed - MAX_ATTEMPTS).coerceAtMost(5)
                val lockMs = (30_000L shl step).coerceAtMost(15 * 60_000L)
                editor.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + lockMs)
            }
            editor.apply()
            onWrongPin?.invoke(failed, failed >= MAX_ATTEMPTS)
        }
        return ok
    }

    /** true quando il wizard iniziale è stato completato: da quel momento il Guardiano è attivo. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** false quando il tutore ha messo in pausa la protezione dalla dashboard. */
    var protectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROTECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_PROTECTION, value).apply()

    private fun hash(salt: ByteArray, pin: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(salt)
            digest(pin.toByteArray(Charsets.UTF_8))
        }

    companion object {
        const val PIN_LENGTH = 6
        private const val MAX_ATTEMPTS = 5
        private const val FILE_NAME = DeviceStorage.SECURE_PREFS
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_FAILED = "pin_failed_attempts"
        private const val KEY_LOCK_UNTIL = "pin_lock_until"
        private const val KEY_ONBOARDING = "onboarding_done"
        private const val KEY_PROTECTION = "protection_enabled"

        fun isValidFormat(pin: String): Boolean =
            pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }
    }
}
