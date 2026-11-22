package com.zjr.hesimusic.lab

import androidx.compose.ui.graphics.Color
import java.util.Locale

internal val GoodColor = Color(0xFF2E7D32)
internal val BadColor = Color(0xFFC62828)
internal val WarnColor = Color(0xFFEF6C00)

internal fun fmt(value: Double, decimals: Int = 3): String =
    if (!value.isFinite()) "—" else String.format(Locale.US, "%.${decimals}f", value)

internal fun signed(value: Double, decimals: Int = 3): String =
    if (!value.isFinite()) "—" else String.format(Locale.US, "%+.${decimals}f", value)

internal fun pct(fraction: Double): String =
    if (!fraction.isFinite()) "—" else String.format(Locale.US, "%.0f%%", fraction * 100.0)

internal fun fmtBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}
