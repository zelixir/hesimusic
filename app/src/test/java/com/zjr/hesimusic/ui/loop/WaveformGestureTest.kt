package com.zjr.hesimusic.ui.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * issue 00023 用户回归:点 A 点左侧,进度条指针跑到 B 点左侧。
 *
 * 根因(真机复现):按下点落在 A/B 手柄命中区(24dp)时手势进入"拖手柄"分支,但**没移动**时
 * 既不发 DragAStart 也不发 DragAMove,松手只发 CommitWindow → reducer 把位置退回"模式起点":
 * - SEAM:模式起点 = B−5s → 指针出现在 B 点左侧(用户看到的);
 * - LOOP_ONLY:模式起点 = A−300ms → 指针跳到 A。
 *
 * 这里锁住纯判定:没拖过 = 按松手位置 seek(哪怕按在手柄命中区内);拖过 = Commit。
 */
class WaveformGestureTest {

    private val width = 984f
    private val durationMs = 284_865L
    private val aMs = 23_301L
    private val bMs = 261_457L
    private val tolerancePx = 72f // 24dp @3x

    private fun xOf(ms: Long): Float = ms.toFloat() / durationMs * width

    @Test
    fun tapNearAWithoutDragSeeksAtTapPositionInsteadOfCommitting() {
        // 用户点的是 A 左侧(A 线约在 x=80):按在手柄命中区内,但没有移动。
        val tapX = xOf(10_000L)
        assertEquals("前提:落点确实在 A 手柄命中区内", 'A', WaveformGesture.handleAt(tapX, width, aMs, bMs, durationMs, tolerancePx))
        val release = WaveformGesture.release(dragged = false, upX = tapX, width = width, durationMs = durationMs)
        assertEquals(
            "没拖过的点按必须当 seek,且落点=用户点的位置(绝不能 CommitWindow 退回模式起点)",
            WaveformRelease.Seek(10_000L),
            release,
        )
    }

    @Test
    fun tapExactlyOnAAndBSeeksToThatPosition() {
        for (ms in listOf(aMs, bMs, aMs + 1L, bMs - 1L)) {
            val x = xOf(ms)
            val release = WaveformGesture.release(dragged = false, upX = x, width = width, durationMs = durationMs)
            val seek = release as? WaveformRelease.Seek
            assertEquals("必须是 seek 而不是 commit: target=" + ms, true, seek != null)
            // x↔ms 的浮点往返允许 ±2ms。
            assertEquals("target=" + ms, ms.toDouble(), seek!!.positionMs.toDouble(), 2.0)
        }
    }

    @Test
    fun tapLeftOfAEchoesExactMediaTime() {
        // A 之前 3 个点:0、A−10s、A−1s —— 落点必须原样回传(不做任何折返/取模)。
        for (ms in listOf(0L, aMs - 10_000L, aMs - 1_000L)) {
            val x = xOf(ms)
            assertEquals(
                "target=" + ms,
                WaveformRelease.Seek(ms),
                WaveformGesture.release(dragged = false, upX = x, width = width, durationMs = durationMs),
            )
        }
    }

    @Test
    fun realDragCommits() {
        assertEquals(
            WaveformRelease.Commit,
            WaveformGesture.release(dragged = true, upX = xOf(50_000L), width = width, durationMs = durationMs),
        )
    }

    @Test
    fun handleHitTestIsAPriorityAndRespectsTolerance() {
        assertEquals('A', WaveformGesture.handleAt(xOf(aMs) + tolerancePx - 1f, width, aMs, bMs, durationMs, tolerancePx))
        assertEquals('B', WaveformGesture.handleAt(xOf(bMs), width, aMs, bMs, durationMs, tolerancePx))
        assertNull(
            "A 命中区外、远离 B",
            WaveformGesture.handleAt(xOf(aMs) - tolerancePx - 5f, width, aMs, bMs, durationMs, tolerancePx),
        )
        assertNull("宽度/时长非法", WaveformGesture.handleAt(0f, 0f, aMs, bMs, durationMs, tolerancePx))
        assertNull(WaveformGesture.handleAt(0f, width, aMs, bMs, 0L, tolerancePx))
    }

    @Test
    fun positionIsClampedToDuration() {
        assertEquals(0L, WaveformGesture.positionMsAt(-100f, width, durationMs))
        assertEquals(durationMs, WaveformGesture.positionMsAt(width + 100f, width, durationMs))
        assertEquals(0L, WaveformGesture.positionMsAt(0f, 0f, durationMs))
    }
}
