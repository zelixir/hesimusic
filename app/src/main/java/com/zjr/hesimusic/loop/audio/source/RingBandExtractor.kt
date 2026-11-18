package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.IOException

/** 环带播放的 Extractor：read 从 RingBandReader 取模；seek 只改 offset。 */
@UnstableApi
class RingBandExtractor(
    private val timeline: LoopingPcmTimeline,
    private val ring: LoopRing,
    private val reader: RingBandReader,
    private val renderer: RingBandRenderer,
    private val logger: (String) -> Unit = {},
    private val onSeamOutputFrames: ((Long) -> Unit)? = null,
) : Extractor {

    private var trackOutput: TrackOutput? = null
    private var samples = 0L
    private var lastLogMs = 0L
    private var lastSeam = -1L

    private val format: Format = Format.Builder()
        .setId("ring-band")
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(timeline.sampleRate)
        .setChannelCount(timeline.channels)
        .setMaxInputSize(READ_CHUNK_BYTES)
        .build()

    private val seekMap = object : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = C.TIME_UNSET
        override fun getSeekPoints(positionUs: Long): SeekMap.SeekPoints {
            val p = positionUs.coerceAtLeast(0L)
            return SeekMap.SeekPoints(SeekPoint(p, p))
        }
    }

    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO).also { it.format(format) }
        output.seekMap(seekMap)
        output.endTracks()
        logger("RingBand extractor init")
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (LoopSourcePauseGate.paused) return Extractor.RESULT_CONTINUE
        renderer.error?.let { throw IOException("RingBand render failed: " + it) }
        val track = trackOutput ?: return Extractor.RESULT_CONTINUE
        val minFrames = minOf(timeline.loopFrames, 2L * timeline.sampleRate)
        if (ring.renderedFrames < minFrames) {
            // 预渲染不足 2 块（或整环更短）时不出样本；不允许补零。
            return Extractor.RESULT_CONTINUE
        }
        val result = reader.read(READ_CHUNK_BYTES / timeline.bytesPerFrame)
        if (result == null) {
            logger(
                "RingBand read CONTINUE filled=" + ring.renderedFrames +
                    " offset=" + reader.offsetFrames +
                    " underruns=" + reader.underruns,
            )
            return Extractor.RESULT_CONTINUE
        }
        val data = ParsableByteArray(result.data)
        track.sampleData(data, result.data.size)
        track.sampleMetadata(result.ptsUs, C.BUFFER_FLAG_KEY_FRAME, result.data.size, 0, null)
        samples += 1L
        reader.lastSeamOutputFrames.takeIf { it >= 0L && it != lastSeam }?.let { seam ->
            lastSeam = seam
            logger("RingBand seam outFrames=" + seam)
            onSeamOutputFrames?.invoke(seam)
        }
        val now = System.currentTimeMillis()
        if (now - lastLogMs >= 5_000L) {
            lastLogMs = now
            logger(
                "RingBand durationMs=" + (timeline.loopFrames * 1000L / timeline.sampleRate) +
                    " bandFrames=" + timeline.loopFrames +
                    " filledFrames=" + ring.renderedFrames +
                    " offset=" + reader.offsetFrames +
                    " reads=" + reader.reads +
                    " underruns=" + reader.underruns,
            )
        }
        if (samples == 1L || samples % 100L == 0L) {
            logger("RingBand read n=" + samples + " frames=" + result.frames)
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        val offset = Math.floorMod(
            timeline.frameAtTimeline(timeUs) - timeline.aFrame,
            timeline.loopFrames,
        )
        reader.seekToFrame(offset)
        logger(
            "RingBand seek position=" + position + " timeUs=" + timeUs +
                " offsetFrames=" + offset,
        )
    }

    override fun release() {
        renderer.release()
    }

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
    }
}
