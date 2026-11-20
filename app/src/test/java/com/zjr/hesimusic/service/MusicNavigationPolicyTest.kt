package com.zjr.hesimusic.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-085:repeat=ALL 时 hasNextMediaItem() 恒 true(播放器自己绕回),手写绕回不可达。
 */
class MusicNavigationPolicyTest {

    @Test
    fun `repeat ALL 的最后一项交给播放器绕回`() {
        assertFalse(
            "hasNext=true 已被播放器处理,不能再手写绕回",
            MusicNavigationPolicy.shouldWrapToStart(
                hasNext = true,
                repeatMode = Player.REPEAT_MODE_ALL,
                mediaItemCount = 3,
            ),
        )
        assertFalse(
            "repeat=ALL 的尾部同样交给播放器",
            MusicNavigationPolicy.shouldWrapToEnd(
                hasPrevious = true,
                repeatMode = Player.REPEAT_MODE_ALL,
                mediaItemCount = 3,
            ),
        )
    }

    @Test
    fun `repeat OFF 或 ONE 的末项需要手写绕回`() {
        for (mode in intArrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE)) {
            assertTrue(
                "repeat=" + mode + " 末项 hasNext=false:手写绕回",
                MusicNavigationPolicy.shouldWrapToStart(false, mode, 3),
            )
            assertTrue(
                "repeat=" + mode + " 首项 hasPrevious=false:手写绕回",
                MusicNavigationPolicy.shouldWrapToEnd(false, mode, 3),
            )
        }
    }

    @Test
    fun `还有下一项或队列为空时不绕回`() {
        assertFalse(MusicNavigationPolicy.shouldWrapToStart(true, Player.REPEAT_MODE_OFF, 3))
        assertFalse(MusicNavigationPolicy.shouldWrapToStart(false, Player.REPEAT_MODE_OFF, 0))
        assertFalse(MusicNavigationPolicy.shouldWrapToEnd(true, Player.REPEAT_MODE_OFF, 3))
        assertFalse(MusicNavigationPolicy.shouldWrapToEnd(false, Player.REPEAT_MODE_OFF, 0))
    }
}
