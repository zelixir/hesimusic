package com.zjr.hesimusic.loop.audio.source

/**
 * 循环 PCM 数据源核心（纯 JVM，不依赖 Android / Media3）。
 *
 * 职责：
 * - 维护"对外播放时间 T → 媒体帧"的 cursor 与 PTS；
 * - 按 1 秒块按需解码（[pumpDecode]），命中缓存直接供样本；
 * - seek 只改 cursor/PTS 基准并清空待读数据，不重建、不整环解码、不阻塞调用线程；
 * - B→A 在内部取模，PTS 继续递增，不触发 seek。
 *
 * 线程模型：
 * - [read]/[seekToTimelineUs] 由播放线程调用；
 * - [pumpDecode] 由单个解码 worker 调用（内部用 [decodeLock] 串行化）；
 * - 块缓存本身线程安全；解码 provider 的调用不持有状态锁，seek 不会被解码阻塞。
 */
class LoopingPcmSourceCore(
    val timeline: LoopingPcmTimeline,
    private val provider: PcmRangeProvider,
    maxCacheBytes: Long = PcmBlockCache.DEFAULT_MAX_BYTES,
    private val prefetchBlocks: Int = DEFAULT_PREFETCH_BLOCKS,
    private val logger: (String) -> Unit = {},
) {

    data class ReadResult(
        val frames: Int,
        val ptsUs: Long,
        val data: ByteArray,
    )

    private val lock = Any()
    private val decodeLock = Any()

    val cache = PcmBlockCache(
        key = timeline.cacheKey(),
        framesPerBlock = timeline.framesPerBlock,
        bytesPerFrame = timeline.bytesPerFrame,
        maxBytes = maxCacheBytes,
    )

    private var nextFrame: Long = timeline.frameAtTimeline(0L)
    private var ptsBaseUs: Long = 0L
    private var emittedFrames: Long = 0L
    private var skippedBlockCount: Long = 0L
    private var skippedFrameCount: Long = 0L
    private var skipStreak: Int = 0
    private var decodeCalls: Long = 0L
    private var lastError: String? = null
    private var released: Boolean = false

    /** 最近一次 seek 的对外时间目标（诊断）。 */
    @Volatile
    var lastSeekUs: Long = 0L
        private set

    fun currentPtsUs(): Long = synchronized(lock) {
        ptsBaseUs + framesToUs(emittedFrames)
    }

    fun currentFrame(): Long = synchronized(lock) { nextFrame }

    fun currentBlockIndex(): Int = synchronized(lock) {
        timeline.blockIndexForFrame(nextFrame)
    }

    fun decodeCallCount(): Long = synchronized(lock) { decodeCalls }

    fun skippedBlocks(): Long = synchronized(lock) { skippedBlockCount }

    fun skippedFrames(): Long = synchronized(lock) { skippedFrameCount }

    fun error(): String? = synchronized(lock) { lastError }

    fun isCurrentBlockReady(): Boolean = synchronized(lock) {
        cache.state(timeline.blockIndexForFrame(nextFrame)) == PcmBlockCache.State.READY
    }

    fun needsDecode(): Boolean = synchronized(lock) {
        val current = timeline.blockIndexForFrame(nextFrame)
        if (cache.state(current) != PcmBlockCache.State.READY) return true
        for (d in 1..prefetchBlocks) {
            val block = wrappedBlock(current + d)
            if (block != current && cache.state(block) == null) return true
        }
        false
    }

    /**
     * 取下一段 PCM。
     *
     * @param maxBytes 本次最多返回的字节数（会向下取整到整帧）
     * @param advance false = PEEK（不推进 cursor）
     * @return null = 当前块未就绪或已到错误状态，调用方应等待 worker 解码
     */
    fun read(maxBytes: Int, advance: Boolean = true): ReadResult? = synchronized(lock) {
        if (released || maxBytes < timeline.bytesPerFrame) return null
        if (lastError != null) return null

        var block = currentBlockLocked()
        if (block == null) return null

        val offset = (nextFrame - block.startFrame).toInt()
        if (offset < 0 || offset >= block.frames) return null
        val maxFrames = maxBytes / timeline.bytesPerFrame
        val frames = minOf(maxFrames, block.frames - offset)
        if (frames <= 0) return null

        val pts = ptsBaseUs + framesToUs(emittedFrames)
        val startBytes = offset * timeline.bytesPerFrame
        val byteLength = frames * timeline.bytesPerFrame
        val data = block.data.copyOfRange(startBytes, startBytes + byteLength)

        if (advance) {
            emittedFrames += frames.toLong()
            nextFrame = timeline.normalizeFrame(nextFrame + frames)
            skipStreak = 0
            cache.trimToBudget(timeline.blockIndexForFrame(nextFrame))
        }
        ReadResult(frames = frames, ptsUs = pts, data = data)
    }

    /**
     * 对外时间轴 seek。
     *
     * - 立即清空待读队列（本实现没有独立队列，cursor 即队列头）；
     * - 命中块缓存时下一次 [read] 立即可读；
     * - 未命中只标记目标块，解码由 worker 的 [pumpDecode] 完成，本方法不阻塞；
     * - 返回目标本身，保证 MediaPeriod 的 seek 位置与用户目标一致。
     */
    fun seekToTimelineUs(targetUs: Long): Long = synchronized(lock) {
        val t = targetUs.coerceAtLeast(0L)
        lastSeekUs = t
        ptsBaseUs = t
        emittedFrames = 0L
        skipStreak = 0
        nextFrame = timeline.frameAtTimeline(t)
        val block = timeline.blockIndexForFrame(nextFrame)
        cache.playheadBlock = block
        cache.retryFailed(block)
        cache.trimToBudget(block)
        t
    }

    /**
     * 解码一块（当前块优先，其次 k+1/k+2 预取）。
     *
     * @return true = 本次推进了解码（解码成功/失败跳过/预取）；false = 无事可做或当前块正在解码
     */
    fun pumpDecode(): Boolean {
        synchronized(decodeLock) {
            var target: Int? = null
            var skipped = false
            synchronized(lock) {
                if (released || lastError != null) return false
                val current = timeline.blockIndexForFrame(nextFrame)
                cache.playheadBlock = current
                cache.trimToBudget(current)
                when (cache.state(current)) {
                    PcmBlockCache.State.FAILED -> {
                        skipFailedBlockLocked(current)
                        skipped = true
                    }
                    PcmBlockCache.State.READY -> {
                        target = findPrefetchLocked(current)
                    }
                    PcmBlockCache.State.PENDING -> Unit
                    null -> target = current
                }
            }
            if (skipped) return true
            val block = target ?: return false
            return decodeBlock(block)
        }
    }

    /** 当前块 + 已就绪的连续块能覆盖到的 PTS 末尾。 */
    fun bufferedEndUs(): Long = synchronized(lock) {
        var frame = nextFrame
        var aheadFrames = 0L
        var guard = 0
        while (guard < timeline.blockCount) {
            val block = cache.get(timeline.blockIndexForFrame(frame)) ?: break
            val offset = (frame - block.startFrame).toInt()
            if (offset < 0 || offset >= block.frames) break
            aheadFrames += block.frames - offset
            frame = timeline.normalizeFrame(block.startFrame + block.frames)
            if (frame == nextFrame) break
            guard++
        }
        ptsBaseUs + framesToUs(emittedFrames + aheadFrames)
    }

    /** 渲染器 skipData：按对外 PTS 跳过样本（不读取 PCM）。 */
    fun skipToPts(positionUs: Long): Int = synchronized(lock) {
        val current = ptsBaseUs + framesToUs(emittedFrames)
        if (positionUs <= current) return 0
        val deltaFrames = ((positionUs - current) * timeline.sampleRate + 999_999L) / 1_000_000L
        if (deltaFrames <= 0L) return 0
        val step = minOf(deltaFrames, timeline.loopFrames)
        emittedFrames += step
        nextFrame = timeline.normalizeFrame(nextFrame + step)
        cache.trimToBudget(timeline.blockIndexForFrame(nextFrame))
        step.toInt()
    }

    fun release() {
        synchronized(lock) {
            released = true
            cache.clear()
        }
    }

    private fun currentBlockLocked(): PcmBlock? {
        val current = timeline.blockIndexForFrame(nextFrame)
        cache.playheadBlock = current
        var state = cache.state(current)
        if (state == PcmBlockCache.State.FAILED) {
            skipFailedBlockLocked(current)
            state = cache.state(timeline.blockIndexForFrame(nextFrame))
        }
        if (state != PcmBlockCache.State.READY) return null
        return cache.get(timeline.blockIndexForFrame(nextFrame))
    }

    private fun skipFailedBlockLocked(block: Int) {
        val blockStart = timeline.blockStartFrame(block)
        val blockEnd = blockStart + timeline.blockFrames(block)
        val skip = (blockEnd - nextFrame).coerceAtLeast(0L)
        if (skip <= 0L) return
        nextFrame = timeline.normalizeFrame(blockEnd)
        emittedFrames += skip
        skippedBlockCount += 1L
        skippedFrameCount += skip
        skipStreak += 1
        cache.trimToBudget(timeline.blockIndexForFrame(nextFrame))
        logger(
            "loop block decode failed; skipped block=" + block +
                " frames=" + skip + " next=" + nextFrame,
        )
        if (skipStreak >= timeline.blockCount) {
            lastError = "all loop blocks failed"
        }
    }

    private fun findPrefetchLocked(current: Int): Int? {
        if (prefetchBlocks <= 0) return null
        for (d in 1..prefetchBlocks) {
            val block = wrappedBlock(current + d)
            if (block == current) continue
            if (cache.state(block) == null) return block
        }
        return null
    }

    private fun wrappedBlock(block: Int): Int {
        val n = timeline.blockCount
        val mod = Math.floorMod(block, n)
        return mod
    }

    private fun decodeBlock(block: Int): Boolean {
        if (!cache.markPending(block)) return false
        val startUs = timeline.blockDecodeStartUs(block)
        val endUs = timeline.blockDecodeEndUs(block)
        val expectedFrames = timeline.blockFrames(block)
        var error: String? = null
        var segment: com.zjr.hesimusic.loop.analysis.Pcm16Segment? = null
        try {
            synchronized(lock) { decodeCalls += 1L }
            segment = provider.decode(startUs, endUs)
        } catch (e: Exception) {
            error = "decode block " + block + " [" + startUs + "," + endUs + ") failed: " +
                (e.message ?: e.javaClass.simpleName)
        }

        synchronized(lock) {
            if (released) {
                cache.markFailed(block)
                return true
            }
            val result = segment
            if (error != null || result == null) {
                cache.markFailed(block)
                logger(error ?: ("decode returned null at block " + block))
                return true
            }
            if (result.sampleRate != timeline.sampleRate || result.channels != timeline.channels) {
                cache.markFailed(block)
                logger(
                    "format mismatch at block " + block +
                        ": sr=" + result.sampleRate + "/" + timeline.sampleRate +
                        " ch=" + result.channels + "/" + timeline.channels,
                )
                return true
            }
            if (result.frames < expectedFrames) {
                cache.markFailed(block)
                logger(
                    "short decode at block " + block +
                        ": got=" + result.frames + " want=" + expectedFrames,
                )
                return true
            }
            val trimmed = result.takeFrames(expectedFrames)
            val expectedBytes = (expectedFrames * timeline.bytesPerFrame).toInt()
            if (trimmed.data.size != expectedBytes) {
                cache.markFailed(block)
                logger(
                    "byte size mismatch at block " + block +
                        ": got=" + trimmed.data.size + " want=" + expectedBytes,
                )
                return true
            }
            cache.put(
                PcmBlock(
                    index = block,
                    startFrame = timeline.blockStartFrame(block),
                    frames = expectedFrames.toInt(),
                    data = trimmed.data,
                ),
            )
            logger(
                "decode block=" + block + " frames=" + expectedFrames +
                    " calls=" + decodeCalls + " cacheBytes=" + cache.cachedBytes(),
            )
            return true
        }
    }

    private fun framesToUs(frames: Long): Long =
        frames * 1_000_000L / timeline.sampleRate

    companion object {
        /** 当前 + 预取 2 块 = 最多 3 块窗口。 */
        const val DEFAULT_PREFETCH_BLOCKS: Int = 2
    }
}
