package com.zerobet.safeguard.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.zerobet.safeguard.admin.SafeGuardDeviceAdminReceiver
import com.zerobet.safeguard.data.DefaultData
import com.zerobet.safeguard.ui.components.PinPad
import com.zerobet.safeguard.ui.theme.SafeGreen
import com.zerobet.safeguard.vpn.DomainBlockerVpnService

/** Wizard iniziale in 3 passi, eseguito dal tutore: PIN -> banca -> permessi. */
@Composable
fun OnboardingScreen(vm: MainViewModel, onFinished: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Crea il PIN del tutore", "Banca autorizzata", "Permessi obbligatori")

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(
                "SafeGuard · Passo ${step + 1} di 3",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(titles[step], style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f)) {
            when (step) {
                0 -> PinSetupStep(vm) { step = 1 }
                1 -> BankStep(vm) { step = 2 }
                else -> PermissionsStep(vm, onFinished)
            }
        }
    }
}

// -------------------------------------------------------------------------------------------
// Passo 1: creazione del PIN (inserimento + conferma)
// -------------------------------------------------------------------------------------------
@Composable
private fun PinSetupStep(vm: MainViewModel, onDone: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        PinPad(
            title = if (first == null) "Scegli un PIN di 6 cifre" else "Ripeti il PIN per conferma",
            subtitle = "Il PIN deve essere scelto e custodito da un familiare o tutore, non dalla persona da proteggere.",
            error = error,
            onComplete = { pin ->
                val previous = first
                when {
                    previous == null -> {
                        first = pin
                        error = null
                    }
                    previous == pin -> {
                        vm.pinManager.setPin(pin)
                        onDone()
                    }
                    else -> {
                        first = null
                        error = "I due PIN non coincidono. Riprova."
                    }
                }
            }
        )
    }
}

// -------------------------------------------------------------------------------------------
// Passo 2: scelta della banca da mettere in whitelist
// -------------------------------------------------------------------------------------------
@Composable
private fun BankStep(vm: MainViewModel, onDone: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    var custom by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val presets = DefaultData.bankPresets

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text(
            "Seleziona la banca usata per l'home banking: resterà raggiungibile. " +
                "Tutte le altre banche e finanziarie in elenco verranno bloccate.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(presets.size) { index ->
                val preset = presets[index]
                Row(
                    Modifier.fillMaxWidth().clickable { selected = index; custom = ""; error = null },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == index, onClick = { selected = index; custom = ""; error = null })
                    Column {
                        Text(preset.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            preset.domains.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it; selected = -1; error = null },
            label = { Text("Oppure dominio personalizzato") },
            placeholder = { Text("es. lamiabanca.it") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                if (custom.isNotBlank()) {
                    vm.addWhitelisted(name = "Banca personale", domain = custom) { ok ->
                        if (ok) onDone() else error = "Dominio non valido"
                    }
                } else {
                    vm.addBankPreset(presets[selected], onDone)
                }
            },
            enabled = selected >= 0 || custom.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) { Text("Avanti") }
    }
}

// -------------------------------------------------------------------------------------------
// Passo 3: concessione dei tre permessi di sistema
// -------------------------------------------------------------------------------------------
@Composable
private fun PermissionsStep(vm: MainViewModel, onFinished: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PermissionState.read(context)) }
    val refresh = { state = PermissionState.read(context) }

    // I permessi vengono concessi in schermate di sistema: al rientro si rilegge lo stato.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) refresh()
    }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Android 13+: senza questo permesso la notifica del servizio VPN non sarebbe visibile.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PermissionCard(
            title = "1. Filtro di rete (VPN locale)",
            description = "Crea una VPN che resta sul telefono e controlla solo le richieste DNS. Nessun dato di navigazione viene inviato a server esterni.",
            granted = state.vpn,
            onGrant = {
                val intent = VpnService.prepare(context)
                if (intent != null) vpnLauncher.launch(intent) else refresh()
            }
        )
        PermissionCard(
            title = "2. Amministratore dispositivo",
            description = "Impedisce la disinstallazione dell'app dalle normali schermate di Android finché non viene sbloccata con il PIN.",
            granted = state.deviceAdmin,
            onGrant = { adminLauncher.launch(SafeGuardDeviceAdminReceiver.activationIntent(context)) }
        )
        PermissionCard(
            title = "3. Guardiano Impostazioni (Accessibilità)",
            description = "Chiede il PIN quando qualcuno apre le Impostazioni per disattivare SafeGuard e legge i testi visibili in browser e app per bloccare pagine e annunci di scommesse, trading e prestiti. L'analisi avviene solo sul telefono: nulla viene salvato o inviato. Nell'elenco scegli \"SafeGuard – Guardiano Impostazioni\" e attivalo.",
            granted = state.accessibility,
            onGrant = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                vm.pinManager.protectionEnabled = true
                vm.pinManager.onboardingDone = true   // da qui in poi il Guardiano è attivo
                DomainBlockerVpnService.start(context)
                onFinished()
            },
            enabled = state.allGranted,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Attiva la protezione") }
        if (!state.allGranted) {
            Text(
                "Concedi tutti e tre i permessi per continuare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun PermissionCard(title: String, description: String, granted: Boolean, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (granted) {
                Text("✓ Concesso", color = SafeGreen, fontWeight = FontWeight.SemiBold)
            } else {
                OutlinedButton(onClick = onGrant) { Text("Concedi permesso") }
            }
        }
    }
}
