package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopCandidatesTest {

    @Test
    fun `空数组与 null 返回空列表`() {
        assertTrue(LoopCandidates.parse(DoubleArray(0)).isEmpty())
        assertTrue(LoopCandidates.parse(null).isEmpty())
    }

    @Test
    fun `长度不足一个候选返回空列表`() {
        assertTrue(LoopCandidates.parse(doubleArrayOf(1.0, 2.0, 3.0, 4.0)).isEmpty())
    }

    @Test
    fun `长度非 5 或 6 的倍数整体拒绝`() {
        // 11 = 2 组 + 1 个残缺值:不猜测截断语义,整体返回空。
        val flat = DoubleArray(11) { it.toDouble() }
        assertTrue(LoopCandidates.parse(flat).isEmpty())
    }

    @Test
    fun `单个候选字段按序映射`() {
        val flat = doubleArrayOf(76792.0, 3544053.0, 0.987, 0.12, 0.34)
        val list = LoopCandidates.parse(flat)
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals(76792L, c.startSamples)
        assertEquals(3544053L, c.endSamples)
        assertEquals(0.987, c.score, 0.0)
        assertEquals(0.12, c.noteDistance, 0.0)
        assertEquals(0.34, c.loudnessDifference, 0.0)
    }

    @Test
    fun `多个候选保持字段配对并按 score 降序`() {
        val flat = doubleArrayOf(
            10.0, 20.0, 0.1, 0.5, 0.6,
            30.0, 40.0, 0.9, 0.2, 0.3,
            50.0, 60.0, 0.5, 0.4, 0.4,
        )
        val list = LoopCandidates.parse(flat)
        assertEquals(3, list.size)
        assertEquals(30L, list[0].startSamples)
        assertEquals(0.9, list[0].score, 0.0)
        assertEquals(50L, list[1].startSamples)
        assertEquals(10L, list[2].startSamples)
        assertEquals(0.6, list[2].loudnessDifference, 0.0)
    }

    @Test
    fun `六字段候选映射第 6 个 seamQuality_前 5 个字段位置不变`() {
        val flat = doubleArrayOf(76792.0, 3544053.0, 0.987, 0.12, 0.34, 0.86)
        val list = LoopCandidates.parse(flat)
        assertEquals(1, list.size)
        val c = list[0]
        assertEquals(76792L, c.startSamples)
        assertEquals(3544053L, c.endSamples)
        assertEquals("score 仍是第 3 个字段(融合分)", 0.987, c.score, 0.0)
        assertEquals(0.12, c.noteDistance, 0.0)
        assertEquals(0.34, c.loudnessDifference, 0.0)
        assertEquals("seamQuality 是新增的第 6 个字段", 0.86, c.seamQuality, 0.0)
    }

    @Test
    fun `多候选六字段保持配对且按 score 降序`() {
        val flat = doubleArrayOf(
            10.0, 20.0, 0.1, 0.5, 0.6, 0.11,
            30.0, 40.0, 0.9, 0.2, 0.3, 0.92,
            50.0, 60.0, 0.5, 0.4, 0.4, 0.55,
        )
        val list = LoopCandidates.parse(flat)
        assertEquals(3, list.size)
        assertEquals(30L, list[0].startSamples)
        assertEquals(0.92, list[0].seamQuality, 0.0)
        assertEquals(50L, list[1].startSamples)
        assertEquals(0.55, list[1].seamQuality, 0.0)
        assertEquals(10L, list[2].startSamples)
        assertEquals(0.11, list[2].seamQuality, 0.0)
    }

    @Test
    fun `五字段旧契约向后兼容_seamQuality 读作 0`() {
        // v8 之前的 JNI 输出:没有第 6 个字段;必须继续按 5 字段解析,seamQuality=0(未知)。
        val flat = doubleArrayOf(
            10.0, 20.0, 0.8, 0.1, 0.2,
            30.0, 40.0, 0.9, 0.3, 0.4,
        )
        val list = LoopCandidates.parse(flat)
        assertEquals(2, list.size)
        assertEquals(30L, list[0].startSamples)
        assertEquals(0.9, list[0].score, 0.0)
        assertEquals(0.0, list[0].seamQuality, 0.0)
        assertEquals(10L, list[1].startSamples)
        assertEquals(0.0, list[1].seamQuality, 0.0)
    }

    @Test
    fun `公倍数长度 30 按当前六字段契约解释`() {
        // 30 = 5×6 = 6×5,歧义时优先当前契约(同版本 JNI 与解析器不会混用)。
        val flat = DoubleArray(30) { it.toDouble() }
        val list = LoopCandidates.parse(flat)
        assertEquals(5, list.size)
        // 第 5 条记录 score=26 最高排第一,其第 6 个 double 是 29(seamQuality)。
        assertEquals("第 6 个 double 是每条记录自己的 seamQuality", 29.0, list[0].seamQuality, 0.0)
    }

    @Test
    fun `非有限 seamQuality 归一为 0 未知`() {
        val list = LoopCandidates.parse(
            doubleArrayOf(
                1.0, 2.0, 0.7, 0.1, 0.2, Double.NaN,
                3.0, 4.0, 0.6, 0.1, 0.2, Double.POSITIVE_INFINITY,
            ),
        )
        assertEquals(2, list.size)
        assertEquals(0.0, list[0].seamQuality, 0.0)
        assertEquals(0.0, list[1].seamQuality, 0.0)
    }

    @Test
    fun `采样点小数截断为 Long`() {
        val list = LoopCandidates.parse(doubleArrayOf(10.9, 20.9, 0.5, 0.1, 0.1))
        assertEquals(10L, list[0].startSamples)
        assertEquals(20L, list[0].endSamples)
    }

    @Test
    fun `负分候选同样参与排序`() {
        val list = LoopCandidates.parse(
            doubleArrayOf(
                1.0, 2.0, -0.2, 0.0, 0.0,
                3.0, 4.0, 0.2, 0.0, 0.0,
                5.0, 6.0, -0.9, 0.0, 0.0,
            ),
        )
        assertEquals(3L, list[0].startSamples)
        assertEquals(1L, list[1].startSamples)
        assertEquals(5L, list[2].startSamples)
    }

    @Test
    fun `同分时保持引擎顺序`() {
        val list = LoopCandidates.parse(
            doubleArrayOf(
                1.0, 2.0, 0.5, 0.0, 0.0,
                3.0, 4.0, 0.5, 0.0, 0.0,
            ),
        )
        assertEquals(1L, list[0].startSamples)
        assertEquals(3L, list[1].startSamples)
    }
}
