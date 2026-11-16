package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.LoopCandidatesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoopCandidateRestore] 的纯 JVM 单测(issue 00016 §3/§6):
 * 重进结果页的候选列表恢复 + 选中项匹配 + 老数据单候选兜底 +
 * **用户拖过的 A/B 优先**(保存窗口不在候选里 → 作为第 0 个合成候选插入,绝不改回候选 0)。
 */
class LoopCandidateRestoreTest {

    private fun row(
        startSamples: Long = 44_100L,
        endSamples: Long = 132_300L,
        candidatesJson: String? = null,
        score: Double = 0.9,
        noteDistance: Double = 0.12,
        loudnessDifference: Double = 0.34,
    ) = SongLoop(
        songId = 42L,
        loopStartSamples = startSamples,
        loopEndSamples = endSamples,
        sampleRate = 44_100,
        loopStartMs = startSamples * 1000L / 44_100L,
        loopEndMs = endSamples * 1000L / 44_100L,
        score = score,
        noteDistance = noteDistance,
        loudnessDifference = loudnessDifference,
        createdAt = 1_700_000_000_000L,
        confirmed = true,
        candidatesJson = candidatesJson,
    )

    private fun tenCandidates(): List<LoopCandidateKt> = (0 until 10).map { index ->
        LoopCandidateKt(
            startSamples = 44_100L * (index * 3 + 1),
            endSamples = 44_100L * (index * 3 + 1) + 132_300L,
            score = 0.95 - index * 0.05,
            noteDistance = 0.01 * index,
            loudnessDifference = 0.02 * index,
        )
    }

    @Test
    fun `重进页面恢复十个候选且选中已确认那个`() {
        val candidates = tenCandidates()
        val confirmed = candidates[3]
        val restored = LoopCandidateRestore.fromRow(
            row(
                startSamples = confirmed.startSamples,
                endSamples = confirmed.endSamples,
                candidatesJson = LoopCandidatesJson.encode(candidates),
            ),
        )

        assertTrue("应判定为来自落库候选列表", restored.fromStoredList)
        assertEquals("候选数量必须恢复为 10(问题的核心)", 10, restored.candidates.size)
        assertEquals("候选顺序必须与落库时一致", candidates, restored.candidates)
        assertEquals("选中项必须指向已确认那个候选", 3, restored.selectedIndex)
    }

    @Test
    fun `每个候选都能被匹配到自己的下标`() {
        val candidates = tenCandidates()
        val json = LoopCandidatesJson.encode(candidates)
        candidates.indices.forEach { index ->
            val restored = LoopCandidateRestore.fromRow(
                row(
                    startSamples = candidates[index].startSamples,
                    endSamples = candidates[index].endSamples,
                    candidatesJson = json,
                ),
            )
            assertEquals("下标 $index 必须自匹配", index, restored.selectedIndex)
        }
    }

    @Test
    fun `老数据_json为null_退回单候选`() {
        val restored = LoopCandidateRestore.fromRow(row(candidatesJson = null))

        assertFalse("null 表示老数据,不是候选列表", restored.fromStoredList)
        assertEquals(1, restored.candidates.size)
        assertEquals(0, restored.selectedIndex)
        assertEquals(
            "单候选必须逐字沿用行内最佳窗口与评分(与改造前行为一致)",
            LoopCandidateKt(
                startSamples = 44_100L,
                endSamples = 132_300L,
                score = 0.9,
                noteDistance = 0.12,
                loudnessDifference = 0.34,
            ),
            restored.candidates.single(),
        )
    }

    @Test
    fun `损坏json退回单候选`() {
        val restored = LoopCandidateRestore.fromRow(row(candidatesJson = "[{\"startSamples\":1,"))
        assertFalse(restored.fromStoredList)
        assertEquals(1, restored.candidates.size)
        assertEquals(44_100L, restored.candidates.single().startSamples)
    }

    @Test
    fun `空数组退回单候选`() {
        val restored = LoopCandidateRestore.fromRow(row(candidatesJson = "[]"))
        assertFalse(restored.fromStoredList)
        assertEquals(1, restored.candidates.size)
    }

    @Test
    fun `保存窗口不在候选里时作为第0个合成候选插入_A_B仍是用户保存的窗口`() {
        val candidates = tenCandidates()
        val restored = LoopCandidateRestore.fromRow(
            row(
                // 用户手动拖过 A/B:落库窗口不在候选列表里
                startSamples = 12_345L,
                endSamples = 678_900L,
                score = 0.77,
                noteDistance = 0.05,
                loudnessDifference = 0.06,
                candidatesJson = LoopCandidatesJson.encode(candidates),
            ),
        )

        assertTrue(restored.fromStoredList)
        assertTrue("必须标记为插入了合成候选", restored.splicedSavedWindow)
        assertEquals("候选数为 parsed+1(10 → 11)", 11, restored.candidates.size)
        assertEquals("选中合成候选(第 0 个)", 0, restored.selectedIndex)
        assertEquals(
            "第 0 个候选就是用户保存的窗口,分数取行内已有值(不得造 0)",
            LoopCandidateKt(
                startSamples = 12_345L,
                endSamples = 678_900L,
                score = 0.77,
                noteDistance = 0.05,
                loudnessDifference = 0.06,
            ),
            restored.candidates.first(),
        )
        assertEquals(
            "原有 10 个候选必须原样跟在合成候选之后(顺序不变)",
            candidates,
            restored.candidates.drop(1),
        )
    }

    @Test
    fun `保存窗口命中候选时不插入_候选数不变`() {
        val candidates = tenCandidates()
        val confirmed = candidates[7]
        val restored = LoopCandidateRestore.fromRow(
            row(
                startSamples = confirmed.startSamples,
                endSamples = confirmed.endSamples,
                candidatesJson = LoopCandidatesJson.encode(candidates),
            ),
        )

        assertFalse("命中候选时不得插入合成候选", restored.splicedSavedWindow)
        assertEquals("候选数保持 10", 10, restored.candidates.size)
        assertEquals("选中命中项", 7, restored.selectedIndex)
        assertEquals("列表顺序完全不变", candidates, restored.candidates)
    }

    @Test
    fun `合成候选与_十候选恢复的边界_不重复插入`() {
        // 合成候选的下标 0 与行内窗口一致:再次恢复时必须命中,不再插入(候选数仍 11 → 命中 0)
        val saved = LoopCandidateKt(12_345L, 678_900L, 0.77, 0.05, 0.06)
        val eleven = listOf(saved) + tenCandidates()
        val restored = LoopCandidateRestore.fromRow(
            row(
                startSamples = saved.startSamples,
                endSamples = saved.endSamples,
                candidatesJson = LoopCandidatesJson.encode(eleven),
            ),
        )
        assertFalse("已落库的合成候选属于候选列表,不得二次插入", restored.splicedSavedWindow)
        assertEquals(11, restored.candidates.size)
        assertEquals(0, restored.selectedIndex)
    }
}
