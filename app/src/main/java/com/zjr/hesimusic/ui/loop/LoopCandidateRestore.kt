package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.LoopCandidatesJson

/**
 * 重新进入结果页时恢复出来的候选列表(issue 00016)。
 *
 * @property candidates 结果页候选列表:通常是"落库时的候选 sequence(引擎 score 降序)";
 *   若用户**手动拖过 A/B** 再确认(保存窗口不在候选里),则把保存窗口作为**第 0 个合成候选**
 *   插到最前面(见 [splicedSavedWindow]);
 * @property selectedIndex 当前选中下标:未插入时指向与 [SongLoop.loopStartSamples]/[SongLoop.loopEndSamples]
 *   完全一致的那个候选;插入了合成候选 / 老数据时恒为 0;
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
 * - 解析成功但行内窗口**不命中**(用户拖过 A/B)→ 把行内窗口作为**第 0 个合成候选插入**,
 *   列表变成 `[saved] + parsed`,selectedIndex = 0;合成候选的 score/noteDistance/loudnessDifference
 *   取行里已有的值(即该窗口自己保存的分数),绝不造 0。
 */
object LoopCandidateRestore {

    fun fromRow(row: SongLoop): RestoredCandidates {
        val saved = LoopCandidateKt(
            startSamples = row.loopStartSamples,
            endSamples = row.loopEndSamples,
            score = row.score,
            noteDistance = row.noteDistance,
            loudnessDifference = row.loudnessDifference,
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
        val matched = stored.indexOfFirst { candidate ->
            candidate.startSamples == row.loopStartSamples &&
                candidate.endSamples == row.loopEndSamples
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
}
