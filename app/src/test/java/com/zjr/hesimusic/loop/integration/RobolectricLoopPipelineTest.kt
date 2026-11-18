package com.zjr.hesimusic.loop.integration

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MaskingMediaSource
import androidx.media3.test.utils.FakeClock
import com.zjr.hesimusic.loop.audio.source.LoopingPcmTimeline
import com.zjr.hesimusic.loop.audio.source.PcmTrackFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * JVM(Robolectric)上跑真实 ExoPlayer 的集成契约测试。
 *
 * 覆盖之前真机烧掉的 6 个时序/接缝坑：createPeriod 早于 enable、ENDED 只能靠 seek 离开、
 * Extractor 无数据时不能空转、buffered/nextLoad 与 LoadControl 的配合、环带三圈 PTS 连续、
 * seek 映射回 offset mod L。全部时间由 [FakeClock] 推进，不 sleep 到真实播放时长。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // SDK 36 的 android-all 需要 Java 21；本机 JDK 17，SDK 35 足够跑 ExoPlayer 契约
@UnstableApi
class RobolectricLoopPipelineTest {

    private val wav = WavPcm.load()
    private val clock = FakeClock(/* isAutoAdvancing= */ false)
    private val players = ArrayList<ExoPlayer>()
    private val renderers = ArrayList<com.zjr.hesimusic.loop.audio.source.RingBandRenderer>()

    @After
    fun tearDown() {
        players.forEach { runCatching { it.release() } }
        players.clear()
        renderers.forEach { runCatching { it.release() } }
        renderers.clear()
    }

    private fun timeline(entryUs: Long = 0L): LoopingPcmTimeline = LoopingPcmTimeline(
        songId = 1L,
        loopStartUs = 0L,
        loopEndUs = wav.durationUs,
        format = PcmTrackFormat(wav.sampleRate, wav.channels),
        entryPositionUs = entryUs,
    )

    private fun item(id: String): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse("loop://" + id))
        .setMediaId(id)
        .build()

    private fun newPlayer(sink: RecordingAudioSink): ExoPlayer =
        PlayerPump.buildPlayer(sink, clock).also { players += it }

    private fun feedExpectedBytes(startFrame: Long, frames: Int): ByteArray =
        wav.bytesAt(startFrame, frames)

    private fun assertFeedBytes(feed: RecordingAudioSink.Feed, expectedStartFrame: Long, maxFrames: Int = 64) {
        val n = minOf(feed.frames, maxFrames)
        val actual = feed.data.copyOf(n * wav.bytesPerFrame)
        val expected = wav.bytesAt(expectedStartFrame, n)
        var i = 0
        while (i < actual.size) {
            if (actual[i] != expected[i]) {
                assertEquals("feed byte mismatch at sample frame " + (i / wav.bytesPerFrame), expected[i], actual[i])
            }
            i++
        }
    }

    // ------------------------------------------------------------------
    // 1. 环带接缝：三圈逐帧内容 + PTS 连续
    // ------------------------------------------------------------------
    @Test
    fun ringBandPlaysThreeLapsWithContinuousPts() {
        val tl = timeline()
        val decoder = WavRingBandStreamDecoder(wav, chunkFrames = 8_000)
        val seams = CopyOnWriteArrayList<Long>()
        val assembly = RingBandTestSource.create(
            item = item("ring-three-laps"),
            timeline = tl,
            decoder = decoder,
            onSeamOutputFrames = { seams += it },
        )
        renderers += assembly.renderer
        val sink = RecordingAudioSink()
        val player = newPlayer(sink)
        player.setMediaSource(assembly.source)
        player.prepare()
        player.play()

        val threeLaps = 3L * tl.loopFrames
        assertTrue(
            "player did not write three laps in time; feeds=" + sink.feedCount() +
                " frames=" + sink.totalFrames() + " state=" + player.playbackState +
                " err=" + player.playerError,
            PlayerPump.pumpUntil(clock, timeoutMs = 5_000L, stepMs = 50L) {
                sink.totalFrames() >= threeLaps
            },
        )

        val feeds = sink.feeds()
        assertTrue("expected at least three sink feeds, got " + feeds.size, feeds.size >= 3)
        var cumulative = 0L
        for (feed in feeds) {
            assertEquals(
                "pts must equal cumulative frames at 8kHz",
                cumulative * 1_000_000L / wav.sampleRate,
                feed.ptsUs,
            )
            assertFeedBytes(feed, cumulative, maxFrames = 128)
            cumulative += feed.frames
        }
        assertTrue("expected >= 3 laps, got " + cumulative, cumulative >= threeLaps)
        assertTrue("sink.play() must be called", sink.playCalls.get() > 0)

        val expectedSeams = listOf(
            tl.loopFrames,
            2L * tl.loopFrames,
            3L * tl.loopFrames,
        )
        assertTrue(
            "seam callbacks missing; got=" + seams,
            seams.containsAll(expectedSeams),
        )
        assertFalse("seam output frames must be strictly increasing", seams.zipWithNext().any { it.first >= it.second })
    }

    // ------------------------------------------------------------------
    // 2. ENDED 只能靠 seek 离开，prepare/play 都是空操作
    // ------------------------------------------------------------------
    @Test
    fun playerLeavesEndedBySeekNotPrepare() {
        val raw = WavPcm::class.java.getResourceAsStream("/loop-integration/tone-8000-mono.wav")!!
            .use { it.readBytes() }
        val sink = RecordingAudioSink()
        val player = newPlayer(sink)
        player.setMediaSource(RingBandTestSource.wavProgressiveSource(item("wav-ended"), raw))
        player.prepare()
        player.play()

        assertTrue(
            "finite WAV did not reach ENDED; state=" + player.playbackState +
                " frames=" + sink.totalFrames() + " err=" + player.playerError,
            PlayerPump.pumpUntil(clock, timeoutMs = 6_000L, stepMs = 50L) {
                player.playbackState == Player.STATE_ENDED
            },
        )
        assertTrue("no audio reached the sink", sink.totalFrames() > 0)
        val feedsWhenEnded = sink.feedCount()

        // prepare() 在 ENDED 是 no-op：仍停在 ENDED，也不会重新出帧。
        player.prepare()
        PlayerPump.pumpFor(clock, 120L)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertEquals("prepare() must not restart rendering", feedsWhenEnded, sink.feedCount())

        // play() 单独也不能离开 ENDED。
        player.play()
        PlayerPump.pumpFor(clock, 120L)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertEquals("play() alone must not restart rendering", feedsWhenEnded, sink.feedCount())

        // seek 才恢复：重新出帧，且离开 ENDED。
        player.seekTo(0L)
        player.play()
        assertTrue(
            "seek did not resume playback; state=" + player.playbackState +
                " feeds=" + sink.feedCount() + " err=" + player.playerError,
            PlayerPump.pumpUntil(clock, timeoutMs = 4_000L, stepMs = 50L) {
                sink.feedCount() > feedsWhenEnded && player.playbackState != Player.STATE_ENDED
            },
        )
    }

    // ------------------------------------------------------------------
    // 3. Extractor 暂时无数据时不能 while(RESULT_CONTINUE) 空转
    // ------------------------------------------------------------------
    @Test
    fun noBusySpinWhenExtractorHasNoData() {
        val tl = timeline()
        val never = CountDownLatch(1)
        val decoder = WavRingBandStreamDecoder(wav, gate = never)
        val counter = CountingExtractorHolder()
        val assembly = RingBandTestSource.create(
            item = item("ring-starved"),
            timeline = tl,
            decoder = decoder,
            extractorWrapper = { counter.wrap(it) },
        )
        renderers += assembly.renderer
        val sink = RecordingAudioSink()
        val player = newPlayer(sink)
        player.setMediaSource(assembly.source)
        player.prepare()

        PlayerPump.pumpFor(clock, 300L)
        assertTrue("decoder should have been started", decoder.streamStarted.await(1, TimeUnit.SECONDS))
        assertTrue("no data should reach the sink", sink.totalFrames() == 0L)
        assertTrue("player must stay buffering", player.playbackState != Player.STATE_READY)
        assertTrue("extractor must actually wait for data, underruns=" + assembly.reader.underruns,
            assembly.reader.underruns > 0)

        val readCalls = counter.extractor.readCalls.get()
        val maxContinue = counter.extractor.maxConsecutiveContinue.get()
        assertTrue("extractor was never polled", readCalls > 0)
        // 每次无数据都会在 awaitFilled 里等 8ms；300ms 内应当远小于 100 次。
        // 若退回"立即 return RESULT_CONTINUE"，这里会瞬间冲到数千次。
        assertTrue("busy spin: reads=" + readCalls + " in 300ms", readCalls <= 100)
        assertTrue("too many consecutive CONTINUE=" + maxContinue, maxContinue <= 100)
        never.countDown()
    }

    /** 把 CountingExtractor 带出 create() 的类型擦除（wrapper 返回 Extractor）。 */
    private class CountingExtractorHolder {
        lateinit var extractor: CountingExtractor
        fun wrap(delegate: androidx.media3.extractor.Extractor): androidx.media3.extractor.Extractor {
            val c = CountingExtractor(delegate)
            extractor = c
            return c
        }
    }

    // ------------------------------------------------------------------
    // 4. buffered position 随 READY 块推进（与 LoadControl/continueLoading 配合）
    // ------------------------------------------------------------------
    @Test
    fun bufferedPositionAdvancesWithReadyBlocks() {
        val tl = timeline()
        val sink = RecordingAudioSink()
        val player = newPlayer(sink)
        player.setMediaSource(
            RingBandTestSource.loopingPcmSource(item("blocks"), tl, WavPcmRangeProvider(wav)),
        )
        player.prepare()

        assertTrue(
            "player never became READY; state=" + player.playbackState + " err=" + player.playerError,
            PlayerPump.pumpUntil(clock, timeoutMs = 5_000L, stepMs = 20L) {
                player.playbackState == Player.STATE_READY
            },
        )
        val firstBuffered = player.bufferedPosition
        assertTrue("bufferedPosition must cover at least one ready block, got " + firstBuffered,
            firstBuffered >= 1_000_000L)
        assertEquals("buffered end must snap to 1s block grid", 0L, firstBuffered % 1_000_000L)
        assertEquals("timeline is an infinite loop", C.TIME_UNSET, player.duration)

        // 剩余块解完后 buffered 覆盖整个环；再加载已无更多块。
        val fullLoopUs = wav.durationUs
        assertTrue(
            "bufferedPosition did not advance to the full loop; buffered=" + player.bufferedPosition,
            PlayerPump.pumpUntil(clock, timeoutMs = 4_000L, stepMs = 20L) {
                player.bufferedPosition >= fullLoopUs
            },
        )
        assertEquals(fullLoopUs, player.bufferedPosition)
    }

    // ------------------------------------------------------------------
    // 5. seek 映射回 offset mod L（环带内容 + PTS 不重置）
    // ------------------------------------------------------------------
    @Test
    fun seekMapsToOffsetModLoopLength() {
        val tl = timeline()
        val seekUs = 3_500_000L // 3.5s mod 3s => 0.5s => frame 4000
        val expectedOffset = Math.floorMod(
            tl.frameAtTimeline(seekUs) - tl.aFrame,
            tl.loopFrames,
        )
        assertEquals(4_000L, expectedOffset)

        // 5a. 直接对生产 Extractor 调 seek：只改 offset，不重解码。
        val directDecoder = WavRingBandStreamDecoder(wav)
        val direct = RingBandTestSource.create(
            item = item("ring-seek-direct"),
            timeline = tl,
            decoder = directDecoder,
        )
        renderers += direct.renderer
        direct.extractor.seek(0L, seekUs)
        assertEquals("seek must map to offset mod L", expectedOffset, direct.reader.offsetFrames)
        direct.renderer.release()

        // 5b. 真实播放器 seek：取出 seek 后的第一帧，内容必须是 offset 4000 起的环带。
        val assembly = RingBandTestSource.create(
            item = item("ring-seek-player"),
            timeline = tl,
            decoder = WavRingBandStreamDecoder(wav, chunkFrames = 8_000),
        )
        renderers += assembly.renderer
        val sink = RecordingAudioSink()
        val player = newPlayer(sink)
        player.setMediaSource(assembly.source)
        player.prepare()
        player.play()

        val lapsBeforeSeek = 3L * tl.loopFrames
        assertTrue(
            "did not write enough data before seek; frames=" + sink.totalFrames(),
            PlayerPump.pumpUntil(clock, timeoutMs = 5_000L, stepMs = 50L) {
                sink.totalFrames() >= lapsBeforeSeek
            },
        )
        player.pause()
        PlayerPump.pumpUntil(clock, timeoutMs = 2_000L, stepMs = 20L) { !player.playWhenReady }
        val feedsBefore = sink.feedCount()
        val lastPtsBefore = sink.feeds().lastOrNull()?.ptsUs ?: 0L

        player.seekTo(seekUs)
        PlayerPump.pumpFor(clock, 100L)
        player.play()

        assertTrue(
            "no feed after seek; state=" + player.playbackState + " err=" + player.playerError,
            PlayerPump.pumpUntil(clock, timeoutMs = 4_000L, stepMs = 20L) {
                sink.feedCount() > feedsBefore
            },
        )
        val newFeeds = sink.feeds().drop(feedsBefore).filter { it.frames > 0 }
        assertTrue("no non-empty feed after seek", newFeeds.isNotEmpty())
        val first = newFeeds.first()
        assertFeedBytes(first, expectedOffset, maxFrames = 256)
        assertTrue(
            "PTS must not reset across seek: before=" + lastPtsBefore + " after=" + first.ptsUs,
            first.ptsUs >= lastPtsBefore,
        )
    }

    // ------------------------------------------------------------------
    // 6. 真实 ExoPlayer 确实先 createPeriod 后 enable；旧严格实现会失败
    // ------------------------------------------------------------------
    @Test
    fun createPeriodBeforeEnableIsToleratedByLoopingSource() {
        val tl = timeline()
        val recorder = RecordingMediaSource(
            RingBandTestSource.loopingPcmSource(item("masked"), tl, WavPcmRangeProvider(wav)),
        )
        val masked = MaskingMediaSource(recorder, /* useLazyPreparation= */ false)
        val sink = RecordingAudioSink()
        val player = newPlayer(sink)
        player.setMediaSource(masked)
        player.prepare()

        assertTrue(
            "masked looping source never prepared; state=" + player.playbackState +
                " err=" + player.playerError + " events=" + recorder.events,
            PlayerPump.pumpUntil(clock, timeoutMs = 5_000L, stepMs = 20L) {
                player.playbackState == Player.STATE_READY
            },
        )
        assertTrue("masking path must call createPeriod, events=" + recorder.events,
            recorder.events.any { it.startsWith("createPeriod") })
        assertTrue("masking path must call enable, events=" + recorder.events,
            recorder.events.contains("enable"))
        assertEquals("real ExoPlayer ordering with MaskingMediaSource is createPeriod before enable",
            false, recorder.createSawEnabled)

        // 旧实现若在 createPeriod 里 check(enabled)，会在同一真实时序下直接失败。
        val strictPlayer = newPlayer(RecordingAudioSink())
        strictPlayer.setMediaSource(
            MaskingMediaSource(
                StrictEnableMediaSource(
                    RingBandTestSource.loopingPcmSource(item("strict"), tl, WavPcmRangeProvider(wav)),
                ),
                /* useLazyPreparation= */ false,
            ),
        )
        strictPlayer.prepare()
        assertTrue(
            "strict old-style source should fail the createPeriod-before-enable ordering",
            PlayerPump.pumpUntil(clock, timeoutMs = 4_000L, stepMs = 20L) {
                strictPlayer.playerError != null
            },
        )
        assertNotNull(strictPlayer.playerError)
        assertTrue(
            "unexpected strict error: " + strictPlayer.playerError,
            strictPlayer.playerError!!.message?.contains("createPeriod called before enable") == true,
        )
    }
}
