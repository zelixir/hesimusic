package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * [AnalysisProgress] / [AnalysisTiming] 单测(issue 00018)。
 *
 * 覆盖:单调不减、0..1 边界、EMA 后相邻台阶上限、开工即 >0、两种预算比例下斜率接近常数、
 * 取消/重试重置、末段 0.95→1.0、时长档实测比例校准。
 */
class AnalysisProgressTest {

    @Before
    fun resetTiming() {
        AnalysisTiming.clear()
    }

    // -----------------------------------------------------------------------
    // 端点行为
    // -----------------------------------------------------------------------

    @Test
    fun `开始即大于零而不是长时间停在零`() {
        assertEquals(AnalysisProgress.START, progress(AnalysisProgress.Phase.STARTING), 1e-6f)
        assertEquals(AnalysisProgress.START, progress(AnalysisProgress.Phase.DECODING, prev = 0f), 1e-6f)
        assertEquals(AnalysisProgress.START, progress(AnalysisProgress.Phase.DECODING, prev = Float.NaN), 1e-6f)
        assertTrue(progress(AnalysisProgress.Phase.DECODING, prev = 0f) > 0f)
    }

    @Test
    fun `收尾停在百分之九十五且结束分步走台阶到一`() {
        // 收尾按 MAX_STEP 逐步逼近 0.95(不会一次从 0.9 跳到 1.0)。
        val finalizing = progress(
            AnalysisProgress.Phase.FINALIZING,
            decoded = TOTAL_FRAMES,
            blocks = TOTAL_BLOCKS,
            prev = 0.90f,
        )
        assertEquals(0.95f, finalizing, 1e-6f)

        // DONE 也受 MAX_STEP 约束:0.95 → 1.0 是最后一个台阶。
        val done = progress(AnalysisProgress.Phase.DONE, prev = finalizing)
        assertEquals(1f, done, 0f)
        assertEquals(AnalysisProgress.MAX_STEP, done - finalizing, 1e-6f)

        // 从低位进入 DONE 也不会一次跳到底。
        assertEquals(
            AnalysisProgress.MAX_STEP,
            progress(AnalysisProgress.Phase.DONE, prev = 0f),
            1e-6f,
        )
    }

    // -----------------------------------------------------------------------
    // 单调 / 边界
    // -----------------------------------------------------------------------

    @Test
    fun `进度单调不减且永不回退`() {
        var prev = 0f
        for (fraction in listOf(0f, 0.1f, 0.05f, 0.3f, 0.2f, 0.6f, 0.5f, 0.9f, 1f)) {
            val next = progress(
                AnalysisProgress.Phase.DECODING,
                decoded = (fraction * TOTAL_FRAMES).toLong(),
                prev = prev,
            )
            assertTrue("解码工作量回退:$prev -> $next", next >= prev)
            prev = next
        }
        for (fraction in listOf(0f, 0.2f, 0.1f, 0.7f, 0.5f, 0.3f, 1f)) {
            val next = progress(
                AnalysisProgress.Phase.ANALYZING,
                decoded = TOTAL_FRAMES,
                blocks = (fraction * TOTAL_BLOCKS).toLong(),
                prev = prev,
            )
            assertTrue("分析工作量回退:$prev -> $next", next >= prev)
            prev = next
        }
        // 阶段回退(分析 → 解码)也不允许回退。
        val high = progress(
            AnalysisProgress.Phase.ANALYZING,
            decoded = TOTAL_FRAMES,
            blocks = TOTAL_BLOCKS,
            prev = 0.60f,
        )
        val regressed = progress(
            AnalysisProgress.Phase.DECODING,
            decoded = TOTAL_FRAMES / 4,
            prev = high,
        )
        assertEquals(high, regressed, 0f)
    }

    @Test
    fun `进度始终落在 0 到 1 边界内`() {
        val values = listOf(
            progress(
                AnalysisProgress.Phase.DECODING,
                decoded = -5,
                totalFrames = -10,
                prev = -1f,
            ),
            progress(
                AnalysisProgress.Phase.DECODING,
                decoded = Long.MAX_VALUE,
                totalFrames = 1,
                prev = 0f,
            ),
            progress(
                AnalysisProgress.Phase.ANALYZING,
                decoded = TOTAL_FRAMES,
                blocks = Long.MAX_VALUE,
                totalBlocks = Long.MAX_VALUE,
                prev = 0f,
            ),
            progress(
                AnalysisProgress.Phase.ANALYZING,
                decoded = TOTAL_FRAMES,
                blocks = 10,
                totalBlocks = 0,
                prev = 0.5f,
            ),
            progress(AnalysisProgress.Phase.FINALIZING, prev = 0f),
            progress(AnalysisProgress.Phase.DONE, prev = 0f),
            progress(AnalysisProgress.Phase.DECODING, prev = 2f),
            progress(AnalysisProgress.Phase.DECODING, prev = Float.NaN),
            progress(AnalysisProgress.Phase.DECODING, prev = 0.5f, maxStep = Float.NaN),
            progress(AnalysisProgress.Phase.DECODING, prev = 0.5f, alpha = Float.POSITIVE_INFINITY),
        )
        values.forEach { value -> assertTrue("越界:$value", value >= 0f && value <= 1f) }
        // DONE 也走台阶上限:从 0 开始只前进一个 MAX_STEP。
        assertEquals(AnalysisProgress.MAX_STEP, progress(AnalysisProgress.Phase.DONE, prev = 0f), 1e-6f)
        assertEquals(1f, progress(AnalysisProgress.Phase.DONE, prev = 1f), 0f)
        assertEquals(1f, progress(AnalysisProgress.Phase.DONE, prev = 0.99f), 0f)
    }

    // -----------------------------------------------------------------------
    // 平滑:相邻台阶上限
    // -----------------------------------------------------------------------

    @Test
    fun `EMA 平滑后相邻更新台阶不超过上限且结束为一`() {
        val timeline = simulate(decodeMs = 5_300, analyzeMs = 2_700, decodeWeight = 0.6625f)
        val series = timeline.progress
        assertEquals(AnalysisProgress.START, series.first(), 1e-6f)
        for (i in 1 until series.size) {
            val step = series[i] - series[i - 1]
            assertTrue("第 " + i + " 步回退:" + series[i - 1] + " -> " + series[i], step >= 0f)
            assertTrue(
                "第 " + i + " 步 " + step + " 超过上限 " + AnalysisProgress.MAX_STEP,
                step <= AnalysisProgress.MAX_STEP + 1e-5f,
            )
        }
        assertEquals(1f, series.last(), 0f)
    }

    // -----------------------------------------------------------------------
    // 时间加权:两种预算比例下斜率接近常数
    // -----------------------------------------------------------------------

    @Test
    fun `预算比例与实际耗时匹配时进度斜率接近常数`() {
        // 解码快、分析慢
        assertSlopeFlat(decodeMs = 5_300, analyzeMs = 2_700)
        // 解码慢、分析快
        assertSlopeFlat(decodeMs = 2_000, analyzeMs = 6_000)
        assertSlopeFlat(decodeMs = 6_000, analyzeMs = 2_000)
    }

    @Test
    fun `解码与分析预算权重按配置分配`() {
        val decodeEnd = progress(
            AnalysisProgress.Phase.DECODING,
            decoded = TOTAL_FRAMES,
            prev = 0f,
            decodeWeight = 0.25f,
            analyzeWeight = 0.75f,
            alpha = 1f,
            maxStep = 1f,
        )
        assertEquals(AnalysisProgress.START + 0.93f * 0.25f, decodeEnd, 1e-5f)
        val analyzeEnd = progress(
            AnalysisProgress.Phase.ANALYZING,
            decoded = TOTAL_FRAMES,
            blocks = TOTAL_BLOCKS,
            prev = decodeEnd,
            decodeWeight = 0.25f,
            analyzeWeight = 0.75f,
            alpha = 1f,
            maxStep = 1f,
        )
        assertEquals(AnalysisProgress.WORK_END, analyzeEnd, 1e-5f)
        // 权重只取比例:(1,1) 与 (0.5,0.5) 等价。
        val a = progress(
            AnalysisProgress.Phase.ANALYZING,
            decoded = TOTAL_FRAMES,
            blocks = 500,
            prev = 0f,
            decodeWeight = 1f,
            analyzeWeight = 1f,
            alpha = 1f,
            maxStep = 1f,
        )
        val b = progress(
            AnalysisProgress.Phase.ANALYZING,
            decoded = TOTAL_FRAMES,
            blocks = 500,
            prev = 0f,
            decodeWeight = 0.5f,
            analyzeWeight = 0.5f,
            alpha = 1f,
            maxStep = 1f,
        )
        assertEquals(a, b, 1e-6f)
        // 权重全 0 → 回退默认 0.66/0.34。
        val fallback = progress(
            AnalysisProgress.Phase.DECODING,
            decoded = TOTAL_FRAMES,
            prev = 0f,
            decodeWeight = 0f,
            analyzeWeight = 0f,
            alpha = 1f,
            maxStep = 1f,
        )
        assertEquals(AnalysisProgress.START + 0.93f * 0.66f, fallback, 1e-4f)
    }

    // -----------------------------------------------------------------------
    // 取消 / 重试重置
    // -----------------------------------------------------------------------

    @Test
    fun `取消或重试时进度重置到起点`() {
        var prev = 0f
        repeat(20) {
            prev = progress(
                AnalysisProgress.Phase.ANALYZING,
                decoded = TOTAL_FRAMES,
                blocks = 500,
                prev = prev,
            )
        }
        assertTrue("应先推进到中高位:$prev", prev > 0.5f)

        // 新一次 analyze 用 smoothedPrev = 0,从 START 重新开始,不继承旧值。
        val restarted = progress(
            AnalysisProgress.Phase.DECODING,
            decoded = 0,
            blocks = 0,
            prev = 0f,
        )
        assertEquals(AnalysisProgress.START, restarted, 1e-6f)
        assertTrue("重置后应低于旧进度", restarted < prev)
    }

    // -----------------------------------------------------------------------
    // 总量未知 / 非法输入
    // -----------------------------------------------------------------------

    @Test
    fun `总量未知时只按已知段计且不产生 NaN`() {
        assertEquals(
            AnalysisProgress.START,
            progress(AnalysisProgress.Phase.DECODING, decoded = 0, totalFrames = 0),
            1e-6f,
        )
        val partial = progress(
            AnalysisProgress.Phase.DECODING,
            decoded = 123,
            totalFrames = 0,
            prev = 0.2f,
        )
        assertTrue("应为有限值:$partial", partial.isFinite() && partial >= 0.2f)
        val unknownBlocks = progress(
            AnalysisProgress.Phase.ANALYZING,
            decoded = TOTAL_FRAMES,
            blocks = 50,
            totalBlocks = 0,
            prev = 0.3f,
        )
        assertTrue("应为有限值:$unknownBlocks", unknownBlocks.isFinite() && unknownBlocks >= 0.3f)
    }

    // -----------------------------------------------------------------------
    // 时长档校准
    // -----------------------------------------------------------------------

    @Test
    fun `同档位实测比例用于下次预算`() {
        assertEquals(
            AnalysisProgress.DEFAULT_DECODE_WEIGHT,
            AnalysisTiming.decodeWeightFor(248_000),
            0f,
        )
        AnalysisTiming.record(durationMs = 248_000, decodeMs = 5_300, analyzeMs = 2_700)
        // 同一 10s 档位(240_000..249_999)都用这次实测比例 5300/8000。
        assertEquals(0.6625f, AnalysisTiming.decodeWeightFor(248_900), 1e-4f)
        assertEquals(0.6625f, AnalysisTiming.decodeWeightFor(249_999), 1e-4f)
        // 不同档位仍用默认。
        assertEquals(
            AnalysisProgress.DEFAULT_DECODE_WEIGHT,
            AnalysisTiming.decodeWeightFor(260_000),
            0f,
        )
    }

    @Test
    fun `实测比例被夹在安全区间且非法耗时被忽略`() {
        AnalysisTiming.record(durationMs = 1_000, decodeMs = 1, analyzeMs = 99)
        assertEquals(AnalysisTiming.MIN_WEIGHT, AnalysisTiming.decodeWeightFor(1_000), 0f)
        AnalysisTiming.record(durationMs = 1_000, decodeMs = 99, analyzeMs = 1)
        assertEquals(AnalysisTiming.MAX_WEIGHT, AnalysisTiming.decodeWeightFor(1_000), 0f)
        // 任一段 <=0 → 忽略,保留上次校准值。
        AnalysisTiming.record(durationMs = 1_000, decodeMs = 0, analyzeMs = 100)
        AnalysisTiming.record(durationMs = 15_000, decodeMs = 100, analyzeMs = 0)
        assertEquals(AnalysisTiming.MAX_WEIGHT, AnalysisTiming.decodeWeightFor(1_000), 0f)
        assertEquals(AnalysisTiming.defaultDecodeWeight, AnalysisTiming.decodeWeightFor(15_000), 0f)
    }

    @Test
    fun `clear 清空校准并恢复默认`() {
        AnalysisTiming.record(durationMs = 248_000, decodeMs = 5_300, analyzeMs = 2_700)
        assertTrue(AnalysisTiming.snapshot().containsKey(24L))
        AnalysisTiming.clear()
        assertTrue(AnalysisTiming.snapshot().isEmpty())
        assertEquals(
            AnalysisProgress.DEFAULT_DECODE_WEIGHT,
            AnalysisTiming.decodeWeightFor(248_000),
            0f,
        )
    }

    // -----------------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------------

    private data class Timeline(
        val progress: List<Float>,
        val phases: List<AnalysisProgress.Phase>,
    )

    private fun progress(
        phase: AnalysisProgress.Phase,
        decoded: Long = 0L,
        totalFrames: Long = TOTAL_FRAMES,
        blocks: Long = 0L,
        totalBlocks: Long = TOTAL_BLOCKS,
        prev: Float = 0f,
        decodeWeight: Float = AnalysisProgress.DEFAULT_DECODE_WEIGHT,
        analyzeWeight: Float = AnalysisProgress.DEFAULT_ANALYZE_WEIGHT,
        alpha: Float = AnalysisProgress.DEFAULT_ALPHA,
        maxStep: Float = AnalysisProgress.MAX_STEP,
    ): Float = AnalysisProgress.compute(
        phase = phase,
        decodedFrames = decoded,
        totalFrames = totalFrames,
        analyzedBlocks = blocks,
        totalBlocks = totalBlocks,
        smoothedPrev = prev,
        decodeWeight = decodeWeight,
        analyzeWeight = analyzeWeight,
        alpha = alpha,
        maxStep = maxStep,
    )

    /**
     * 合成"100ms 一次更新"的真实时间序列:解码 [decodeMs] + 分析 [analyzeMs] + 一次收尾 + 终态。
     * 进度预算比例 = 实际耗时比例(模拟 [AnalysisTiming] 已校准)。
     */
    private fun simulate(
        decodeMs: Long,
        analyzeMs: Long,
        decodeWeight: Float,
        tickMs: Long = 100L,
    ): Timeline {
        val analyzeWeight = 1f - decodeWeight
        val decodeTicks = maxOf(1, (decodeMs / tickMs).toInt())
        val analyzeTicks = maxOf(1, (analyzeMs / tickMs).toInt())
        val values = ArrayList<Float>()
        val phases = ArrayList<AnalysisProgress.Phase>()

        var prev = progress(AnalysisProgress.Phase.STARTING, prev = 0f)
        values += prev
        phases += AnalysisProgress.Phase.STARTING

        for (i in 1..decodeTicks) {
            val decoded = (i.toDouble() / decodeTicks * TOTAL_FRAMES).toLong()
            prev = progress(
                AnalysisProgress.Phase.DECODING,
                decoded = decoded,
                prev = prev,
                decodeWeight = decodeWeight,
                analyzeWeight = analyzeWeight,
            )
            values += prev
            phases += AnalysisProgress.Phase.DECODING
        }
        for (i in 1..analyzeTicks) {
            val blocks = (i.toDouble() / analyzeTicks * TOTAL_BLOCKS).toLong()
            prev = progress(
                AnalysisProgress.Phase.ANALYZING,
                decoded = TOTAL_FRAMES,
                blocks = blocks,
                prev = prev,
                decodeWeight = decodeWeight,
                analyzeWeight = analyzeWeight,
            )
            values += prev
            phases += AnalysisProgress.Phase.ANALYZING
        }
        // 收尾:DONE 前按 MAX_STEP 逐步补齐到 0.95(最多 10 次)。
        var settle = 0
        while (prev < AnalysisProgress.WORK_END && settle < 10) {
            prev = progress(
                AnalysisProgress.Phase.FINALIZING,
                decoded = TOTAL_FRAMES,
                blocks = TOTAL_BLOCKS,
                prev = prev,
                decodeWeight = decodeWeight,
                analyzeWeight = analyzeWeight,
            )
            values += prev
            phases += AnalysisProgress.Phase.FINALIZING
            settle++
        }
        // 终态:DONE 同样受台阶约束,分 1~2 次到 1.0。
        var doneSteps = 0
        while (prev < 1f && doneSteps < 4) {
            prev = progress(
                AnalysisProgress.Phase.DONE,
                decoded = TOTAL_FRAMES,
                blocks = TOTAL_BLOCKS,
                prev = prev,
                decodeWeight = decodeWeight,
                analyzeWeight = analyzeWeight,
            )
            values += prev
            phases += AnalysisProgress.Phase.DONE
            doneSteps++
        }
        return Timeline(values, phases)
    }

    /**
     * 断言:预算比例与真实耗时一致时,解码段与分析段的平均斜率接近,且逐 tick 斜率方差很小。
     * 跳过每段前 10 个 tick(EMA 从 START 收敛的启动瞬态)与收尾/终态。
     */
    private fun assertSlopeFlat(decodeMs: Long, analyzeMs: Long) {
        val decodeWeight = decodeMs.toFloat() / (decodeMs + analyzeMs)
        val timeline = simulate(decodeMs, analyzeMs, decodeWeight)
        val deltas = ArrayList<Pair<AnalysisProgress.Phase, Float>>()
        for (i in 1 until timeline.progress.size) {
            deltas += timeline.phases[i] to (timeline.progress[i] - timeline.progress[i - 1])
        }
        val decodeDeltas = deltas
            .filter { it.first == AnalysisProgress.Phase.DECODING }
            .drop(10)
            .map { it.second }
        val analyzeDeltas = deltas
            .filter { it.first == AnalysisProgress.Phase.ANALYZING }
            .drop(10)
            .map { it.second }
        assertTrue("解码段样本不足", decodeDeltas.isNotEmpty())
        assertTrue("分析段样本不足", analyzeDeltas.isNotEmpty())

        val meanDecode = decodeDeltas.average().toFloat()
        val meanAnalyze = analyzeDeltas.average().toFloat()
        val overall = (meanDecode + meanAnalyze) / 2f
        assertTrue(
            "两段斜率差异过大:$meanDecode vs $meanAnalyze",
            abs(meanDecode - meanAnalyze) / overall <= 0.15f,
        )

        val warmed = decodeDeltas + analyzeDeltas
        val mean = warmed.average()
        val variance = warmed.sumOf { (it - mean) * (it - mean) } / warmed.size
        val cv = sqrt(variance) / mean
        assertTrue("逐 tick 斜率方差过大:cv=$cv", cv <= 0.05)
    }

    private companion object {
        const val TOTAL_FRAMES = 11_000_000L
        const val TOTAL_BLOCKS = 1_000L
    }
}
