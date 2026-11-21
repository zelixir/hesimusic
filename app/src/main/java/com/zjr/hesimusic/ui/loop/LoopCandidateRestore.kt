package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.LoopCandidatesJson
import kotlin.math.abs

/**
 * 重新进入结果页时恢复出来的候选列表(issue 00016)。
 *
 * @property candidates 结果页候选列表:通常是"落库时的候选 sequence(引擎 score 降序)";
 *   若用户**手动拖过 A/B** 再确认(保存窗口不在候选里),则把保存窗口作为**第 0 个合成候选**
 *   插到最前面(见 [splicedSavedWindow]);
 * @property selectedIndex 当前选中下标:未插入时指向与 [SongLoop.loopStartSamples]/[SongLoop.loopEndSamples]
 *   **在 [LoopCandidateRestore.MATCH_TOLERANCE_SAMPLES] 容差内**匹配的那个候选;插入了合成候选 / 老数据时恒为 0;
 * @property fromStoredList true = 候选列表主体来自 candidatesJson;false = 老数据(仅有最佳窗口)的单候选回退;
 * @property splicedSavedWindow true = 保存窗口不在候选列表里,已作为第 0 个合成候选插入(候选数 +1)。
 */
data class RestoredCandidates(
    val candidates: List<LoopCandidateKt>,
    val selectedIndex: Int,
    val fromStoredList: Boolean,
    val splicedSavedWindow: Boolean = false,
)

/**
 * `song_loop` 行 → 结果页候选列表的纯函数恢复逻辑(无 Android 依赖,可 JVM 单测)。
 *
 * 契约(issue 00016 §3,lead 裁决补充):
 * - **用户保存的窗口永远优先于候选排序**:恢复后 A/B 必须等于行内保存窗口,绝不能被候选排序改写
 *   (否则用户手动拖过的 A/B 会被无声吞掉);
 * - 优先解析 [SongLoop.candidatesJson];解析失败 / null / 空数组 → 退回**单候选**(老数据兼容,
 *   与改造前行为逐字一致,A/B = 行内窗口);
 * - 解析成功且行内窗口**命中**某个候选 → 该候选的 [RestoredCandidates.selectedIndex],列表不插入;
 *   命中判定带容差(见 [MATCH_TOLERANCE_SAMPLES]):落库的采样点是"采样点 → ms 夹取 → 再取整"链路上
 *   的近似回写,可能与候选原始采样点差 1~2 个样本(真机实例:保存 231787ms vs 候选 231786ms,差 1ms),
 *   严格相等会把同一个窗口误判成"用户拖过",于是插入合成候选、真机显示「候选(11)」;
 * - 解析成功但行内窗口**不命中**(用户拖过 A/B)→ 把行内窗口作为**第 0 个合成候选插入**,
 *   列表变成 `[saved] + parsed`,selectedIndex = 0;合成候选的 score/noteDistance/loudnessDifference
 *   取行里已有的值(即该窗口自己保存的分数),绝不造 0;seamQuality 行里未存 → 0(未知),
 *   UI 只显示相似度、不显示接缝百分比。
 */
object LoopCandidateRestore {

    /**
     * 命中容差之一:样本数(规格要求 ≤2 样本)。
     *
     * 2 个样本在 44.1kHz 下 ≈ 0.045ms,只吸收浮点/取整噪声。
     */
    const val MATCH_TOLERANCE_SAMPLES = 2L

    /**
     * 命中容差之二:毫秒(兜住「采样点 → 显示 ms(round) → 保存采样点(round)」链路的真实误差)。
     *
     * - 链路误差实测:44.1kHz 下最大 **22 样本**(0.5ms),48kHz 下 24 样本;远超 2 样本,
     *   所以只靠 ±2 样本无法消掉真机上的重复候选;
     * - 真机复现:保存窗口 231787ms vs 候选 0 的 231786ms(差 1ms = 44.1kHz 下 44 样本);
     * - 1ms 仍远小于任何人手拖动精度(拖 B 一个像素就是几十 ms),所以不会把"用户真的改了窗口"
     *   误判成命中;而真正的窗口改动(验收用例:差 100 样本 ≈ 2.27ms)依然判为不命中、插入合成候选。
     */
    const val MATCH_TOLERANCE_MS = 1.0

    fun fromRow(row: SongLoop): RestoredCandidates {
        val saved = LoopCandidateKt(
            startSamples = row.loopStartSamples,
            endSamples = row.loopEndSamples,
            score = row.score,
            noteDistance = row.noteDistance,
            loudnessDifference = row.loudnessDifference,
            // song_loop 行不存 seamQuality:合成候选按"未知"处理(0),不臆造接缝分。
            seamQuality = 0.0,
        )
        val stored = LoopCandidatesJson.decode(row.candidatesJson)
        if (stored.isNullOrEmpty()) {
            // 老数据:没有候选列表,只能把行内窗口当唯一候选(行为与改造前完全一致)。
            return RestoredCandidates(
                candidates = listOf(saved),
                selectedIndex = 0,
                fromStoredList = false,
            )
        }
        val match = Matcher(row)
        val matched = stored.indexOfFirst { candidate ->
            match.withinTolerance(candidate.startSamples, row.loopStartSamples) &&
                match.withinTolerance(candidate.endSamples, row.loopEndSamples)
        }
        if (matched >= 0) {
            return RestoredCandidates(
                candidates = stored,
                selectedIndex = matched,
                fromStoredList = true,
            )
        }
        // 用户手动拖过 A/B:保存窗口不在候选里 → 作为第 0 个合成候选插入,
        // 保证恢复后 A/B == 保存窗口(用户调整优先于候选排序)。
        return RestoredCandidates(
            candidates = listOf(saved) + stored,
            selectedIndex = 0,
            fromStoredList = true,
            splicedSavedWindow = true,
        )
    }

    /** 采样点匹配:≤2 样本 或 ≤[MATCH_TOLERANCE_MS] 毫秒(两者取"任一满足")。 */
    private class Matcher(row: SongLoop) {

        private val sampleRate: Double = row.sampleRate.toDouble()

        fun withinTolerance(candidateSamples: Long, savedSamples: Long): Boolean {
            val delta = abs(candidateSamples - savedSamples)
            if (delta <= MATCH_TOLERANCE_SAMPLES) return true
            if (sampleRate <= 0.0) return false
            return delta * 1000.0 / sampleRate <= MATCH_TOLERANCE_MS
        }
    }
}
