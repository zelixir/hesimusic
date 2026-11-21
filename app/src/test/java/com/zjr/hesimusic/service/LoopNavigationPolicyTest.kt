package com.zjr.hesimusic.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #106 循环中切歌的纯策略测试。
 *
 * 口径（真机/契约测试已核实）：ExoPlayer 的 seekToNext/Previous 对 repeat=ONE 使用
 * **导航重复模式**（ONE 降级成 OFF），所以"多曲队列原地不动"并不成立；真正需要本层
 * 接管的是**退场**：循环武装时 next/prev 必须先把旧循环清干净（单曲队列尤其明显）。
 */
class LoopNavigationPolicyTest {

    @Test
    fun exitBeforeNavigation_whenArmedIntentTargetsCurrentSong() {
        assertTrue(
            LoopNavigationPolicy.shouldExitBeforeNavigation(
                repeatMode = Player.REPEAT_MODE_ONE,
                currentSongId = 39944L,
                armedSongId = 39944L,
                claimActive = false,
            ),
        )
    }

    @Test
    fun exitBeforeNavigation_whenClaimIsActiveEvenWithoutIntent() {
        assertTrue(
            LoopNavigationPolicy.shouldExitBeforeNavigation(
                repeatMode = Player.REPEAT_MODE_ONE,
                currentSongId = 39944L,
                armedSongId = null,
                claimActive = true,
            ),
        )
    }

    @Test
    fun noExit_whenUserJustPickedRepeatOneWithoutLoop() {
        // 用户自己选"单曲循环"（无无缝意图、无 claim）→ 保持原生语义，不改变既有行为。
        assertFalse(
            LoopNavigationPolicy.shouldExitBeforeNavigation(
                repeatMode = Player.REPEAT_MODE_ONE,
                currentSongId = 39944L,
                armedSongId = null,
                claimActive = false,
            ),
        )
    }

    @Test
    fun noExit_whenRepeatIsNotOne() {
        for (mode in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)) {
            assertFalse(
                "repeat=" + mode,
                LoopNavigationPolicy.shouldExitBeforeNavigation(
                    repeatMode = mode,
                    currentSongId = 39944L,
                    armedSongId = 39944L,
                    claimActive = true,
                ),
            )
        }
    }

    @Test
    fun noExit_whenIntentBelongsToAnotherSong() {
        assertFalse(
            LoopNavigationPolicy.shouldExitBeforeNavigation(
                repeatMode = Player.REPEAT_MODE_ONE,
                currentSongId = 39944L,
                armedSongId = 41405L,
                claimActive = false,
            ),
        )
    }

    @Test
    fun noExit_withoutCurrentSong() {
        assertFalse(
            LoopNavigationPolicy.shouldExitBeforeNavigation(
                repeatMode = Player.REPEAT_MODE_ONE,
                currentSongId = null,
                armedSongId = null,
                claimActive = true,
            ),
        )
    }
}
