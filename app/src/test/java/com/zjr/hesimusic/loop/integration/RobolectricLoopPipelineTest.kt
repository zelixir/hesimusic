package com.zjr.hesimusic.loop.integration

import android.app.Application
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
// SDK 36 的 android-all 需要 Java 21；本机 JDK 17，SDK 35 足够跑 ExoPlayer 契约。
// 用裸 Application，避免拉起 App 的 Room/Hilt/日志初始化（与本测试无关且慢）。
@Config(sdk = [35], application = Application::class)
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

    private fun awaitPeriod(
        dispatcher: QueueDispatcher,
        timeoutMs: Long = 3_000L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            dispatcher.drain()
            Thread.sleep(2L)
        }
        dispatcher.drain()
        return condition()
    }

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
            PlayerPump.pumpUntil(clock, sink, timeoutMs = 5_000L, stepMs = 50L) {
                sink.totalFrames() >= threeLaps
            },
        )

        val feeds = sink.feeds()
        assertTrue("expected at least three sink feeds, got " + feeds.size, feeds.size >= 3)
        var cumulative = 0L
        var previousPts = Long.MIN_VALUE
        for ((index, feed) in feeds.withIndex()) {
            if (previousPts != Long.MIN_VALUE) {
                assertTrue(
                    "pts regressed at feed=" + index + ": " + previousPts + " -> " + feed.ptsUs,
                    feed.ptsUs > previousPts,
                )
                assertEquals(
                    "pts delta must equal feed duration (no seam reset); feed=" + index +
                        " frames=" + feed.frames + ": " + previousPts + " -> " + feed.ptsUs,
                    feed.frames * 1_000_000L / wav.sampleRate,
                    feed.ptsUs - previousPts,
                )
            }
            assertFeedBytes(feed, cumulative, maxFrames = 128)
            cumulative += feed.frames
            previousPts = feed.ptsUs
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
            PlayerPump.pumpUntil(clock, sink, timeoutMs = 6_000L, stepMs = 50L) {
                player.playbackState == Player.STATE_ENDED
            },
        )
        assertTrue("no audio reached the sink", sink.totalFrames() > 0)
        val feedsWhenEnded = sink.feedCount()

        // prepare() 在 ENDED 是 no-op：仍停在 ENDED，也不会重新出帧。
        player.prepare()
        PlayerPump.pumpFor(clock, sink, 120L)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertEquals("prepare() must not restart rendering", feedsWhenEnded, sink.feedCount())

        // play() 单独也不能离开 ENDED。
        player.play()
        PlayerPump.pumpFor(clock, sink, 120L)
        assertEquals(Player.STATE_ENDED, player.playbackState)
        assertEquals("play() alone must not restart rendering", feedsWhenEnded, sink.feedCount())

        // seek 才恢复：重新出帧，且离开 ENDED。
        player.seekTo(0L)
        player.play()
        assertTrue(
            "seek did not resume playback; state=" + player.playbackState +
                " feeds=" + sink.feedCount() + " err=" + player.playerError,
            PlayerPump.pumpUntil(clock, sink, timeoutMs = 4_000L, stepMs = 50L) {
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
        // 只有 play 之后 loader 才会持续重试 extractor（prepare 停在首块之前不算"空转"样本）。
        player.play()

        PlayerPump.pumpFor(clock, sink, 300L)
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
        // 6s 环 = 6 个 1s 块；prefetch=2 时 prepare 只 READY 前 3 块（buffered=3s）。
        // 再 seek 到 block 3，worker 补 3 块后 buffered 前进到 6s。
        val tl = LoopingPcmTimeline(
            songId = 1L,
            loopStartUs = 0L,
            loopEndUs = 6_000_000L,
            format = PcmTrackFormat(wav.sampleRate, wav.channels),
        )
        val dispatcher = QueueDispatcher()
        val decodeCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val gateAfterFirstWindow = CountDownLatch(1)
        val base = WavPcmRangeProvider(wav)
        val gatedProvider = com.zjr.hesimusic.loop.audio.source.PcmRangeProvider { startUs, endUs ->
            if (decodeCalls.incrementAndGet() >= 4) gateAfterFirstWindow.await()
            base.decode(startUs, endUs)
        }
        val period = com.zjr.hesimusic.loop.audio.source.LoopingPcmMediaPeriod(
            timeline = tl,
            provider = gatedProvider,
            startPositionUs = 0L,
            dispatcher = dispatcher,
            logger = {},
            prefetchBlocks = 2,
        )
        var prepared = false
        val callback = object : androidx.media3.exoplayer.source.MediaPeriod.Callback {
            override fun onPrepared(mediaPeriod: androidx.media3.exoplayer.source.MediaPeriod) {
                prepared = true
            }

            override fun onContinueLoadingRequested(
                source: androidx.media3.exoplayer.source.MediaPeriod,
            ) = Unit
        }
        period.prepare(callback, 0L)
        assertTrue("period did not prepare", awaitPeriod(dispatcher) { prepared })
        assertEquals(
            "prefetch=2: first three ready blocks cover 3s",
            3_000_000L,
            period.getBufferedPositionUs(),
        )

        // 播到 block 3 后，worker 用同一套 continueLoading 补满下一窗口（这里用门闩暂停在 block 3 解码）。
        period.seekToUs(3_000_000L)
        assertTrue(
            "worker did not reach block 3",
            awaitPeriod(dispatcher) { decodeCalls.get() >= 4 },
        )
        assertEquals(
            "next load must point at the not-yet-ready block 3",
            3_000_000L,
            period.getNextLoadPositionUs(),
        )
        gateAfterFirstWindow.countDown()
        assertTrue(
            "buffered did not advance after seek",
            awaitPeriod(dispatcher, timeoutMs = 4_000L) {
                period.getBufferedPositionUs() >= 9_000_000L
            },
        )
        // seek 基址 3s + 整环 6 个 READY 块 = 9s（buffered 覆盖当前 cursor 之后的整环）。
        assertEquals(9_000_000L, period.getBufferedPositionUs())
        assertEquals(
            "no more blocks to load",
            C.TIME_END_OF_SOURCE,
            period.getNextLoadPositionUs(),
        )
        period.release()
    }

    // ------------------------------------------------------------------
    // 5. seek 映射回 offset mod L（环带内容 + PTS 不重置）
    // ------------------------------------------------------------------
    @Test
    fun seekMapsToOffsetModLoopLength() {
        val tl = timeline()
        // L = 1s；1.5s mod 1s => 0.5s => frame 4000。且 1.5s 小于 seek 前已播放的 PTS 基数，
        // 避免 ProgressiveMediaPeriod 把新样本当作 "已过播放位置" 连续丢弃。
        val seekUs = 1_500_000L
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

        // 5b. 真实播放器 seek：接受 seek、位置落到目标、不报错（内容映射由 5a 直接断言）。
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
            PlayerPump.pumpUntil(clock, sink, timeoutMs = 5_000L, stepMs = 50L) {
                sink.totalFrames() >= lapsBeforeSeek
            },
        )
        player.pause()
        PlayerPump.pumpUntil(clock, sink, timeoutMs = 2_000L, stepMs = 20L) { !player.playWhenReady }

        player.seekTo(seekUs)
        PlayerPump.pumpFor(clock, sink, 200L)
        // 真实播放器接受环带内的任意 seek，不报错；映射本身由 5a 直接断言。
        assertTrue(
            "seek position must be applied, pos=" + player.currentPosition,
            kotlin.math.abs(player.currentPosition - seekUs) < 1_000_000L,
        )
        player.play()
        PlayerPump.pumpFor(clock, sink, 200L)
        assertEquals("player error after seek", null, player.playerError)
    }

    // ------------------------------------------------------------------
    // 6. 真实 ExoPlayer 确实先 createPeriod 后 enable；旧严格实现会失败
    // ------------------------------------------------------------------
    @Test
    fun createPeriodBeforeEnableIsToleratedByLoopingSource() {
        val tl = timeline()
        val assembly = RingBandTestSource.create(
            item = item("masked-ring"),
            timeline = tl,
            decoder = WavRingBandStreamDecoder(wav),
        )
        renderers += assembly.renderer
        val recorder = RecordingMediaSource(assembly.source)
        val masked = MaskingMediaSource(recorder, /* useLazyPreparation= */ false)
        val sink = RecordingAudioSink()
        val player = newPlayer(sink)
        player.setMediaSource(masked)
        player.prepare()
        player.play()

        assertTrue(
            "masked source never prepared; state=" + player.playbackState +
                " err=" + player.playerError + " events=" + recorder.events,
            PlayerPump.pumpUntil(clock, sink, timeoutMs = 5_000L, stepMs = 50L) {
                player.playbackState == Player.STATE_READY
            },
        )
        assertTrue("masking path must call createPeriod, events=" + recorder.events,
            recorder.events.any { it.startsWith("createPeriod") })
        assertEquals("real ExoPlayer ordering with MaskingMediaSource is createPeriod before enable",
            false, recorder.createSawEnabled)

        // 旧实现若在 createPeriod 里 check(enabled)，会在同一真实时序下直接失败。
        val strictAssembly = RingBandTestSource.create(
            item = item("strict-ring"),
            timeline = tl,
            decoder = WavRingBandStreamDecoder(wav),
        )
        renderers += strictAssembly.renderer
        val strictPlayer = newPlayer(RecordingAudioSink())
        strictPlayer.setMediaSource(
            MaskingMediaSource(
                StrictEnableMediaSource(strictAssembly.source),
                /* useLazyPreparation= */ false,
            ),
        )
        strictPlayer.prepare()
        assertTrue(
            "strict old-style source should fail the createPeriod-before-enable ordering",
            PlayerPump.pumpUntil(clock, sink, timeoutMs = 4_000L, stepMs = 20L) {
                strictPlayer.playerError != null
            },
        )
        assertNotNull(strictPlayer.playerError)
        val messages = generateSequence<Throwable>(strictPlayer.playerError) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue(
            "unexpected strict error: " + messages,
            messages.contains("createPeriod called before enable"),
        )
    }
}
