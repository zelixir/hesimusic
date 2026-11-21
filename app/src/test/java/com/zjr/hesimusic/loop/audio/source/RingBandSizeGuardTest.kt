package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MediaItem
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 环带启用的尺寸守卫(`RingBandSource.canUse`,纯 JVM,无设备)。
 *
 * 契约:[LoopingPcmExtractorMediaSource.create] 只有在
 * `loopFrames * bytesPerFrame <= heap/6` 时才启用环带(00027 起主/预览同口径);超出预算
 * 必须**降级**(回退旧分块实现),并把可读原因(`reason=SIZE|PROVIDER`、bandBytes、budget)
 * 交给 logger,供 UI/日志明确提示"这首歌太长,无法用环带"而不是静默换实现。
 *
 * 说明:
 * - canUse 的预算参数是**显式传入**的,所以本测试不依赖跑测试的 JVM 堆大小;
 * - 真机 memoryClass 典型值 256~512MB,这里用 402MB(项目文档/真机实测口径);
 * - 10 分钟窗口 = 600s × 48000Hz × 2ch × 2B = 115,200,000 B,大于 402MB/6=67.1MB。
 */
class RingBandSizeGuardTest {

    private val sampleRate = 48_000
    private val channels = 2
    private val bytesPerFrame = channels * 2

    /** 真机 heap 预算(402MB)的典型值;显式传入,避免测试机 JVM 堆影响结论。 */
    private val deviceHeapBytes = 402L * 1024L * 1024L

    private fun window(lengthUs: Long): LoopingPcmTimeline = LoopingPcmTimeline(
        songId = 1L,
        loopStartUs = 0L,
        loopEndUs = lengthUs,
        format = PcmTrackFormat(sampleRate, channels),
    )

    private fun bandBytes(timeline: LoopingPcmTimeline): Long =
        timeline.loopFrames * timeline.bytesPerFrame

    @Test
    fun `十分钟窗口超过真机堆预算时必须拒绝`() {
        val timeline = window(600_000_000L) // 600s
        assertEquals(600L * sampleRate, timeline.loopFrames)
        val bytes = bandBytes(timeline)
        assertEquals(115_200_000L, bytes)
        val budget = RingBandSource.budgetBytes(deviceHeapBytes, RingBandSource.BUDGET_DIVISOR_MAIN)
        assertEquals(70_254_592L, budget)

        assertFalse(
            "10min ring (" + bytes + "B) must exceed device budget " + budget + "B",
            RingBandSource.canUse(timeline, deviceHeapBytes),
        )
        // 降级原因(与 LoopingPcmExtractorMediaSource 的日志口径一致,便于报告引用):
        // bandBytes=115200000 budget=70254592 over=44945408(约 1.64 倍预算)
        println(
            "SIZE-GUARD window=600s bandBytes=" + bytes + " budget=" + budget +
                " over=" + (bytes - budget) + " ratio=" + (bytes.toDouble() / budget),
        )
        assertTrue("reason payload must name the exceeded budget", bytes > budget)
        // heap/6 时代 10 分钟窗口只超出约 1.64 倍(旧 heap/10 时是 2.73 倍),但仍足以被用户感知。
        assertTrue("overrun must be large enough to be user visible", bytes * 2L >= budget * 3L)
    }

    @Test
    fun `一小时窗口在任意常见真机堆上都拒绝`() {
        val timeline = window(3_600_000_000L)
        val bytes = bandBytes(timeline)
        // 402MB / 512MB / 1GB 三种 memoryClass 都不够(预算 67MB ~ 171MB)
        for (heap in longArrayOf(402L, 512L, 1024L) ) {
            val heapBytes = heap * 1024L * 1024L
            assertFalse(
                "60min ring " + bytes + "B must exceed heap=" + heap + "MB",
                RingBandSource.canUse(timeline, heapBytes),
            )
        }
        println("SIZE-GUARD window=3600s bandBytes=" + bytes)
    }

    @Test
    fun `预算边界严格等于堆的六分之一`() {
        val timeline = window(2_000_000L) // 2s
        val bytes = bandBytes(timeline)
        assertEquals(384_000L, bytes) // 2s*48000*4B

        assertTrue("bytes == heap/6 must pass", RingBandSource.canUse(timeline, bytes * 6L))
        assertFalse(
            "bytes = heap/6 + 1 must fail",
            RingBandSource.canUse(timeline, bytes * 6L - 1L),
        )
        // 0/负预算必须拒绝,不能因为整数除法出现"0 字节也通过"的边界
        assertFalse(RingBandSource.canUse(timeline, 0L))
        assertFalse(RingBandSource.canUse(timeline, -1L))
    }

    @Test
    fun `降级原因消息包含字节数与预算`() {
        // 用 fake provider 触发"非 PcmDecoderRangeProvider"分支;这里的目的是钉住
        // 降级原因消息的**格式**(reason/bandBytes/budget 三个要素必须齐全且数值可复现),
        // 避免 UI 层拿到空原因。SIZE 分支共用同一行 logger,只有 reason 取值不同。
        val timeline = window(600_000_000L)
        val logs = captureSelectionLogs(
            timeline = timeline,
            provider = PcmRangeProvider { _, _ ->
                Pcm16Segment(ByteArray(0), sampleRate, channels, 0L)
            },
        )
        val fallback = logs.firstOrNull { it.contains("RingBand fallback") }
            ?: fail("fallback log missing; logs=" + logs).let { return }
        assertTrue(
            "must explain why: " + fallback,
            fallback.contains("reason=SIZE") || fallback.contains("reason=PROVIDER"),
        )
        assertTrue("must report band bytes: " + fallback, fallback.contains("bandBytes=115200000"))
        assertTrue(
            "must report budget (heap/6): " + fallback,
            fallback.contains("budget=" + (Runtime.getRuntime().maxMemory() / 6L)),
        )
        println("SIZE-GUARD fallback-log=" + fallback)
    }

    @Test
    fun `SIZE 分支同样能给出降级原因`() {
        // RingBand 只在 provider 是 PcmDecoderRangeProvider 时才可能启用;为了在 JVM 上
        // 走到 SIZE 分支,这里只分配一个未初始化实例(不调用任何解码方法),用它通过类型检查。
        // 降级路径不会调用 provider.decode()/path,所以这不伪造任何解码数据。
        val decoder = allocateWithoutConstructor(PcmDecoder::class.java)
        val provider = PcmDecoderRangeProvider(decoder, "/size-guard/never-decoded.flac")
        val heap = Runtime.getRuntime().maxMemory()
        // 优先用 10 分钟窗口(115,200,000B);若跑测试的 JVM 堆特别大(budget > 窗口),
        // 退回 60 分钟窗口以保证确实越过预算。断言的是"超预算 -> reason=SIZE",不依赖具体堆。
        val tenMinutes = window(600_000_000L)
        val budget = RingBandSource.budgetBytes(heap, RingBandSource.BUDGET_DIVISOR_MAIN)
        val timeline = if (bandBytes(tenMinutes) > budget) tenMinutes else window(3_600_000_000L)
        assertTrue(
            "test premise: ring " + bandBytes(timeline) + "B must exceed JVM budget " + budget + "B",
            bandBytes(timeline) > budget,
        )
        assertFalse(RingBandSource.canUse(timeline, heap))

        val logs = captureSelectionLogs(timeline = timeline, provider = provider)
        val fallback = logs.firstOrNull { it.contains("RingBand fallback") }
            ?: fail("fallback log missing; logs=" + logs).let { return }
        assertTrue("must name SIZE as the reason: " + fallback, fallback.contains("reason=SIZE"))
        assertTrue(
            "must report exact band bytes: " + fallback,
            fallback.contains("bandBytes=" + bandBytes(timeline)),
        )
        assertTrue(
            "must report exact budget: " + fallback,
            fallback.contains("budget=" + RingBandSource.budgetBytes(heap, RingBandSource.BUDGET_DIVISOR_MAIN)),
        )
        println("SIZE-GUARD size-log=" + fallback)
    }

    // ---------------------------------------------------------------- helpers

    private fun captureSelectionLogs(
        timeline: LoopingPcmTimeline,
        provider: PcmRangeProvider,
    ): List<String> {
        val logs = ArrayList<String>()
        val item = try {
            MediaItem.Builder().setMediaId("ring-band-size-guard").build()
        } catch (e: Throwable) {
            throw AssertionError(
                "MediaItem cannot be built on the unit-test JVM; " +
                    "this test must not fake the selection path: " + e,
                e,
            )
        }
        // create() 的降级路径会先 logger(...) 再组装 MediaSource;JVM 上 MediaSource
        // 组装若碰到 Android Looper 会抛异常,这里只关心已经打出的原因日志。
        try {
            LoopingPcmExtractorMediaSource.create(
                item = item,
                timeline = timeline,
                provider = provider,
                logger = { logs.add(it) },
            )
        } catch (_: Throwable) {
            // 断言只看 logger;组装失败与"是否给出降级原因"无关。
        }
        return logs
    }

    /**
     * 不调用构造器分配实例(仅用于 JVM 上通过 PcmDecoderRangeProvider 的类型检查)。
     * 若运行 JDK 不允许该技巧,测试直接失败并说明原因,而不是静默跳过。
     */
    private fun <T> allocateWithoutConstructor(type: Class<T>): T {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        return allocate.invoke(unsafe, type) as T
    }
}