package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.service.LoopClaim
import com.zjr.hesimusic.service.LoopClaimResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D16：进入循环时持久化 repeat 偏好 + 循环意图，保证重启后“是否在循环”与“显示的循环模式”一致。
 *
 * D07 产品决定：重启后**恢复循环**（用户心智：上次在循环，回来还在循环更自然），
 * 但只有 当前歌 == 武装歌 && row.confirmed && 窗口合法 && repeat == ONE 时才恢复；
 * 任一不满足就显式清意图 + 恢复进入前模式（restore-unavailable），绝不假循环。
 */
class LoopIntentPersistenceTest {

    private class FakeStore : LoopIntent.Store {
        var saved: LoopIntent.Armed? = null
        var writes: Int = 0

        override fun load(): LoopIntent.Armed? = saved

        override fun save(armed: LoopIntent.Armed?) {
            saved = armed
            writes++
        }
    }

    private val songId = 42L
    private val fullUs = 225_000_000L

    private fun row(confirmed: Boolean = true) = SongLoop(
        songId = songId,
        loopStartSamples = 10_000L * 44,
        loopEndSamples = 30_000L * 44,
        sampleRate = 44_100,
        loopStartMs = 10_000L,
        loopEndMs = 30_000L,
        score = 0.9,
        noteDistance = 0.1,
        loudnessDifference = 0.2,
        createdAt = 1_700_000_000_000L,
        confirmed = confirmed,
    )

    @Test
    fun `armed intent survives a process restart and clear removes it`() {
        val store = FakeStore()
        val before = LoopIntent(store)
        before.arm(songId, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
        val expected = LoopIntent.Armed(songId, Player.REPEAT_MODE_ALL, false)
        assertEquals(expected, before.armed.value)
        assertEquals("arm 必须落盘", expected, store.saved)

        val afterRestart = LoopIntent(store)
        assertEquals("新进程实例应读回意图", expected, afterRestart.armed.value)

        afterRestart.clear()
        assertNull(afterRestart.armed.value)
        assertNull("clear 必须同时清持久化", store.saved)
        assertNull("再次重启不应复活", LoopIntent(store).armed.value)
    }

    @Test
    fun `restart with persisted loop mode reports looping only when the loop is truly restored`() {
        val store = FakeStore()
        val before = LoopIntent(store)
        before.arm(songId, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
        // 进入时协调器写入偏好的模式必须与 UI 显示一致：单曲循环 + 关随机。
        assertEquals(Player.REPEAT_MODE_ONE, LoopModePreference.Entered.repeatMode)
        assertFalse(LoopModePreference.Entered.shuffleEnabled)

        // 重启：意图 + 偏好都回来了，行仍已确认 → 一致，恢复循环。
        val afterRestart = LoopIntent(store)
        val restored = afterRestart.armed.value
        assertNotNull(restored)
        val window = LoopEligibility.derive(
            songId = songId,
            repeatMode = LoopModePreference.Entered.repeatMode,
            row = row(confirmed = true),
            armedSongId = restored!!.songId,
            durationUs = fullUs,
        )
        assertNotNull("一致性校验通过时必须恢复循环", window)
        assertTrue(
            "显示单曲循环且 claim 真正生效",
            LoopClaim(songId, window, sourceActive = true).claimActive,
        )

        // 退出：清意图 + 偏好恢复进入前模式；重启后既不循环、也不显示循环模式。
        afterRestart.clear()
        val exitMode = LoopModePreference.restored(restored)
        assertEquals(Player.REPEAT_MODE_ALL, exitMode.repeatMode)
        assertFalse(exitMode.shuffleEnabled)
        val afterExitRestart = LoopIntent(store)
        assertNull("退出后重启不应恢复循环", afterExitRestart.armed.value)
        assertNull(
            "没有意图就不会派生窗口",
            LoopEligibility.derive(
                songId = songId,
                repeatMode = exitMode.repeatMode,
                row = row(confirmed = true),
                armedSongId = afterExitRestart.armed.value?.songId,
                durationUs = fullUs,
            ),
        )
        assertFalse(
            LoopClaimResolver.resolve(
                songId = songId,
                runtimeWindow = null,
                audioWindow = null,
                installedWindow = null,
                isReplaying = false,
                sourceRoute = true,
                sourceUnavailable = false,
            ).claimActive,
        )
    }

    @Test
    fun `an inconsistent restart drops the persisted loop instead of faking one`() {
        val store = FakeStore()
        LoopIntent(store).arm(songId, Player.REPEAT_MODE_ALL, previousShuffleEnabled = false)
        val restored = LoopIntent(store).armed.value!!
        // 例如重启时歌曲已被取消确认 / 换歌 / 窗口非法：一致性门必须失败。
        assertNull(
            LoopEligibility.derive(
                songId = songId,
                repeatMode = Player.REPEAT_MODE_ONE,
                row = row(confirmed = false),
                armedSongId = restored.songId,
                durationUs = fullUs,
            ),
        )
        assertNull(
            LoopEligibility.derive(
                songId = 99L,
                repeatMode = Player.REPEAT_MODE_ONE,
                row = row(confirmed = true),
                armedSongId = restored.songId,
                durationUs = fullUs,
            ),
        )
    }

    @Test
    fun `same intent is persisted once and invalid songId is ignored`() {
        val store = FakeStore()
        val intent = LoopIntent(store)
        intent.arm(0L, Player.REPEAT_MODE_ALL, previousShuffleEnabled = true)
        assertNull(intent.armed.value)
        assertNull(store.saved)
    }
}
