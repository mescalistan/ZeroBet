package com.zerobet.safeguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0D3B66)
val SafeGreen = Color(0xFF2E7D32)
val WarnAmber = Color(0xFFB26A00)

private val Colors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F5),
    onPrimaryContainer = Color(0xFF06223D),
    secondary = Color(0xFF3F6C8F),
    surface = Color(0xFFFAFBFD),
    background = Color(0xFFFAFBFD),
    surfaceVariant = Color(0xFFE7ECF2),
    error = Color(0xFFB3261E),
)

@Composable
fun SafeGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
