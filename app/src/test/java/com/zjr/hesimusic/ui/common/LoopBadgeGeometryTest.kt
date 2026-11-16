package com.zjr.hesimusic.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 序号下方 ∞ 符号的尺寸换算(issue 00007)。
 *
 * 宽度必须来自字体度量出的「两位数字」宽度 × 0.64(原 0.8 的 80%),高度取宽的一半,
 * 并在行内留白不足(超大字体缩放)时被夹住,保证不画到下一行。
 */
class LoopBadgeGeometryTest {

    @Test
    fun `符号宽度是两位数字宽度的八成,高度取一半`() {
        val geometry = loopBadgeGeometry(twoDigitWidthPx = 20f, maxHeightPx = 16f)

        assertEquals(12.8f, geometry.widthPx, 0.0001f)
        assertEquals(6.4f, geometry.heightPx, 0.0001f)
        assertEquals(6.4f * 0.28f, geometry.strokeWidthPx, 0.0001f)
    }

    @Test
    fun `高度超过行内留白时被夹住,线宽跟着变细`() {
        val geometry = loopBadgeGeometry(twoDigitWidthPx = 100f, maxHeightPx = 10f)

        assertEquals(64f, geometry.widthPx, 0.0001f)
        assertEquals(10f, geometry.heightPx, 0.0001f)
        assertEquals(2.8f, geometry.strokeWidthPx, 0.0001f)
    }

    @Test
    fun `极小字号下线宽保留 1px 下限,零宽度不产生负数`() {
        val tiny = loopBadgeGeometry(twoDigitWidthPx = 1f, maxHeightPx = 16f)
        assertEquals(0.64f, tiny.widthPx, 0.0001f)
        assertEquals(1.5f, tiny.strokeWidthPx, 0.0001f)

        val empty = loopBadgeGeometry(twoDigitWidthPx = 0f, maxHeightPx = 16f)
        assertEquals(0f, empty.widthPx, 0.0001f)
        assertTrue("无字体度量结果时不应画出负尺寸", empty.heightPx >= 0f)
    }
}