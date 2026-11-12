package com.zerobet.safeguard.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerobet.safeguard.admin.SafeGuardDeviceAdminReceiver
import com.zerobet.safeguard.data.AutoLearnedBlockedDomain
import com.zerobet.safeguard.data.BlockedDomain
import com.zerobet.safeguard.data.Category
import com.zerobet.safeguard.data.WhitelistedBank
import com.zerobet.safeguard.data.SyncStatus
import com.zerobet.safeguard.guard.GuardState
import com.zerobet.safeguard.sync.BlocklistSources
import com.zerobet.safeguard.sync.SyncScheduler
import com.zerobet.safeguard.sync.SyncState
import com.zerobet.safeguard.ui.theme.SafeGreen
import com.zerobet.safeguard.ui.theme.WarnAmber
import com.zerobet.safeguard.vpn.DomainBlockerVpnService
import java.text.DateFormat
import java.util.Date

/**
 * Dashboard del tutore. Viene composta solo dopo la verifica del PIN (vedi MainActivity),
 * quindi ogni azione qui dentro è già autorizzata.
 */
@Composable
fun DashboardScreen(vm: MainViewModel, onLock: () -> Unit) {
    val context = LocalContext.current
    val running by DomainBlockerVpnService.running.collectAsStateWithLifecycle()
    val blockedCount by DomainBlockerVpnService.blockedCount.collectAsStateWithLifecycle()
    val whitelist by vm.whitelist.collectAsStateWithLifecycle()
    val blocked by vm.blocked.collectAsStateWithLifecycle()
    val learned by vm.learned.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val syncRunning by SyncState.running.collectAsStateWithLifecycle()
    val syncProgress by SyncState.progress.collectAsStateWithLifecycle()
    var privateDnsForced by remember { mutableStateOf(isPrivateDnsForced(context)) }

    var permissions by remember { mutableStateOf(PermissionState.read(context)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showUninstallDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissions = PermissionState.read(context)
        privateDnsForced = isPrivateDnsForced(context)
        // Se la protezione dovrebbe essere attiva ma il servizio non gira (es. dopo un crash), lo riavvia.
        if (vm.pinManager.protectionEnabled && permissions.vpn && !DomainBlockerVpnService.running.value) {
            DomainBlockerVpnService.start(context)
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.pinManager.protectionEnabled = true
            DomainBlockerVpnService.start(context)
        }
        permissions = PermissionState.read(context)
    }

    fun setProtection(enabled: Boolean) {
        if (enabled) {
            val consent = VpnService.prepare(context)
            if (consent != null) {
                vpnLauncher.launch(consent)
            } else {
                vm.pinManager.protectionEnabled = true
                DomainBlockerVpnService.start(context)
            }
        } else {
            vm.pinManager.protectionEnabled = false
            DomainBlockerVpnService.stop(context)
        }
    }

    /** Apre una schermata di sistema concedendo al tutore la finestra di grazia del Guardiano. */
    fun openSystemScreen(intent: Intent) {
        GuardState.grant(GuardState.GRACE_AFTER_PIN_MS)
        context.startActivity(intent)
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "SafeGuard",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onLock) { Text("Blocca") }
            }
        }

        // ---- Stato della protezione + pausa ----
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (running) "Protezione attiva" else "Protezione in pausa",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (running) SafeGreen else WarnAmber
                        )
                        Text(
                            "Richieste bloccate in questa sessione: $blockedCount",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Switch(checked = running, onCheckedChange = { setProtection(it) })
                }
            }
        }

        // ---- Permessi mancanti ----
        if (!permissions.allGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Protezione incompleta", fontWeight = FontWeight.SemiBold)
                        if (!permissions.vpn) {
                            OutlinedButton(onClick = { setProtection(true) }) { Text("Riattiva permesso VPN") }
                        }
                        if (!permissions.deviceAdmin) {
                            OutlinedButton(onClick = {
                                openSystemScreen(SafeGuardDeviceAdminReceiver.activationIntent(context))
                            }) { Text("Riattiva Amministratore dispositivo") }
                        }
                        if (!permissions.accessibility) {
                            OutlinedButton(onClick = {
                                openSystemScreen(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }) { Text("Riattiva Guardiano Impostazioni") }
                        }
                    }
                }
            }
        }

        // ---- DNS privato impostato a mano: il filtro è scavalcato ----
        if (privateDnsForced) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("DNS privato attivo", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Sul telefono è impostato un provider di DNS privato: le richieste non passano dal " +
                                "filtro di SafeGuard. Imposta \"DNS privato\" su Disattivato o Automatico in " +
                                "Impostazioni → Rete e Internet.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // ---- Aggiornamento delle liste esterne ----
        item {
            SyncCard(
                status = syncStatus,
                running = syncRunning,
                progress = syncProgress,
                onSyncNow = { SyncScheduler.syncNow(context) }
            )
        }

        // ---- Whitelist / Blacklist / Auto-appresi ----
        item {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Whitelist (${whitelist.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Blacklist (${blocked.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Appresi (${learned.size})") })
            }
        }

        if (tab == 0) {
            item { AddWhitelistRow(vm) }
            if (whitelist.isEmpty()) {
                item { Text("Nessuna banca autorizzata.", Modifier.padding(vertical = 8.dp)) }
            }
            items(whitelist, key = { "w${it.id}" }) { bank ->
                DomainRow(title = bank.domain, subtitle = bank.name, onRemove = { vm.removeWhitelisted(bank) })
            }
        } else if (tab == 2) {
            item {
                Text(
                    "Domini bloccati in automatico dall'app: per il nome a rischio (euristica) o per i " +
                        "contenuti rilevati a schermo. \"Consenti\" sposta il dominio in whitelist.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            if (learned.isEmpty()) {
                item { Text("Nessun dominio appreso finora.", Modifier.padding(vertical = 8.dp)) }
            }
            items(learned, key = { "l${it.domain}" }) { entry ->
                LearnedRow(entry, onAllow = { vm.allowLearned(entry) }, onRemove = { vm.removeLearned(entry) })
            }
        } else {
            item { AddBlacklistRow(vm) }
            item { BlacklistFilter.Chips() }
            val visible = blocked.filter { BlacklistFilter.selected == null || it.category == BlacklistFilter.selected }
            items(visible, key = { "b${it.id}" }) { entry ->
                DomainRow(
                    title = entry.pattern,
                    subtitle = (if (entry.isKeyword) "Parola chiave · " else "Dominio · ") + Category.label(entry.category),
                    onRemove = { vm.removeBlocked(entry) }
                )
            }
        }

        // ---- Rimozione legittima dell'app ----
        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showUninstallDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sblocca Disinstallazione") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = { Text("Sbloccare la disinstallazione?") },
            text = {
                Text(
                    "La protezione verrà messa in pausa e l'Amministratore dispositivo disattivato per 5 minuti, " +
                        "così da poter rimuovere SafeGuard. Se annulli la disinstallazione, riattiva i permessi dalla dashboard."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUninstallDialog = false
                    // 1) niente più blocco del Guardiano per 5 minuti
                    GuardState.grant(GuardState.GRACE_FOR_UNINSTALL_MS)
                    // 2) pausa del filtro e rimozione dell'admin: da ora la disinstallazione standard è possibile
                    setProtection(false)
                    SafeGuardDeviceAdminReceiver.deactivate(context)
                    // 3) apre direttamente il dialogo di disinstallazione di sistema
                    context.startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}"))
                    )
                }) { Text("Sblocca e disinstalla", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) { Text("Annulla") }
            }
        )
    }
}

/** Filtro per categoria della blacklist (stato UI condiviso tra chip ed elenco). */
private object BlacklistFilter {
    var selected by mutableStateOf<String?>(null)

    private val options = listOf(
        null to "Tutti",
        Category.CUSTOM to "Personalizzati",
        Category.GAMBLING to "Scommesse",
        Category.TRADING to "Trading",
        Category.LOANS to "Prestiti",
        Category.BANK to "Banche",
        Category.ADS to "Pubblicità",
        Category.DOH to "DoH",
    )

    @Composable
    fun Chips() {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (category, label) ->
                FilterChip(selected = selected == category, onClick = { selected = category }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun AddWhitelistRow(vm: MainViewModel) {
    var domain by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it; error = false },
            label = { Text("Dominio banca da autorizzare") },
            placeholder = { Text("es. unicredit.it") },
            singleLine = true,
            isError = error,
            supportingText = if (error) ({ Text("Dominio non valido") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                vm.addWhitelisted(name = "Aggiunta dal tutore", domain = domain) { ok ->
                    if (ok) domain = "" else error = true
                }
            },
            enabled = domain.isNotBlank()
        ) { Text("Aggiungi") }
    }
}

@Composable
private fun AddBlacklistRow(vm: MainViewModel) {
    var value by rememberSaveable { mutableStateOf("") }
    var isKeyword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; error = false },
                label = { Text(if (isKeyword) "Parola chiave da bloccare" else "Dominio da bloccare") },
                placeholder = { Text(if (isKeyword) "es. scommesse" else "es. sito.com") },
                singleLine = true,
                isError = error,
                supportingText = if (error) ({
                    Text(if (isKeyword) "Minimo 4 caratteri, solo lettere, cifre e trattini" else "Dominio non valido")
                }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    vm.addBlocked(value, isKeyword) { ok -> if (ok) value = "" else error = true }
                },
                enabled = value.isNotBlank()
            ) { Text("Blocca") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = isKeyword, onCheckedChange = { isKeyword = it; error = false })
            Spacer(Modifier.width(8.dp))
            Text("Blocca come parola chiave (tutti i domini che la contengono)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** true se in Impostazioni è configurato un provider DNS-over-TLS specifico (modalità "hostname"). */
private fun isPrivateDnsForced(context: android.content.Context): Boolean = try {
    Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"
} catch (e: Exception) {
    false
}

@Composable
private fun SyncCard(status: List<SyncStatus>, running: Boolean, progress: String, onSyncNow: () -> Unit) {
    val total = status.sumOf { it.domainCount }
    val lastSuccess = status.maxOfOrNull { it.lastSuccessAt } ?: 0L
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Liste esterne", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (lastSuccess > 0) "$total domini · ultimo aggiornamento ${formatter.format(Date(lastSuccess))}"
                else "Mai aggiornate: avvia il primo aggiornamento.",
                style = MaterialTheme.typography.bodyMedium
            )
            status.forEach { s ->
                val detail = if (s.lastError != null) "errore: ${s.lastError}" else "${s.domainCount} domini"
                Text(
                    "• ${BlocklistSources.label(s.source)} — $detail",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.lastError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onSyncNow, enabled = !running) {
                Text(
                    if (!running) "Forza aggiornamento ora"
                    else if (progress.isNotEmpty()) "In corso: $progress…"
                    else "Aggiornamento in corso…"
                )
            }
        }
    }
}

@Composable
private fun LearnedRow(entry: AutoLearnedBlockedDomain, onAllow: () -> Unit, onRemove: () -> Unit) {
    val origin = if (entry.origin == AutoLearnedBlockedDomain.ORIGIN_SCREEN) "Contenuto a schermo" else "Nome a rischio"
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.domain, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$origin · ${entry.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onAllow) { Text("Consenti") }
            TextButton(onClick = onRemove) { Text("Rimuovi") }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DomainRow(title: String, subtitle: String, onRemove: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRemove) { Text("Rimuovi") }
        }
        HorizontalDivider()
    }
}
