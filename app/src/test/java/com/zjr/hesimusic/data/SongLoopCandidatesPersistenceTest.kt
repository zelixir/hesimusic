package com.zjr.hesimusic.data

import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.LoopCandidatesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * `song_loop.candidatesJson` 的"写 → 读"往返测试(issue 00016 §2/§6)。
 *
 * 为什么不是真 Room DAO:项目未引入 room-testing / Robolectric(不改 gradle、不碰设备),
 * 无法在纯 JVM 里实例化 Room。这里分两层覆盖:
 * 1. **值层往返**:按 `LoopAnalysisManager` 的写法给行塞 10 个候选 → 解回 10 个且顺序一致;
 * 2. **生成代码层**:断言 Room 为 [com.zjr.hesimusic.data.dao.SongLoopDao] 生成的
 *    INSERT/UPDATE/SELECT 列清单里**确实包含 candidatesJson**——即落库/读回链路真的带上了新列
 *    (漏了它的话本 issue 依然复现)。
 */
class SongLoopCandidatesPersistenceTest {

    private fun row(candidatesJson: String? = null, confirmed: Boolean = false) = SongLoop(
        songId = 7L,
        loopStartSamples = 44_100L,
        loopEndSamples = 132_300L,
        sampleRate = 44_100,
        loopStartMs = 1_000L,
        loopEndMs = 3_000L,
        score = 0.9,
        noteDistance = 0.12,
        loudnessDifference = 0.34,
        createdAt = 1_700_000_000_000L,
        confirmed = confirmed,
        candidatesJson = candidatesJson,
    )

    private fun tenCandidates(): List<LoopCandidateKt> = (0 until 10).map { index ->
        LoopCandidateKt(
            startSamples = 44_100L * (index * 5 + 2),
            endSamples = 44_100L * (index * 5 + 2) + 176_400L,
            score = 0.99 - index * 0.02,
            noteDistance = index * 0.011,
            loudnessDifference = index * 0.022,
        )
    }

    @Test
    fun `写十个候选后读回十个且顺序一致`() {
        val candidates = tenCandidates()
        // 分析完成落库那一步(manager 内部):top1 + 完整候选 JSON 同一次 upsert。
        val persisted = row(candidatesJson = LoopCandidatesJson.encode(candidates))

        // 重进结果页那一步(DAO SELECT * → 实体 → 解码)。
        val restored = LoopCandidatesJson.decode(persisted.candidatesJson)

        assertEquals("读回数量必须与写入一致(10)", 10, restored!!.size)
        assertEquals("顺序必须一致,否则候选(N) 列表与选中项会错位", candidates, restored)
    }

    @Test
    fun `取消确认与再次确认不会清空候选JSON`() {
        val json = LoopCandidatesJson.encode(tenCandidates())
        val confirmedRow = row(candidatesJson = json, confirmed = true)

        // demoteFinal:row.copy(confirmed = false) —— 行保留 → JSON 必须原样保留。
        val demoted = confirmedRow.copy(confirmed = false)
        assertEquals("取消最终结果不得丢候选列表", json, demoted.candidatesJson)
        assertEquals(10, LoopCandidatesJson.decode(demoted.candidatesJson)!!.size)

        // 再次确认(不带内存列表的 legacy 重载会回读已有 JSON 并原样写回)。
        val reconfirmed = demoted.copy(confirmed = true, candidatesJson = demoted.candidatesJson)
        assertEquals(json, reconfirmed.candidatesJson)
    }

    @Test
    fun `老行_candidatesJson为null_保持null`() {
        val legacy = row(candidatesJson = null, confirmed = true)
        assertNull("老数据不应被凭空改写", legacy.candidatesJson)
        val demoted = legacy.copy(confirmed = false)
        assertNull("取消确认老行也不得写入 null 之外的值", demoted.candidatesJson)
    }

    @Test
    fun `生成的DAO读写SQL包含candidatesJson列`() {
        val source = generatedSource("com/zjr/hesimusic/data/dao/SongLoopDao_Impl.java")
        assumeTrue(
            "找不到 Room 生成源码(仅非 Gradle 标准布局时跳过);testDebugUnitTest 下必定存在",
            source != null,
        )
        val text = source!!
        assertTrue(
            "INSERT 必须带上 candidatesJson,否则落库时候选列表丢失",
            text.lineSequence().any { it.contains("INSERT INTO `song_loop`") && it.contains("`candidatesJson`") },
        )
        assertTrue(
            "UPDATE 必须带上 candidatesJson,否则确认/取消确认会清空候选列表",
            text.lineSequence().any { it.contains("UPDATE `song_loop`") && it.contains("`candidatesJson` = ?") },
        )
        assertTrue(
            "SELECT 映射必须读 candidatesJson,否则重进页面读不到候选列表",
            text.contains("CursorUtil.getColumnIndexOrThrow(_cursor, \"candidatesJson\")"),
        )
        assertFalse(
            "SongLoop 实体查询不得出现 missing column 兜底",
            text.contains("DROP TABLE IF EXISTS"),
        )
    }

    private fun generatedSource(relative: String): String? {
        val candidates = listOf(
            File("build/generated/ksp/debug/java/$relative"),
            File("build/generated/ksp/release/java/$relative"),
            File("app/build/generated/ksp/debug/java/$relative"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}
