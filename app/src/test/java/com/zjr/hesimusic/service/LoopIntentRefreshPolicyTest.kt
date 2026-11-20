package com.zjr.hesimusic.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-016:setMediaItems 每次都会派发 PLAYLIST_CHANGED。
 *
 * 修复前的监听对任何 reason 都补算,并把"setMediaItems 不触发 transition"当 settle 前提。
 * 这里锁住:只有 AUTO/REPEAT/SEEK 或 PLAYLIST_CHANGED 的"空→非空"才补算;
 * settle 只在 currentMediaItem 缺失时启动,出现后才尝试。
 */
class LoopIntentRefreshPolicyTest {

    @Test
    fun `同队列重设触发的 PLAYLIST_CHANGED 不是换歌信号`() {
        assertFalse(
            "previous=true,current=true 只是重设队列,不能当切歌",
            LoopIntentRefreshPolicy.shouldRefreshOnTransition(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                previousItemPresent = true,
                currentItemPresent = true,
            ),
        )
    }

    @Test
    fun `PLAYLIST_CHANGED 只在 currentMediaItem 由空变非空时补算`() {
        assertTrue(
            LoopIntentRefreshPolicy.shouldRefreshOnTransition(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                previousItemPresent = false,
                currentItemPresent = true,
            ),
        )
        assertFalse(
            "非空变空不需要补算窗口",
            LoopIntentRefreshPolicy.shouldRefreshOnTransition(
                reason = Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED,
                previousItemPresent = true,
                currentItemPresent = false,
            ),
        )
    }

    @Test
    fun `AUTO REPEAT SEEK 仍按切歌处理`() {
        for (reason in intArrayOf(
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK,
        )) {
            assertTrue(
                "reason=" + reason + " 应补算",
                LoopIntentRefreshPolicy.shouldRefreshOnTransition(reason, true, true),
            )
            assertFalse(
                "reason=" + reason + " 但当前 item 已为空:不补算",
                LoopIntentRefreshPolicy.shouldRefreshOnTransition(reason, true, false),
            )
        }
    }

    @Test
    fun `settle 的前提是 item 缺失 出现后才尝试`() {
        assertTrue(LoopIntentRefreshPolicy.shouldStartSettle(currentItemMissing = true))
        assertFalse("item 已在时不需要 2s 轮询", LoopIntentRefreshPolicy.shouldStartSettle(currentItemMissing = false))
        assertFalse(LoopIntentRefreshPolicy.shouldAttemptSettle(currentItemPresent = false))
        assertTrue(LoopIntentRefreshPolicy.shouldAttemptSettle(currentItemPresent = true))
    }
}
