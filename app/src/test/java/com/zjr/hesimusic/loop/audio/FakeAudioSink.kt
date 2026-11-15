package com.zjr.hesimusic.loop.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 测试用 AudioSink：记录每次真正交给 delegate 的 (frames, pts, bytes, buffer 实例, 字节序)。
 *
 * [maxFramesPerCall] < 实际帧数时只消费一部分并返回 false，用来模拟 delegate 背压。
 */
class FakeAudioSink : AudioSink {

    data class Feed(
        val frames: Int,
        val ptsUs: Long,
        val bytes: ByteArray,
        val order: ByteOrder,
        val bufferIdentity: Int,
    )

    var bytesPerFrame: Int = 2
    var maxFramesPerCall: Int = Int.MAX_VALUE
    var currentPositionUs: Long = 0L
    var ended: Boolean = false
    var hasPending: Boolean = false

    val feeds = mutableListOf<Feed>()
    var configureCalls: Int = 0
    var playCalls: Int = 0
    var pauseCalls: Int = 0
    var flushCalls: Int = 0
    var resetCalls: Int = 0
    var releaseCalls: Int = 0
    var playToEndCalls: Int = 0
    var isEndedCalls: Int = 0
    var discontinuityCalls: Int = 0

    private var listener: AudioSink.Listener? = null
    private var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
    private var skipSilence: Boolean = false
    private var audioAttributes: AudioAttributes = AudioAttributes.Builder().build()
    private var audioSessionId: Int = 0
    private var volume: Float = 1f

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean = true

    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun getCurrentPositionUs(forceDiscontinuityPosition: Boolean): Long = currentPositionUs

    override fun configure(format: Format, bufferSize: Int, audioTrackChannelConfig: IntArray?) {
        configureCalls += 1
        ended = false
        val channels = format.channelCount.coerceAtLeast(1)
        val bytesPerSample = when (format.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            else -> 2
        }
        bytesPerFrame = channels * bytesPerSample
    }

    override fun play() {
        playCalls += 1
        ended = false
    }

    override fun handleDiscontinuity() {
        discontinuityCalls += 1
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val availableFrames = buffer.remaining() / bytesPerFrame
        if (availableFrames <= 0) return true
        val frames = minOf(availableFrames, maxFramesPerCall)
        if (frames <= 0) return false
        val length = frames * bytesPerFrame
        val start = buffer.position()
        val data = ByteArray(length)
        val dup = buffer.duplicate()
        dup.position(start)
        dup.limit(start + length)
        dup.get(data)
        val identity = System.identityHashCode(buffer)
        val order = buffer.order()
        buffer.position(start + length)
        feeds.add(Feed(frames, presentationTimeUs, data, order, identity))
        return buffer.position() >= buffer.limit()
    }

    override fun playToEndOfStream() {
        playToEndCalls += 1
        ended = true
    }

    override fun isEnded(): Boolean {
        isEndedCalls += 1
        return ended
    }

    override fun hasPendingData(): Boolean = hasPending

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilence

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
        // no-op
    }

    override fun getAudioTrackBufferSizeUs(): Long = 0L

    override fun enableTunnelingV21() {
        // no-op
    }

    override fun disableTunneling() {
        // no-op
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun pause() {
        pauseCalls += 1
    }

    override fun flush() {
        flushCalls += 1
        ended = false
    }

    override fun reset() {
        resetCalls += 1
        ended = false
    }

    override fun release() {
        releaseCalls += 1
    }
}
