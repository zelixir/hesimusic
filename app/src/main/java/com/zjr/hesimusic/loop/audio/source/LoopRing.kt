package com.zjr.hesimusic.loop.audio.source

/**
 * 方案 A 的纯 PCM 环带（PCM16 交错、小端）。
 *
 * 只做「连续写入 + 按帧取模读取」，不依赖 Android / 解码器。
 * frames = ceil(B*sr) - ceil(A*sr)，byte 数 = frames * channels * 2。
 *
 * 00022/00023：环带不再只能从 A 顺序追加，而是带**帧级覆盖位图**：
 * - [writeFramesAt] 可以从任意 offset 跳填（seek 到未填充区时不必等解码从头追上来）；
 * - 读取只依赖覆盖位图，[contiguousFilledFrames] 返回从 offset 起连续可读的帧数；
 * - 已填数据不会被跳填破坏（写入只落在缺口里，覆盖位图只增不减）。
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
    private var data = ByteArray((frames * bytesPerFrame).toInt())

    /** 每帧 1 bit 的覆盖位图；跳填/顺序填共用。 */
    private val coverage = LongArray(((frames + 63L) / 64L).toInt())
    private val monitor = Object()

    /** 已写入的帧数（含跳填重复写入）；仅诊断/兼容旧口径，判断完整性请用 [isFullyCovered]。 */
    @Volatile
    var renderedFrames: Long = 0L
        private set

    /** 去重后已覆盖的帧数；== [frames] 表示整环可读。 */
    @Volatile
    var coveredFrames: Long = 0L
        private set

    /**
     * 以**静音**补齐（窗口尾部实际短于预期）的帧数；[coveredFrames] 已包含这些帧。
     *
     * 00024：窗口 B = 文件尾时，最后一个编码样本之后的帧物理上不存在，只能补静音；
     * 这里把这段单独记账，让上层能区分"真实音频"与"补齐静音"（见 [audioCoveredFrames]）。
     */
    @Volatile
    var tailTrimmedFrames: Long = 0L
        private set

    /** 第一段静音补齐的环内偏移；-1 = 没有。 */
    @Volatile
    var tailTrimmedFromOffset: Long = -1L
        private set

    @Volatile
    var closed: Boolean = false
        private set

    /** 顺序 append 的写游标；与跳填游标互不影响。 */
    private var appendCursor: Long = 0L

    /** 整环是否都已填充（回绕到任意位置都能读）。 */
    val isFullyCovered: Boolean
        get() = coveredFrames >= frames

    /** 真正有音频数据的帧数（= [coveredFrames] - [tailTrimmedFrames]）。 */
    val audioCoveredFrames: Long
        get() = (coveredFrames - tailTrimmedFrames).coerceAtLeast(0L)

    /** 是否用过静音补齐（"尾部短于预期"），供诊断/上层判读。 */
    val hasTailTrim: Boolean
        get() = tailTrimmedFrames > 0L

    /** 顺序追加已解码 PCM；返回实际写入帧数（不覆盖已写高水位）。 */
    fun append(src: ByteArray, srcOffsetBytes: Int = 0): Int = synchronized(monitor) {
        if (closed) return 0
        val available = ((src.size - srcOffsetBytes) / bytesPerFrame).coerceAtLeast(0)
        val writable = (frames - appendCursor).coerceAtMost(available.toLong()).toInt()
        if (writable <= 0) return 0
        val start = appendCursor
        System.arraycopy(
            src,
            srcOffsetBytes,
            data,
            (start * bytesPerFrame).toInt(),
            writable * bytesPerFrame,
        )
        markLocked(start, writable.toLong())
        appendCursor += writable
        renderedFrames = (renderedFrames + writable).coerceAtMost(frames)
        monitor.notifyAll()
        writable
    }

    /**
     * 从环内帧偏移 [offsetFrames] 开始写入（按帧取模，最多写到环尾为止）。
     *
     * 返回本次写入帧数（0 表示环已释放/源为空）。跨越环尾请由调用方循环调用。
     */
    fun writeFramesAt(
        offsetFrames: Long,
        src: ByteArray,
        srcOffsetBytes: Int = 0,
    ): Int = synchronized(monitor) {
        if (closed) return 0
        val available = ((src.size - srcOffsetBytes) / bytesPerFrame).coerceAtLeast(0)
        if (available <= 0) return 0
        val offset = Math.floorMod(offsetFrames, frames)
        val run = minOf(available.toLong(), frames - offset).toInt()
        System.arraycopy(
            src,
            srcOffsetBytes,
            data,
            (offset * bytesPerFrame).toInt(),
            run * bytesPerFrame,
        )
        markLocked(offset, run.toLong())
        renderedFrames = (renderedFrames + run).coerceAtMost(frames)
        monitor.notifyAll()
        run
    }

    /**
     * 从环内帧偏移 [offsetFrames] 起，把 [countFrames] 中**尚未覆盖**的帧写成静音并标记覆盖。
     *
     * 00024：窗口 B 落在文件尾时，最后一段帧永远解不出来（源已结束）；这里用静音把覆盖位图补到
     * 窗口末尾，读取端不会卡在缺口上、播放可以正常走完/回绕，同时用 [tailTrimmedFrames] /
     * [audioCoveredFrames] 记录"尾部实际短于预期"。已覆盖的真实音频绝不会被覆盖。
     *
     * 不跨环尾（与 [writeFramesAt] 一致），跨环由调用方循环。
     *
     * @return 本次新标记为静音的帧数；0 = 已全部覆盖或环已释放。
     */
    fun fillSilenceAt(offsetFrames: Long, countFrames: Long): Int = synchronized(monitor) {
        if (closed) return 0
        if (countFrames <= 0L) return 0
        val offset = Math.floorMod(offsetFrames, frames)
        var frame = offset
        var left = minOf(countFrames, frames - offset)
        var silent = 0L
        while (left > 0L) {
            // 跳过已覆盖的真实音频（同线程重入 monitor 安全）：补齐绝不能改写已有 PCM。
            val filledAhead = contiguousFilledFrames(frame, left)
            if (filledAhead > 0L) {
                frame += filledAhead
                left -= filledAhead
                continue
            }
            // filledAhead == 0 说明起点未覆盖：只补到下一个已填帧为止（不越过真实音频）。
            val run = minOf(distanceToFilledFrame(frame), left)
            val startByte = (frame * bytesPerFrame).toInt()
            java.util.Arrays.fill(data, startByte, startByte + (run * bytesPerFrame).toInt(), 0.toByte())
            markLocked(frame, run)
            renderedFrames = (renderedFrames + run).coerceAtMost(frames)
            silent += run
            frame += run
            left -= run
        }
        if (silent > 0L) {
            tailTrimmedFrames += silent
            if (tailTrimmedFromOffset < 0L) tailTrimmedFromOffset = offset
            monitor.notifyAll()
        }
        silent.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** [offsetFrames] 起连续已填充的帧数（可跨环回绕），最多 [maxFrames]。 */
    fun contiguousFilledFrames(offsetFrames: Long, maxFrames: Long): Long {
        if (maxFrames <= 0L || closed) return 0L
        val cap = minOf(maxFrames, frames)
        synchronized(monitor) {
            var offset = Math.floorMod(offsetFrames, frames)
            var counted = 0L
            while (counted < cap) {
                val wordIndex = (offset ushr 6).toInt()
                val bit = (offset and 63L).toInt()
                val word = coverage[wordIndex] ushr bit
                // 不能读越过环尾：越过 frames 的位不属于环，必须回绕到 0 继续。
                val inWord = minOf(64L - bit, frames - offset)
                val limit = minOf(inWord, cap - counted)
                var run = 0L
                while (run < limit && ((word ushr run.toInt()) and 1L) != 0L) {
                    run += 1L
                }
                counted += run
                if (run < limit) break
                offset = (offset + run) % frames
            }
            return counted
        }
    }

    /** [offsetFrames] 起 [countFrames] 帧是否全部已填充（可跨环回绕）。 */
    fun isRangeFilled(offsetFrames: Long, countFrames: Long): Boolean {
        if (countFrames <= 0L) return true
        if (countFrames >= frames) return isFullyCovered
        return contiguousFilledFrames(offsetFrames, countFrames) >= countFrames
    }

    /** 环内第一个未填充帧；整环已满返回 -1。 */
    fun firstUnfilledFrame(): Long {
        if (closed) return -1L
        synchronized(monitor) {
            for (i in coverage.indices) {
                val missing = coverage[i].inv()
                if (missing != 0L) {
                    val frame = i.toLong() * 64L + java.lang.Long.numberOfTrailingZeros(missing)
                    return if (frame < frames) frame else -1L
                }
            }
            return -1L
        }
    }

    /**
     * 从 [offsetFrames] 起向前数，到第一个已填充帧的帧数（含 0：起点已填）。
     * 整环都没有已填帧时返回 [frames]。
     */
    fun distanceToFilledFrame(offsetFrames: Long): Long {
        if (closed) return frames
        synchronized(monitor) {
            var offset = Math.floorMod(offsetFrames, frames)
            var scanned = 0L
            while (scanned < frames) {
                val wordIndex = (offset ushr 6).toInt()
                val bit = (offset and 63L).toInt()
                val word = coverage[wordIndex] ushr bit
                if (word != 0L) {
                    scanned += java.lang.Long.numberOfTrailingZeros(word).toLong()
                    return scanned
                }
                // 只扫到环尾（越过 frames 的位不属于环），到环尾后回绕到 0。
                val take = minOf(64L - bit, frames - offset, frames - scanned)
                scanned += take
                offset = (offset + take) % frames
            }
            return frames
        }
    }

    /** 等到 [offsetFrames] 起 [countFrames] 帧都填充（有新写入会唤醒）。 */
    fun awaitRangeFilled(offsetFrames: Long, countFrames: Long, timeoutMs: Long): Boolean {
        synchronized(monitor) {
            if (isRangeFilled(offsetFrames, countFrames)) return true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!closed && !isRangeFilled(offsetFrames, countFrames)) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) break
                try {
                    monitor.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return isRangeFilled(offsetFrames, countFrames)
        }
    }

    /** 取任意 offset（帧号，可为负/超过 L）开始的 count 帧，按 mod 重复环内容。 */
    fun copyFrames(offsetFrames: Long, count: Int): ByteArray {
        require(count >= 0) { "count must be >= 0" }
        if (closed) return ByteArray(0)
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
        if (count <= 0 || closed) return ByteArray(0)
        val take = minOf(count.toLong(), contiguousFilledFrames(offsetFrames, count.toLong())).toInt()
        if (take <= 0) return ByteArray(0)
        return copyFrames(offsetFrames, take)
    }

    /** 旧口径：等到已写入帧数达到 [targetFrames]（有 append/跳填会唤醒）。 */
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

    /** 释放 ring 持有的 PCM ByteArray；可重复调用。 */
    fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            data = ByteArray(0)
            renderedFrames = 0L
            coveredFrames = 0L
            tailTrimmedFrames = 0L
            tailTrimmedFromOffset = -1L
            appendCursor = 0L
            java.util.Arrays.fill(coverage, 0L)
            monitor.notifyAll()
        }
    }

    fun clear() {
        synchronized(monitor) {
            if (closed) return
            data.fill(0)
            renderedFrames = 0L
            coveredFrames = 0L
            tailTrimmedFrames = 0L
            tailTrimmedFromOffset = -1L
            appendCursor = 0L
            java.util.Arrays.fill(coverage, 0L)
            monitor.notifyAll()
        }
    }

    fun presentationTimeUs(frameIndex: Long): Long =
        frameIndex * 1_000_000L / sampleRate

    /** 标记 [start] 起 [count] 帧已覆盖（不跨环尾，由调用方切分）。 */
    private fun markLocked(start: Long, count: Long) {
        var frame = start
        var left = count
        while (left > 0L) {
            val wordIndex = (frame ushr 6).toInt()
            val bit = (frame and 63L).toInt()
            val span = minOf(left, 64L - bit)
            val mask = if (span >= 64L) {
                -1L
            } else {
                ((1L shl span.toInt()) - 1L) shl bit
            }
            val old = coverage[wordIndex]
            val added = java.lang.Long.bitCount(mask and old.inv())
            coverage[wordIndex] = old or mask
            coveredFrames += added.toLong()
            frame += span
            left -= span
        }
    }
}
