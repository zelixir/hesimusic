package com.zjr.hesimusic.loop.audio.source

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extractor 路径的后台预取：按 [targetBlocks] 把当前块之后的数据提前解码。
 *
 * ProgressiveMediaSource 的 Extractor.read 只在阻塞时同步泵一块；如果没有后台预取，
 * 1s 块边界上的解码抖动会直接让 AudioSink 饿死（表现为每 1~2 秒一次的欠载）。
 */
class LoopPcmPrefetcher(
    private val core: LoopingPcmSourceCore,
    private val executor: ExecutorService,
    private val targetBlocks: Int = 6,
    private val logger: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var released = false

    fun kick() {
        if (released || LoopSourcePauseGate.paused) return
        if (!running.compareAndSet(false, true)) return
        try {
            executor.execute {
                var progressed = false
                try {
                    var guard = 0
                    while (!released && !LoopSourcePauseGate.paused &&
                        core.readyBlockCount() < targetBlocks &&
                        core.needsDecode() &&
                        guard < targetBlocks * 2
                    ) {
                        if (!core.pumpDecode()) break
                        progressed = true
                        guard += 1
                    }
                } finally {
                    running.set(false)
                    if (progressed && !released && !LoopSourcePauseGate.paused &&
                        core.readyBlockCount() < targetBlocks && core.needsDecode()
                    ) {
                        kick()
                    } else if (progressed) {
                        logger(
                            "LoopPcmSource prefetch ready=" + core.readyBlockCount() +
                                " target=" + targetBlocks,
                        )
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            running.set(false)
        }
    }

    fun release() {
        released = true
        executor.shutdownNow()
    }
}
