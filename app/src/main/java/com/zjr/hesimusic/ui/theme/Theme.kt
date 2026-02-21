package com.zjr.hesimusic.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.zjr.hesimusic.data.preferences.AppThemePalette

internal fun paletteSeedColor(palette: AppThemePalette, customColor: Int): Color = when (palette) {
    AppThemePalette.BLUE -> Color(0xFF1A8DCE)
    AppThemePalette.GREEN -> Color(0xFF2E7D32)
    AppThemePalette.PURPLE -> Color(0xFF5E35B1)
    AppThemePalette.ORANGE -> Color(0xFFEF6C00)
    AppThemePalette.RED -> Color(0xFFC62828)
    AppThemePalette.PINK -> Color(0xFFD81B60)
    AppThemePalette.TEAL -> Color(0xFF00796B)
    AppThemePalette.YELLOW -> Color(0xFFB26A00)
    AppThemePalette.CUSTOM -> Color(customColor)
}

private fun colorSchemeForPalette(
    palette: AppThemePalette,
    darkTheme: Boolean,
    customColor: Int
): ColorScheme {
    val seed = paletteSeedColor(palette, customColor)
    return if (darkTheme) {
        darkColorScheme(
            primary = lerp(seed, Color.White, 0.15f),
            secondary = lerp(seed, Color.White, 0.28f),
            tertiary = lerp(seed, Color.White, 0.4f)
        )
    } else {
        lightColorScheme(
            primary = seed,
            secondary = lerp(seed, Color.Black, 0.12f),
            tertiary = lerp(seed, Color.Black, 0.22f)
        )
    }
}

@Composable
fun HesimusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: AppThemePalette = AppThemePalette.BLUE,
    customColor: Int = 0xFF1A8DCE.toInt(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> colorSchemeForPalette(palette = palette, darkTheme = darkTheme, customColor = customColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
