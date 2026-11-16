package com.zjr.hesimusic.loop

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进入/退出决策的纯函数测试(issue 00004 追加:repeat + shuffle 两个模式)。
 *
 * 语义来源:
 * - 进入:记录 repeat + shuffle 两个原值,播放层唯一写点强制 ONE/false;
 * - 切到别的歌 → 两个都恢复;
 * - 用户改 repeat → **不写任何模式**(repeat 保留用户新值,shuffle 保持 false);
 * - 用户改 shuffle → shuffle 保留用户新值,repeat 恢复 prevRepeat,
 *   且强制不变量 shuffle=true ⇒ repeat != ONE(prevRepeat==ONE 时改用 ALL);
 * - 进入前的值 == 当前值 → 对应字段为 null(不写,避免多余事件);
 * - 未武装 / 仍在武装歌 → 不退出。
 */
class LoopIntentDecisionTest {

    private val armed = LoopIntent.Armed(
        songId = 1L,
        previousRepeatMode = Player.REPEAT_MODE_ALL,
        previousShuffleEnabled = true,
    )

    /** 无缝生效时的实际模式:repeat=ONE + shuffle=false。 */
    private val seamlessMode = LoopIntentDecision.Mode(
        repeatMode = Player.REPEAT_MODE_ONE,
        shuffleEnabled = false,
    )

    // ---------------------------------------------------------------- 切歌退出

    @Test
    fun `未武装时不退出`() {
        assertFalse(
            "没有意图就没有退出动作",
            LoopIntentDecision.shouldExitOnSongChange(null, newSongId = 2L)
        )
        assertNull(
            "未武装:不退出,无恢复计划",
            LoopIntentDecision.modeToRestoreOnSongChange(null, newSongId = 2L, current = seamlessMode)
        )
        assertNull(
            "未武装:repeat 事件不退出",
            LoopIntentDecision.modeToRestoreOnRepeatChange(null)
        )
        assertNull(
            "未武装:shuffle 事件不退出",
            LoopIntentDecision.modeToRestoreOnShuffleChange(null, current = seamlessMode)
        )
    }

    @Test
    fun `进入武装歌本身不退出`() {
        assertFalse(
            "新歌 == 武装歌:这是显式进入,不能退出",
            LoopIntentDecision.shouldExitOnSongChange(armed, newSongId = 1L)
        )
        assertNull(
            "进入武装歌不写任何模式",
            LoopIntentDecision.modeToRestoreOnSongChange(armed, newSongId = 1L, current = seamlessMode)
        )
    }

    @Test
    fun `切到别的歌时 repeat 与 shuffle 都恢复进入前的值`() {
        assertTrue(
            "切到 2 号歌应退出无缝",
            LoopIntentDecision.shouldExitOnSongChange(armed, newSongId = 2L)
        )
        assertEquals(
            "应恢复进入前的 ALL + shuffle=true",
            LoopIntentDecision.ModeRestore(
                repeatMode = Player.REPEAT_MODE_ALL,
                shuffleEnabled = true,
            ),
            LoopIntentDecision.modeToRestoreOnSongChange(armed, newSongId = 2L, current = seamlessMode)
        )
    }

    @Test
    fun `切到无当前歌(null)也退出并恢复两个模式`() {
        assertTrue(
            "当前歌变为 null(队列清空)同样离开武装歌",
            LoopIntentDecision.shouldExitOnSongChange(armed, newSongId = null)
        )
        assertEquals(
            "仍应恢复进入前的 ALL + shuffle=true",
            LoopIntentDecision.ModeRestore(Player.REPEAT_MODE_ALL, shuffleEnabled = true),
            LoopIntentDecision.modeToRestoreOnSongChange(armed, newSongId = null, current = seamlessMode)
        )
    }

    @Test
    fun `进入前的值与当前相同时不写(但依然退出)`() {
        val armedSame = LoopIntent.Armed(
            songId = 1L,
            previousRepeatMode = Player.REPEAT_MODE_ONE,
            previousShuffleEnabled = false,
        )
        val restore = LoopIntentDecision.modeToRestoreOnSongChange(
            armedSame, newSongId = 2L, current = seamlessMode,
        )
        assertEquals(
            "两个字段都为 null:不产生任何播放器事件",
            LoopIntentDecision.ModeRestore(repeatMode = null, shuffleEnabled = null),
            restore
        )
        assertTrue("ModeRestore 对象非空 = 仍然退出", restore != null)
        assertTrue("isEmpty 便于调用方/日志判断是否真的写了播放器", restore!!.isEmpty)
    }

    @Test
    fun `只有 repeat 不同时只写 repeat`() {
        val restore = LoopIntentDecision.modeToRestoreOnSongChange(
            armed, newSongId = 2L,
            current = LoopIntentDecision.Mode(Player.REPEAT_MODE_ONE, shuffleEnabled = true),
        )
        assertEquals(
            "shuffle 已等于进入前的 true → 不写",
            LoopIntentDecision.ModeRestore(repeatMode = Player.REPEAT_MODE_ALL, shuffleEnabled = null),
            restore
        )
    }

    @Test
    fun `只有 shuffle 不同时只写 shuffle`() {
        val restore = LoopIntentDecision.modeToRestoreOnSongChange(
            armed, newSongId = 2L,
            current = LoopIntentDecision.Mode(Player.REPEAT_MODE_ALL, shuffleEnabled = false),
        )
        assertEquals(
            "repeat 已等于进入前的 ALL → 不写",
            LoopIntentDecision.ModeRestore(repeatMode = null, shuffleEnabled = true),
            restore
        )
    }

    // ---------------------------------------------------------------- repeat 事件

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
    fun `用户改 repeat 退出时不写任何模式(不恢复 shuffle)`() {
        // 3 态按钮「单曲循环 → 列表循环」:用户刚选了列表循环,shuffle 必须保持 false。
        // 若恢复 prevShuffle=true,显示会跳成「随机播放」,与用户刚按下的操作打架。
        val restore = LoopIntentDecision.modeToRestoreOnRepeatChange(armed)
        assertEquals(
            "两个字段都不写:repeat 保留用户新值,shuffle 保持 false",
            LoopIntentDecision.ModeRestore(repeatMode = null, shuffleEnabled = null),
            restore
        )
        assertTrue(restore!!.isEmpty)
    }

    // ---------------------------------------------------------------- shuffle 事件

    @Test
    fun `用户手动改 shuffle 时清空意图`() {
        assertTrue(
            "偏好已写成 true(用户打开随机播放)→ 应清空意图",
            LoopIntentDecision.shouldClearOnShuffleChange(
                armed, newShuffleEnabled = true, persistedShuffleEnabled = true
            )
        )
        assertTrue(
            "偏好已写成 false(用户关闭随机播放)→ 应清空意图",
            LoopIntentDecision.shouldClearOnShuffleChange(
                armed, newShuffleEnabled = false, persistedShuffleEnabled = false
            )
        )
    }

    @Test
    fun `无缝进入写 shuffle=false 不写偏好,不清空意图`() {
        assertFalse(
            "进入时把 shuffle 改成 false 但偏好仍是 true → 内部写入,保留意图",
            LoopIntentDecision.shouldClearOnShuffleChange(
                armed, newShuffleEnabled = false, persistedShuffleEnabled = true
            )
        )
    }

    @Test
    fun `未武装时改 shuffle 不清空意图`() {
        assertFalse(
            "未武装时 shuffle 与无缝无关",
            LoopIntentDecision.shouldClearOnShuffleChange(
                null, newShuffleEnabled = true, persistedShuffleEnabled = true
            )
        )
    }

    @Test
    fun `用户改 shuffle 退出时保留新 shuffle 只恢复 repeat`() {
        assertEquals(
            "shuffle 保持用户打开的 true;repeat 恢复进入前的 ALL",
            LoopIntentDecision.ModeRestore(repeatMode = Player.REPEAT_MODE_ALL, shuffleEnabled = null),
            LoopIntentDecision.modeToRestoreOnShuffleChange(
                armed,
                current = LoopIntentDecision.Mode(Player.REPEAT_MODE_ONE, shuffleEnabled = true),
            )
        )
    }

    @Test
    fun `用户开 shuffle 且 prevRepeat==ONE 时按不变量置 ALL`() {
        // 不变量:shuffle=true ⇒ repeat != ONE。prevRepeat 恰好是 ONE 时不能原样恢复。
        val armedOne = LoopIntent.Armed(
            songId = 1L,
            previousRepeatMode = Player.REPEAT_MODE_ONE,
            previousShuffleEnabled = false,
        )
        assertEquals(
            "prevRepeat=ONE 且 shuffle=true → repeat 强制改为 ALL",
            LoopIntentDecision.ModeRestore(repeatMode = Player.REPEAT_MODE_ALL, shuffleEnabled = null),
            LoopIntentDecision.modeToRestoreOnShuffleChange(
                armedOne,
                current = LoopIntentDecision.Mode(Player.REPEAT_MODE_ONE, shuffleEnabled = true),
            )
        )
    }

    @Test
    fun `用户关闭 shuffle 时不触发不变量强制`() {
        // 用户把 shuffle 关掉时,repeat 恢复成 ONE 是合法的(shuffle=false)。
        val armedOne = LoopIntent.Armed(
            songId = 1L,
            previousRepeatMode = Player.REPEAT_MODE_ONE,
            previousShuffleEnabled = false,
        )
        val restore = LoopIntentDecision.modeToRestoreOnShuffleChange(
            armedOne,
            current = LoopIntentDecision.Mode(Player.REPEAT_MODE_ALL, shuffleEnabled = false),
        )
        assertEquals(
            "shuffle=false 时 repeat 恢复 prevRepeat=ONE",
            LoopIntentDecision.ModeRestore(repeatMode = Player.REPEAT_MODE_ONE, shuffleEnabled = null),
            restore
        )
    }

    @Test
    fun `用户改 shuffle 时 repeat 已等于进入前的值则不写`() {
        val restore = LoopIntentDecision.modeToRestoreOnShuffleChange(
            armed,
            current = LoopIntentDecision.Mode(Player.REPEAT_MODE_ALL, shuffleEnabled = true),
        )
        assertEquals(
            "repeat 已是 ALL → 无需写",
            LoopIntentDecision.ModeRestore(repeatMode = null, shuffleEnabled = null),
            restore
        )
        assertTrue(restore!!.isEmpty)
    }

    // ---------------------------------------------------------------- 纯函数

    @Test
    fun `决策是纯函数(相同输入相同输出)`() {
        val a = LoopIntentDecision.modeToRestoreOnSongChange(armed, newSongId = 2L, current = seamlessMode)
        val b = LoopIntentDecision.modeToRestoreOnSongChange(armed, newSongId = 2L, current = seamlessMode)
        assertEquals("纯函数:相同输入应得到相同结果", a, b)
        assertEquals(
            "纯函数:shuffle 决策同样幂等",
            LoopIntentDecision.shouldClearOnShuffleChange(armed, true, true),
            LoopIntentDecision.shouldClearOnShuffleChange(armed, true, true),
        )
    }
}
