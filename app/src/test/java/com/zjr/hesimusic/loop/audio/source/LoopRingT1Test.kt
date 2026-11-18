package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * T1：环带供给的离散事件模拟（纯 JVM，无线程/sleep）。
 *
 * 135 次/s、每次 341 帧；1s 块、单解码器、20~40ms 固定种子延迟。
 * 扫描预渲染块数 1..6，找出最小安全预渲染量。
 */
class LoopRingT1Test {

    private data class SimResult(
        val preRenderBlocks: Int,
        val firstSoundMs: Double,
        val minBufferBlocks: Double,
        val starvations: Int,
    )

    private fun simulate(
        preRenderBlocks: Int,
        readFrames: Long = 341L,
        seed: Long = 20261118L,
    ): SimResult {
        val blockFrames = 48_000L
        val readIntervalMs = 1_000.0 / 135.0
        val ringBlocks = 15
        val maxDelayMs = 40.0
        val minDelayMs = 20.0
        val random = Random(seed)
        fun nextDelay() = minDelayMs + random.nextDouble() * (maxDelayMs - minDelayMs)

        var now = 0.0
        var renderedFrames = 0L
        var consumedFrames = 0L
        var decodeDoneAt = nextDelay() // block 0 已启动
        var firstSoundMs = -1.0
        var playbackStartMs = -1.0
        var nextReadAt = Double.POSITIVE_INFINITY
        var minBufferBlocks = Double.MAX_VALUE
        var starvations = 0
        val endMs = 30_000.0

        while (now <= endMs + 1.0) {
            val nextEvent = minOf(decodeDoneAt, nextReadAt)
            if (nextEvent == Double.POSITIVE_INFINITY) break
            now = nextEvent
            if (decodeDoneAt <= now + 1e-9) {
                renderedFrames += blockFrames
                decodeDoneAt = Double.POSITIVE_INFINITY
                if (firstSoundMs < 0.0 && renderedFrames >= preRenderBlocks * blockFrames) {
                    firstSoundMs = now
                    playbackStartMs = now
                    nextReadAt = now
                }
                if (renderedFrames - consumedFrames < ringBlocks * blockFrames) {
                    decodeDoneAt = now + nextDelay()
                }
            }
            if (nextReadAt <= now + 1e-9) {
                if (playbackStartMs < 0.0) {
                    nextReadAt = Double.POSITIVE_INFINITY
                    continue
                }
                val buffer = renderedFrames - consumedFrames
                minBufferBlocks = minOf(minBufferBlocks, buffer.toDouble() / blockFrames)
                if (buffer < readFrames) starvations += 1
                consumedFrames += minOf(readFrames, buffer)
                nextReadAt = now + readIntervalMs
                if (decodeDoneAt == Double.POSITIVE_INFINITY &&
                    renderedFrames - consumedFrames < ringBlocks * blockFrames
                ) {
                    decodeDoneAt = now + nextDelay()
                }
            }
        }
        return SimResult(
            preRenderBlocks = preRenderBlocks,
            firstSoundMs = firstSoundMs,
            minBufferBlocks = if (minBufferBlocks == Double.MAX_VALUE) 0.0 else minBufferBlocks,
            starvations = starvations,
        )
    }

    @Test
    fun `supply sim finds minimum safe pre-render and no starvation`() {
        val started = System.nanoTime()
        val results = (1..6).map { simulate(it) }
        for (r in results) {
            println(
                "T1 preRender=" + r.preRenderBlocks +
                    " firstSoundMs=" + "%.1f".format(r.firstSoundMs) +
                    " minBufferBlocks=" + "%.4f".format(r.minBufferBlocks) +
                    " starvations=" + r.starvations,
            )
        }
        val safe = results.firstOrNull {
            it.starvations == 0 && it.minBufferBlocks > 1.0 && it.firstSoundMs <= 300.0
        }
        assertTrue("no safe pre-render in 1..6", safe != null)
        assertEquals("minimum safe pre-render", 2, safe!!.preRenderBlocks)
        assertTrue("first sound <=300ms", results.all { it.firstSoundMs <= 300.0 })
        assertTrue("all runs starve-free", results.all { it.starvations == 0 })
        assertTrue("min buffer >1 block for safe run", safe.minBufferBlocks > 1.0)
        // 341 帧/135Hz = 46035 帧/s，略低于 48k；再用 356 帧/135Hz
        // （48060 帧/s）验证实时消费口径下安全预渲染量不退化。
        val realtime = (1..6).map { simulate(it, readFrames = 356L) }
        val realtimeSafe = realtime.firstOrNull {
            it.starvations == 0 && it.minBufferBlocks > 1.0 && it.firstSoundMs <= 300.0
        }
        println(
            "T1 realtime356 safePreRender=" + (realtimeSafe?.preRenderBlocks ?: -1) +
                " minBuffer=" + "%.4f".format(realtimeSafe?.minBufferBlocks ?: -1.0),
        )
        assertEquals("356 frames/135Hz minimum safe pre-render", 2, realtimeSafe?.preRenderBlocks)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        println("T1 elapsedMs=" + elapsedMs + " safePreRender=" + safe.preRenderBlocks)
        assertTrue("T1 must run <100ms, was " + elapsedMs + "ms", elapsedMs < 100L)
    }
}
