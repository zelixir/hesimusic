package com.zjr.hesimusic.ui.player

import androidx.media3.common.Player
import com.zjr.hesimusic.loop.LoopIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05 的 XOR / 退出写入序列 / 入口可见性（纯 JVM）。
 *
 * 重点：无缝态点“单曲循环”= 退出无缝 + 普通单曲循环，而不是切到 ALL；
 * 退出恢复的每一步都不能出现 shuffle=true + repeat=ONE 非法组合。
 */
class LoopSeamlessExitPolicyTest {

    private fun armed(repeat: Int, shuffle: Boolean = false) =
        LoopIntent.Armed(songId = 7L, previousRepeatMode = repeat, previousShuffleEnabled = shuffle)

    private fun simulate(initialRepeat: Int, initialShuffle: Boolean, writes: List<LoopSeamlessExitPolicy.ModeWrite>): List<Pair<Int, Boolean>> {
        var repeat = initialRepeat
        var shuffle = initialShuffle
        val states = ArrayList<Pair<Int, Boolean>>()
        for (write in writes) {
            when (write) {
                is LoopSeamlessExitPolicy.ModeWrite.Repeat -> repeat = write.mode
                is LoopSeamlessExitPolicy.ModeWrite.Shuffle -> shuffle = write.enabled
            }
            states += repeat to shuffle
        }
        return states
    }

    @Test
    fun repeat_tap_in_seamless_state_exits_and_keeps_plain_single_loop() {
        assertEquals(
            LoopSeamlessExitPolicy.RepeatTap.EXIT_KEEP_PLAIN_SINGLE,
            LoopSeamlessExitPolicy.repeatTap(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ONE, seamlessActive = true),
        )
        // 即使状态被外部写成非 ONE，只要无缝仍生效，点击语义仍是“退出 + 普通单曲循环”。
        assertEquals(
            LoopSeamlessExitPolicy.RepeatTap.EXIT_KEEP_PLAIN_SINGLE,
            LoopSeamlessExitPolicy.repeatTap(shuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ALL, seamlessActive = true),
        )
    }

    @Test
    fun normal_repeat_cycle_is_unchanged() {
        assertEquals(
            LoopSeamlessExitPolicy.RepeatTap.TO_SINGLE_LOOP,
            LoopSeamlessExitPolicy.repeatTap(shuffleEnabled = true, repeatMode = Player.REPEAT_MODE_ALL, seamlessActive = false),
        )
        assertEquals(
            LoopSeamlessExitPolicy.RepeatTap.TO_LIST_LOOP,
            LoopSeamlessExitPolicy.repeatTap(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ONE, seamlessActive = false),
        )
        assertEquals(
            LoopSeamlessExitPolicy.RepeatTap.TO_SHUFFLE,
            LoopSeamlessExitPolicy.repeatTap(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ALL, seamlessActive = false),
        )
        assertEquals(
            LoopSeamlessExitPolicy.RepeatTap.TO_SHUFFLE,
            LoopSeamlessExitPolicy.repeatTap(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF, seamlessActive = false),
        )
    }

    @Test
    fun exit_restores_previous_list_mode() {
        val writes = LoopSeamlessExitPolicy.exitWrites(armed(Player.REPEAT_MODE_ALL), keepPlainSingleLoop = false)
        assertEquals(
            listOf(
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_ALL),
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(false),
            ),
            writes,
        )
    }

    @Test
    fun exit_restores_previous_shuffle_mode_and_stays_legal() {
        val writes = LoopSeamlessExitPolicy.exitWrites(armed(Player.REPEAT_MODE_OFF, shuffle = true), keepPlainSingleLoop = false)
        assertEquals(
            listOf(
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_OFF),
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(true),
            ),
            writes,
        )
        val states = simulate(Player.REPEAT_MODE_ONE, false, writes)
        for ((repeat, shuffle) in states) {
            assertFalse("shuffle=true 且 repeat=ONE 的中间态非法", shuffle && repeat == Player.REPEAT_MODE_ONE)
        }
    }

    @Test
    fun exit_from_plain_single_loop_keeps_one() {
        val writes = LoopSeamlessExitPolicy.exitWrites(armed(Player.REPEAT_MODE_ONE), keepPlainSingleLoop = false)
        assertEquals(
            listOf(
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(false),
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_ONE),
            ),
            writes,
        )
    }

    @Test
    fun dirty_previous_mode_is_normalized() {
        // 历史/异常数据：进入前 repeat=ONE + shuffle=true；退出必须归一成 ONE/false。
        val writes = LoopSeamlessExitPolicy.exitWrites(armed(Player.REPEAT_MODE_ONE, shuffle = true), keepPlainSingleLoop = false)
        val states = simulate(Player.REPEAT_MODE_ONE, false, writes)
        for ((repeat, shuffle) in states) {
            assertFalse(shuffle && repeat == Player.REPEAT_MODE_ONE)
        }
        assertEquals(Player.REPEAT_MODE_ONE, states.last().first)
        assertFalse(states.last().second)
    }

    @Test
    fun xor_exit_keeps_plain_single_loop_and_never_illegal() {
        val writes = LoopSeamlessExitPolicy.exitWrites(armed(Player.REPEAT_MODE_ALL), keepPlainSingleLoop = true)
        assertEquals(
            listOf(
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(false),
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_ONE),
            ),
            writes,
        )
        val states = simulate(Player.REPEAT_MODE_ONE, false, writes)
        for ((repeat, shuffle) in states) {
            assertFalse(shuffle && repeat == Player.REPEAT_MODE_ONE)
        }
        assertEquals(Player.REPEAT_MODE_ONE, states.last().first)
    }

    @Test
    fun exit_entry_visibility() {
        assertTrue(LoopSeamlessExitPolicy.shouldShowExitEntry(7L, 7L, claimActive = false))
        assertTrue(LoopSeamlessExitPolicy.shouldShowExitEntry(7L, null, claimActive = true))
        assertFalse(LoopSeamlessExitPolicy.shouldShowExitEntry(7L, 8L, claimActive = false))
        assertFalse(LoopSeamlessExitPolicy.shouldShowExitEntry(null, 7L, claimActive = false))
        assertFalse(LoopSeamlessExitPolicy.shouldShowExitEntry(null, null, claimActive = false))
    }

    @Test
    fun loop_range_label_is_player_page_only() {
        val window = com.zjr.hesimusic.loop.LoopWindow(7L, 70_000_000L, 130_000_000L)
        assertEquals("01:10–02:10", LoopSeamlessExitPolicy.loopRangeLabel(window))
    }

    @Test
    fun exit_outcome_notice_says_restored_mode() {
        val restore = LoopExitOutcome(
            songId = 7L,
            keepPlainSingleLoop = false,
            writes = listOf(
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_ALL),
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(false),
            ),
        )
        val notice = restore.toNotice(nowMs = 123L)
        assertEquals(LoopNoticeKind.EXITED, notice.kind)
        assertTrue(notice.message.contains("已退出无缝循环"))
        assertTrue(notice.message.contains("列表循环"))
        assertTrue(notice.id.contains("user-exit"))
        assertTrue(notice.id.contains("123"))

        val keep = LoopExitOutcome(
            songId = 7L,
            keepPlainSingleLoop = true,
            writes = listOf(
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(false),
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_ONE),
            ),
        )
        val keepNotice = keep.toNotice(nowMs = 124L)
        assertTrue(keepNotice.message.contains("普通单曲循环"))
    }
}
