package com.zjr.hesimusic.loop.audio.source

import android.media.MediaExtractor
import android.media.MediaFormat

/**
 * 只探测音频轨的采样率/声道数，用于在构造循环数据源之前拿到 [PcmTrackFormat]。
 *
 * 生产接线：service 已能解析 songId -> path；在 UI/service 线程快速探测即可
 * （不解码、不读样本）。完整轨道探测仍由 [com.zjr.hesimusic.loop.analysis.PcmDecoder]
 * 的范围解码负责。
 */
object PcmFileFormatProbe {

    fun probe(path: String): PcmTrackFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                if (sampleRate <= 0 || channels <= 0) {
                    throw IllegalArgumentException("invalid audio format: sr=$sampleRate ch=$channels")
                }
                return PcmTrackFormat(sampleRate = sampleRate, channels = channels)
            }
            throw IllegalArgumentException("no audio track in $path")
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
