package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 显式意图驱动的资格判定:五个条件(songId 有效 / 意图命中 / 行已确认 / repeat=ONE / 窗口合法)
 * 缺一不可。旧语义里「行已确认 + repeat=ONE 就自动无缝」必须被意图门挡住。
 */
class LoopEligibilityTest {

    private fun row(
        songId: Long = 1L,
        loopStartMs: Long = 1_000L,
        loopEndMs: Long = 3_000L,
        confirmed: Boolean = true,
    ) = SongLoop(
        songId = songId,
        loopStartSamples = loopStartMs * 44L,
        loopEndSamples = loopEndMs * 44L,
        sampleRate = 44_100,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        score = 0.9,
        noteDistance = 0.12,
        loudnessDifference = 0.34,
        createdAt = 1_700_000_000_000L,
        confirmed = confirmed,
    )

    // ---- 意图门(新增语义) ----

    @Test
    fun `未武装意图时绝不无缝`() {
        assertNull(
            "行已确认 + repeat=ONE,但用户从未显式进入 → 不允许无缝",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(), armedSongId = null)
        )
    }

    @Test
    fun `武装的是别的歌时不无缝`() {
        assertNull(
            "意图在 2 号歌,当前是 1 号歌 → 不允许无缝(切歌即退出)",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(), armedSongId = 2L)
        )
    }

    @Test
    fun `武装命中且 repeat 不是 ONE 时不无缝`() {
        assertNull(
            "意图命中但 repeat=OFF → 窗口必须为空",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_OFF, row(), armedSongId = 1L)
        )
        assertNull(
            "意图命中但 repeat=ALL → 窗口必须为空",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ALL, row(), armedSongId = 1L)
        )
    }

    @Test
    fun `武装命中但行未确认时不无缝`() {
        assertNull(
            "意图命中 + repeat=ONE,但行只是临时结果(confirmed=false) → 不允许无缝",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(confirmed = false), armedSongId = 1L)
        )
    }

    @Test
    fun `武装命中且其余条件满足时返回窗口`() {
        val w = LoopEligibility.derive(42L, Player.REPEAT_MODE_ONE, row(songId = 42L), armedSongId = 42L)
        assertNotNull("显式意图 + 已确认 + 单曲循环 + 合法区间应命中", w)
        assertEquals("songId 应透传", 42L, w!!.songId)
        assertEquals("毫秒起点应换算为微秒", 1_000_000L, w.loopStartUs)
        assertEquals("毫秒终点应换算为微秒", 3_000_000L, w.loopEndUs)
        assertEquals("窗口长度应为 2s", 2_000_000L, w.lengthUs)
    }

    @Test
    fun `武装 id 非法(0 或负数)时不无缝`() {
        assertNull(
            "armedSongId=0 不应命中任何歌",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(), armedSongId = 0L)
        )
        assertNull(
            "armedSongId=-1 不应命中任何歌",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(), armedSongId = -1L)
        )
    }

    // ---- 其余既有条件 ----

    @Test
    fun `行不存在时无窗口`() {
        assertNull(
            "无 song_loop 行应不具备无缝资格",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, null, armedSongId = 1L)
        )
    }

    @Test
    fun `songId 为空时无窗口`() {
        assertNull(
            "songId=null 应不具备无缝资格",
            LoopEligibility.derive(null, Player.REPEAT_MODE_ONE, row(), armedSongId = 1L)
        )
        assertNull(
            "songId=0 应不具备无缝资格",
            LoopEligibility.derive(0L, Player.REPEAT_MODE_ONE, row(), armedSongId = 0L)
        )
        assertNull(
            "songId 为负应不具备无缝资格",
            LoopEligibility.derive(-1L, Player.REPEAT_MODE_ONE, row(), armedSongId = -1L)
        )
    }

    @Test
    fun `已确认但循环区间非法时不生效`() {
        assertNull(
            "loopEnd<=loopStart 应判为非法窗口",
            LoopEligibility.derive(
                1L, Player.REPEAT_MODE_ONE,
                row(loopStartMs = 3_000L, loopEndMs = 3_000L), armedSongId = 1L
            )
        )
        assertNull(
            "loopEnd 早于 loopStart 应判为非法窗口",
            LoopEligibility.derive(
                1L, Player.REPEAT_MODE_ONE,
                row(loopStartMs = 3_000L, loopEndMs = 1_000L), armedSongId = 1L
            )
        )
        assertNull(
            "loopStart 为负应判为非法窗口",
            LoopEligibility.derive(
                1L, Player.REPEAT_MODE_ONE,
                row(loopStartMs = -1L, loopEndMs = 1_000L), armedSongId = 1L
            )
        )
    }

    @Test
    fun `同一输入幂等返回相等窗口`() {
        val a = LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(), armedSongId = 1L)
        val b = LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(), armedSongId = 1L)
        assertEquals("纯函数:相同输入应得到相等窗口", a, b)
    }
}
