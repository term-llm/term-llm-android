package com.termllm.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val OkGreen = Color(0xFF2E7D32)
val WarnAmber = Color(0xFFB26A00)
val ErrorRed = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F4F82),
    secondary = Color(0xFF4A5C6A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC7F5),
    secondary = Color(0xFFAFBECB),
)

@Composable
fun TermLlmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
