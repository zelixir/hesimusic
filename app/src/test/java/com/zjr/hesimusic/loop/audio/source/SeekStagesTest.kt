package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 00023 后续：区内 seek 逐段计时的纯逻辑（JVM 可跑）。
 *
 * 真机上这组数字决定优化落点：apply → extractorSeek → firstBuffer → sinkPlay → ready → playout。
 */
class SeekStagesTest {

    private fun num(map: Map<String, Any?>, key: String): Long? =
        (map[key] as? Number)?.toLong()

    @Test
    fun beginResetsEveryStage() {
        val stages = LoopPreviewDebugBridge.SeekStages()
        stages.begin(1_000L, 1_000_000L)
        stages.noteApplied()
        stages.noteExtractorSeek()
        stages.noteFirstBuffer()
        stages.noteSinkPlay()
        stages.noteReady()
        stages.notePlayout(1_000_120L)

        val first = stages.snapshot()
        assertNotNull(first["applyMs"])
        assertNotNull(first["playoutMs"])
        assertEquals(120L, num(first, "playoutMs"))

        stages.begin(2_000L, 2_000_000L)
        val second = stages.snapshot()
        assertNull(second["applyMs"])
        assertNull(second["extractorSeekMs"])
        assertNull(second["firstBufferMs"])
        assertNull(second["sinkPlayMs"])
        assertNull(second["readyMs"])
        assertNull(second["playoutMs"])
        assertNull(second["playoutObservedMs"])
    }

    @Test
    fun stagesAreMonotonicInPipelineOrder() {
        val stages = LoopPreviewDebugBridge.SeekStages()
        stages.begin(5_000L, 5_000_000L)
        stages.noteApplied()
        Thread.sleep(2L)
        stages.noteExtractorSeek()
        Thread.sleep(2L)
        stages.noteFirstBuffer()
        Thread.sleep(2L)
        stages.noteSinkPlay()
        Thread.sleep(2L)
        stages.noteReady()
        stages.notePlayout(5_000_060L)

        val s = stages.snapshot()
        val apply = num(s, "applyMs")!!
        val extractor = num(s, "extractorSeekMs")!!
        val firstBuffer = num(s, "firstBufferMs")!!
        val sinkPlay = num(s, "sinkPlayMs")!!
        val ready = num(s, "readyMs")!!
        assertTrue("apply >= 0", apply >= 0L)
        assertTrue("extractor >= apply", extractor >= apply)
        assertTrue("firstBuffer >= extractor", firstBuffer >= extractor)
        assertTrue("sinkPlay >= firstBuffer", sinkPlay >= firstBuffer)
        assertTrue("ready >= sinkPlay", ready >= sinkPlay)
        assertEquals(60L, num(s, "playoutMs"))
    }

    @Test
    fun repeatedNotesKeepFirstTimestamp() {
        val stages = LoopPreviewDebugBridge.SeekStages()
        stages.begin(3_000L, 3_000_000L)
        stages.noteExtractorSeek()
        stages.noteFirstBuffer()
        val first = num(stages.snapshot(), "firstBufferMs")!!
        Thread.sleep(5L)
        stages.noteFirstBuffer()
        assertEquals(first, num(stages.snapshot(), "firstBufferMs"))
    }

    @Test
    fun firstBufferBeforeExtractorSeekIsIgnored() {
        val stages = LoopPreviewDebugBridge.SeekStages()
        stages.begin(4_000L, 4_000_000L)
        // seek 请求刚发出时，sink 里上一个位置的 buffer 可能被重复递进来：不算数据侧首块。
        stages.noteFirstBuffer()
        assertNull(stages.snapshot()["firstBufferMs"])
        stages.noteExtractorSeek()
        Thread.sleep(2L)
        stages.noteFirstBuffer()
        val s = stages.snapshot()
        val extractor = num(s, "extractorSeekMs")!!
        val firstBuffer = num(s, "firstBufferMs")!!
        assertTrue(firstBuffer >= extractor)
    }

    @Test
    fun playoutBeforeRequestIsIgnored() {
        val stages = LoopPreviewDebugBridge.SeekStages()
        stages.begin(9_000L, 9_000_000L)
        // 上一轮 seek 迟到的 playout 事件（早于本次请求）不能算到本次头上。
        stages.notePlayout(8_999_950L)
        assertNull(stages.snapshot()["playoutMs"])
        // 正常事件仍可记录一次。
        stages.notePlayout(9_000_080L)
        assertEquals(80L, num(stages.snapshot(), "playoutMs"))
        // 第二次事件不覆盖（同一轮只取第一次出声）。
        stages.notePlayout(9_000_500L)
        assertEquals(80L, num(stages.snapshot(), "playoutMs"))
    }

    @Test
    fun stagesWithoutBeginStayEmpty() {
        val stages = LoopPreviewDebugBridge.SeekStages()
        stages.noteApplied()
        stages.noteFirstBuffer()
        stages.notePlayout(1_000L)
        val s = stages.snapshot()
        assertNull(s["applyMs"])
        assertNull(s["firstBufferMs"])
        assertNull(s["playoutMs"])
        assertEquals(0L, num(s, "generation"))
    }
}
