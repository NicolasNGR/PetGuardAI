package com.petguard.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

val PetOrange = Color(0xFFFF6938)
val PetInk = Color(0xFF1E1D23)
val PetPanel = Color(0xFF383741)
val PetMuted = Color(0xFFC2CAD3)
val PetGreen = Color(0xFF006B50)
val PetAttention = Color(0xFFE69F00)
val PetDanger = Color(0xFFB3261E)
val PetBlue = Color(0xFF56B4E9)

data class PetSignalColors(val attention: Color, val danger: Color, val healthy: Color, val info: Color)

@Composable
fun petSecondaryTextColor(): Color {
    return if (MaterialTheme.colorScheme.background.luminance() > .5f) {
        Color(0xFF46505A)
    } else {
        Color(0xFFD0D6DF)
    }
}

@Composable
fun petSignalColors(): PetSignalColors {
    val lightSurface = MaterialTheme.colorScheme.background.luminance() > .5f
    return if (lightSurface) PetSignalColors(
        attention = Color(0xFF805500), danger = Color(0xFFB3261E), healthy = Color(0xFF006B50), info = Color(0xFF005A8B)
    ) else PetSignalColors(
        attention = Color(0xFFFFCB5C), danger = Color(0xFFFF6B6B), healthy = Color(0xFF5AD5A6), info = PetBlue
    )
}

@Composable
fun PetGuardTheme(light: Boolean, contrast: Boolean, content: @Composable () -> Unit) {
    val dark = if (contrast) darkColorScheme(primary = PetBlue, onPrimary = Color.Black, background = Color.Black, surface = Color(0xFF101820), onSurface = Color.White)
    else darkColorScheme(primary = PetOrange, onPrimary = PetInk, background = PetInk, surface = PetPanel, onSurface = Color(0xFFF6F4F8))
    val bright = if (contrast) lightColorScheme(primary = Color(0xFF005A8B), onPrimary = Color.White, background = Color.White, surface = Color.White, onSurface = Color.Black)
    else lightColorScheme(primary = Color(0xFFB84019), onPrimary = Color.White, background = Color(0xFFF7F5F2), surface = Color.White, onSurface = Color(0xFF25232B))
    MaterialTheme(colorScheme = if (light) bright else dark, content = content)
}

