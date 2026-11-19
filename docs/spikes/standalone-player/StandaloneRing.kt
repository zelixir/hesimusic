package com.zjr.hesimusic.loop.audio.standalone

/**
 * 独立循环内核的 PCM 环（PCM16 交错、小端）。
 *
 * 与生产 [com.zjr.hesimusic.loop.audio.source.LoopRing] 同思路，但面向播放写线程做了两点：
 * 1. [readInto] 直接把取模后的连续片段写进调用方缓冲区，播放线程**零分配**；
 * 2. [awaitRenderedAtLeast] 允许“先播已解出的前缀、后台继续解码”的首声路径。
 *
 * 线程模型：append（解码线程）与 readInto（播放线程）用同一把锁；单次 arraycopy 很小，
 * 锁持有时间在微秒级。整环模式下 [readInto] 不等待，永不阻塞播放线程。
 */
class StandaloneRing(
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(channels > 0) { "channels must be > 0" }
        require(frames > 0L) { "frames must be > 0" }
        require(frames * channels * 2L <= Int.MAX_VALUE.toLong()) { "ring too large" }
    }

    val bytesPerFrame: Int = channels * 2
    private var data = ByteArray((frames * bytesPerFrame).toInt())
    private val monitor = Object()

    @Volatile
    var renderedFrames: Long = 0L
        private set

    @Volatile
    var closed: Boolean = false
        private set

    /** 顺序追加已解码 PCM；返回实际写入帧数（不覆盖已写高水位）。 */
    fun append(src: ByteArray, srcOffsetBytes: Int = 0): Int = synchronized(monitor) {
        if (closed) return 0
        val available = ((src.size - srcOffsetBytes) / bytesPerFrame).coerceAtLeast(0)
        val writable = (frames - renderedFrames).coerceAtMost(available.toLong()).toInt()
        if (writable <= 0) return 0
        System.arraycopy(
            src,
            srcOffsetBytes,
            data,
            (renderedFrames * bytesPerFrame).toInt(),
            writable * bytesPerFrame,
        )
        renderedFrames += writable
        monitor.notifyAll()
        writable
    }

    /**
     * 从环内 [offsetFrames]（可为负/超过 L，按 mod 取模）读 [countFrames] 帧到 [dst]。
     * 返回实际写入字节数；调用方保证 countFrames 覆盖的数据已经 append。
     */
    fun readInto(offsetFrames: Long, dst: ByteArray, dstOffsetBytes: Int, countFrames: Int): Int {
        if (countFrames <= 0) return 0
        val byteCount = countFrames * bytesPerFrame
        require(dstOffsetBytes >= 0 && dstOffsetBytes + byteCount <= dst.size) {
            "dst overflow: off=$dstOffsetBytes bytes=$byteCount size=${dst.size}"
        }
        synchronized(monitor) {
            if (closed) return 0
            var ringFrame = Math.floorMod(offsetFrames, frames)
            var dstPos = dstOffsetBytes
            var left = countFrames
            while (left > 0) {
                val run = minOf(left.toLong(), frames - ringFrame).toInt()
                System.arraycopy(
                    data,
                    (ringFrame * bytesPerFrame).toInt(),
                    dst,
                    dstPos,
                    run * bytesPerFrame,
                )
                dstPos += run * bytesPerFrame
                left -= run
                ringFrame = 0L
            }
            return byteCount
        }
    }

    /** 取任意 offset 开始的 count 帧（测试/接缝校验用；会分配）。 */
    fun copyFrames(offsetFrames: Long, countFrames: Int): ByteArray {
        if (countFrames <= 0) return ByteArray(0)
        val out = ByteArray(countFrames * bytesPerFrame)
        readInto(offsetFrames, out, 0, countFrames)
        return out
    }

    /** 从 [offsetFrames] 起已连续渲染的帧数（整环模式下 = frames）。 */
    fun availableFrom(offsetFrames: Long): Long {
        if (offsetFrames < 0L) return 0L
        val rendered = renderedFrames
        return (rendered - offsetFrames).coerceAtLeast(0L)
    }

    /** 等待连续前缀达到 [targetFrames]；有新 append 会 notify。超时返回当前是否达标。 */
    fun awaitRenderedAtLeast(targetFrames: Long, timeoutMs: Long): Boolean {
        synchronized(monitor) {
            if (renderedFrames >= targetFrames) return true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed && renderedFrames < targetFrames) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) break
                try {
                    monitor.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return renderedFrames >= targetFrames
        }
    }

    fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            data = ByteArray(0)
            renderedFrames = 0L
            monitor.notifyAll()
        }
    }

    /** 已渲染帧的累计字节数（内存口径）。 */
    fun allocatedBytes(): Long = frames * bytesPerFrame.toLong()
}
