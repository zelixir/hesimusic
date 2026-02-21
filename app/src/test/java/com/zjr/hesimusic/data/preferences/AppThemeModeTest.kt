package com.zjr.hesimusic.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeModeTest {

    @Test
    fun `parseAppThemeMode returns system for unknown values`() {
        assertEquals(AppThemeMode.SYSTEM, parseAppThemeMode(null))
        assertEquals(AppThemeMode.SYSTEM, parseAppThemeMode("UNKNOWN"))
    }

    @Test
    fun `resolveDarkTheme follows selected mode`() {
        assertEquals(true, resolveDarkTheme(AppThemeMode.SYSTEM, true))
        assertEquals(false, resolveDarkTheme(AppThemeMode.SYSTEM, false))
        assertEquals(false, resolveDarkTheme(AppThemeMode.LIGHT, true))
        assertEquals(true, resolveDarkTheme(AppThemeMode.DARK, false))
    }

    @Test
    fun `parseAppThemePalette returns blue for unknown values`() {
        assertEquals(AppThemePalette.BLUE, parseAppThemePalette(null))
        assertEquals(AppThemePalette.BLUE, parseAppThemePalette("UNKNOWN"))
    }
}
