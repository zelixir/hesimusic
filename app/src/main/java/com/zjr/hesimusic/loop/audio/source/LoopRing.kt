package com.zjr.hesimusic.loop.audio.source

/**
 * 方案 A 的纯 PCM 环带（PCM16 交错、小端）。
 *
 * 只做「连续写入 + 按帧取模读取」，不依赖 Android / 解码器。
 * frames = ceil(B*sr) - ceil(A*sr)，byte 数 = frames * channels * 2。
 */
class LoopRing(
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(channels > 0) { "channels must be > 0" }
        require(frames > 0) { "frames must be > 0" }
        require(frames * channels * 2 <= Int.MAX_VALUE) { "ring too large" }
    }

    val bytesPerFrame: Int = channels * 2
    private val data = ByteArray((frames * bytesPerFrame).toInt())
    private val monitor = Object()

    @Volatile
    var renderedFrames: Long = 0L
        private set

    /** 顺序追加已解码 PCM；返回实际写入帧数（不覆盖已写高水位）。 */
    fun append(src: ByteArray, srcOffsetBytes: Int = 0): Int = synchronized(monitor) {
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

    /** 取任意 offset（帧号，可为负/超过 L）开始的 count 帧，按 mod 重复环内容。 */
    fun copyFrames(offsetFrames: Long, count: Int): ByteArray {
        require(count >= 0) { "count must be >= 0" }
        val out = ByteArray(count * bytesPerFrame)
        if (count == 0) return out
        var ringFrame = Math.floorMod(offsetFrames, frames)
        var dst = 0
        var left = count
        while (left > 0) {
            val run = minOf(left.toLong(), frames - ringFrame).toInt()
            System.arraycopy(
                data,
                (ringFrame * bytesPerFrame).toInt(),
                out,
                dst,
                run * bytesPerFrame,
            )
            dst += run * bytesPerFrame
            left -= run
            ringFrame = 0L
        }
        return out
    }

    /** 已渲染范围内的取模读取；未渲染返回 0（与 DataSource 的 stall 语义一致）。 */
    fun readAvailable(offsetFrames: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val rendered = renderedFrames
        if (rendered <= 0L) return ByteArray(0)
        val take = minOf(count.toLong(), rendered).toInt()
        return copyFrames(offsetFrames, take)
    }

    /** Extractor 等待高水位；有新 append 会 notify。超时返回当前是否已达标。 */
    fun awaitRenderedAtLeast(targetFrames: Long, timeoutMs: Long): Boolean {
        synchronized(monitor) {
            if (renderedFrames >= targetFrames) return true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (renderedFrames < targetFrames) {
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

    fun clear() {
        synchronized(monitor) {
            data.fill(0)
            renderedFrames = 0L
            monitor.notifyAll()
        }
    }

    fun presentationTimeUs(frameIndex: Long): Long =
        frameIndex * 1_000_000L / sampleRate
}
