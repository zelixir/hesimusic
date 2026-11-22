package com.zjr.hesimusic.service

import androidx.media3.common.Player
import com.zjr.hesimusic.loop.LoopWindow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE 接管状态 / 引擎口径 / 换源后播放拉起的单测（真机 /loop/enter 回归）。
 *
 * 真机现象：SOURCE 已接管时 `/state.engine` 仍报 exo（engine 只看 M3′ 的 audio.window），
 * 且换源后播放器停在 IDLE（replaceMediaItem 不会自己 prepare/play）。
 */
class LoopSourceRuntimeTest {

    private val aMs = 92_752L
    private val bMs = 173_546L
    private val window = LoopWindow(songId = 40169L, loopStartUs = aMs * 1_000L, loopEndUs = bMs * 1_000L)
    private val otherWindow = LoopWindow(songId = 40169L, loopStartUs = 10_000_000L, loopEndUs = 20_000_000L)

    @After
    fun tearDown() {
        LoopSourceRuntime.reset()
        LoopSourceFallback.reset()
    }

    // ---------------------------------------------------------------- 接管状态

    @Test
    fun `installed window is visible and window scoped`() {
        assertNull(LoopSourceRuntime.installedWindow)
        assertFalse(LoopSourceRuntime.isInstalledFor(window))

        LoopSourceRuntime.onInstalled(window, "/music/40169.flac")
        assertEquals(window, LoopSourceRuntime.installedWindow)
        assertEquals("/music/40169.flac", LoopSourceRuntime.installedPath)
        assertTrue(LoopSourceRuntime.isInstalledFor(window))
        // A/B 变了就是另一个窗口（需要重新接管）。
        assertFalse(LoopSourceRuntime.isInstalledFor(otherWindow))
        // 别的歌不算。
        assertFalse(
            LoopSourceRuntime.isInstalledFor(
                LoopWindow(songId = 7L, loopStartUs = aMs * 1_000L, loopEndUs = bMs * 1_000L),
            ),
        )
        assertFalse(LoopSourceRuntime.isInstalledFor(null))

        LoopSourceRuntime.onReleased()
        assertNull(LoopSourceRuntime.installedWindow)
        assertNull(LoopSourceRuntime.installedPath)
    }

    @Test
    fun `build failure makes an installed window unusable`() {
        // 00027/P0-1：factory 回退 delegate 后协调器即使调用过 onInstalled，
        // 也不能再把该窗口当作 SOURCE 生效。
        LoopSourceRuntime.onInstalled(window, "/music/40169.flac")
        assertTrue(LoopSourceRuntime.isInstalledFor(window))

        LoopSourceFallback.reportBuildFailure(
            songId = window.songId,
            window = window,
            throwable = IllegalStateException("decoder init failed"),
        )

        assertFalse(LoopSourceRuntime.isInstalledFor(window))
        // 默认 sourceWindow = installedWindow：engine/loopPath 必须立刻退回普通播放。
        assertEquals("exo", LoopSourceRuntime.engineName(null, currentSongId = window.songId))
        assertEquals("none", LoopSourceRuntime.loopPath(null, currentSongId = window.songId))
    }

    @Test
    fun `onInstalledIfBuilt refuses a window the factory already failed`() {
        LoopSourceFallback.reportBuildFailure(
            songId = window.songId,
            window = window,
            throwable = IllegalStateException("decoder init failed"),
        )

        assertFalse(
            LoopSourceRuntime.onInstalledIfBuilt(
                window = window,
                filePath = "/music/40169.flac",
                buildUnavailable = false,
            ),
        )
        assertNull(LoopSourceRuntime.installedWindow)
        assertFalse(LoopSourceRuntime.isInstalledFor(window))
    }

    @Test
    fun `onInstalledIfBuilt registers a genuinely built window`() {
        assertTrue(
            LoopSourceRuntime.onInstalledIfBuilt(
                window = window,
                filePath = "/music/40169.flac",
                buildUnavailable = false,
            ),
        )
        assertEquals(window, LoopSourceRuntime.installedWindow)
        assertTrue(LoopSourceRuntime.isInstalledFor(window))
    }

    // ---------------------------------------------------------------- engine 口径

    @Test
    fun `source takeover reports loop even without an m3 window`() {
        LoopSourceRuntime.onInstalled(window, "/music/40169.flac")
        assertEquals(
            "loop",
            LoopSourceRuntime.engineName(m3Window = null, sourceWindow = window, currentSongId = 40169L),
        )
        assertEquals(
            "source",
            LoopSourceRuntime.loopPath(m3Window = null, sourceWindow = window, currentSongId = 40169L),
        )
    }

    @Test
    fun `m3 window still reports loop and wins the path label`() {
        assertEquals(
            "loop",
            LoopSourceRuntime.engineName(m3Window = window, sourceWindow = null, currentSongId = 40169L),
        )
        assertEquals(
            "m3",
            LoopSourceRuntime.loopPath(m3Window = window, sourceWindow = null, currentSongId = 40169L),
        )
        // M3′ 与 SOURCE 窗口同时在（切换瞬间）：M3′ 优先，行为与旧逻辑一致。
        assertEquals(
            "m3",
            LoopSourceRuntime.loopPath(m3Window = window, sourceWindow = otherWindow, currentSongId = 40169L),
        )
    }

    @Test
    fun `window of another song never reports loop`() {
        assertEquals(
            "exo",
            LoopSourceRuntime.engineName(m3Window = window, sourceWindow = null, currentSongId = 7L),
        )
        assertEquals(
            "exo",
            LoopSourceRuntime.engineName(m3Window = null, sourceWindow = window, currentSongId = 7L),
        )
        assertEquals("none", LoopSourceRuntime.loopPath(null, window, 7L))
    }

    @Test
    fun `cold start without cached song id accepts the window`() {
        // 真机冷启动：服务层窗口已按当前歌算出，但 debug 的 Player.Listener 缓存可能还是 null。
        assertEquals("loop", LoopSourceRuntime.engineName(null, window, currentSongId = null))
        assertEquals("source", LoopSourceRuntime.loopPath(null, window, currentSongId = null))
    }

    @Test
    fun `no window at all reports exo`() {
        assertEquals("exo", LoopSourceRuntime.engineName(null, null, 40169L))
        assertEquals("none", LoopSourceRuntime.loopPath(null, null, 40169L))
        // 非法窗口（B <= A）同样不算 loop。
        val broken = LoopWindow(songId = 40169L, loopStartUs = bMs * 1_000L, loopEndUs = aMs * 1_000L)
        assertEquals("exo", LoopSourceRuntime.engineName(broken, null, 40169L))
        assertEquals("exo", LoopSourceRuntime.engineName(null, broken, 40169L))
    }

    // ---------------------------------------------------------------- 换源后拉起播放

    @Test
    fun `revive plan prepares an idle player and respects play intent`() {
        // 真机 /loop/enter：setMediaItems 后还是 IDLE；play 已请求 → 必须 prepare + play。
        val idlePlaying = LoopSourceRuntime.revivePlan(Player.STATE_IDLE, playWhenReadyBefore = true)
        assertTrue(idlePlaying.prepare)
        assertTrue(idlePlaying.play)

        // IDLE 且用户没要求播放：只 prepare，绝不偷播。
        val idlePaused = LoopSourceRuntime.revivePlan(Player.STATE_IDLE, playWhenReadyBefore = false)
        assertTrue(idlePaused.prepare)
        assertFalse(idlePaused.play)
    }

    @Test
    fun `revive plan never prepares a player that is already loading`() {
        for (state in listOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            val playing = LoopSourceRuntime.revivePlan(state, playWhenReadyBefore = true)
            assertFalse("state=$state must not re-prepare", playing.prepare)
            assertTrue(playing.play)

            val paused = LoopSourceRuntime.revivePlan(state, playWhenReadyBefore = false)
            assertFalse(paused.prepare)
            assertFalse(paused.play)
        }
    }

    @Test
    fun `rebuild already loading skips the second prepare`() {
        // 00029：setMediaItems 后播放器已在 BUFFERING/READY → sink 已 configure，
        // 再 prepare() 会 flush 并二次 configure（275/305ms 双静音）。
        for (stateAfter in listOf(Player.STATE_BUFFERING, Player.STATE_READY, Player.STATE_ENDED)) {
            val playing = LoopSourceRuntime.revivePlan(
                Player.STATE_READY,
                playWhenReadyBefore = true,
                sourcesRebuilt = true,
                stateAfterRebuild = stateAfter,
            )
            assertFalse("stateAfter=$stateAfter 换源后禁止再显式 prepare", playing.prepare)
            assertTrue("仍然要补 play", playing.play)
        }
    }

    @Test
    fun `rebuild left idle still prepares`() {
        // play() 不会自动 prepare：rebuild 后仍 IDLE 时必须显式 prepare，否则源停住。
        val playing = LoopSourceRuntime.revivePlan(
            Player.STATE_READY,
            playWhenReadyBefore = true,
            sourcesRebuilt = true,
            stateAfterRebuild = Player.STATE_IDLE,
        )
        assertTrue("IDLE 必须 prepare，否则假循环", playing.prepare)
        assertTrue(playing.play)

        val paused = LoopSourceRuntime.revivePlan(
            Player.STATE_READY,
            playWhenReadyBefore = false,
            sourcesRebuilt = true,
            stateAfterRebuild = Player.STATE_IDLE,
        )
        assertTrue("暂停换源仍必须 prepare", paused.prepare)
        assertFalse("pwr=false 绝不偷播", paused.play)
    }
}
