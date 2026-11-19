package com.zjr.hesimusic.ui.about

import com.zjr.hesimusic.loop.audio.source.LoopEngineInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关于页信息区格式化单测(纯 JVM,不依赖 Compose/Android/Robolectric)。
 *
 * 覆盖:版本串、渠道、引擎文案、循环路径、回退原因可见性、环带体积、
 * 整段复制文本的列对齐,以及 `LoopEngineInfo` 引擎常量 → 循环路径的归一化。
 */
class AboutInfoFormatTest {

    private fun info(
        versionName: String = "2.3.0",
        versionCode: Long = 1234L,
        flavor: String = "prod",
        buildTime: String = "2026-11-19 19:42:10",
        engine: String = LoopEngineInfo.ENGINE_SOURCE_RING,
        loopPath: String = "source",
        bandBytes: Long = 7_680_000L,
        fallbackReason: String? = null,
        engineRaw: String = "",
    ) = AboutInfo(
        versionName = versionName,
        versionCode = versionCode,
        flavor = flavor,
        buildTime = buildTime,
        engine = engine,
        loopPath = loopPath,
        bandBytes = bandBytes,
        fallbackReason = fallbackReason,
        engineRaw = engineRaw,
    )

    @Test
    fun `version line combines name and code`() {
        assertEquals("2.3.0 (1234)", AboutInfoText.formatVersionLine("2.3.0", 1234L))
        assertEquals("2.3.0-demo (7)", AboutInfoText.formatVersionLine(" 2.3.0-demo ", 7L))
        // 没有 release 参数时 versionName 回落到 0.1(code 回落到 1),仍要可读。
        assertEquals("0.1 (1)", AboutInfoText.formatVersionLine("0.1", 1L))
        assertEquals("未知 (1)", AboutInfoText.formatVersionLine(null, 1L))
        assertEquals("未知 (1)", AboutInfoText.formatVersionLine("   ", 1L))
    }

    @Test
    fun `channel is the raw flavor in lower case`() {
        assertEquals("prod", AboutInfoText.formatChannel("prod"))
        assertEquals("demo", AboutInfoText.formatChannel(" Demo "))
        assertEquals("未知", AboutInfoText.formatChannel(null))
        assertEquals("未知", AboutInfoText.formatChannel(""))
    }

    @Test
    fun `engine labels map runtime values to readable text`() {
        // LoopEngineInfo 的运行时常量。
        assertEquals("环带 (SOURCE)", AboutInfoText.formatEngine(LoopEngineInfo.ENGINE_SOURCE_RING, "source"))
        assertEquals("老引擎 (M3′)", AboutInfoText.formatEngine(LoopEngineInfo.ENGINE_M3_PRIME, "m3"))
        // LoopSourceMode 的枚举名 / 已本地化文案 / 普通播放。
        assertEquals("环带 (SOURCE)", AboutInfoText.formatEngine("SOURCE", null))
        assertEquals("老引擎 (M3′)", AboutInfoText.formatEngine("M3_PRIME", null))
        assertEquals("无循环 (普通播放)", AboutInfoText.formatEngine("exo", null))
        assertEquals("无循环 (普通播放)", AboutInfoText.formatEngine(null, "none"))
        // 未知值原样展示,不吞信息。
        assertEquals("custom-engine", AboutInfoText.formatEngine("custom-engine", null))
        assertEquals("未知", AboutInfoText.formatEngine(null, null))
        assertEquals("未知", AboutInfoText.formatEngine("  ", " "))
    }

    @Test
    fun `loop path is normalized to source m3 none`() {
        assertEquals("source", AboutInfoText.formatLoopPath("source"))
        assertEquals("m3", AboutInfoText.formatLoopPath(" M3 "))
        assertEquals("m3", AboutInfoText.formatLoopPath("m3_prime"))
        assertEquals("none", AboutInfoText.formatLoopPath("none"))
        assertEquals("未知", AboutInfoText.formatLoopPath(null))
    }

    @Test
    fun `fallback reason only shows on a real fallback`() {
        assertEquals("ring-too-large", AboutInfoText.formatFallbackReason("ring-too-large"))
        assertEquals("RING_TOO_LARGE", AboutInfoText.formatFallbackReason(" RING_TOO_LARGE "))
        assertNull(AboutInfoText.formatFallbackReason(null))
        assertNull(AboutInfoText.formatFallbackReason(""))
        assertNull(AboutInfoText.formatFallbackReason("none"))
        assertNull(AboutInfoText.formatFallbackReason("NULL"))
        assertNull(AboutInfoText.formatFallbackReason("-"))
        assertNull(AboutInfoText.formatFallbackReason("未知"))
    }

    @Test
    fun `band bytes are humanized and hidden when absent`() {
        assertEquals("7.32 MB", AboutInfoText.formatBandBytes(7_680_000L))
        assertEquals("1.00 MB", AboutInfoText.formatBandBytes(1_048_576L))
        assertEquals("1.5 KB", AboutInfoText.formatBandBytes(1536L))
        assertEquals("512 B", AboutInfoText.formatBandBytes(512L))
        assertNull(AboutInfoText.formatBandBytes(0L))
        assertNull(AboutInfoText.formatBandBytes(-1L))
    }

    @Test
    fun `rows keep the spec order and skip absent diagnostics`() {
        val rows = AboutInfoText.rows(info())
        assertEquals(
            listOf("版本", "构建渠道", "构建时间", "循环引擎", "循环路径", "环带大小"),
            rows.map { it.label },
        )
        assertEquals("2.3.0 (1234)", rows[0].value)
        assertEquals("prod", rows[1].value)
        assertEquals("2026-11-19 19:42:10", rows[2].value)
        assertEquals("环带 (SOURCE)", rows[3].value)
        assertEquals("source", rows[4].value)
        assertEquals("7.32 MB", rows[5].value)

        // 回退时:引擎变老引擎,并多出「回退原因」行。
        val fallbackRows = AboutInfoText.rows(
            info(engine = LoopEngineInfo.ENGINE_M3_PRIME, loopPath = "m3", bandBytes = 0L, fallbackReason = "ring-too-large")
        )
        assertEquals("老引擎 (M3′)", fallbackRows[3].value)
        assertEquals("m3", fallbackRows[4].value)
        assertEquals(listOf("版本", "构建渠道", "构建时间", "循环引擎", "循环路径", "回退原因"), fallbackRows.map { it.label })
        assertEquals("ring-too-large", fallbackRows[5].value)

        // 引擎原始值(display 单行)只在其非空时追加在末尾。
        val rawRows = AboutInfoText.rows(info(engineRaw = "ENGINE=SOURCE_RING BAND_BYTES=7680000"))
        assertEquals("引擎原始值", rawRows.last().label)
        assertEquals("ENGINE=SOURCE_RING BAND_BYTES=7680000", rawRows.last().value)
    }

    @Test
    fun `clipboard text aligns the colon column and carries everything`() {
        val text = AboutInfoText.formatForClipboard(info(fallbackReason = "RING_TOO_LARGE"))
        val lines = text.split("\n")
        // 列对齐:标签(含冒号)补齐到 10 显示列,值从第 12 列开始,和需求里的样例一致。
        assertEquals("版本:      2.3.0 (1234)", lines[0])
        assertEquals("构建渠道:  prod", lines[1])
        assertEquals("构建时间:  2026-11-19 19:42:10", lines[2])
        assertEquals("循环引擎:  环带 (SOURCE)", lines[3])
        assertEquals("循环路径:  source", lines[4])
        assertEquals("环带大小:  7.32 MB", lines[5])
        assertEquals("回退原因:  RING_TOO_LARGE", lines[6])
        lines.forEach { line ->
            val label = line.substringBefore(": ") + ":"
            assertTrue("value column drifted: " + line, AboutInfoText.displayWidth(label) <= AboutInfoText.LABEL_COLUMNS)
        }
        // 每行都是「label: value」,没有空行。
        assertEquals(7, lines.size)
        assertTrue(lines.none { it.isBlank() })
    }

    @Test
    fun `display width counts CJK as two columns`() {
        assertEquals(4, AboutInfoText.displayWidth("版本"))
        assertEquals(9, AboutInfoText.displayWidth("构建渠道:"))
        assertEquals(3, AboutInfoText.displayWidth("abc"))
        assertEquals(3, AboutInfoText.displayWidth("M3′"))
        assertEquals(10, AboutInfoText.displayWidth(AboutInfoText.padDisplayWidth("构建渠道:", 10)))
        // 已超宽时原样返回,不截断。
        assertEquals("构建渠道:", AboutInfoText.padDisplayWidth("构建渠道:", 4))
    }

    @Test
    fun `engine constant maps to the loop path reported on the about page`() {
        assertEquals("source", LoopEngineInfoSource.loopPathOf(LoopEngineInfo.ENGINE_SOURCE_RING))
        assertEquals("m3", LoopEngineInfoSource.loopPathOf(LoopEngineInfo.ENGINE_M3_PRIME))
        assertEquals("m3", LoopEngineInfoSource.loopPathOf(" m3 "))
        assertEquals("none", LoopEngineInfoSource.loopPathOf(null))
        assertEquals("none", LoopEngineInfoSource.loopPathOf("unknown"))
    }
}
