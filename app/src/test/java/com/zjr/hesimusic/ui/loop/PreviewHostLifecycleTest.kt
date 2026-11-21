package com.zjr.hesimusic.ui.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景 #45:结果页旋转重建后预览必须仍然可用。
 *
 * JVM 单测跑不起真的 Activity 重建,但生产接线(LoopScreen → LoopViewModel →
 * [PreviewHostLifecycle])的决策序列可以完整模拟:
 *
 * - onHostDestroyed(isChangingConfigurations = true)  ← 旋转/分屏/深色/字体重建
 * - onHostCreated(...)                                ← 新宿主首帧
 *
 * 断言:配置变更**不释放**预览、只重挂探针(REATTACH_ONLY);真正离开才释放;
 * 播放器在重建中丢过时按**页面位置**恢复(不是 A)。
 */
class PreviewHostLifecycleTest {

    /** 不碰 Android/ExoPlayer 的假预览,只暴露宿主决策需要的三件事。 */
    private class FakePreview(
        override var isPrepared: Boolean,
        override var songId: Long?,
    ) : PreviewHostTarget {

        var attachCount = 0
            private set

        var releaseCount = 0
            private set

        override fun attachProbe() {
            attachCount += 1
        }

        override fun release() {
            releaseCount += 1
            isPrepared = false
            songId = null
        }
    }

    @Test
    fun `rotation keeps preview alive and only reattaches probe`() {
        val preview = FakePreview(isPrepared = true, songId = 42L)
        val host = PreviewHostLifecycle(preview)

        // Activity 配置变更销毁:播放器由 ViewModel 持有,必须保留。
        assertEquals(
            PreviewHostDestroyAction.KEEP_ALIVE,
            host.onHostDestroyed(isChangingConfigurations = true),
        )
        // 新宿主首帧:播放器还在 —— 只重挂探针,不 seek/不重建。
        assertEquals(
            PreviewHostCreateAction.REATTACH_ONLY,
            host.onHostCreated(pageReady = true, pageSongId = 42L),
        )
        // 销毁那一刻状态机就把探针挂回去,新宿主首帧再幂等挂一次。
        assertEquals("重建后探针必须重新挂上(否则 /debug/preview 恒 409)", 2, preview.attachCount)
        assertEquals("配置变更重建绝不能释放预览", 0, preview.releaseCount)
        assertEquals("重建后宿主代数 +1", 2, host.generation)
        assertTrue("预览播放器必须仍然 prepared", preview.isPrepared)
        assertEquals(42L, preview.songId)
    }

    @Test
    fun `all configuration-change paths keep the preview`() {
        // 未声明 configChanges 的 Activity 上,这四条路径都是"销毁并重建"。
        listOf("rotation", "split-screen", "dark-mode", "font-scale").forEach { path ->
            val preview = FakePreview(isPrepared = true, songId = 7L)
            val host = PreviewHostLifecycle(preview)

            assertEquals(path, PreviewHostDestroyAction.KEEP_ALIVE, host.onHostDestroyed(true))
            assertEquals(path, PreviewHostCreateAction.REATTACH_ONLY, host.onHostCreated(true, 7L))
            assertEquals(path, 2, preview.attachCount)
        }
    }

    @Test
    fun `leaving the page releases the preview`() {
        val preview = FakePreview(isPrepared = true, songId = 42L)
        val host = PreviewHostLifecycle(preview)

        assertEquals(
            PreviewHostDestroyAction.RELEASE_AND_RESUME,
            host.onHostDestroyed(isChangingConfigurations = false),
        )
        assertEquals("真正离开才释放预览", 1, preview.releaseCount)
        assertEquals("真正离开不增加宿主代数", 1, host.generation)
    }

    @Test
    fun `recreate after the player was lost restores from page position not from A`() {
        // 兜底:旧 release 路径/OEM 回收导致重建时播放器已经不在了。
        val preview = FakePreview(isPrepared = false, songId = null)
        val host = PreviewHostLifecycle(preview)
        host.onHostDestroyed(true)

        assertEquals(
            PreviewHostCreateAction.RESTORE_FROM_PAGE_STATE,
            host.onHostCreated(pageReady = true, pageSongId = 42L),
        )
        assertEquals(2, preview.attachCount)

        // 恢复落点 = 页面当前位置(只按时长 clamp),绝不能重置到 A。
        val pagePosition = 12_345L
        val a = 1_000L
        val restored = PreviewHostLifecyclePolicy.restoreDisplayMs(pagePosition, durationMs = 200_000L)
        assertEquals(pagePosition, restored)
        assertNotEquals("重建恢复不能回到 A", a, restored)
        assertEquals("时长未知时也按页面位置", pagePosition, PreviewHostLifecyclePolicy.restoreDisplayMs(pagePosition, 0L))
        assertEquals("越界位置按时长 clamp", 5_000L, PreviewHostLifecyclePolicy.restoreDisplayMs(9_999L, 5_000L))
        assertEquals("负位置归零", 0L, PreviewHostLifecyclePolicy.restoreDisplayMs(-1L, 5_000L))
    }

    @Test
    fun `page still loading only reattaches probe without restore`() {
        val preview = FakePreview(isPrepared = false, songId = null)
        val host = PreviewHostLifecycle(preview)

        assertEquals(
            PreviewHostCreateAction.NONE,
            host.onHostCreated(pageReady = false, pageSongId = null),
        )
        assertEquals("探针仍要挂上(debug 端点要能看到这一任页面实例)", 1, preview.attachCount)
    }

    @Test
    fun `repeated rotations never release and keep one prepared preview`() {
        val preview = FakePreview(isPrepared = true, songId = 42L)
        val host = PreviewHostLifecycle(preview)

        repeat(3) {
            assertEquals(PreviewHostDestroyAction.KEEP_ALIVE, host.onHostDestroyed(true))
            assertEquals(PreviewHostCreateAction.REATTACH_ONLY, host.onHostCreated(true, 42L))
        }
        assertEquals(4, host.generation)
        assertEquals(6, preview.attachCount)
        assertEquals("连续三次重建都不允许释放", 0, preview.releaseCount)
        assertTrue(preview.isPrepared)
        assertEquals(42L, preview.songId)

        // 最后真正离开:释放一次。
        assertEquals(PreviewHostDestroyAction.RELEASE_AND_RESUME, host.onHostDestroyed(false))
        assertEquals(1, preview.releaseCount)
        assertEquals(4, host.generation)
    }
}
