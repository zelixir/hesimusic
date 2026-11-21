package com.zjr.hesimusic.service

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.LoopEligibility
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.PcmTrackFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D14：窗口合法性只能有一套口径。
 *
 * 同一个窗口必须在这四条路径上得到同一个结论：
 * 1. 主播放资格 [LoopEligibility.derive]（进入循环）；
 * 2. claim 真值 [LoopClaimResolver]（UI/MediaSession/通知）；
 * 3. SOURCE 预检 [LoopSourceFallback.evaluate]（回退判定）；
 * 4. M3 音频层写入前的闸门（协调器实际使用 [LoopWindowRules.writable]）。
 *
 * 修复前 1-sample / 499ms 窗口在 eligibility 里"有资格"，在 claim/fallback 里非法；
 * 本测试先按统一口径断言，修复前应为红。
 */
class LoopWindowConsistencyTest {

    private val songId = 7L
    private val format = PcmTrackFormat(sampleRate = 48_000, channels = 2)
    private val durationUs = 30_000_000L

    private val zero = LoopWindow(songId, 10_000_000L, 10_000_000L)
    private val reversed = LoopWindow(songId, 12_000_000L, 10_000_000L)
    private val oneSample = LoopWindow(songId, 17_496_000L, 17_496_023L)
    private val oneMs = LoopWindow(songId, 10_000_000L, 10_001_000L)
    private val under500 = LoopWindow(songId, 10_000_000L, 10_499_999L)
    private val exactly500 = LoopWindow(songId, 10_000_000L, 10_500_000L)
    private val pastDuration = LoopWindow(songId, 20_000_000L, 30_500_000L)

    private val badWindows = listOf(zero, reversed, oneSample, oneMs, under500)

    @After
    fun tearDown() {
        LoopClaimStore.resetForTest()
        LoopSourceFallback.reset()
        LoopSourceRuntime.reset()
    }

    private fun rowOf(window: LoopWindow, confirmed: Boolean = true): SongLoop = SongLoop(
        songId = window.songId,
        loopStartSamples = window.loopStartUs / 1_000L * 44L,
        loopEndSamples = window.loopEndUs / 1_000L * 44L,
        sampleRate = 44_100,
        loopStartMs = window.loopStartUs / 1_000L,
        loopEndMs = window.loopEndUs / 1_000L,
        score = 0.9,
        noteDistance = 0.1,
        loudnessDifference = 0.2,
        createdAt = 1_700_000_000_000L,
        confirmed = confirmed,
    )

    private fun eligible(window: LoopWindow): LoopWindow? = LoopEligibility.derive(
        songId = songId,
        repeatMode = Player.REPEAT_MODE_ONE,
        row = rowOf(window),
        armedSongId = songId,
        durationUs = durationUs,
    )

    private fun claimOf(window: LoopWindow): LoopClaim = LoopClaimResolver.resolve(
        songId = songId,
        runtimeWindow = window,
        audioWindow = window,
        installedWindow = window,
        isReplaying = false,
        sourceRoute = true,
        sourceUnavailable = false,
    )

    private fun fallbackReason(window: LoopWindow): LoopSourceFallback.Reason {
        val decision = LoopSourceFallback.evaluate(
            window = window,
            songId = songId,
            filePath = "/music/a.mp3",
            probe = { format },
            heapBytes = 402_653_184L,
            durationUs = durationUs,
        )
        return (decision as LoopSourceFallback.Decision.UseM3Prime).reason
    }

    @Test
    fun `sub minimum windows are rejected by every path`() {
        for (window in badWindows) {
            assertNull("eligibility 必须拒绝 " + window, eligible(window))
            assertFalse("claim 必须拒绝 " + window, claimOf(window).claimActive)
            assertEquals(
                "fallback 必须判 window-invalid " + window,
                LoopSourceFallback.Reason.WINDOW_INVALID,
                fallbackReason(window),
            )
            assertNull("M3 写入闸门必须拒绝 " + window, LoopWindowRules.writable(window, durationUs))
        }
    }

    @Test
    fun `past duration windows are rejected by every path`() {
        assertNull("eligibility 必须拒绝越界窗口", eligible(pastDuration))
        assertNull("M3 写入闸门必须拒绝越界窗口", LoopWindowRules.writable(pastDuration, durationUs))
        assertEquals(
            LoopSourceFallback.Reason.WINDOW_INVALID,
            fallbackReason(pastDuration),
        )
        // claim 本身没有时长输入：越界窗口必须在写入前就被挡住，所以 claim 看到的只是
        // "窗口没写进音频层"→ 不生效。
        val claim = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = null,
            audioWindow = null,
            installedWindow = null,
            isReplaying = false,
            sourceRoute = true,
            sourceUnavailable = false,
        )
        assertFalse(claim.claimActive)
    }

    @Test
    fun `exactly 500ms passes every path`() {
        assertEquals(exactly500, eligible(exactly500))
        assertTrue(claimOf(exactly500).claimActive)
        assertEquals(
            LoopSourceFallback.Decision.UseSource::class.java,
            LoopSourceFallback.evaluate(
                window = exactly500,
                songId = songId,
                filePath = "/music/a.mp3",
                probe = { format },
                heapBytes = 402_653_184L,
                durationUs = durationUs,
            ).javaClass,
        )
        assertEquals(exactly500, LoopWindowRules.writable(exactly500, durationUs))
    }
}
