package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMainPlayer
import com.zjr.hesimusic.loop.audio.source.LoopSourceMediaTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「四处开关同源」的守卫（C 复核）。
 *
 * 曾经翻车：renderers 看主开关、MediaSourceFactory 看另一个、协调器/会话层各读全局开关，
 * 于是 SOURCE 下「换了输出通路但播放源还是普通文件」→ 卡成 PPT。现在四个接线点共用
 * [MainPlayerRouting] 里的**同一个布尔快照**，本测试把这一点钉住：
 *
 * 1. 主播放器 renderers（di/MediaModule.kt）；
 * 2. LoopAwareMediaSourceFactory.enabled（di/MediaModule.kt）；
 * 3. LoopPlaybackCoordinator 的替换分支；
 * 4. LoopSessionPlayer 的投影分支。
 */
class MainPlayerRouteTest {

    private val window = LoopWindow(songId = 7L, loopStartUs = 10_000_000L, loopEndUs = 50_000_000L)
    private val itemTag = LoopSourceItemTag(
        songId = 7L,
        loopStartUs = 10_000_000L,
        loopEndUs = 50_000_000L,
        filePath = "/music/a.mp3",
    )

    @After
    fun tearDown() {
        MainPlayerRouting.reset()
        LoopSourceFallback.reset()
        LoopSourceMainPlayer.setEnabledForTest(null)
        LoopSourceMainPlayer.activateMainPlayer(false)
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
    }

    @Test
    fun `four wiring points agree on one boolean`() {
        for (mainEnabled in listOf(false, true)) {
            for (sourceMode in listOf(false, true)) {
                val expected = mainEnabled && sourceMode
                // 与既有纯函数同义：主开关 AND 引擎开关。
                assertEquals(
                    "legacy pure function must agree main=$mainEnabled source=$sourceMode",
                    expected,
                    LoopSourceMainPlayer.shouldUseSourceForMainPlayer(
                        mainEnabled = mainEnabled,
                        sourceMode = sourceMode,
                    ),
                )
                val route = MainPlayerRoute.of(expected)
                MainPlayerRouting.activate(route)
                val four = listOf(
                    MainPlayerRouting.renderersUseSource(route),
                    MainPlayerRouting.mediaSourceFactoryEnabled(route),
                    MainPlayerRouting.coordinatorReplacesItem(
                        window = window,
                        sourceUnavailable = false,
                        route = route,
                    ),
                    MainPlayerRouting.sessionProjectsSource(
                        tag = itemTag,
                        sourceUnavailable = false,
                        route = route,
                    ),
                )
                assertEquals("main=$mainEnabled source=$sourceMode four=$four", List(4) { expected }, four)
            }
        }
    }

    @Test
    fun `runtime switch flips cannot move the construction snapshot`() {
        MainPlayerRouting.activate(MainPlayerRoute.of(true))
        // 构建后用户/调试把两个全局开关都关掉：四个接线点仍必须看构建时快照，
        // 不允许出现“renderers 已切 SOURCE、源却按普通文件播”的半切换。
        LoopSourceMainPlayer.setEnabledForTest(false)
        LoopSourceMode.current = LoopSourceMode.M3_PRIME
        assertTrue(MainPlayerRouting.renderersUseSource())
        assertTrue(MainPlayerRouting.mediaSourceFactoryEnabled())
        assertTrue(MainPlayerRouting.coordinatorReplacesItem(window, sourceUnavailable = false))
        assertTrue(MainPlayerRouting.sessionProjectsSource(itemTag, sourceUnavailable = false))

        MainPlayerRouting.reset()
        assertFalse(MainPlayerRouting.renderersUseSource())
        assertFalse(MainPlayerRouting.mediaSourceFactoryEnabled())
        assertFalse(MainPlayerRouting.coordinatorReplacesItem(window, sourceUnavailable = false))
        assertFalse(MainPlayerRouting.sessionProjectsSource(itemTag, sourceUnavailable = false))
    }

    @Test
    fun `registered source failure disables replacement and projection together`() {
        MainPlayerRouting.activate(MainPlayerRoute.of(true))
        LoopSourceFallback.reportUnavailable(
            songId = 7L,
            window = window,
            reason = LoopSourceFallback.Reason.RING_TOO_LARGE,
            detail = "test",
        )
        val unavailable = LoopSourceFallback.isUnavailable(7L, window)
        assertTrue(unavailable)
        // ③ 和 ④ 同时失效：不许“协调器没换源、会话层却按 T 投影”的错位。
        assertFalse(MainPlayerRouting.coordinatorReplacesItem(window, unavailable))
        assertFalse(MainPlayerRouting.sessionProjectsSource(itemTag, unavailable))
        // 其它窗口/其它歌不受影响（换窗口即可重试）。
        val otherWindow = LoopWindow(7L, 20_000_000L, 50_000_000L)
        assertTrue(MainPlayerRouting.coordinatorReplacesItem(otherWindow, sourceUnavailable = false))
        assertTrue(MainPlayerRouting.sessionProjectsSource(itemTag, sourceUnavailable = false))
    }

    @Test
    fun `missing window or non loop tag is never replaced or projected`() {
        MainPlayerRouting.activate(MainPlayerRoute.of(true))
        assertFalse(MainPlayerRouting.coordinatorReplacesItem(null, sourceUnavailable = false))
        assertFalse(MainPlayerRouting.sessionProjectsSource(null, sourceUnavailable = false))
        assertFalse(MainPlayerRouting.sessionProjectsSource("normal-tag", sourceUnavailable = false))
        // 预览页的旧 tag 也认（同一个循环数据源家族），但路线关闭时一律不投影。
        assertTrue(MainPlayerRouting.sessionProjectsSource(LoopSourceMediaTag, sourceUnavailable = false))
        MainPlayerRouting.reset()
        assertFalse(MainPlayerRouting.sessionProjectsSource(LoopSourceMediaTag, sourceUnavailable = false))
    }
}
