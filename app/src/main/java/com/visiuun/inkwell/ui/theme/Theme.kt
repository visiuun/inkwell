package com.visiuun.inkwell.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.visiuun.inkwell.data.Settings
import com.visiuun.inkwell.data.ThemeMode

/** The four pigments, as accent colours for the interface itself. */
object InkColors {
    val Black = Color(0xFF1C1B1A)
    val Paper = Color(0xFFE8E5DA)
    val Yellow = Color(0xFFFFD426)
    val Red = Color(0xFFE5392B)
}

// Built from the panel's own pigments: yellow leads, red is the accent, surfaces stay neutral
// so nothing drifts toward brown or olive.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFFD426), onPrimary = Color(0xFF231B00),
    primaryContainer = Color(0xFFFFD426), onPrimaryContainer = Color(0xFF231B00),
    secondary = Color(0xFFE8E5DA), onSecondary = Color(0xFF1C1B1A),
    secondaryContainer = Color(0xFF2E2D2B), onSecondaryContainer = Color(0xFFEDE9DF),
    tertiary = Color(0xFFFF5A4A), onTertiary = Color(0xFF2D0200),
    tertiaryContainer = Color(0xFF8C1A10), onTertiaryContainer = Color(0xFFFFDAD5),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131313), onBackground = Color(0xFFE7E3DC),
    surface = Color(0xFF131313), onSurface = Color(0xFFE7E3DC),
    surfaceVariant = Color(0xFF2F2E2C), onSurfaceVariant = Color(0xFFCAC6BD),
    outline = Color(0xFF94908A), outlineVariant = Color(0xFF464440),
    inverseSurface = Color(0xFFE7E3DC), inverseOnSurface = Color(0xFF30302E), inversePrimary = Color(0xFF6E5A00),
    surfaceContainerLowest = Color(0xFF0E0E0E), surfaceContainerLow = Color(0xFF1B1B1A),
    surfaceContainer = Color(0xFF201F1E), surfaceContainerHigh = Color(0xFF2A2928),
    surfaceContainerHighest = Color(0xFF353432), surfaceDim = Color(0xFF131313), surfaceBright = Color(0xFF3A3937),
)

// Light theme reads like ink on paper: black leads, yellow fills the containers.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1C1B1A), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD426), onPrimaryContainer = Color(0xFF231B00),
    secondary = Color(0xFF5F5B52), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E5D9), onSecondaryContainer = Color(0xFF1D1B16),
    tertiary = Color(0xFFC3291C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD5), onTertiaryContainer = Color(0xFF410001),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF9F3), onBackground = Color(0xFF1C1B1A),
    surface = Color(0xFFFBF9F3), onSurface = Color(0xFF1C1B1A),
    surfaceVariant = Color(0xFFE7E3D8), onSurfaceVariant = Color(0xFF4A473F),
    outline = Color(0xFF7B776D), outlineVariant = Color(0xFFCBC7BC),
    inverseSurface = Color(0xFF31302E), inverseOnSurface = Color(0xFFF3F0E9), inversePrimary = Color(0xFFFFD426),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F3EC),
    surfaceContainer = Color(0xFFEFEDE6), surfaceContainerHigh = Color(0xFFE9E7E0),
    surfaceContainerHighest = Color(0xFFE3E1DA), surfaceDim = Color(0xFFDBD9D2), surfaceBright = Color(0xFFFBF9F3),
)

/** True-black variant for OLED panels: black backgrounds, containers pulled toward black. */
private fun ColorScheme.toOled(): ColorScheme {
    fun dim(c: Color, f: Float) = lerp(c, Color.Black, f)
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = dim(surfaceContainerLow, 0.6f),
        surfaceContainer = dim(surfaceContainer, 0.5f),
        surfaceContainerHigh = dim(surfaceContainerHigh, 0.4f),
        surfaceContainerHighest = dim(surfaceContainerHighest, 0.35f),
        surfaceBright = dim(surfaceBright, 0.35f),
        surfaceVariant = dim(surfaceVariant, 0.35f),
        secondaryContainer = dim(secondaryContainer, 0.3f),
        inverseOnSurface = Color.Black,
    )
}

data class AppTheme(val isDark: Boolean, val oled: Boolean)

val LocalAppTheme = staticCompositionLocalOf { AppTheme(isDark = false, oled = false) }

@Composable
fun InkwellTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val oled = dark && settings.oled
    var scheme = if (dark) DarkScheme else LightScheme
    if (oled) scheme = scheme.toOled()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalAppTheme provides AppTheme(dark, oled)) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = Typography(),
            content = content,
        )
    }
}
