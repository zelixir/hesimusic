package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.ceil

/**
 * 整条分析时间线的进度单测(issue 00018,设备不可用时的替代验证)。
 *
 * 用假时钟([AnalysisProgressTracker] 的 clock 注入)合成"解码 → 分析 → 收尾 → Done",
 * 断言最终上报序列:
 * 1. 单调不减;
 * 2. 任意相邻两次差 ≤ [AnalysisProgress.MAX_STEP](含最后一个 FINALIZING → DONE);
 * 3. 从收尾起点到 0.95 的更新次数 ≤ ceil((0.95 - prev) / MAX_STEP);
 * 4. 终态恰好 1.0;
 * 5. 相邻上报空档 ≤ 心跳空档阈值 + 一个刷新周期(证明 >250ms 空档会被 heartbeat 填充)。
 */
class AnalysisProgressTrackerTest {

    @Before
    fun resetTiming() {
        AnalysisTiming.clear()
    }

    @Test
    fun `整条时间线_解码快分析慢_单调且收尾不跳变`() {
        val publications = TimelineHarness(
            durationMs = 248_000,
            decodeMs = 1_000,
            analyzeMs = 4_000,
            engineFractions = ENGINE_COARSE,
        ).run()
        assertTimeline(publications, label = "解码快/分析慢")
    }

    @Test
    fun `整条时间线_解码慢分析快_单调且收尾不跳变`() {
        val publications = TimelineHarness(
            durationMs = 248_000,
            decodeMs = 4_000,
            analyzeMs = 1_000,
            engineFractions = ENGINE_COARSE,
        ).run()
        assertTimeline(publications, label = "解码慢/分析快")
    }

    @Test
    fun `整条时间线_极短曲目_收尾也按台阶走`() {
        val publications = TimelineHarness(
            durationMs = 300,
            decodeMs = 200,
            analyzeMs = 100,
            engineFractions = ENGINE_SHORT,
        ).run()
        // 极短曲目本身 0.3s 就结束,之后是收尾补齐台阶,不再要求"开局 0.5s ≤ 0.15"。
        assertTimeline(publications, enforceEarlySlow = false, label = "极短曲目 0.3s")
    }

    // -----------------------------------------------------------------------
    // 时间线断言
    // -----------------------------------------------------------------------

    private fun assertTimeline(
        publications: List<Publication>,
        enforceEarlySlow: Boolean = true,
        label: String = "",
    ) {
        assertTrue("上报次数过少:" + publications.size, publications.size > 4)
        assertEquals(AnalysisProgress.START, publications.first().progress, 1e-6f)

        // 1. 单调不减。
        for (i in 1 until publications.size) {
            assertTrue(
                "第 " + i + " 次回退:" + publications[i - 1].progress + " -> " + publications[i].progress,
                publications[i].progress >= publications[i - 1].progress - 1e-6f,
            )
        }

        // 2. 相邻台阶 ≤ MAX_STEP(含 FINALIZING → DONE 那一步)。
        var maxStep = 0f
        for (i in 1 until publications.size) {
            val step = publications[i].progress - publications[i - 1].progress
            maxStep = maxOf(maxStep, step)
            assertTrue(
                "第 " + i + " 次台阶 " + step + " 超过上限 " + AnalysisProgress.MAX_STEP,
                step <= AnalysisProgress.MAX_STEP + 1e-5f,
            )
        }

        // 3. 收尾到 0.95 的更新次数不超过理论下限。
        val firstFinalizing = publications.indexOfFirst {
            it.phase == AnalysisProgress.Phase.FINALIZING
        }
        assertTrue("时间线应进入 FINALIZING", firstFinalizing > 0)
        val valueBeforeFinalizing = publications[firstFinalizing - 1].progress
        var reachedWorkEnd = valueBeforeFinalizing >= AnalysisProgress.WORK_END - 1e-5f
        var finalizeUpdates = 0
        var index = firstFinalizing
        while (index < publications.size && publications[index].phase == AnalysisProgress.Phase.FINALIZING) {
            finalizeUpdates++
            if (publications[index].progress >= AnalysisProgress.WORK_END - 1e-5f) {
                reachedWorkEnd = true
            }
            index++
        }
        assertTrue("收尾没有到 0.95", reachedWorkEnd)
        val minUpdates = ceil(
            ((AnalysisProgress.WORK_END - valueBeforeFinalizing).coerceAtLeast(0f) /
                AnalysisProgress.MAX_STEP).toDouble(),
        ).toInt()
        assertTrue(
            "收尾用了 " + finalizeUpdates + " 次,理论上限 " + minUpdates,
            finalizeUpdates <= minUpdates,
        )

        // 4. 终态恰好 1.0。
        assertEquals(1f, publications.last().progress, 0f)

        // 5. 相邻上报空档不超过心跳阈值 + 一个刷新周期。
        var maxGapMs = 0L
        for (i in 1 until publications.size) {
            maxGapMs = maxOf(maxGapMs, publications[i].atMs - publications[i - 1].atMs)
        }
        val allowedGapMs = AnalysisProgressTracker.STALL_FILL_AFTER_MS +
            AnalysisProgressTracker.UPDATE_INTERVAL_MS
        assertTrue(
            "存在 " + maxGapMs + "ms 的上报空档(上限 " + allowedGapMs + "ms)",
            maxGapMs <= allowedGapMs,
        )

        // 额外:开局 0.5s 内不超过 0.15(仅正常时长曲目;极短曲目 0.3s 就进入收尾补齐)。
        if (enforceEarlySlow) {
            val progressAt500Ms = publications.lastOrNull { it.atMs <= 500L }?.progress ?: 0f
            assertTrue("开局 0.5s 已到 " + progressAt500Ms, progressAt500Ms <= 0.15f)
        }

        println(
            "timeline[" + label + "]: updates=" + publications.size + " maxStep=" + maxStep +
                " maxGapMs=" + maxGapMs + " finalizeUpdates=" + finalizeUpdates,
        )
    }

    // -----------------------------------------------------------------------
    // 假时钟时间线
    // -----------------------------------------------------------------------

    private data class Publication(
        val atMs: Long,
        val phase: AnalysisProgress.Phase,
        val progress: Float,
    )

    /**
     * 合成一条时间线(100ms tick):
     * - 解码期间每 tick 上报一次 fraction(t/decodeMs),结束时 onDecodeComplete;
     * - 分析期间按 [engineFractions] 在 analyzeMs 内铺开(末次一定在结尾);
     * - 收尾后每 tick 调 heartbeat 补到 0.95,再保持 2 个 tick,finish 后发 Done(1.0)。
     */
    private class TimelineHarness(
        private val durationMs: Long,
        private val decodeMs: Long,
        private val analyzeMs: Long,
        private val engineFractions: List<Float>,
    ) {
        private var now = 0L
        private val publications = ArrayList<Publication>()
        private lateinit var tracker: AnalysisProgressTracker

        fun run(): List<Publication> {
            AnalysisTiming.record(durationMs, decodeMs, analyzeMs)
            tracker = AnalysisProgressTracker(durationMs, clock = { now }) { phase, progress ->
                publications.add(Publication(now, phase, progress))
            }
            tracker.start()

            val end = decodeMs + analyzeMs
            val engineTimes = engineFractions.indices.map { i ->
                if (i == engineFractions.lastIndex) {
                    end
                } else {
                    decodeMs + analyzeMs * i / (engineFractions.size - 1)
                }
            }
            var engineIndex = 0
            var tick = 0L
            while (tick < end) {
                tick += AnalysisProgressTracker.UPDATE_INTERVAL_MS
                now = tick
                if (tick <= decodeMs) {
                    tracker.onDecode(tick.toFloat() / decodeMs.toFloat())
                }
                if (tick == decodeMs) {
                    tracker.onDecodeComplete(durationMs * AnalysisProgressTracker.NOMINAL_SAMPLE_RATE / 1000L)
                }
                while (engineIndex < engineTimes.size && engineTimes[engineIndex] <= tick) {
                    tracker.onAnalyze(engineFractions[engineIndex])
                    engineIndex++
                }
                tracker.heartbeat()
            }

            now = end
            tracker.onFinalizing()
            val budgetMs = tracker.finalizeWaitBudgetMs()
            var waitedMs = 0L
            while (!tracker.finalizingSettled() && waitedMs < budgetMs) {
                now += AnalysisProgressTracker.UPDATE_INTERVAL_MS
                waitedMs += AnalysisProgressTracker.UPDATE_INTERVAL_MS
                tracker.heartbeat()
            }
            if (tracker.finalizingSettled()) {
                repeat(2) {
                    now += AnalysisProgressTracker.UPDATE_INTERVAL_MS
                    tracker.heartbeat()
                }
            }
            tracker.finish()
            // 管理器随后把状态切到 Done(调试口固定报 1.0)。
            publications.add(Publication(now, AnalysisProgress.Phase.DONE, 1f))
            return publications
        }
    }

    private companion object {
        /** 引擎真实回调的粗粒度进度序列(loop_engine.cpp)。 */
        val ENGINE_COARSE = listOf(
            0.02f, 0.05f, 0.30f, 0.50f, 0.51f, 0.53f, 0.58f, 0.585f, 0.595f, 0.60f, 0.72f, 0.95f, 1.0f,
        )

        val ENGINE_SHORT = listOf(0.02f, 0.50f, 1.0f)
    }
}
