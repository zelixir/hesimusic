package com.zjr.hesimusic.ui.loop

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 场景 #45 的真对象单测(Robolectric):Activity 重建后预览的**探针**与**状态机**自洽。
 *
 * 只构造 [PreviewPlayer] 的页面状态(不创建 ExoPlayer:播放器本来就延迟到 play 时创建),
 * 覆盖:
 * - 重建后探针必须重新指向同一个播放器实例(`/debug/preview` 不能 409);
 * - 配置变更销毁 → 新宿主首帧 = REATTACH_ONLY,播放器保持 prepared;
 * - 兜底恢复的落点 = 页面位置(不是 A),暂停态不偷偷创建播放器;
 * - 真正 release 才 detach 探针。
 */
@RunWith(RobolectricTestRunner::class)
// 与 RobolectricLoopPipelineTest 一致:SDK 35 的 android-all 可在本机 JDK 上跑。
// 用裸 Application,避免拉起 App 的 Room/Hilt/日志初始化。
@Config(sdk = [35], application = Application::class)
@UnstableApi
class PreviewProbeHostRebindTest {

    private lateinit var player: PreviewPlayer

    private val song = Song(
        id = 4242L,
        title = "rotation",
        artist = "test",
        album = "test",
        filePath = "/tmp/rotation.ogg",
        duration = 200_000L,
        mimeType = "audio/ogg",
        size = 1L,
        dateAdded = 0L,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        player = PreviewPlayer(context, PcmDecoder(context))
    }

    @After
    fun tearDown() {
        runCatching { player.release() }
    }

    @Test
    fun `rotation keeps the same preview instance and the probe reattaches`() {
        player.prepare(song, aMs = 1_000L, bMs = 5_000L)
        assertTrue("prepare 后预览应已就绪", player.isPrepared)
        assertTrue("页面打开时探针应已挂上", PreviewProbe.attached())

        // 模拟旧 onDispose release 造成的 detach(重建后引用丢失的那条老路径)。
        PreviewProbe.detach(player)
        assertFalse(PreviewProbe.attached())

        val host = PreviewHostLifecycle(player)
        assertEquals(
            PreviewHostDestroyAction.KEEP_ALIVE,
            host.onHostDestroyed(isChangingConfigurations = true),
        )
        assertTrue("配置变更销毁时状态机就要把探针挂回去", PreviewProbe.attached())
        assertEquals(
            PreviewHostCreateAction.REATTACH_ONLY,
            host.onHostCreated(pageReady = true, pageSongId = song.id),
        )

        assertTrue("重建后探针必须重新挂上(否则 /debug/preview 恒 409)", PreviewProbe.attached())
        assertTrue("重建不能把预览置为未 prepare", player.isPrepared)
        assertEquals(song.id, player.songId)
        // 探针读到的必须是这一份仍 prepared 的实例(而不是旧实例/空引用)。
        val snapshot = PreviewProbe.snapshot()
        assertNotNull("探针必须能读到重建后的预览现场", snapshot)
        assertEquals(true, snapshot!!["prepared"])
        assertEquals(song.id, (snapshot["songId"] as Number).toLong())
        assertEquals("重建后位置不能回到 A", 1_000L, (snapshot["displayMs"] as Number).toLong())

        // 常规路径恢复:播放器还活着时只对齐位置,不 seek/不重建。
        assertTrue(
            player.resumeAfterHostRecreate(
                songId = song.id,
                mode = PreviewMode.LOOP_ONLY,
                displayMs = 3_456L,
                playing = false,
                freeAudition = false,
            ),
        )
        assertEquals("恢复落点必须是页面位置,不能重置到 A", 3_456L, player.positionMs.value)
    }

    @Test
    fun `paused restore keeps page position and never builds a player`() {
        player.prepare(song, aMs = 1_000L, bMs = 5_000L)

        assertTrue(
            player.resumeAfterHostRecreate(
                songId = song.id,
                mode = PreviewMode.LOOP_ONLY,
                displayMs = 2_222L,
                playing = false,
                freeAudition = false,
            ),
        )
        assertEquals(2_222L, player.positionMs.value)
        assertFalse("暂停态恢复不应在旋转路径里创建播放器", player.hasPlayer)
        assertTrue(PreviewProbe.attached())
    }

    @Test
    fun `restore rejects a different song instead of reviving stale state`() {
        player.prepare(song, aMs = 1_000L, bMs = 5_000L)
        assertFalse(
            player.resumeAfterHostRecreate(
                songId = song.id + 1,
                mode = PreviewMode.LOOP_ONLY,
                displayMs = 2_222L,
                playing = true,
                freeAudition = false,
            ),
        )
        assertEquals("未 prepare 的歌曲不能恢复", 1_000L, player.positionMs.value)
    }

    @Test
    fun `leaving the page through the state machine releases and detaches`() {
        player.prepare(song, aMs = 1_000L, bMs = 5_000L)
        assertTrue(PreviewProbe.attached())

        val host = PreviewHostLifecycle(player)
        assertEquals(
            PreviewHostDestroyAction.RELEASE_AND_RESUME,
            host.onHostDestroyed(isChangingConfigurations = false),
        )

        assertFalse("真正离开页面(非配置变更)才 detach 探针", PreviewProbe.attached())
        assertFalse(player.isPrepared)
    }

    @Test
    fun `release detaches the probe and clears prepared state`() {
        player.prepare(song, aMs = 1_000L, bMs = 5_000L)
        assertTrue(PreviewProbe.attached())

        player.release()

        assertFalse("真正离开页面才 detach 探针", PreviewProbe.attached())
        assertFalse(player.isPrepared)
        assertEquals(null, player.songId)
    }
}
