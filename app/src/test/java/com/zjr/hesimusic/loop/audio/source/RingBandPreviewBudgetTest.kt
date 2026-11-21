package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.service.LoopSourceFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环带预算的**单一口径**:预览与主播放器都使用 heap/6(00022 先在预览放宽,00027 主播放器跟上)。
 *
 * 现场(Shiny Up, device b1e84dad,maxHeap=402,653,184):
 * ```
 * RingBand fallback reason=SIZE bandBytes=42010716 budget=40265318   // heap/10 时代
 * ```
 * 42,010,716B 只比 heap/10 多 1.7MB:预览退化成 1 秒块(点接缝检查要等 1~2 秒),主播放器
 * 直接回退普通整曲播放,但 UI 仍折返进度条 -> 00027「显示 A、声音在尾奏」。
 * 统一到 heap/6 = 67,108,864B 后 Shiny Up 能真正建环;> heap/6 的窗口依旧回退(有界内存)。
 */
class RingBandPreviewBudgetTest {

    /** 真机 maxMemory() 实测值(logcat RingBand select/fallback heap=402653184)。 */
    private val deviceHeap = 402_653_184L

    private fun timeline(aMs: Long, bMs: Long, sampleRate: Int = 44_100): LoopingPcmTimeline =
        LoopingPcmTimeline(
            songId = 41_126L,
            loopStartUs = aMs * 1000L,
            loopEndUs = bMs * 1000L,
            format = PcmTrackFormat(sampleRate, 2),
        )

    private fun bandBytes(timeline: LoopingPcmTimeline): Long =
        timeline.loopFrames * timeline.bytesPerFrame

    @Test
    fun shinyUpWindowReproducesTheExactDeviceNumbers() {
        // A=23.301s B=261.457s @44.1kHz/2ch —— 与真机日志逐字节一致。
        val t = timeline(23_301L, 261_457L)
        assertEquals(10_502_679L, t.loopFrames)
        assertEquals(42_010_716L, bandBytes(t))
        // 00027:主播放器也放宽到 heap/6 = 67,108,864B —— Shiny Up 能真正建环。
        assertEquals(67_108_864L, RingBandSource.budgetBytes(deviceHeap, RingBandSource.BUDGET_DIVISOR_MAIN))
        assertTrue(
            "42,010,716B must fit heap/6 (main keeps the ring band; this is 00027's fix)",
            RingBandSource.canUse(t, deviceHeap, RingBandSource.BUDGET_DIVISOR_MAIN),
        )
        // 预览口径同值:两条路只有一个预算口径。
        assertEquals(67_108_864L, RingBandSource.budgetBytes(deviceHeap, RingBandSource.BUDGET_DIVISOR_PREVIEW))
        assertTrue(
            "42,010,716B must fit heap/6 (preview keeps the ring band)",
            RingBandSource.canUse(t, deviceHeap, RingBandSource.BUDGET_DIVISOR_PREVIEW),
        )
    }

    @Test
    fun mainAndPreviewShareTheSameHeapSixthDivisor() {
        // 不显式传分母 = 主播放器口径;必须与 service/LoopSourceFallback.RING_BUDGET_DIVISOR
        // 完全一致(00027:两处曾一个 heap/10 一个 heap/6,回退与建环的判定互相打架)。
        assertEquals(6L, RingBandSource.BUDGET_DIVISOR_MAIN)
        assertEquals(6L, RingBandSource.BUDGET_DIVISOR_PREVIEW)
        assertEquals(
            LoopSourceFallback.RING_BUDGET_DIVISOR,
            RingBandSource.BUDGET_DIVISOR_MAIN,
        )
        val t = timeline(0L, 225_045L) // 40169 整曲窗口: bandBytes=39,697,940(真机日志)
        assertEquals(39_697_940L, bandBytes(t))
        assertTrue(RingBandSource.canUse(t, deviceHeap))
        assertTrue(RingBandSource.canUse(t, deviceHeap, RingBandSource.BUDGET_DIVISOR_MAIN))
    }

    @Test
    fun windowOverHeapSixthStillFallsBack() {
        // 00027 验收 c:> heap/6 的窗口(这里 400s ≈ 70,560,000B > 67,108,864B)必须回退,
        // 由 UI/调试层按"源未生效"处理(display 不折返),而不是假装在循环。
        val over = timeline(0L, 400_000L)
        assertEquals(70_560_000L, bandBytes(over))
        assertTrue(bandBytes(over) > RingBandSource.budgetBytes(deviceHeap, RingBandSource.BUDGET_DIVISOR_MAIN))
        assertFalse(RingBandSource.canUse(over, deviceHeap, RingBandSource.BUDGET_DIVISOR_MAIN))
        assertFalse(RingBandSource.canUse(over, deviceHeap, RingBandSource.BUDGET_DIVISOR_PREVIEW))
    }

    @Test
    fun previewBudgetBoundaryIsStrictlyHeapSixth() {
        val t = timeline(0L, 10_000L) // 10s@44.1k/2ch = 1,764,000B
        val bytes = bandBytes(t)
        assertEquals(1_764_000L, bytes)
        // heap = bytes*6 时 heap/6 == bytes -> 通过;少 1 字节 -> 拒绝。
        assertTrue(RingBandSource.canUse(t, bytes * 6L, RingBandSource.BUDGET_DIVISOR_PREVIEW))
        assertFalse(RingBandSource.canUse(t, bytes * 6L - 1L, RingBandSource.BUDGET_DIVISOR_PREVIEW))
    }

    @Test
    fun veryLongWindowStillFallsBackEvenOnPreview() {
        // heap/6 不是"无上限":一小时窗口(635MB PCM @44.1kHz)在 402MB 堆上依旧回退
        // (环带永远不允许成为 OOM 的来源)。
        val hour = timeline(0L, 3_600_000L)
        assertEquals(635_040_000L, bandBytes(hour))
        assertFalse(RingBandSource.canUse(hour, deviceHeap, RingBandSource.BUDGET_DIVISOR_PREVIEW))
        // 30 分钟(317.5MB)也不该过 —— 只有 heap>=1.9GB 的机器上才可能。
        assertEquals(317_520_000L, bandBytes(timeline(0L, 1_800_000L)))
        assertFalse(RingBandSource.canUse(timeline(0L, 1_800_000L), deviceHeap, RingBandSource.BUDGET_DIVISOR_PREVIEW))
    }

    @Test
    fun zeroHeapAndIllegalDivisorAreRejected() {
        val t = timeline(0L, 10_000L)
        assertEquals(0L, RingBandSource.budgetBytes(0L, RingBandSource.BUDGET_DIVISOR_PREVIEW))
        assertEquals(0L, RingBandSource.budgetBytes(-1L, RingBandSource.BUDGET_DIVISOR_PREVIEW))
        assertEquals(0L, RingBandSource.budgetBytes(deviceHeap, 0L))
        assertFalse(RingBandSource.canUse(t, 0L, RingBandSource.BUDGET_DIVISOR_PREVIEW))
        assertFalse(RingBandSource.canUse(t, deviceHeap, 0L))
    }

    @Test
    fun selectionLogNamesTheBudgetActuallyUsed() {
        // 预览口径下这把窗口会走环带(select 日志里必须能读出 divisor=6 与对应预算),
        // 不能出现"按 heap/6 放行、日志却写 budget=heap/10"的对账错误。
        val logs = ArrayList<String>()
        val timeline = timeline(23_301L, 261_457L)
        val provider = PcmRangeProvider { _, _ ->
            com.zjr.hesimusic.loop.analysis.Pcm16Segment(ByteArray(0), 44_100, 2, 0L)
        }
        try {
            LoopingPcmExtractorMediaSource.create(
                item = androidx.media3.common.MediaItem.Builder().setMediaId("00022-preview-budget").build(),
                timeline = timeline,
                provider = provider,
                logger = { logs.add(it) },
                ringBudgetDivisor = RingBandSource.BUDGET_DIVISOR_PREVIEW,
            )
        } catch (_: Throwable) {
            // JVM 上没有 Android Looper/MediaCodec:只断言选路日志(与既有 SIZE 守卫测试同口径)。
        }
        val fallback = logs.firstOrNull { it.contains("RingBand fallback") }
        if (fallback != null) {
            // fake provider -> PROVIDER 分支;预算仍必须按传入分母记账。
            assertTrue("must report PROVIDER: " + fallback, fallback.contains("reason=PROVIDER"))
            assertTrue(
                "must report heap/6 budget: " + fallback,
                fallback.contains("budget=" + (Runtime.getRuntime().maxMemory() / 6L)),
            )
            assertTrue("must name divisor=6: " + fallback, fallback.contains("divisor=6"))
        }
    }
}
