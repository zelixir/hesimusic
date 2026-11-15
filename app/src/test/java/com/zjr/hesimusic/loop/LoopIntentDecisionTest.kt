package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切歌退出的纯函数决策:是否需要退出、退出时把 repeat 恢复成什么。
 *
 * 语义来源(issue 00004):
 * - 手动切到别的歌 → 立即退出无缝,并恢复进入前的 repeat;
 * - 切回武装歌本身(显式入口「先 arm 再 play」触发的那次 transition)→ 不退出;
 * - 没有记录(未武装)→ 保持现状。
 */
class LoopIntentDecisionTest {

    private val armed = LoopIntent.Armed(songId = 1L, previousRepeatMode = Player.REPEAT_MODE_ALL)

    @Test
    fun `未武装时不退出`() {
        assertFalse(
            "没有意图就没有退出动作",
            LoopIntentDecision.shouldExitOnSongChange(null, newSongId = 2L)
        )
        assertNull(
            "未武装时保持现状(无 repeat 可恢复)",
            LoopIntentDecision.repeatToRestore(null, newSongId = 2L, currentRepeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `进入武装歌本身不退出`() {
        assertFalse(
            "新歌 == 武装歌:这是显式进入,不能退出",
            LoopIntentDecision.shouldExitOnSongChange(armed, newSongId = 1L)
        )
        assertNull(
            "进入武装歌不写 repeat",
            LoopIntentDecision.repeatToRestore(armed, newSongId = 1L, currentRepeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `切到别的歌时退出并恢复 prevRepeat`() {
        assertTrue(
            "切到 2 号歌应退出无缝",
            LoopIntentDecision.shouldExitOnSongChange(armed, newSongId = 2L)
        )
        assertEquals(
            "应恢复进入前的 ALL",
            Player.REPEAT_MODE_ALL,
            LoopIntentDecision.repeatToRestore(armed, newSongId = 2L, currentRepeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `切到无当前歌(null)也退出`() {
        assertTrue(
            "当前歌变为 null(队列清空)同样离开武装歌",
            LoopIntentDecision.shouldExitOnSongChange(armed, newSongId = null)
        )
        assertEquals(
            "仍应恢复 prevRepeat",
            Player.REPEAT_MODE_ALL,
            LoopIntentDecision.repeatToRestore(armed, newSongId = null, currentRepeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `prevRepeat 与当前相同时无需写回`() {
        val armedOne = LoopIntent.Armed(songId = 1L, previousRepeatMode = Player.REPEAT_MODE_ONE)
        assertTrue(
            "即使无需写回,仍然要退出并清空意图",
            LoopIntentDecision.shouldExitOnSongChange(armedOne, newSongId = 2L)
        )
        assertNull(
            "prevRepeat == 当前 repeat:无需写(避免无意义的播放器事件)",
            LoopIntentDecision.repeatToRestore(armedOne, newSongId = 2L, currentRepeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `prevRepeat 为 OFF 时也能恢复`() {
        val armedOff = LoopIntent.Armed(songId = 7L, previousRepeatMode = Player.REPEAT_MODE_OFF)
        assertEquals(
            "进入前是 OFF 也要如实恢复",
            Player.REPEAT_MODE_OFF,
            LoopIntentDecision.repeatToRestore(armedOff, newSongId = 8L, currentRepeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    // ---- repeat 事件:用户手动改 vs 内部临时改写 ----

    @Test
    fun `用户手动把 repeat 改成非 ONE 时清空意图`() {
        assertTrue(
            "偏好已写成 ALL(用户手动改)→ 应清空意图",
            LoopIntentDecision.shouldClearOnRepeatChange(
                armed, newRepeatMode = Player.REPEAT_MODE_ALL, persistedRepeatMode = Player.REPEAT_MODE_ALL
            )
        )
        assertTrue(
            "偏好已写成 OFF(用户手动改)→ 应清空意图",
            LoopIntentDecision.shouldClearOnRepeatChange(
                armed, newRepeatMode = Player.REPEAT_MODE_OFF, persistedRepeatMode = Player.REPEAT_MODE_OFF
            )
        )
    }

    @Test
    fun `队列临时改写(偏好未变)不清空意图`() {
        assertFalse(
            "队列播放把 ONE 临时改成 ALL 但不写偏好 → 保留意图",
            LoopIntentDecision.shouldClearOnRepeatChange(
                armed, newRepeatMode = Player.REPEAT_MODE_ALL, persistedRepeatMode = Player.REPEAT_MODE_ONE
            )
        )
    }

    @Test
    fun `repeat 仍是 ONE 或未武装时不清空意图`() {
        assertFalse(
            "repeat=ONE 不清空(进入无缝时就是 ONE)",
            LoopIntentDecision.shouldClearOnRepeatChange(
                armed, newRepeatMode = Player.REPEAT_MODE_ONE, persistedRepeatMode = Player.REPEAT_MODE_ONE
            )
        )
        assertFalse(
            "未武装时没有可清空的意图",
            LoopIntentDecision.shouldClearOnRepeatChange(
                null, newRepeatMode = Player.REPEAT_MODE_ALL, persistedRepeatMode = Player.REPEAT_MODE_ALL
            )
        )
    }

    @Test
    fun `决策是纯函数(相同输入相同输出)`() {
        val a = LoopIntentDecision.repeatToRestore(armed, newSongId = 2L, currentRepeatMode = Player.REPEAT_MODE_ONE)
        val b = LoopIntentDecision.repeatToRestore(armed, newSongId = 2L, currentRepeatMode = Player.REPEAT_MODE_ONE)
        assertEquals("纯函数:相同输入应得到相同结果", a, b)
    }
}
