package com.zerobet.safeguard.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.zerobet.safeguard.R

/**
 * Finché questo receiver è un amministratore attivo, Android non permette la disinstallazione
 * standard dell'app: prima va disattivato l'admin, e quella schermata è presidiata dal
 * Guardiano Impostazioni (richiesta PIN).
 */
class SafeGuardDeviceAdminReceiver : DeviceAdminReceiver() {

    /** Testo mostrato dal sistema nel dialogo di conferma della disattivazione. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)

    companion object {
        fun component(context: Context) =
            ComponentName(context, SafeGuardDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java).isAdminActive(component(context))

        /** Intent di sistema per chiedere all'utente di attivare l'admin. */
        fun activationIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    context.getString(R.string.device_admin_description)
                )

        /** Rimuove l'admin (usato da "Sblocca disinstallazione", dopo verifica del PIN). */
        fun deactivate(context: Context) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (dpm.isAdminActive(component(context))) dpm.removeActiveAdmin(component(context))
        }
    }
}
