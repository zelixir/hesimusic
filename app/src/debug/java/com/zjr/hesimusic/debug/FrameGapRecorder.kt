package com.zjr.hesimusic.debug

import android.os.Process
import java.util.LinkedHashMap

/**
 * 设备侧“停写段”记录器（issue 00029）。
 *
 * 5ms 采样 AudioSink 累计输出帧数，写入固定 60s 环形缓冲；debug 线程读。
 * 写线程只做：System.nanoTime() + 两次数组写 + 几个 volatile 字段，不分配、不加锁。
 */
object FrameGapRecorder {

    private const val PERIOD_MS = 5L
    private const val CAPACITY = 12_000 // 60s / 5ms

    private val stampsMs = LongArray(CAPACITY)
    private val frames = LongArray(CAPACITY)

    @Volatile private var sequence = 0L
    @Volatile private var writeIndex = 0
    @Volatile private var count = 0

    @Volatile private var lastGapMs = 0L
    @Volatile private var started = false

    private var thread: Thread? = null
    private var framesProvider: (() -> Long)? = null

    @Synchronized
    fun start(provider: () -> Long) {
        if (started) return
        framesProvider = provider
        started = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var lastFrames = -1L
            var lastChangeMs = 0L
            var ongoingGapMs = 0L
            var running = true
            while (running) {
                try {
                    val nowMs = System.nanoTime() / 1_000_000L
                    val f = try {
                        framesProvider?.invoke() ?: 0L
                    } catch (_: Throwable) {
                        lastFrames
                    }
                    if (lastFrames >= 0L) {
                        if (f == lastFrames) {
                            if (lastChangeMs > 0L) {
                                ongoingGapMs = nowMs - lastChangeMs
                                // /state 字段不做阈值过滤：最近一次“帧数没涨”的原始时长。
                                if (ongoingGapMs > 0L) lastGapMs = ongoingGapMs
                            }
                        } else {
                            if (ongoingGapMs > 0L) lastGapMs = ongoingGapMs
                            ongoingGapMs = 0L
                            lastChangeMs = nowMs
                        }
                    } else {
                        lastChangeMs = nowMs
                    }
                    lastFrames = f
                    append(nowMs, f)
                } catch (_: Throwable) {
                    // 记录器绝不能影响播放
                }
                try {
                    Thread.sleep(PERIOD_MS)
                } catch (_: InterruptedException) {
                    running = false
                }
            }
        }, "FrameGapRecorder").apply {
            isDaemon = true
        }
        thread?.start()
    }

    /** 仅当前停写段/最近一次停写段，给 /state 轻量字段用。 */
    fun lastGapMs(): Long = lastGapMs

    private fun append(nowMs: Long, f: Long) {
        sequence += 1L
        val idx = writeIndex
        stampsMs[idx] = nowMs
        frames[idx] = f
        writeIndex = (idx + 1) % CAPACITY
        if (count < CAPACITY) count += 1
        sequence += 1L
    }

    /** GET /debug/frame-gaps?windowMs=N：返回样本/写帧间隔 p50、自适应阈值与停写段。 */
    fun snapshot(windowMs: Long): Map<String, Any?> {
        val nowMs = System.nanoTime() / 1_000_000L
        val window = windowMs.coerceIn(100L, 60_000L)
        val n = readSnapshot()
        val r = FrameGapAnalyzer.analyze(n.stamps, n.frames, window, nowMs)
        return linkedMapOf<String, Any?>(
            "windowMs" to window,
            "samples" to r.samples,
            "sampleIntervalP50Ms" to r.sampleIntervalP50Ms,
            "writeIntervalP50Ms" to r.writeIntervalP50Ms,
            "thresholdMs" to r.thresholdMs,
            "gaps" to r.gaps.map {
                linkedMapOf<String, Any?>(
                    "startAtMs" to it.startAtMs,
                    "durationMs" to it.durationMs,
                )
            },
            "lastWriteGapMs" to lastGapMs,
        )
    }

    private class Snapshot(val stamps: LongArray, val frames: LongArray)

    private fun readSnapshot(): Snapshot {
        while (true) {
            val s1 = sequence
            if (s1 and 1L != 0L) continue
            val c = count
            val idx = writeIndex
            val total = c.coerceAtMost(CAPACITY)
            val ts = LongArray(total)
            val fs = LongArray(total)
            val start = if (c < CAPACITY) 0 else idx
            for (i in 0 until total) {
                val k = (start + i) % CAPACITY
                ts[i] = stampsMs[k]
                fs[i] = frames[k]
            }
            val s2 = sequence
            if (s1 == s2 && s2 and 1L == 0L) return Snapshot(ts, fs)
        }
    }
}
