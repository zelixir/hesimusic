package com.zjr.hesimusic.loop.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 测试公共工具：mono 16bit PCM + 媒体微秒换算。 */
object PcmTestSupport {

    const val SAMPLE_RATE: Int = 1000
    const val BYTES_PER_FRAME: Int = 2

    fun format(
        sampleRate: Int = SAMPLE_RATE,
        channelCount: Int = 1,
        pcmEncoding: Int = C.ENCODING_PCM_16BIT,
    ): Format = Format.Builder()
        .setSampleRate(sampleRate)
        .setChannelCount(channelCount)
        .setPcmEncoding(pcmEncoding)
        .build()

    /** 生成从 [startFrame] 开始的 [frameCount] 帧 mono PCM16，样本值 = 帧号。 */
    fun pcm16Buffer(
        startFrame: Int,
        frameCount: Int,
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): ByteBuffer {
        val bb = ByteBuffer.allocate(frameCount * BYTES_PER_FRAME).order(order)
        for (i in 0 until frameCount) {
            bb.putShort((startFrame + i).toShort())
        }
        bb.flip()
        return bb
    }

    fun bytesOf(buffer: ByteBuffer): ByteArray {
        val dup = buffer.duplicate()
        val data = ByteArray(dup.remaining())
        dup.get(data)
        return data
    }

    fun bytesOf(feed: FakeAudioSink.Feed): ByteArray = feed.bytes.copyOf()

    fun ptsOfFrame(frameIndex: Long, sampleRate: Int = SAMPLE_RATE): Long =
        frameIndex * 1_000_000L / sampleRate
}
