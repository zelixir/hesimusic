package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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

    @Test
    fun `行不存在时无窗口`() {
        assertNull(
            "无 song_loop 行应不具备无缝资格",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, null)
        )
    }

    @Test
    fun `songId 为空时无窗口`() {
        assertNull(
            "songId=null 应不具备无缝资格",
            LoopEligibility.derive(null, Player.REPEAT_MODE_ONE, row())
        )
        assertNull(
            "songId=0 应不具备无缝资格",
            LoopEligibility.derive(0L, Player.REPEAT_MODE_ONE, row())
        )
        assertNull(
            "songId 为负应不具备无缝资格",
            LoopEligibility.derive(-1L, Player.REPEAT_MODE_ONE, row())
        )
    }

    @Test
    fun `未确认的临时结果不生效`() {
        assertNull(
            "confirmed=false 的临时结果不允许无缝",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(confirmed = false))
        )
    }

    @Test
    fun `重复模式不是单曲循环时不生效`() {
        assertNull(
            "repeat=OFF 不应无缝",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_OFF, row())
        )
        assertNull(
            "repeat=ALL 不应无缝",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ALL, row())
        )
    }

    @Test
    fun `已确认但循环区间非法时不生效`() {
        assertNull(
            "loopEnd<=loopStart 应判为非法窗口",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(loopStartMs = 3_000L, loopEndMs = 3_000L))
        )
        assertNull(
            "loopEnd 早于 loopStart 应判为非法窗口",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(loopStartMs = 3_000L, loopEndMs = 1_000L))
        )
        assertNull(
            "loopStart 为负应判为非法窗口",
            LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row(loopStartMs = -1L, loopEndMs = 1_000L))
        )
    }

    @Test
    fun `正常命中返回微秒窗口`() {
        val w = LoopEligibility.derive(42L, Player.REPEAT_MODE_ONE, row(songId = 42L))
        assertNotNull("已确认 + 单曲循环 + 合法区间应命中", w)
        assertEquals("songId 应透传", 42L, w!!.songId)
        assertEquals("毫秒起点应换算为微秒", 1_000_000L, w.loopStartUs)
        assertEquals("毫秒终点应换算为微秒", 3_000_000L, w.loopEndUs)
        assertEquals("窗口长度应为 2s", 2_000_000L, w.lengthUs)
    }

    @Test
    fun `同一输入幂等返回相等窗口`() {
        val a = LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row())
        val b = LoopEligibility.derive(1L, Player.REPEAT_MODE_ONE, row())
        assertEquals("纯函数:相同输入应得到相等窗口", a, b)
    }
}
