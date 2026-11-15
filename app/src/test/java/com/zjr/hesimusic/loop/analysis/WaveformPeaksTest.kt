package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformPeaksTest {

    @Test
    fun `空输入生成全零桶`() {
        val peaks = WaveformPeaks.buildPeaks(FloatArray(0), buckets = 128)
        assertEquals(128, peaks.buckets)
        assertEquals(128, peaks.mn.size)
        assertEquals(128, peaks.mx.size)
        for (i in 0 until 128) {
            assertEquals("空输入 mn[$i] 应为 0", 0f, peaks.mn[i], 0f)
            assertEquals("空输入 mx[$i] 应为 0", 0f, peaks.mx[i], 0f)
        }
    }

    @Test
    fun `默认桶数为 3000 且长度匹配`() {
        val peaks = WaveformPeaks.buildPeaks(FloatArray(10) { 1f })
        assertEquals(WaveformPeaks.DEFAULT_BUCKETS, peaks.buckets)
        assertEquals(3000, peaks.mn.size)
        assertEquals(3000, peaks.mx.size)
    }

    @Test
    fun `非法桶数回退到默认值`() {
        assertEquals(3000, WaveformPeaks.buildPeaks(FloatArray(1), buckets = 0).buckets)
        assertEquals(3000, WaveformPeaks.buildPeaks(FloatArray(1), buckets = -5).buckets)
    }

    @Test
    fun `按步长分桶并取每桶 min-max`() {
        // 6000 样本 / 3000 桶 → step=2,每桶正好 2 个样本。
        val mono = FloatArray(6000) { i -> if (i % 2 == 0) -32768f else 16384f }
        val peaks = WaveformPeaks.buildPeaks(mono, buckets = 3000)
        assertEquals(3000, peaks.buckets)
        for (i in 0 until 3000) {
            assertEquals("mn[$i] 应为 -1", -1f, peaks.mn[i], 0f)
            assertEquals("mx[$i] 应为 0.5", 0.5f, peaks.mx[i], 0f)
        }
    }

    @Test
    fun `尾部不足一桶的样本被丢弃`() {
        // 5 样本 / 2 桶 → step=2:桶0=样本 0,1;桶1=样本 2,3;样本 4 不参与。
        val mono = floatArrayOf(1f, 2f, 3f, 4f, 32768f)
        val peaks = WaveformPeaks.buildPeaks(mono, buckets = 2)
        assertEquals(2, peaks.buckets)
        assertEquals(0f, peaks.mn[0], 0f)
        assertEquals(2f / 32768f, peaks.mx[0], 0f)
        assertEquals(0f, peaks.mn[1], 0f)
        assertEquals(4f / 32768f, peaks.mx[1], 0f)
    }

    @Test
    fun `样本数少于桶数时 step 至少为 1 且多余桶保持零`() {
        val mono = floatArrayOf(-16384f, 16384f)
        val peaks = WaveformPeaks.buildPeaks(mono, buckets = 4)
        assertEquals(4, peaks.buckets)
        assertEquals(-0.5f, peaks.mn[0], 0f)
        assertEquals(0f, peaks.mx[0], 0f)
        assertEquals(0f, peaks.mn[1], 0f)
        assertEquals(0.5f, peaks.mx[1], 0f)
        assertEquals(0f, peaks.mn[3], 0f)
        assertEquals(0f, peaks.mx[3], 0f)
    }

    @Test
    fun `单桶取全曲 min-max`() {
        val mono = floatArrayOf(-32768f, 32767f, 0f, -100f)
        val peaks = WaveformPeaks.buildPeaks(mono, buckets = 1)
        assertEquals(-1f, peaks.mn[0], 0f)
        assertEquals(32767f / 32768f, peaks.mx[0], 0f)
    }

    @Test
    fun `alreadyNormalized 输入不再除以 32768`() {
        val mono = floatArrayOf(-0.75f, 0.25f)
        val peaks = WaveformPeaks.buildPeaks(mono, buckets = 1, alreadyNormalized = true)
        assertEquals(-0.75f, peaks.mn[0], 0f)
        assertEquals(0.25f, peaks.mx[0], 0f)
    }

    @Test
    fun `全正样本的 min 保持 0 全负样本的 max 保持 0`() {
        val positive = WaveformPeaks.buildPeaks(floatArrayOf(100f, 200f), buckets = 1)
        assertEquals(0f, positive.mn[0], 0f)
        assertEquals(200f / 32768f, positive.mx[0], 0f)

        val negative = WaveformPeaks.buildPeaks(floatArrayOf(-100f, -200f), buckets = 1)
        assertEquals(-200f / 32768f, negative.mn[0], 0f)
        assertEquals(0f, negative.mx[0], 0f)
    }
}
