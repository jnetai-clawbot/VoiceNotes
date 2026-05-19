package com.jnetaol.voicememo.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = VMPrimary,
    onPrimary = Color.White,
    primaryContainer = VMPrimaryVariant,
    onPrimaryContainer = Color.White,
    secondary = VMSecondary,
    onSecondary = Color.Black,
    tertiary = VMAccent,
    onTertiary = Color.White,
    background = VMBackground,
    onBackground = VMTextPrimary,
    surface = VMSurface,
    onSurface = VMTextPrimary,
    surfaceVariant = VMSurfaceVariant,
    onSurfaceVariant = VMTextSecondary,
    error = VMError,
    onError = Color.White,
    outline = VMTextMuted
)

@Composable
fun VoiceMemoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography(), content = content)
}
