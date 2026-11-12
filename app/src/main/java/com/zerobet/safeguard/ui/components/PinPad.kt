package com.zerobet.safeguard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zerobet.safeguard.security.PinManager
import kotlinx.coroutines.delay

/**
 * Tastierino numerico a 6 cifre. Usa pulsanti propri (non la tastiera di sistema) così il PIN
 * non passa da IME di terze parti. Quando vengono inserite 6 cifre invoca [onComplete] e si azzera.
 */
@Composable
fun PinPad(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    onComplete: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }

    fun press(digit: Char) {
        if (!enabled || pin.length >= PinManager.PIN_LENGTH) return
        pin += digit
        if (pin.length == PinManager.PIN_LENGTH) {
            val entered = pin
            pin = ""
            onComplete(entered)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(24.dp))

        // Indicatori delle cifre inserite
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(PinManager.PIN_LENGTH) { index ->
                val filled = index < pin.length
                Box(
                    Modifier
                        .size(16.dp)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            CircleShape
                        )
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }

        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
            if (error != null) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }

        val rows = listOf("123", "456", "789")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { digit -> DigitKey(digit, enabled) { press(digit) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(72.dp))
                DigitKey('0', enabled) { press('0') }
                TextButton(
                    onClick = { pin = pin.dropLast(1) },
                    enabled = enabled && pin.isNotEmpty(),
                    modifier = Modifier.size(72.dp)
                ) { Text("⌫", style = MaterialTheme.typography.headlineSmall) }
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(72.dp)
    ) {
        Text(digit.toString(), style = MaterialTheme.typography.headlineSmall)
    }
}

/**
 * Schermata di verifica del PIN del tutore, con gestione del blocco temporaneo dopo
 * troppi tentativi errati. Riutilizzata dalla dashboard e dalla LockScreenActivity.
 */
@Composable
fun PinVerify(
    pinManager: PinManager,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onSuccess: () -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }
    var lockoutMs by remember { mutableStateOf(pinManager.lockoutRemainingMs()) }

    // Conto alla rovescia del blocco
    LaunchedEffect(lockoutMs > 0) {
        while (lockoutMs > 0) {
            delay(1000)
            lockoutMs = pinManager.lockoutRemainingMs()
        }
    }

    PinPad(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        enabled = lockoutMs <= 0,
        error = if (lockoutMs > 0) "Troppi tentativi errati. Riprova tra ${(lockoutMs + 999) / 1000} s" else error,
        onComplete = { pin ->
            if (pinManager.verifyPin(pin)) {
                error = null
                onSuccess()
            } else {
                error = "PIN errato"
                lockoutMs = pinManager.lockoutRemainingMs()
            }
        }
    )
}
