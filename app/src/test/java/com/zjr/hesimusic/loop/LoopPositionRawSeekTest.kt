package com.zjr.hesimusic.loop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 展示位置 → 引擎原始 seek 位置的映射(issue 00001)。
 *
 * 语义已经收敛为**与当前播放位置无关**的三段:
 * 1. `target < A`  → 原样(回前奏);
 * 2. `A <= target < B` → **原样**(永远落在第 0 轮的同一相位);
 * 3. `target >= B` → `A + ((target - A) mod L)`(折回第 0 轮)。
 *
 * 历史实现会把区间内的目标映射到"当前轮次",导致「拖到 35s」实际 seek 到 65s(第 1 轮),
 * 引擎再按输入 PTS 重新定相时一旦对不齐,落点就漂到 A 附近。固定第 0 轮后,
 * seek 目标 == 引擎输入 PTS,位置确定可预测;向后 seek 由引擎的 discontinuity 处理。
 */
class LoopPositionRawSeekTest {

    // A=1_000_000µs, B=1_100_000µs, L=100_000µs
    private val window = LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 1_100_000L)

    private fun seek(target: Long): Long = LoopPosition.rawSeekUs(target, window)

    @Test
    fun `目标在 A 之前原样 seek 到前奏`() {
        assertEquals(500_000L, seek(500_000L))
        assertEquals(0L, seek(0L))
        assertEquals(-1L, seek(-1L))
    }

    @Test
    fun `区间内目标永远落在第 0 轮的同一相位`() {
        assertEquals(1_000_000L, seek(1_000_000L))
        assertEquals(1_030_000L, seek(1_030_000L))
        assertEquals(1_099_999L, seek(1_099_999L))
    }

    @Test
    fun `目标恰在 B 点折返到 A`() {
        assertEquals(1_000_000L, seek(1_100_000L))
    }

    @Test
    fun `目标在 B 之后折返到 A 之后`() {
        assertEquals(1_000_001L, seek(1_100_001L))
        assertEquals(1_005_000L, seek(1_100_000L + 3L * 100_000L + 5_000L))
        // 同相位的大目标与单轮目标等价
        assertEquals(1_005_000L, seek(1_100_000L + 100_000L + 5_000L))
    }

    @Test
    fun `seek 是幂等的且不依赖当前播放位置`() {
        val displays = listOf(0L, 999_999L, 1_000_000L, 1_050_000L, 1_099_999L)
        for (display in displays) {
            val raw = seek(display)
            assertEquals("display=$display 再 seek 一次结果不变", raw, seek(raw))
        }
    }

    @Test
    fun `非法窗口原样返回`() {
        val invalid = LoopWindow(songId = 1L, loopStartUs = 10L, loopEndUs = 10L)
        assertEquals(1_234_567L, LoopPosition.rawSeekUs(1_234_567L, invalid))
    }

    @Test
    fun `微窗口边界`() {
        val micro = LoopWindow(songId = 2L, loopStartUs = 10L, loopEndUs = 12L)
        assertEquals(10L, LoopPosition.rawSeekUs(10L, micro))
        assertEquals(11L, LoopPosition.rawSeekUs(11L, micro))
        assertEquals(10L, LoopPosition.rawSeekUs(12L, micro))
    }
}
