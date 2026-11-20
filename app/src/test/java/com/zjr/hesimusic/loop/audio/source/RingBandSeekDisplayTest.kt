package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * issue 00023 裁决 a:区内 forward/backward seek 的**落点**必须等于请求值。
 *
 * 这里把整条链路(展示位置 → 数据源时间轴 T → 环内 offset → 首块 PTS → 展示位置)
 * 用纯 JVM 串起来,窗口取失败场景同口径:Redstar Rising A=17496ms B=50167ms。
 * 旧实现在 backward 时会把 PTS 留在"读前位置"(真机 seek 25000 停在 45905),
 * 本测试锁住"PTS 基准由 seek 决定"。
 */
class RingBandSeekDisplayTest {

    private val sr = 1_000
    private val aMs = 17_496L
    private val bMs = 50_167L
    private val window = LoopWindow(
        songId = 39944L,
        loopStartUs = aMs * 1_000L,
        loopEndUs = bMs * 1_000L,
    )
    private val timeline = LoopingPcmTimeline(
        songId = 39944L,
        loopStartUs = window.loopStartUs,
        loopEndUs = window.loopEndUs,
        format = PcmTrackFormat(sr, 1),
    )
    private val ring = LoopRing(sr, 1, timeline.loopFrames).also { r ->
        // 整环填满(内容不重要,只看 PTS/offset)。
        r.append(ByteArray((timeline.loopFrames * timeline.bytesPerFrame).toInt()))
    }
    private val reader = RingBandReader(ring)

    /**
     * 走一遍 PreviewPlayer.seekToDisplayMs(in-loop) + RingBandExtractor.seek 的投影,
     * 返回首个 PCM 块的展示位置(ms)。
     */
    private fun seekToDisplay(targetMs: Long, currentTimelineMs: Long): Long {
        val targetTUs = LoopSourceProjection.timelineUsForDisplay(
            displayUs = targetMs * 1_000L,
            currentTimelineUs = currentTimelineMs * 1_000L,
            window = window,
        )
        val offset = Math.floorMod(
            timeline.frameAtTimeline(targetTUs) - timeline.aFrame,
            timeline.loopFrames,
        )
        reader.seekToOffset(offset, targetTUs)
        val read = reader.read(200)
        assertNotNull("seek 后必须能立刻读到块(整环已填)", read)
        return LoopSourceProjection.displayUs(read!!.ptsUs, window) / 1_000L
    }

    @Test
    fun insideForwardSeeksLandOnTarget() {
        var currentT = 2_000L // 播放头在 A+2s
        for (target in listOf(22_000L, 30_000L, 45_000L)) {
            val display = seekToDisplay(target, currentT)
            assertTrue(
                "forward target=" + target + " 落点=" + display,
                abs(display - target) <= 2L,
            )
            // 播放推进 200 帧后再做下一次 seek。
            currentT = reader.read(200)!!.ptsUs / 1_000L + 200L
        }
    }

    @Test
    fun insideBackwardSeeksLandOnTarget() {
        var currentT = 2_000L
        currentT = seekToDisplay(40_000L, currentT) // 先前进到 40s 附近
        for (target in listOf(25_000L, 20_000L, 18_000L)) {
            val display = seekToDisplay(target, currentT)
            assertTrue(
                "backward target=" + target + " 落点=" + display + "(旧实现在这里会停在读前位置)",
                abs(display - target) <= 2L,
            )
            currentT = reader.read(200)!!.ptsUs / 1_000L + 200L
        }
    }

    @Test
    fun backwardAfterCrossingFirstRoundLandsOnTarget() {
        // 播放头已经跨过第 1 轮(T = L + 5s),展示位置折回 A+5s;再往回拖到 A+1004ms。
        val currentT = timeline.lengthUs / 1_000L + 5_000L
        val display = seekToDisplay(aMs + 1_004L, currentT)
        assertEquals("跨轮之后的 backward 落点", aMs + 1_004L, display)
    }

    @Test
    fun totalReadFramesStaysMonotonicAcrossBackwardSeek() {
        seekToDisplay(40_000L, 2_000L)
        val before = reader.totalReadFrames
        val display = seekToDisplay(20_000L, 22_504L)
        assertTrue("落点必须回退到 20s(实际 " + display + ")", abs(display - 20_000L) <= 2L)
        assertTrue(
            "totalReadFrames 不能回退(节流/覆盖率统计仍单调): " + before + " -> " + reader.totalReadFrames,
            reader.totalReadFrames > before,
        )
    }

    @Test
    fun segmentBaseFollowsSeekTarget() {
        seekToDisplay(30_000L, 2_000L)
        val firstBase = reader.segmentBasePtsUs
        seekToDisplay(20_000L, firstBase / 1_000L + 200L)
        val secondBase = reader.segmentBasePtsUs
        assertTrue(
            "回退 seek 之后段基准必须变小: " + firstBase + " -> " + secondBase,
            secondBase < firstBase,
        )
    }
}
