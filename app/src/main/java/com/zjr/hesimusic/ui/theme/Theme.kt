package com.zjr.hesimusic.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.zjr.hesimusic.data.preferences.AppThemePalette

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

private val GreenDarkColorScheme = darkColorScheme(
    primary = Green80,
    secondary = GreenGrey80,
    tertiary = GreenAccent80
)
private val GreenLightColorScheme = lightColorScheme(
    primary = Green40,
    secondary = GreenGrey40,
    tertiary = GreenAccent40
)
private val PurpleDarkColorScheme = darkColorScheme(
    primary = Violet80,
    secondary = VioletGrey80,
    tertiary = VioletAccent80
)
private val PurpleLightColorScheme = lightColorScheme(
    primary = Violet40,
    secondary = VioletGrey40,
    tertiary = VioletAccent40
)
private val OrangeDarkColorScheme = darkColorScheme(
    primary = Orange80,
    secondary = OrangeGrey80,
    tertiary = OrangeAccent80
)
private val OrangeLightColorScheme = lightColorScheme(
    primary = Orange40,
    secondary = OrangeGrey40,
    tertiary = OrangeAccent40
)

private fun colorSchemeForPalette(palette: AppThemePalette, darkTheme: Boolean): ColorScheme {
    return when (palette) {
        AppThemePalette.BLUE -> if (darkTheme) DarkColorScheme else LightColorScheme
        AppThemePalette.GREEN -> if (darkTheme) GreenDarkColorScheme else GreenLightColorScheme
        AppThemePalette.PURPLE -> if (darkTheme) PurpleDarkColorScheme else PurpleLightColorScheme
        AppThemePalette.ORANGE -> if (darkTheme) OrangeDarkColorScheme else OrangeLightColorScheme
    }
}

@Composable
fun HesimusicTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: AppThemePalette = AppThemePalette.BLUE,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        else -> colorSchemeForPalette(palette = palette, darkTheme = darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
