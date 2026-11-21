package com.zjr.hesimusic.ui.player

import androidx.media3.common.Player
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.LoopExitReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private val window = LoopWindow(7L, 10_000_000L, 50_000_000L)

    private fun outcome(
        keepPlainSingleLoop: Boolean,
        writes: List<LoopSeamlessExitPolicy.ModeWrite>,
    ) = LoopExitOutcome(
        songId = 7L,
        keepPlainSingleLoop = keepPlainSingleLoop,
        writes = writes,
        window = window,
        previousRepeatMode = Player.REPEAT_MODE_ONE,
        previousShuffleEnabled = false,
    )

    @Test
    fun user_exit_publishes_user_exit_notice_and_consumes_once() {
        val exit = outcome(
            keepPlainSingleLoop = false,
            writes = listOf(
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_ALL),
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(false),
            ),
        )
        val notice = exit.toExitNotice(nowMs = 123L)
        assertEquals(LoopExitReason.USER_EXIT, notice.reason)
        assertEquals(Player.REPEAT_MODE_ALL, notice.nextRepeatMode)
        assertFalse(notice.nextShuffleEnabled)
        assertTrue("summary 必须带 user-exit", notice.summary().contains("user-exit"))

        LoopClaimStore.clearExitNotice()
        LoopClaimStore.publishExit(notice)
        assertEquals(notice, LoopClaimStore.consumeExitNotice())
        assertNull("consume 是一次性的", LoopClaimStore.consumeExitNotice())
    }

    @Test
    fun user_exit_ui_feed_is_exited_and_shown_only_once() {
        val exit = outcome(
            keepPlainSingleLoop = true,
            writes = listOf(
                LoopSeamlessExitPolicy.ModeWrite.Shuffle(false),
                LoopSeamlessExitPolicy.ModeWrite.Repeat(Player.REPEAT_MODE_ONE),
            ),
        )
        LoopClaimStore.clearExitNotice()
        LoopClaimStore.publishExit(exit.toExitNotice(nowMs = 124L))

        val ui = ServiceLoopExitNoticeFeed.consumeFor(7L)!!
        assertEquals(LoopNoticeKind.EXITED, ui.kind)
        assertTrue("提示里也要能截图到原因码", ui.message.contains("user-exit"))
        assertTrue(ui.message.contains("已退出无缝循环"))
        assertNull("同一条 service notice 不能被消费两次", ServiceLoopExitNoticeFeed.consumeFor(7L))

        // UI 只弹一次：markShown 后同一 id 不再入队。
        val seen = HashSet<String>()
        val center = LoopNoticeCenter(
            seen = object : LoopNoticeSeenStore {
                override fun isSeen(id: String, nowMs: Long): Boolean = id in seen
                override fun mark(id: String, nowMs: Long) {
                    seen += id
                }
            },
            now = { 1L },
        )
        center.offer(ui)
        assertEquals(1, center.notices.value.size)
        center.markShown(ui.id)
        center.offer(ui)
        assertTrue("同一退出事件不得复弹", center.notices.value.isEmpty())
    }
}
