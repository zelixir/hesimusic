package com.zjr.hesimusic.loop.audio.source

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import java.util.concurrent.CountDownLatch

/**
 * 合成 PCM 解码器：按绝对帧号生成样本值 = floorMod(frame - loopStartFrame, loopFrames)。
 * 用于断言"3 圈逐样本等于理论 [A,B) 重复"以及块对齐/seek 内容。
 */
class SyntheticPcmProvider(
    private val sampleRate: Int,
    private val channels: Int,
    private val loopStartFrame: Long,
    private val loopFrames: Long,
    private val valueForRelativeFrame: (Long) -> Short = { it.toShort() },
) : PcmRangeProvider {

    private val lock = Any()
    private val calls = mutableListOf<Pair<Long, Long>>()
    private val startedLatch = CountDownLatch(1)
    private var concurrent = 0

    @Volatile
    var maxConcurrent: Int = 0
        private set

    @Volatile
    var failWhen: ((startUs: Long, endUs: Long) -> Boolean)? = null

    @Volatile
    var gate: CountDownLatch? = null

    @Volatile
    var decodeDelayMs: Long = 0L

    override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
        synchronized(lock) {
            calls += startUs to endUs
            concurrent += 1
            if (concurrent > maxConcurrent) maxConcurrent = concurrent
        }
        try {
            startedLatch.countDown()
            gate?.await()
            if (decodeDelayMs > 0L) Thread.sleep(decodeDelayMs)
            if (failWhen?.invoke(startUs, endUs) == true) {
                throw IllegalStateException("synthetic decode failure at [$startUs,$endUs)")
            }

            val firstFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sampleRate)
            val frames = Pcm16Frames.framesForUsCeil(endUs - startUs, sampleRate)
            require(frames <= Int.MAX_VALUE) { "too many frames: $frames" }
            val bytesPerFrame = channels * 2
            val data = ByteArray((frames * bytesPerFrame).toInt())
            var offset = 0
            for (i in 0 until frames.toInt()) {
                val absolute = firstFrame + i
                val relative = Math.floorMod(absolute - loopStartFrame, loopFrames)
                val value = valueForRelativeFrame(relative)
                for (c in 0 until channels) {
                    data[offset++] = (value.toInt() and 0xff).toByte()
                    data[offset++] = ((value.toInt() shr 8) and 0xff).toByte()
                }
            }
            return Pcm16Segment(
                data = data,
                sampleRate = sampleRate,
                channels = channels,
                frames = frames,
            )
        } finally {
            synchronized(lock) { concurrent -= 1 }
        }
    }

    fun callsSnapshot(): List<Pair<Long, Long>> = synchronized(lock) { calls.toList() }

    fun decodeCount(): Int = synchronized(lock) { calls.size }

    fun awaitDecodeStarted() {
        startedLatch.await()
    }

    fun clearCalls() {
        synchronized(lock) { calls.clear() }
    }
}
