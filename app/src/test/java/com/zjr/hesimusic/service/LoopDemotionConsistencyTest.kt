package com.zjr.hesimusic.service

import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.loop.LoopEligibility
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D13：confirmed 语义 + demote 显式退出。
 *
 * 场景：已确认 → 正在循环（claim=true）→ 取消确认 → 必须显式退场：
 * claim=false、intent 清空、repeat/shuffle 恢复进入前、位置回到媒体时间轴、
 * 退场原因可见（exitNotice）。
 */
class LoopDemotionConsistencyTest {

    private val songId = 7L
    private val window = LoopWindow(songId, 50_000_000L, 80_000_000L)
    private val fullUs = 225_000_000L
    private val previousRepeat = Player.REPEAT_MODE_ALL
    private val previousShuffle = false
    private val armed = LoopIntent.Armed(songId, previousRepeat, previousShuffle)

    private fun row(confirmed: Boolean = true) = SongLoop(
        songId = songId,
        loopStartSamples = 50L * 44,
        loopEndSamples = 80L * 44,
        sampleRate = 44_100,
        loopStartMs = 50_000L,
        loopEndMs = 80_000L,
        score = 0.9,
        noteDistance = 0.1,
        loudnessDifference = 0.2,
        createdAt = 1_700_000_000_000L,
        confirmed = confirmed,
    )

    @After
    fun tearDown() {
        LoopClaimStore.resetForTest()
        LoopSourceFallback.reset()
        LoopSourceRuntime.reset()
        LoopPlaybackPhaseRuntime.reset()
    }

    @Test
    fun `unconfirmed candidates can only be auditioned in preview not looped in main playback`() {
        assertFalse(
            "未确认候选必须被主播放确认门拒绝",
            LoopEligibility.isConfirmedForMainPlayback(row(confirmed = false)),
        )
        assertTrue(
            "已确认结果才允许主播放",
            LoopEligibility.isConfirmedForMainPlayback(row(confirmed = true)),
        )
        // 主播放资格：未确认行永远 derive 不出窗口。
        assertNull(
            LoopEligibility.derive(
                songId = songId,
                repeatMode = Player.REPEAT_MODE_ONE,
                row = row(confirmed = false),
                armedSongId = songId,
                durationUs = fullUs,
            ),
        )
    }

    @Test
    fun `confirm then loop then demote exits visibly and restores the media timeline`() {
        // 1) 已确认 + 显式意图 + repeat=ONE → 窗口成立，claim 生效。
        val eligible = LoopEligibility.derive(
            songId = songId,
            repeatMode = Player.REPEAT_MODE_ONE,
            row = row(confirmed = true),
            armedSongId = songId,
            durationUs = fullUs,
        )
        assertEquals("已确认窗口应可进入", window, eligible)
        val looping = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = eligible,
            audioWindow = eligible,
            installedWindow = eligible,
            isReplaying = false,
            sourceRoute = true,
            sourceUnavailable = false,
        )
        assertTrue("确认后应处于循环", looping.claimActive)
        assertEquals("循环中展示位置折回", 51_000L, looping.displayPositionMs(81_000L))

        // 2) demote：同一首歌的行 confirmed 1→0 → 必须给出显式退出计划。
        val exit = LoopRevocation.detect(
            armed = armed,
            previousWindow = eligible,
            songId = songId,
            row = row(confirmed = false),
            currentRepeatMode = Player.REPEAT_MODE_ONE,
            currentShuffleEnabled = false,
        )
        assertNotNull("demote 必须触发显式退出（而不是静默停）", exit)
        assertEquals(LoopExitReason.CONFIRMATION_REVOKED, exit!!.reason)
        assertEquals("必须恢复进入前的 repeat", previousRepeat, exit.restoreRepeatMode)
        assertEquals("必须恢复进入前的 shuffle", previousShuffle, exit.restoreShuffleEnabled)
        assertTrue("循环模式要变化，才需要写播放器", exit.repeatChanged)

        // 3) 退场后：eligibility/claim 都不再声称循环。
        assertNull(
            "demote 后不能再派生窗口",
            LoopEligibility.derive(
                songId = songId,
                repeatMode = Player.REPEAT_MODE_ONE,
                row = row(confirmed = false),
                armedSongId = songId,
                durationUs = fullUs,
            ),
        )
        val after = LoopClaimResolver.resolve(
            songId = songId,
            runtimeWindow = null,
            audioWindow = null,
            installedWindow = eligible,
            isReplaying = false,
            sourceRoute = true,
            sourceUnavailable = false,
        )
        assertFalse("demote 后 claim 必须为 false", after.claimActive)
        assertNull(after.effectiveWindow)

        // 4) 位置回到媒体时间轴：claim 不生效时 display 原样透传。
        val mediaPositionMs = 81_000L
        assertEquals(
            "退场后位置必须是媒体时间（不再折回 A）",
            mediaPositionMs,
            after.displayPositionMs(mediaPositionMs),
        )

        // 5) 原因可见：notice 可被 UI 一次性消费。
        val notice = LoopExitNotice(
            songId = songId,
            reason = exit.reason,
            window = exit.window,
            previousRepeatMode = Player.REPEAT_MODE_ONE,
            nextRepeatMode = exit.restoreRepeatMode,
            previousShuffleEnabled = false,
            nextShuffleEnabled = exit.restoreShuffleEnabled,
            notifiedAtMs = 1L,
        )
        LoopClaimStore.publishExit(notice)
        assertEquals(notice, LoopClaimStore.exitNoticeFlow.value)
        assertEquals(notice, LoopClaimStore.consumeExitNotice())
        assertNull("notice 是一次性的", LoopClaimStore.consumeExitNotice())
        assertTrue(notice.summary().contains("confirmation-revoked"))
    }

    @Test
    fun `user exit notice is published and consumed`() {
        // D05：UI 主动退出与 demote/delete/restore 共用同一条可见状态通道。
        val notice = LoopExitNotice(
            songId = songId,
            reason = LoopExitReason.USER_EXIT,
            window = window,
            previousRepeatMode = Player.REPEAT_MODE_ONE,
            nextRepeatMode = previousRepeat,
            previousShuffleEnabled = false,
            nextShuffleEnabled = previousShuffle,
            notifiedAtMs = 42L,
        )
        LoopClaimStore.publishExit(notice)
        assertEquals(notice, LoopClaimStore.exitNoticeFlow.value)
        assertEquals(notice, LoopClaimStore.consumeExitNotice())
        assertNull("USER_EXIT 同样是一次性提示", LoopClaimStore.consumeExitNotice())
        assertTrue(notice.summary().contains("user-exit"))
    }

    @Test
    fun `deleting the analysed row is also an explicit exit`() {
        val exit = LoopRevocation.detect(
            armed = armed,
            previousWindow = window,
            songId = songId,
            row = null,
            currentRepeatMode = Player.REPEAT_MODE_ONE,
            currentShuffleEnabled = false,
        )
        assertEquals(LoopExitReason.RESULT_DELETED, exit?.reason)
    }

    @Test
    fun `a still-confirmed row never triggers revocation`() {
        assertNull(
            LoopRevocation.detect(
                armed = armed,
                previousWindow = window,
                songId = songId,
                row = row(confirmed = true),
                currentRepeatMode = Player.REPEAT_MODE_ONE,
                currentShuffleEnabled = false,
            ),
        )
        assertNull(
            "没有循环过（上一轮窗口为空）不应误报 demote",
            LoopRevocation.detect(
                armed = armed,
                previousWindow = null,
                songId = songId,
                row = row(confirmed = false),
                currentRepeatMode = Player.REPEAT_MODE_ONE,
                currentShuffleEnabled = false,
            ),
        )
    }
}
