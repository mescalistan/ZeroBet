package com.zerobet.safeguard.ui

import android.app.Application
import android.content.Context
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.admin.SafeGuardDeviceAdminReceiver
import com.zerobet.safeguard.data.BankPreset
import com.zerobet.safeguard.data.BlockedDomain
import com.zerobet.safeguard.data.WhitelistedBank
import com.zerobet.safeguard.guard.SettingsGuardAccessibilityService
import com.zerobet.safeguard.security.PinManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Stato dei tre permessi obbligatori. */
data class PermissionState(val vpn: Boolean, val deviceAdmin: Boolean, val accessibility: Boolean) {
    val allGranted: Boolean get() = vpn && deviceAdmin && accessibility

    companion object {
        fun read(context: Context) = PermissionState(
            vpn = VpnService.prepare(context) == null,
            deviceAdmin = SafeGuardDeviceAdminReceiver.isActive(context),
            accessibility = SettingsGuardAccessibilityService.isEnabled(context)
        )
    }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as SafeGuardApp).repository
    val pinManager: PinManager = (app as SafeGuardApp).pinManager

    val whitelist: StateFlow<List<WhitelistedBank>> =
        repository.whitelist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blocked: StateFlow<List<BlockedDomain>> =
        repository.blocked.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addBankPreset(preset: BankPreset, onDone: () -> Unit = {}) = viewModelScope.launch {
        repository.addBankPreset(preset)
        onDone()
    }

    /** [onResult] riceve false se il dominio non è valido. */
    fun addWhitelisted(name: String, domain: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(repository.addWhitelisted(name, domain))
    }

    fun removeWhitelisted(bank: WhitelistedBank) = viewModelScope.launch {
        repository.removeWhitelisted(bank)
    }

    fun addBlocked(raw: String, isKeyword: Boolean, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        onResult(repository.addBlocked(raw, isKeyword))
    }

    fun removeBlocked(domain: BlockedDomain) = viewModelScope.launch {
        repository.removeBlocked(domain)
    }
}
