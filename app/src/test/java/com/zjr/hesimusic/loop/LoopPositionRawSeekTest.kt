package com.zjr.hesimusic.loop

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopPositionRawSeekTest {

    // A=1_000_000µs,B=1_100_000µs,L=100_000µs
    private val window = LoopWindow(songId = 1L, loopStartUs = 1_000_000L, loopEndUs = 1_100_000L)

    private fun seek(target: Long, currentRaw: Long): Long =
        LoopPosition.rawSeekUs(target, window, currentRaw)

    @Test
    fun `目标在 A 之前原样 seek 到前奏`() {
        assertEquals("前奏 seek 不受当前轮次影响", 500_000L, seek(500_000L, 1_250_000L))
        assertEquals("目标 0 原样", 0L, seek(0L, 1_050_000L))
        assertEquals("负目标原样", -1L, seek(-1L, 1_050_000L))
    }

    @Test
    fun `前奏期 seek 到区间内按第 0 轮映射`() {
        assertEquals("currentRaw < A 时 k=0,target 即原始位置", 1_030_000L, seek(1_030_000L, 200_000L))
        assertEquals("currentRaw 恰在 A 时 k=0", 1_030_000L, seek(1_030_000L, 1_000_000L))
    }

    @Test
    fun `区间内 seek 保持当前轮次`() {
        assertEquals("第 0 轮内 seek", 1_020_000L, seek(1_020_000L, 1_050_000L))
        assertEquals(
            "第 2 轮内 seek 应落在第 2 轮同一相位",
            1_220_000L,
            seek(1_020_000L, 1_250_000L)
        )
        assertEquals(
            "第 1 轮末尾(1_199_999)seek 到区间起点应落在第 1 轮起点",
            1_100_000L,
            seek(1_000_000L, 1_199_999L)
        )
        assertEquals(
            "第 0 轮末尾 seek 到区间起点仍落在 A",
            1_000_000L,
            seek(1_000_000L, 1_099_999L)
        )
    }

    @Test
    fun `当前原始位置恰在轮次边界时归入该轮`() {
        assertEquals(
            "currentRaw==A+2L 时 k=2,target==A 映射到该轮起点",
            1_200_000L,
            seek(1_000_000L, 1_200_000L)
        )
        assertEquals(
            "currentRaw==B 时 k=1,target==A 映射到第 1 轮起点",
            1_100_000L,
            seek(1_000_000L, 1_100_000L)
        )
    }

    @Test
    fun `目标恰在 A 点保持当前轮次起点`() {
        assertEquals("第 3 轮 seek 到 A", 1_300_000L, seek(1_000_000L, 1_345_678L))
    }

    @Test
    fun `目标恰在 B 点按折返分支落在 A`() {
        assertEquals("target==B 走 >= end 分支,折返到第 0 轮 A", 1_000_000L, seek(1_100_000L, 1_250_000L))
    }

    @Test
    fun `目标在 B 之后折返到 A 之后`() {
        assertEquals("target==B+1 折返到 A+1", 1_000_001L, seek(1_100_001L, 1_250_000L))
        val target = 1_100_000L + 3L * 100_000L + 5_000L
        assertEquals("B 之后多轮折返", 1_005_000L, seek(target, 1_250_000L))
        assertEquals(
            "同相位的大目标与多轮等价",
            1_005_000L,
            seek(1_100_000L + 100_000L + 5_000L, 1_250_000L)
        )
    }

    @Test
    fun `seek 到当前展示位置是幂等的`() {
        val raws = listOf(
            0L,
            999_999L,
            1_000_000L,
            1_050_000L,
            1_099_999L,
            1_100_000L,
            1_234_567L,
            1_000_000L + 7L * 100_000L + 42L
        )
        raws.forEach { raw ->
            val shown = LoopPosition.displayUs(raw, window)
            assertEquals(
                "seek 到当前展示位置不应产生跳变:raw=$raw",
                raw,
                LoopPosition.rawSeekUs(shown, window, raw)
            )
        }
    }

    @Test
    fun `长度为 1 微秒时区间内 seek 落在当前原始位置`() {
        val micro = LoopWindow(songId = 1L, loopStartUs = 10L, loopEndUs = 11L)
        assertEquals(
            "target==A 且 target<B,应落在当前轮次同一相位",
            1_000_000L,
            LoopPosition.rawSeekUs(10L, micro, 1_000_000L)
        )
        assertEquals(
            "target==B 走折返分支,落在第 0 轮 A",
            10L,
            LoopPosition.rawSeekUs(11L, micro, 1_000_000L)
        )
    }

    @Test
    fun `非法窗口原样返回目标`() {
        assertEquals(
            "零长度窗口不做映射",
            1_050_000L,
            LoopPosition.rawSeekUs(
                1_050_000L,
                LoopWindow(songId = 1L, loopStartUs = 100L, loopEndUs = 100L),
                1_234_567L
            )
        )
        assertEquals(
            "起点为负的窗口不做映射",
            -50L,
            LoopPosition.rawSeekUs(
                -50L,
                LoopWindow(songId = 1L, loopStartUs = -10L, loopEndUs = 50L),
                10L
            )
        )
    }
}
