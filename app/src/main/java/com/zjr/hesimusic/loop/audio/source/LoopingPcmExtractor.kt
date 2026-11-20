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
import com.zjr.hesimusic.core.invariants.LoopInvariantProbes

/**
 * 把 [LoopingPcmSourceCore] 包装成"虚拟无限长 PCM 容器"的 [Extractor]。
 *
 * 这样 ExoPlayer 使用成熟的 [androidx.media3.exoplayer.source.ProgressiveMediaSource]
 * 加载路径：period 创建、loader、buffered、track selection、renderer 启动全部复用
 * media3 实现，不再手写 MediaPeriod 契约。
 *
 * [read] 忽略 [ExtractorInput]（它由空 DataSource 支撑），直接从块缓存取 PCM；
 * B→A 的 PTS 由 core 连续递增，不需要文件层 seek。
 */
@UnstableApi
class LoopingPcmExtractor(
    private val core: LoopingPcmSourceCore,
    private val timeline: LoopingPcmTimeline,
    private val logger: (String) -> Unit = {},
    private val prefetcher: LoopPcmPrefetcher? = null,
) : Extractor {

    private var trackOutput: TrackOutput? = null
    private var formatSent = false
    private var started = false
    private var samples = 0L
    private var lastReadLogMs = 0L

    private val format: Format = Format.Builder()
        .setId("loop-pcm-extractor")
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

    /**
     * 保险:单候选 extractor 时 media3 直接采用(BundledExtractorsAdapter 不调 sniff),
     * 这里返回 true 只覆盖被显式 sniff 的路径,不代表"sniff=true 才会被采用"。
     */
    override fun sniff(input: ExtractorInput): Boolean = true

    override fun init(output: ExtractorOutput) {
        LoopInvariantProbes.onExtractorInit()
        val track = output.track(0, C.TRACK_TYPE_AUDIO)
        LoopInvariantProbes.onExtractorTrackCreated()
        trackOutput = track
        track.format(format)
        formatSent = true
        output.seekMap(seekMap)
        LoopInvariantProbes.onExtractorSeekMap()
        output.endTracks()
        LoopInvariantProbes.onExtractorEndTracks()
        logger("extractor init format=" + format.sampleMimeType + "/" + format.pcmEncoding + " " + timeline.sampleRate + "Hz/" + timeline.channels + "ch")
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (LoopSourcePauseGate.paused) return Extractor.RESULT_CONTINUE
        val track = trackOutput ?: return Extractor.RESULT_CONTINUE
        // 起播门槛：至少 2 块（2s）就绪再吐首个 sample，避免“先出声再断 347ms”。
        if (!started && core.readyBlockCount() < 2) {
            var guard = 0
            while (core.readyBlockCount() < 2 && guard < 4 && core.pumpDecode()) guard += 1
            if (core.readyBlockCount() < 2) {
                prefetcher?.kick()
                return Extractor.RESULT_CONTINUE
            }
        }
        started = true
        prefetcher?.kick()
        if (!formatSent) {
            track.format(format)
            formatSent = true
        }
        prefetcher?.kick()
        var result = core.read(READ_CHUNK_BYTES)
        if (result == null) {
            // 虚拟容器没有真实 I/O；加载线程按需解一块，避免空转。
            core.pumpDecode()
            result = core.read(READ_CHUNK_BYTES)
        }
        if (result == null) return Extractor.RESULT_CONTINUE

        val bpf = timeline.bytesPerFrame
        val framesValid = result.data.size / bpf
        if (bpf <= 0 || result.data.size % bpf != 0 || framesValid != result.frames || result.frames <= 0) {
            // 绝不把短数据/未填满数组当有效样本；不推进已由 core 负责，这里只拒绝输出。
            logger(
                "read invalid bytes=" + result.data.size + " frames=" + result.frames +
                    " bpf=" + bpf + " block=" + core.currentBlockIndex(),
            )
            return Extractor.RESULT_CONTINUE
        }
        val data = ParsableByteArray(result.data)
        track.sampleData(data, result.data.size)
        track.sampleMetadata(result.ptsUs, C.BUFFER_FLAG_KEY_FRAME, result.data.size, 0, null)
        samples += 1L
        val framesOut = READ_CHUNK_BYTES / bpf
        if (framesValid < framesOut) {
            warnLog(
                "read short framesOut=" + framesOut + " framesValid=" + framesValid +
                    " block=" + core.currentBlockIndex() +
                    " remainingFrames=" + core.currentBlockRemainingFrames() +
                    " blockState=" + core.currentBlockState() +
                    " cursorFrames=" + core.currentFrame(),
            )
        }
        val now = System.currentTimeMillis()
        if (now - lastReadLogMs >= 1_000L) {
            lastReadLogMs = now
            logger(
                "read framesOut=" + framesOut +
                    " framesValid=" + framesValid +
                    " block=" + core.currentBlockIndex() +
                    " remainingFrames=" + core.currentBlockRemainingFrames() +
                    " blockState=" + core.currentBlockState() +
                    " cursorFrames=" + core.currentFrame(),
            )
        }
        if (samples == 1L || samples % 100L == 0L) {
            logger("extractor read n=" + samples + " bytes=" + result.data.size + " ptsUs=" + result.ptsUs)
        }
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        core.seekToTimelineUs(timeUs)
        formatSent = false
        started = false
        prefetcher?.kick()
        logger("extractor seek position=" + position + " timeUs=" + timeUs)
    }

    override fun release() {
        prefetcher?.release()
        core.release()
    }

    private fun warnLog(message: String) {
        try {
            android.util.Log.w("LoopPcmSource", message)
        } catch (_: Throwable) {
            // JVM 单测里 android.util.Log 是 stub。
        }
    }

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
    }
}
