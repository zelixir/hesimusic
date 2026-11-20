package com.zjr.hesimusic.loop.integration

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.audio.source.LoopRing
import com.zjr.hesimusic.loop.audio.source.LoopingPcmDataSource
import com.zjr.hesimusic.loop.audio.source.LoopingPcmMediaSource
import com.zjr.hesimusic.loop.audio.source.LoopingPcmTimeline
import com.zjr.hesimusic.loop.audio.source.PcmRangeProvider
import com.zjr.hesimusic.loop.audio.source.RingBandExtractor
import com.zjr.hesimusic.loop.audio.source.RingBandReader
import com.zjr.hesimusic.loop.audio.source.RingBandRenderer
import com.zjr.hesimusic.loop.audio.source.RingBandStreamDecoder
import org.robolectric.shadows.ShadowLooper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM(Robolectric)集成测试夹具：真实 ExoPlayer + 可注入的 WAV/PCM 数据 + 假 AudioSink。
 *
 * 不创建 MediaCodec、不创建 AudioTrack、不启动设备；所有时间由 [FakeClock] 推进。
 */

/** 解析后的 WAV（只支持 PCM16）。测试资源 loop-integration/tone-8000-mono.wav。 */
@UnstableApi
class WavPcm private constructor(
    val sampleRate: Int,
    val channels: Int,
    private val pcm: ByteArray,
    val totalFrames: Int,
) {
    val bytesPerFrame: Int get() = channels * 2
    val durationUs: Long get() = totalFrames * 1_000_000L / sampleRate

    fun sample(frame: Long): Int {
        val f = Math.floorMod(frame, totalFrames.toLong()).toInt()
        val o = f * bytesPerFrame
        return (pcm[o].toInt() and 0xff) or ((pcm[o + 1].toInt() and 0xff) shl 8)
    }

    /** 从环内帧 [startFrame] 开始取 [frames] 帧（自动按 WAV 长度取模）。 */
    fun bytesAt(startFrame: Long, frames: Int): ByteArray {
        val out = ByteArray(frames * bytesPerFrame)
        var o = 0
        var f = startFrame
        for (i in 0 until frames) {
            val src = Math.floorMod(f, totalFrames.toLong()).toInt() * bytesPerFrame
            System.arraycopy(pcm, src, out, o, bytesPerFrame)
            o += bytesPerFrame
            f++
        }
        return out
    }

    companion object {
        fun load(resource: String = "/loop-integration/tone-8000-mono.wav"): WavPcm {
            val bytes = WavPcm::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: error("missing test WAV resource: " + resource)
            return parse(bytes)
        }

        fun parse(bytes: ByteArray): WavPcm {
            require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
                "not a RIFF/WAVE file"
            }
            var pos = 12
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1
            var dataSize = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                val size = le32(bytes, pos + 4)
                val body = pos + 8
                when (id) {
                    "fmt " -> {
                        val audioFormat = le16(bytes, body)
                        channels = le16(bytes, body + 2)
                        sampleRate = le32(bytes, body + 4)
                        bits = le16(bytes, body + 14)
                        require(audioFormat == 1 && bits == 16) {
                            "only PCM16 WAV supported: format=" + audioFormat + " bits=" + bits
                        }
                    }
                    "data" -> {
                        dataOffset = body
                        dataSize = minOf(size, bytes.size - body)
                    }
                }
                pos = body + size + (size and 1)
            }
            require(dataOffset >= 0 && sampleRate > 0 && channels > 0) { "incomplete WAV" }
            val frames = dataSize / (channels * 2)
            val pcm = bytes.copyOfRange(dataOffset, dataOffset + frames * channels * 2)
            return WavPcm(sampleRate, channels, pcm, frames)
        }

        private fun le16(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)

        private fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
                ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
    }
}

/** 用 WAV 当解码后端的 [PcmRangeProvider]（块路径：LoopingPcmMediaSource）。 */
@UnstableApi
class WavPcmRangeProvider(private val wav: WavPcm) : PcmRangeProvider {
    override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
        val first = Pcm16Frames.frameAtOrAfterUs(startUs, wav.sampleRate)
        val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, wav.sampleRate)
        require(frames <= Int.MAX_VALUE) { "too many frames: " + frames }
        return Pcm16Segment(
            data = wav.bytesAt(first, frames.toInt()),
            sampleRate = wav.sampleRate,
            channels = wav.channels,
            frames = frames,
        )
    }
}

/** 用 WAV 当流式解码后端（环带路径：RingBandRenderer）；可注入门闩模拟暂时没有数据。 */
@UnstableApi
class WavRingBandStreamDecoder(
    private val wav: WavPcm,
    private val gate: CountDownLatch? = null,
    private val chunkFrames: Int = 8000,
) : RingBandStreamDecoder {
    val streamStarted = CountDownLatch(1)

    override fun stream(
        startUs: Long,
        endUs: Long,
        prerollUs: Long,
        onPcm: (data: ByteArray, size: Int) -> Unit,
    ) {
        streamStarted.countDown()
        gate?.await()
        val first = Pcm16Frames.frameAtOrAfterUs(startUs, wav.sampleRate)
        val end = Pcm16Frames.frameAtOrAfterUs(endUs, wav.sampleRate)
        var frame = first
        while (frame < end) {
            val n = minOf(chunkFrames.toLong(), end - frame).toInt()
            val bytes = wav.bytesAt(frame, n)
            onPcm(bytes, bytes.size)
            frame += n
        }
    }

    override fun close() = Unit
}

/**
 * 记录真实 ExoPlayer 写入 AudioSink 的帧/PTS/字节；不创建 AudioTrack。
 *
 * 带背压：最多缓存 [maxBufferedFrames] 帧，测试用 [advanceBy] 模拟声卡按虚拟时间消费。
 * 没有背压时 ExoPlayer 会在一次 idle 里无限加载/渲染（sink 永远收得下），既拖慢又吃内存。
 */
@UnstableApi
class RecordingAudioSink(
    private val sampleRate: Int = 8_000,
    private val maxBufferedFrames: Int = 8_000,
) : AudioSink {

    data class Feed(
        val frames: Int,
        val ptsUs: Long,
        val data: ByteArray,
        val order: ByteOrder,
    )

    private val lock = Any()
    private val feedsInternal = ArrayList<Feed>()

    var bytesPerFrame: Int = 2
        private set

    val configureCalls = AtomicInteger()
    val playCalls = AtomicInteger()
    val flushCalls = AtomicInteger()
    val resetCalls = AtomicInteger()
    val releaseCalls = AtomicInteger()
    val playToEndCalls = AtomicInteger()
    val discontinuityCalls = AtomicInteger()

    /** renderer 传给 AudioSink 的 outputStreamOffsetUs(A-062:1e12 + period 位置)。 */
    val outputStreamOffsets = CopyOnWriteArrayList<Long>()

    @Volatile
    private var ended = false

    private class Pending(val ptsUs: Long, var remaining: Long, var consumed: Long = 0L)

    private val pendingSegments = java.util.ArrayDeque<Pending>()
    private var pendingFrames: Long = 0L

    private var feedPtsUs: Long = 0L
    private var feedFrames: Int = 0
    private val feedChunks = ArrayList<ByteArray>()

    /** 只保留前 400k 帧的逐样本内容（约 25s），防止异常播放把测试堆打满。 */
    private var recordedFrames = 0L
    private var acceptedFrames = 0L

    /** 当前"声卡"播放位置（µs），由 [advanceBy] 推进。 */
    @Volatile
    private var positionUs: Long = 0L

    /** 模拟声卡消费 [ms] 毫秒的音频（只能在测试线程调用）。 */
    fun advanceBy(ms: Long) {
        synchronized(lock) {
            if (pendingFrames <= 0L) return
            var left = minOf(pendingFrames, ms * sampleRate / 1_000L)
            if (left <= 0L) return
            pendingFrames -= left
            while (left > 0L && pendingSegments.isNotEmpty()) {
                val head = pendingSegments.peekFirst()!!
                val take = minOf(head.remaining, left)
                head.remaining -= take
                head.consumed += take
                left -= take
                positionUs = head.ptsUs + head.consumed * 1_000_000L / sampleRate
                if (head.remaining == 0L) pendingSegments.removeFirst()
            }
        }
    }

    @Volatile
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT

    @Volatile
    private var skipSilence = false

    @Volatile
    private var audioAttributes: androidx.media3.common.AudioAttributes =
        androidx.media3.common.AudioAttributes.Builder().build()

    fun feeds(): List<Feed> = synchronized(lock) { feedsInternal.toList() }

    fun feedCount(): Int = synchronized(lock) { feedsInternal.size }

    fun totalFrames(): Long = synchronized(lock) { acceptedFrames }

    override fun setListener(listener: AudioSink.Listener) = Unit

    override fun setOutputStreamOffsetUs(offsetUs: Long) {
        outputStreamOffsets += offsetUs
    }

    override fun supportsFormat(format: Format): Boolean = true

    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun getCurrentPositionUs(forceDiscontinuityPosition: Boolean): Long = positionUs

    override fun configure(format: Format, bufferSize: Int, audioTrackChannelConfig: IntArray?) {
        configureCalls.incrementAndGet()
        val channels = format.channelCount.coerceAtLeast(1)
        val bytesPerSample = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            else -> 2
        }
        bytesPerFrame = channels * bytesPerSample
        ended = false
    }

    override fun play() {
        playCalls.incrementAndGet()
        ended = false
    }

    override fun handleDiscontinuity() {
        discontinuityCalls.incrementAndGet()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val availableFrames = buffer.remaining() / bytesPerFrame
        if (availableFrames <= 0) return true
        synchronized(lock) {
            val space = maxBufferedFrames - pendingFrames
            if (space <= 0L) return false
            val frames = minOf(availableFrames.toLong(), space).toInt()
            val start = buffer.position()
            val length = frames * bytesPerFrame
            val data = ByteArray(length)
            val dup = buffer.duplicate()
            dup.position(start)
            dup.limit(start + length)
            dup.get(data)
            buffer.position(start + length)

            if (feedFrames == 0) feedPtsUs = presentationTimeUs
            feedFrames += frames
            feedChunks.add(data)
            pendingSegments.addLast(Pending(presentationTimeUs, frames.toLong()))
            pendingFrames += frames.toLong()

            val fullyConsumed = buffer.position() >= buffer.limit()
            if (fullyConsumed) {
                // 一个逻辑 buffer 可能因背压被拆成多次 handleBuffer；只在完整消费后记一条 Feed，
                // 这样 PTS 是样本起点，后续断言"PTS 增量 == 帧时长"才有意义。
                val merged = ByteArray(feedFrames * bytesPerFrame)
                var offset = 0
                for (chunk in feedChunks) {
                    System.arraycopy(chunk, 0, merged, offset, chunk.size)
                    offset += chunk.size
                }
                acceptedFrames += feedFrames
                if (recordedFrames < MAX_RECORDED_FRAMES) {
                    feedsInternal.add(Feed(feedFrames, feedPtsUs, merged, buffer.order()))
                    recordedFrames += feedFrames
                }
                feedChunks.clear()
                feedFrames = 0
            }
            return fullyConsumed
        }
    }

    override fun playToEndOfStream() {
        playToEndCalls.incrementAndGet()
        ended = true
    }

    override fun isEnded(): Boolean = ended

    override fun hasPendingData(): Boolean = synchronized(lock) { pendingFrames > 0L }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilence

    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): androidx.media3.common.AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) = Unit

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) = Unit

    override fun getAudioTrackBufferSizeUs(): Long = 0L

    override fun enableTunnelingV21() = Unit

    override fun disableTunneling() = Unit

    override fun setVolume(volume: Float) = Unit

    override fun pause() = Unit

    override fun flush() {
        flushCalls.incrementAndGet()
        ended = false
        synchronized(lock) {
            pendingSegments.clear()
            pendingFrames = 0L
            feedChunks.clear()
            feedFrames = 0
            positionUs = 0L
        }
    }

    override fun reset() {
        resetCalls.incrementAndGet()
        ended = false
        synchronized(lock) {
            pendingSegments.clear()
            pendingFrames = 0L
            feedChunks.clear()
            feedFrames = 0
            positionUs = 0L
        }
    }

    override fun release() {
        releaseCalls.incrementAndGet()
    }

    private companion object {
        const val MAX_RECORDED_FRAMES: Long = 400_000L
    }
}

/** 只提供一个真实 MediaCodecAudioRenderer，音频直接进假 sink（raw PCM 不建 codec）。 */
@UnstableApi
class FakeSinkRenderersFactory(
    private val context: Context,
    private val sink: RecordingAudioSink,
) : RenderersFactory {
    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> = arrayOf(
        MediaCodecAudioRenderer(
            context,
            MediaCodecSelector.DEFAULT,
            /* enableDecoderFallback= */ false,
            eventHandler,
            audioRendererEventListener,
            sink,
        ),
    )
}

/** 常量 DataSource：让 media3 的真实 WavExtractor 从内存字节里读（含 seek 偏移）。 */
@UnstableApi
class ByteArrayDataSource private constructor(
    private val bytes: ByteArray,
) : BaseDataSource(/* isNetwork= */ false) {

    private var uri: Uri? = null
    private var position = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        position = dataSpec.position.toInt()
        opened = true
        transferStarted(dataSpec)
        val remaining = (bytes.size - position).coerceAtLeast(0)
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(remaining.toLong(), dataSpec.length)
        } else {
            remaining.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= bytes.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, bytes.size - position)
        System.arraycopy(bytes, position, buffer, offset, count)
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val bytes: ByteArray) : DataSource.Factory {
        override fun createDataSource(): DataSource = ByteArrayDataSource(bytes)
    }
}

/** 记录 prepare/enable/createPeriod 的调用顺序，用来证明真实 ExoPlayer 的时序。 */
@UnstableApi
class RecordingMediaSource(
    private val delegate: MediaSource,
) : MediaSource by delegate {

    val events = CopyOnWriteArrayList<String>()

    @Volatile
    var createSawEnabled: Boolean? = null
        private set

    private var enabled = false

    override fun prepareSource(
        caller: MediaSource.MediaSourceCaller,
        mediaTransferListener: androidx.media3.datasource.TransferListener?,
        playerId: androidx.media3.exoplayer.analytics.PlayerId,
    ) {
        events += "prepareSource"
        delegate.prepareSource(caller, mediaTransferListener, playerId)
    }

    override fun enable(caller: MediaSource.MediaSourceCaller) {
        enabled = true
        events += "enable"
        delegate.enable(caller)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        createSawEnabled = enabled
        events += "createPeriod(enabled=" + enabled + ")"
        return delegate.createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        events += "releasePeriod"
        delegate.releasePeriod(mediaPeriod)
    }

    override fun disable(caller: MediaSource.MediaSourceCaller) {
        enabled = false
        events += "disable"
        delegate.disable(caller)
    }

    override fun releaseSource(caller: MediaSource.MediaSourceCaller) {
        events += "releaseSource"
        delegate.releaseSource(caller)
    }
}

/** 旧实现的严格版：createPeriod 早于 enable 直接抛异常（用于证明该时序存在）。 */
@UnstableApi
class StrictEnableMediaSource(
    private val delegate: MediaSource,
) : MediaSource by delegate {

    @Volatile
    private var enabled = false

    override fun enable(caller: MediaSource.MediaSourceCaller) {
        enabled = true
        delegate.enable(caller)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        check(enabled) { "createPeriod called before enable" }
        return delegate.createPeriod(id, allocator, startPositionUs)
    }
}

/** 统计 Extractor.read 的 CONTINUE 次数（包在 RingBandExtractor 外层观察）。 */
@UnstableApi
class CountingExtractor(private val delegate: Extractor) : Extractor by delegate {

    val readCalls = AtomicInteger()
    val maxConsecutiveContinue = AtomicInteger()
    private val consecutiveContinue = AtomicInteger()

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val result = delegate.read(input, seekPosition)
        readCalls.incrementAndGet()
        if (result == Extractor.RESULT_CONTINUE) {
            val c = consecutiveContinue.incrementAndGet()
            maxConsecutiveContinue.accumulateAndGet(c) { a, b -> maxOf(a, b) }
        } else {
            consecutiveContinue.set(0)
        }
        return result
    }
}

/** 环带 MediaSource 组装（与生产同构，只替换解码后端为 WAV）。 */
@UnstableApi
object RingBandTestSource {

    data class Assembly(
        val source: MediaSource,
        val ring: LoopRing,
        val reader: RingBandReader,
        val renderer: RingBandRenderer,
        val extractor: Extractor,
    )

    fun create(
        item: MediaItem,
        timeline: LoopingPcmTimeline,
        decoder: RingBandStreamDecoder,
        extractorWrapper: ((Extractor) -> Extractor)? = null,
        onSeamOutputFrames: ((Long) -> Unit)? = null,
        logger: (String) -> Unit = {},
    ): Assembly {
        val ring = LoopRing(timeline.sampleRate, timeline.channels, timeline.loopFrames)
        val startOffset = Math.floorMod(
            timeline.frameAtTimeline(0L) - timeline.aFrame,
            timeline.loopFrames,
        )
        val reader = RingBandReader(ring, startOffset)
        val renderer = RingBandRenderer(timeline, decoder, ring, logger)
        val extractor = RingBandExtractor(
            timeline = timeline,
            ring = ring,
            reader = reader,
            renderer = renderer,
            logger = logger,
            onSeamOutputFrames = onSeamOutputFrames,
        )
        val wrapped = extractorWrapper?.invoke(extractor) ?: extractor
        val extractors = ExtractorsFactory { arrayOf(wrapped) }
        val factory = ProgressiveMediaSource.Factory(
            LoopingPcmDataSource.Factory(),
            extractors,
        )
        renderer.start()
        return Assembly(factory.createMediaSource(item), ring, reader, renderer, wrapped)
    }

    fun loopingPcmSource(
        item: MediaItem,
        timeline: LoopingPcmTimeline,
        provider: PcmRangeProvider,
        logger: (String) -> Unit = {},
    ): MediaSource = LoopingPcmMediaSource(
        item = item,
        timeline = timeline,
        provider = provider,
        logger = logger,
    )

    fun wavProgressiveSource(item: MediaItem, wav: ByteArray): MediaSource =
        ProgressiveMediaSource.Factory(
            ByteArrayDataSource.Factory(wav),
            DefaultExtractorsFactory(),
        ).createMediaSource(item)
}

/** 同步队列 dispatcher：MediaPeriod 级测试里手动 drain，不依赖 Android Handler。 */
@UnstableApi
class QueueDispatcher : com.zjr.hesimusic.loop.audio.source.CallbackDispatcher {
    private val queue = java.util.ArrayDeque<Runnable>()

    override fun post(runnable: Runnable) {
        synchronized(queue) { queue.addLast(runnable) }
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) = Unit

    override fun remove(runnable: Runnable) = Unit

    fun drain() {
        while (true) {
            val next = synchronized(queue) { queue.pollFirst() } ?: return
            next.run()
        }
    }
}

/** 测试时间轴工具：真实 ExoPlayer + FakeClock 的最小泵。 */
@UnstableApi
object PlayerPump {

    fun context(): Context = ApplicationProvider.getApplicationContext()

    fun buildPlayer(sink: RecordingAudioSink, clock: FakeClock): ExoPlayer =
        TestExoPlayerBuilder(context())
            .setClock(clock)
            .setRenderersFactory(FakeSinkRenderersFactory(context(), sink))
            .build()

    /**
     * 推进虚拟时间 + 让 Robolectric 主 Looper 跑 ExoPlayer 的消息 + 让假声卡消费 [stepMs] 音频。
     *
     * 消费必须发生在两次 idle 之间：先渲染（被背压挡住），再消费，再让播放器继续渲染。
     */
    fun tick(clock: FakeClock, sink: RecordingAudioSink? = null, stepMs: Long = 20L) {
        ShadowLooper.idleMainLooper()
        sink?.advanceBy(stepMs)
        clock.advanceTime(stepMs)
        ShadowLooper.idleMainLooper()
    }

    fun pumpUntil(
        clock: FakeClock,
        sink: RecordingAudioSink? = null,
        timeoutMs: Long = 4_000L,
        stepMs: Long = 20L,
        sleepMs: Long = 1L,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            tick(clock, sink, stepMs)
            if (sleepMs > 0L) Thread.sleep(sleepMs)
        }
        tick(clock, sink, stepMs)
        return condition()
    }

    /** 真实时间窗内持续泵（用于观察 loader 是否空转）。 */
    fun pumpFor(clock: FakeClock, sink: RecordingAudioSink? = null, durationMs: Long, stepMs: Long = 10L) {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            tick(clock, sink, stepMs)
            Thread.sleep(1L)
        }
    }
}
