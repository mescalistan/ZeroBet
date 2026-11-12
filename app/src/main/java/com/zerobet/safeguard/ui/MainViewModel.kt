package com.zerobet.safeguard.ui

import android.app.Application
import android.content.Context
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.admin.SafeGuardDeviceAdminReceiver
import com.zerobet.safeguard.data.AutoLearnedBlockedDomain
import com.zerobet.safeguard.data.BankPreset
import com.zerobet.safeguard.data.BlockedDomain
import com.zerobet.safeguard.data.SyncStatus
import com.zerobet.safeguard.data.SyncedDomain
import com.zerobet.safeguard.data.WhitelistedBank
import com.zerobet.safeguard.guard.SettingsGuardAccessibilityService
import com.zerobet.safeguard.security.PinManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as SafeGuardApp).repository
    val pinManager: PinManager = (app as SafeGuardApp).pinManager

    val whitelist: StateFlow<List<WhitelistedBank>> =
        repository.whitelist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val blocked: StateFlow<List<BlockedDomain>> =
        repository.blocked.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val learned: StateFlow<List<AutoLearnedBlockedDomain>> =
        repository.learned.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncStatus: StateFlow<List<SyncStatus>> =
        repository.syncStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Domini delle liste esterne: sono centinaia di migliaia, quindi si caricano a pagine ----

    /** Testo cercato nella blacklist (già ripulito: solo caratteri validi in un dominio). */
    val blacklistQuery = MutableStateFlow("")
    private val syncedLimit = MutableStateFlow(SYNCED_PAGE)

    val syncedCount: StateFlow<Int> =
        repository.syncedCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val synced: StateFlow<List<SyncedDomain>> =
        combine(blacklistQuery, syncedLimit) { query, limit -> query to limit }
            .flatMapLatest { (query, limit) -> repository.synced(query, limit) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncedMatches: StateFlow<Int> =
        blacklistQuery.flatMapLatest { repository.syncedMatches(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun searchBlacklist(raw: String) {
        blacklistQuery.value = raw.lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(60)
        syncedLimit.value = SYNCED_PAGE
    }

    fun showMoreSynced() {
        syncedLimit.value += SYNCED_PAGE
    }

    fun allowSynced(domain: SyncedDomain) = viewModelScope.launch {
        repository.allowSynced(domain)
    }

    fun removeLearned(domain: AutoLearnedBlockedDomain) = viewModelScope.launch {
        repository.removeLearned(domain)
    }

    /** Falso positivo: il dominio viene tolto dagli auto-appresi e messo in whitelist. */
    fun allowLearned(domain: AutoLearnedBlockedDomain) = viewModelScope.launch {
        repository.allowLearned(domain)
    }

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

    private companion object {
        const val SYNCED_PAGE = 200
    }
}
