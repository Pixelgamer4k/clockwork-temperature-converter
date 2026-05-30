package com.clockwork.tempconverter

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

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE2C08d), // Antique Brass
    secondary = Color(0xFFC5A059), // Bronze
    tertiary = Color(0xFF8B5A2B), // Copper
    background = Color(0xFF121416), // Dark Gunmetal background
    surface = Color(0xFF1E2225), // Lighter slate grey
    onPrimary = Color(0xFF3E2700),
    onSecondary = Color(0xFF3A2E00),
    onBackground = Color(0xFFE2E2E6),
    onSurface = Color(0xFFE2E2E6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF7D5A2B), // Deep bronze
    secondary = Color(0xFF6E5D38),
    tertiary = Color(0xFF8B5A2B),
    background = Color(0xFFF4F3EF), // Antique cream paper background
    surface = Color(0xFFEAE7E1),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1B1B),
    onSurface = Color(0xFF1C1B1B)
)

@Composable
fun ClockworkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
