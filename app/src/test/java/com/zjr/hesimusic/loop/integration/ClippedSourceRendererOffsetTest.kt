package com.zjr.hesimusic.loop.integration

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.test.utils.FakeClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A-062:1e12 是**所有 source** 的初始 renderer offset,不是 clip 造成的。
 *
 * clip 的真实效果是改 window:positionInFirstPeriodUs += startUs、duration = end−start,
 * 位置被钳在 [0, end−start]。这里用真实 ExoPlayer 对比"不裁剪"与"裁剪"两种 source:
 * - 两者的 sink outputStreamOffsetUs 都是 INITIAL_RENDERER_POSITION_OFFSET_US(1e12),
 *   证明 1e12 与 clip 无关(clip 起点不会加到 renderer offset 上);
 * - 裁剪后 player.duration == clip 长度,默认位置 0 = clip 起点,当前位置钳在 [0, clipLen]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@UnstableApi
class ClippedSourceRendererOffsetTest {

    private val players = ArrayList<ExoPlayer>()

    @After
    fun tearDown() {
        players.forEach { runCatching { it.release() } }
        players.clear()
    }

    private fun progressive(item: MediaItem): MediaSource {
        val wavBytes = WavPcm::class.java
            .getResourceAsStream("/loop-integration/tone-8000-mono.wav")!!
            .use { it.readBytes() }
        return ProgressiveMediaSource.Factory(
            ByteArrayDataSource.Factory(wavBytes),
            DefaultExtractorsFactory(),
        ).createMediaSource(item)
    }

    private fun playUntilOffset(source: MediaSource, sink: RecordingAudioSink, clock: FakeClock): ExoPlayer {
        val player = PlayerPump.buildPlayer(sink, clock).also { players += it }
        player.setMediaSource(source)
        player.prepare()
        player.play()
        assertTrue(
            "source 没有在限定时间内启动; state=" + player.playbackState +
                " err=" + player.playerError + " offsets=" + sink.outputStreamOffsets,
            PlayerPump.pumpUntil(clock, sink, timeoutMs = 6_000L, stepMs = 20L) {
                sink.outputStreamOffsets.isNotEmpty() && player.playbackState == Player.STATE_READY
            },
        )
        return player
    }

    @Test
    fun `clip 的起点不进入 sink offset 时长与位置按 clip 窗口收敛`() {
        val clipStartUs = 200_000L
        val clipEndUs = 800_000L

        val plainSink = RecordingAudioSink()
        val plain = playUntilOffset(
            progressive(MediaItem.Builder().setUri(Uri.parse("file:///test/plain.wav")).setMediaId("plain").build()),
            plainSink,
            FakeClock(/* isAutoAdvancing= */ false),
        )
        val plainOffset = plainSink.outputStreamOffsets.first()
        assertEquals(
            "所有 source 的初始 renderer offset 都是 1e12",
            1_000_000_000_000L,
            plainOffset,
        )

        val clipSink = RecordingAudioSink()
        val clipItem = MediaItem.Builder()
            .setUri(Uri.parse("file:///test/clipped.wav"))
            .setMediaId("clip")
            .build()
        val clipSource = ClippingMediaSource(progressive(clipItem), clipStartUs, clipEndUs)
        val clipped = playUntilOffset(clipSource, clipSink, FakeClock(/* isAutoAdvancing= */ false))
        val clipOffset = clipSink.outputStreamOffsets.first()

        assertEquals(
            "clip 不改变初始 renderer offset(1e12 与 clip 无关)",
            plainOffset,
            clipOffset,
        )
        assertEquals("1e12 是基础 offset,不是 1e12+clip 起点", 1_000_000_000_000L, clipOffset)

        // clip 的效果在 timeline 上:window 起点前移(+startUs)、时长缩短;period 在 window
        // 内的起点同步前移(-startUs)。两项相加仍是原 window 位置,所以 renderer offset
        // 不叠加 clipStart。
        val window = androidx.media3.common.Timeline.Window()
        clipped.currentTimeline.getWindow(0, window)
        assertEquals(
            "clip 把起点写进 window.positionInFirstPeriodUs",
            clipStartUs,
            window.positionInFirstPeriodUs,
        )
        assertEquals(
            "clip window 时长 = end − start",
            clipEndUs - clipStartUs,
            window.durationUs,
        )
        val period = androidx.media3.common.Timeline.Period()
        clipped.currentTimeline.getPeriod(0, period)
        assertEquals(
            "clip 把 period 在 window 内的起点同步前移(负值)",
            -clipStartUs,
            period.positionInWindowUs,
        )
        assertEquals(
            "sink offset = INITIAL + window.positionInFirstPeriodUs + period.positionInWindowUs; " +
                "clipStart 在两项中互相抵消,所以仍是 1e12",
            clipOffset,
            1_000_000_000_000L + window.positionInFirstPeriodUs + period.positionInWindowUs,
        )

        assertEquals("clip 后 window 时长 = end − start", 600L, clipped.duration)
        assertTrue("clip 下默认位置是 0(不是文件绝对位置)", clipped.currentPosition >= 0L)
        assertTrue(
            "位置必须被 ClippingMediaSource 钳在 [0, clipLen]; pos=" + clipped.currentPosition,
            clipped.currentPosition <= 600L,
        )
        assertTrue("clip 窗口内应有 PCM 写入 sink", clipSink.totalFrames() > 0L)
        assertTrue("未裁剪 source 也应有 PCM 写入 sink", plainSink.totalFrames() > 0L)
        assertTrue("plain source 的位置不应被别人为钳住", plain.currentPosition >= 0L)
    }
}
