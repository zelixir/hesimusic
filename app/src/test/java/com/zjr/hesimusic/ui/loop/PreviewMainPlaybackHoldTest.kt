package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.ui.loop.state.AnalysisPhase
import com.zjr.hesimusic.ui.loop.state.DragState
import com.zjr.hesimusic.ui.loop.state.LoopPageMode
import com.zjr.hesimusic.ui.loop.state.LoopPageState
import com.zjr.hesimusic.ui.loop.state.PlaybackState
import com.zjr.hesimusic.ui.loop.state.PrefillState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #107 / 体验审计 D11：分析页与主播放的交接。
 *
 * 用户裁决（四条判据）：
 * 1. 播放中启动分析 → 主播放**继续播**（分析期间不 pause）；
 * 2. 分析完成 → 打开结果页 → 主播放暂停（预览可出声）；
 * 3. 返回 → 恢复为**进入结果页前**的状态：在播 → 继续播；暂停 → 仍暂停；
 * 4. 播放中启动分析、不打开结果页直接返回 → 主播放一直在播。
 *
 * [PreviewMainPlaybackHold] 是 LoopScreen 的唯一交接决策点（纯 JVM，可测）；这些用例
 * 按真实页面状态序列喂它，断言 pause/resume 的**恰好一次**语义。
 */
class PreviewMainPlaybackHoldTest {

    private val analyzing = LoopPageState.Analyzing(AnalysisPhase.DECODING, 0.2f)
    private val analyzingCandidates = LoopPageState.Analyzing(AnalysisPhase.ANALYZING, 0.7f)

    private fun ready(): LoopPageState.Ready = LoopPageState.Ready(
        songId = 42L,
        durationMs = 200_000L,
        sampleRate = 44_100,
        mode = LoopPageMode.FULL,
        aMs = 10_000L,
        bMs = 60_000L,
        candidates = listOf(
            LoopCandidateKt(
                startSamples = 441_000L,
                endSamples = 2_646_000L,
                score = 0.9,
                noteDistance = 0.05,
                loudnessDifference = 0.05,
            ),
        ),
        selectedCandidate = 0,
        confirmed = false,
        prefill = PrefillState.NotStarted,
        playback = PlaybackState.Stopped,
        positionMs = 0L,
        drag = DragState.Idle,
    )

    // ---------------------------------------------------------------- 判据 1

    @Test
    fun `criterion 1 - starting analysis while playing never pauses main playback`() {
        val hold = PreviewMainPlaybackHold()

        // 真实序列：列表长按 → 进入 loop 页（Loading）→ 解码中 → 分析中。
        assertFalse("Loading 不能接管主播放", hold.onPageStateChanged(LoopPageState.Loading, mainPlaying = true))
        assertFalse("解码期间不能暂停主播放", hold.onPageStateChanged(analyzing, mainPlaying = true))
        assertFalse("候选分析期间不能暂停主播放", hold.onPageStateChanged(analyzingCandidates, mainPlaying = true))

        assertFalse("分析期间从未接管主播放", hold.isHolding)
        // 主播放侧没有被 pause 过 → 判据「帧数继续增长」的模型侧保证。
    }

    @Test
    fun `analysis failure also leaves main playback untouched`() {
        val hold = PreviewMainPlaybackHold()
        assertFalse(
            hold.onPageStateChanged(LoopPageState.Failed("未找到歌曲"), mainPlaying = true),
        )
        assertFalse(hold.isHolding)
        assertFalse("失败退出时不应恢复（本就没暂停）", hold.onPageLeft())
    }

    // ---------------------------------------------------------------- 判据 2

    @Test
    fun `criterion 2 - entering the result page pauses main playback exactly once`() {
        val hold = PreviewMainPlaybackHold()

        hold.onPageStateChanged(analyzing, mainPlaying = true)
        assertTrue(
            "第一次进入结果页必须暂停主播放（预览试听要独占音频）",
            hold.onPageStateChanged(ready(), mainPlaying = true),
        )
        assertEquals("暂停时要记住进入前正在播", true, hold.heldWhilePlaying)

        // 配置变更重建 / 同一 Ready 重复组合：不能再 pause 一次。
        assertFalse(
            "重复 Ready（重建/重组）不得再次暂停",
            hold.onPageStateChanged(ready(), mainPlaying = false),
        )
        assertTrue(hold.isHolding)
    }

    @Test
    fun `result page reached directly from saved result still pauses`() {
        // 有 song_loop 行时 resolveEntry 直接 AnalysisDone：首帧就是 Ready。
        val hold = PreviewMainPlaybackHold()
        assertTrue(hold.onPageStateChanged(ready(), mainPlaying = true))
        assertEquals(true, hold.heldWhilePlaying)
    }

    // ---------------------------------------------------------------- 判据 3

    @Test
    fun `criterion 3 - leaving after playing resumes playback`() {
        val hold = PreviewMainPlaybackHold()
        hold.onPageStateChanged(ready(), mainPlaying = true)

        assertTrue("进入前在播 → 离开必须恢复播放", hold.onPageLeft())
        assertFalse("恢复后不再持有记录", hold.isHolding)
        assertFalse("恢复只能发生一次", hold.onPageLeft())
    }

    @Test
    fun `criterion 3 - leaving after paused stays paused`() {
        val hold = PreviewMainPlaybackHold()
        assertTrue(
            "进入结果页仍要暂停（幂等；此时主播放本来就是暂停）",
            hold.onPageStateChanged(ready(), mainPlaying = false),
        )
        assertEquals("记录的是进入前暂停", false, hold.heldWhilePlaying)

        assertFalse("进入前暂停 → 离开不得自动播放", hold.onPageLeft())
        assertFalse(hold.isHolding)
    }

    @Test
    fun `entering paused then playing preview does not resume main on exit`() {
        // 记录的是「进入结果页前」的状态，之后结果页里的预览播放不改变主播放记录。
        val hold = PreviewMainPlaybackHold()
        hold.onPageStateChanged(ready(), mainPlaying = false)
        // 用户点预览试听（预览与主播放相互独立，这里不喂给 hold）。
        assertFalse(hold.onPageLeft())
    }

    @Test
    fun `reanalyze from result page keeps the original record`() {
        val hold = PreviewMainPlaybackHold()
        hold.onPageStateChanged(ready(), mainPlaying = true)

        // 页内「重新分析」：Ready → Analyzing（页面仍在这首的分析流程里）。
        assertFalse(hold.onPageStateChanged(analyzing, mainPlaying = false))
        assertTrue("重新分析期间主播放保持暂停，记录不丢", hold.isHolding)

        // 新结果 Ready：已经接管过，不再重复 pause。
        assertFalse(hold.onPageStateChanged(ready(), mainPlaying = false))
        assertTrue("最终离开仍按首次进入结果页前的状态恢复", hold.onPageLeft())
    }

    // ---------------------------------------------------------------- 判据 4

    @Test
    fun `criterion 4 - backing out during analysis leaves main playback running`() {
        val hold = PreviewMainPlaybackHold()
        // 播放中启动分析，用户没等结果页出现就返回。
        assertFalse(hold.onPageStateChanged(analyzing, mainPlaying = true))
        assertFalse(
            "从未接管 → 退出时不能 resume()（主播放本来就在播，无需任何动作）",
            hold.onPageLeft(),
        )
        assertFalse(hold.isHolding)
    }

    @Test
    fun `background analysis completion without a result page never touches main playback`() {
        val hold = PreviewMainPlaybackHold()
        // 分析在别处完成（没有 Ready 页面事件）→ hold 从未收到 Ready。
        assertFalse(hold.onPageStateChanged(analyzingCandidates, mainPlaying = true))
        assertFalse("后台完成不得暂停/接管主播放", hold.isHolding)
        assertFalse(hold.onPageLeft())
    }

    @Test
    fun `config change rebuild neither repauses nor restores early`() {
        val hold = PreviewMainPlaybackHold()
        hold.onPageStateChanged(ready(), mainPlaying = true)

        // 旋转/分屏：组合销毁重建，但 PreviewHostDestroyAction.KEEP_ALIVE 不算离开。
        // 新宿主首帧会再次看到 Ready（此时主播放已暂停）。
        assertFalse(hold.onPageStateChanged(ready(), mainPlaying = false))
        assertTrue("重建后仍保持接管，离开时才恢复", hold.isHolding)
        assertTrue(hold.onPageLeft())
    }

    @Test
    fun `a new page session pauses again after the previous one was restored`() {
        val hold = PreviewMainPlaybackHold()
        hold.onPageStateChanged(ready(), mainPlaying = true)
        assertTrue(hold.onPageLeft())

        // 再次进入另一个分析页（或同一首重新打开）：需要重新记录/暂停。
        assertTrue(hold.onPageStateChanged(ready(), mainPlaying = true))
        assertTrue(hold.onPageLeft())
    }
}
