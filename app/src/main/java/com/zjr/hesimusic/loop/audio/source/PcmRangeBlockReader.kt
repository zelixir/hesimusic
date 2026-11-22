package com.zjr.hesimusic.loop.audio.source

import android.os.Process
import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前奏/尾奏的**按需 1 秒块读取器**（issue 00028，纯 JVM）。
 *
 * 环带仍只装 [A,B)；[0,A) 与 (B,dur] 不整段进内存，而是按播放头附近 1 秒块异步解码，
 * 用 [PcmBlockCache] 做小窗口缓存（默认上限 8MB，远小于整曲）。
 *
 * 线程模型：
 * - [read]/[seekToFrame]/[awaitCurrentReady] 由 media3 loading/extractor 线程调用；
 * - 解码在单线程 executor 上（[kick]），同一 reader 内串行；
 * - 缓存线程安全；seek 只改 cursor，不被正在进行的块解码阻塞。
 *
 * 交界无缝要求（Lead 补充第 2 点）：[prefetchBlocks] 默认 3，且 seek/read 每次推进后都会
 * [kick]；前奏在到达 A 之前 1~2 秒就已经把 A 之前的块解好，A 之后由已经预填的环带接手。
 */
class PcmRangeBlockReader(
    private val provider: PcmRangeProvider,
    /** 绝对媒体起始帧（含）。 */
    val startFrame: Long,
    /** 绝对媒体结束帧（不含）。 */
    val endFrameExclusive: Long,
    private val sampleRate: Int,
    private val channels: Int,
    maxCacheBytes: Long = DEFAULT_MAX_BYTES,
    private val prefetchBlocks: Int = DEFAULT_PREFETCH_BLOCKS,
    /** false = 创建时不预热，等首次 seek/read 再解码（尾奏 reader 用，避免无事时空转）。 */
    prefetchOnInit: Boolean = true,
    /**
     * 块解码前导（裁掉后对齐块首）。
     *
     * 00029：常驻会话 provider（[PcmResidentRangeProvider]）自己负责 seek/前导，这里必须传 0，
     * 让每块只解 1.0s 且与上一块无缝衔接；旧的一次性 provider 仍用 250ms 预卷。
     */
    private val decodePrerollUs: Long = DECODE_PREROLL_US,
    private val logger: (String) -> Unit = {},
) {

    data class Read(
        /** 本次首帧的绝对媒体帧号。 */
        val startFrame: Long,
        val frames: Int,
        val data: ByteArray,
    )

    private val bytesPerFrame = channels * 2
    private val framesPerBlock = sampleRate
    private val logId = "PcmRangeBlock[start=" + startFrame + "]"
    private val totalFrames = (endFrameExclusive - startFrame).coerceAtLeast(0L)
    private val blockCount = ((totalFrames + framesPerBlock - 1L) / framesPerBlock).toInt()

    private val cache = PcmBlockCache(
        key = PcmBlockCacheKey(
            songId = 0L,
            loopStartSamples = startFrame,
            loopEndSamples = endFrameExclusive,
            sampleRate = sampleRate,
            channels = channels,
            loopStartUs = frameUs(startFrame),
            loopEndUs = frameUs(endFrameExclusive),
        ),
        framesPerBlock = framesPerBlock,
        bytesPerFrame = bytesPerFrame,
        maxBytes = maxCacheBytes,
    )

    private val lock = Object()
    private val decodeLock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PcmRangeBlock").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile
    private var cursor: Long = startFrame

    @Volatile
    private var released = false

    @Volatile
    private var lastError: String? = null

    /** 诊断：已解码块数。 */
    @Volatile
    var decodedBlocks: Long = 0L
        private set

    /** 诊断：块边界累计短帧数（期望 30s 内 0；非 0 说明块拼接丢帧）。 */
    @Volatile
    var cumulativeShortfallFrames: Long = 0L
        private set

    /** seek 意图预热的目标块；-1 = 无。只解块，不改播放游标。 */
    @Volatile
    private var prewarmBlock: Int = -1

    init {
        if (prefetchOnInit && totalFrames > 0L) kick()
    }

    fun totalFrames(): Long = totalFrames

    fun currentFrame(): Long = cursor

    fun error(): String? = lastError

    fun readyBlockCount(): Int = cache.activeBlockCount()

    /**
     * 从当前 cursor 读最多 [maxFrames] 帧；未就绪返回 null（调用方应 [kick] 并等待）。
     */
    fun read(maxFrames: Int): Read? = synchronized(lock) {
        if (released || maxFrames <= 0 || totalFrames <= 0L) return null
        val frame = cursor.coerceIn(startFrame, endFrameExclusive)
        if (frame >= endFrameExclusive) return null
        val blockIndex = blockIndexForFrame(frame)
        cache.playheadBlock = blockIndex
        val block = cache.get(blockIndex) ?: return null
        val offset = (frame - block.startFrame).toInt()
        if (offset < 0 || offset >= block.frames) return null
        val frames = minOf(
            maxFrames.toLong(),
            (block.frames - offset).toLong(),
            endFrameExclusive - frame,
        ).toInt()
        if (frames <= 0) return null
        val startBytes = offset * bytesPerFrame
        val endBytes = startBytes + frames * bytesPerFrame
        val data = block.data.copyOfRange(startBytes, endBytes)
        cursor = frame + frames
        cache.trimToBudget(blockIndexForFrame(cursor.coerceAtMost(endFrameExclusive - 1L)))
        kick()
        Read(startFrame = frame, frames = frames, data = data)
    }

    /**
     * seek 意图预热：提前解 [absFrame] 所在块，但**不改 cursor**。
     * 真正的 seek 落地后 cursor 指到同一块时会直接命中缓存。
     */
    fun prewarmFrame(absFrame: Long) {
        if (released || totalFrames <= 0L) return
        val target = absFrame.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
        val block = blockIndexForFrame(target)
        if (cache.state(block) == PcmBlockCache.State.READY) return
        synchronized(lock) { prewarmBlock = block }
        kick()
    }

    /**
     * 只读缓存，不推进 cursor：后台校准用（00029 A 交界）。
     * 目标区间必须落在同一个 1s 块内；块尚未 READY 时最多等 [timeoutMs]，期间会 kick 解码。
     */
    fun copyCachedRange(startAbsFrame: Long, count: Int, timeoutMs: Long): ByteArray? {
        if (released || count <= 0) return null
        val start = startAbsFrame.coerceIn(startFrame, endFrameExclusive)
        val end = (start + count).coerceAtMost(endFrameExclusive)
        val frames = (end - start).toInt()
        if (frames <= 0) return null
        val blockIndex = blockIndexForFrame(start)
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        synchronized(lock) {
            while (!released) {
                val block = cache.get(blockIndex)
                if (block != null &&
                    start >= block.startFrame &&
                    end <= block.startFrame + block.frames
                ) {
                    val from = ((start - block.startFrame) * bytesPerFrame).toInt()
                    val to = from + frames * bytesPerFrame
                    return block.data.copyOfRange(from, to)
                }
                if (cache.state(blockIndex) == PcmBlockCache.State.FAILED) return null
                kick()
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) return null
                try {
                    lock.wait(left.coerceAtMost(10L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    /** seek 到绝对媒体帧 [absFrame]（钳在 [startFrame,endFrameExclusive)）。 */
    fun seekToFrame(absFrame: Long) {
        synchronized(lock) {
            val target = absFrame.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
            prewarmBlock = -1
            cursor = target
            val block = blockIndexForFrame(target)
            cache.playheadBlock = block
            cache.retryFailed(block)
            cache.trimToBudget(block)
            lock.notifyAll()
        }
        kick()
    }

    /** 等当前 cursor 所在块就绪（或超时/失败）。 */
    fun awaitCurrentReady(timeoutMs: Long): Boolean {
        synchronized(lock) {
            if (released || totalFrames <= 0L) return false
            if (currentBlockReadyLocked()) return true
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!released && !currentBlockReadyLocked()) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0L) break
                try {
                    lock.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            return currentBlockReadyLocked()
        }
    }

    /**
     * 触发后台顺序解码：当前块 → 后 1..prefetchBlocks 块。
     *
     * 00029：这里**按当前游标顺序选块**，不再“缓存里缺哪块就跳去解哪块”。
     * 旧逻辑在 seek 后加载线程瞬间读完缓存、游标已跳到 12s 时，仍可能根据旧游标
     * 去解 block 1；常驻会话被这种跳读打断，下一块又被迫 seek，块边界样本对齐
     * 因此每 1s 漂移一次（爆音根因）。
     */
    fun kick() {
        if (released) return
        if (!running.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    var guard = 0
                    while (!released && guard <= prefetchBlocks + 1) {
                        val block = takePrewarmBlock() ?: selectBlockAhead() ?: break
                        if (!decodeBlock(block)) break
                        guard += 1
                    }
                } finally {
                    running.set(false)
                    if (!released && (prewarmBlock >= 0 || hasUnstartedBlockAhead())) kick()
                }
            }
        } catch (_: Throwable) {
            running.set(false)
        }
    }

    fun release() {
        released = true
        synchronized(lock) {
            cache.clear()
            lock.notifyAll()
        }
        executor.shutdownNow()
        // 常驻解码会话必须显式关闭；一次性 provider（PcmDecoderRangeProvider）由调用方共享，不能关。
        (provider as? ReleasablePcmRangeProvider)?.release()
    }

    private fun currentBlockReadyLocked(): Boolean {
        val frame = cursor.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
        return cache.state(blockIndexForFrame(frame)) == PcmBlockCache.State.READY
    }

    private fun takePrewarmBlock(): Int? = synchronized(lock) {
        val block = prewarmBlock
        prewarmBlock = -1
        if (block < 0 || block >= blockCount) return null
        if (cache.state(block) == null) block else null
    }

    /** 当前游标起、prefetch 窗口内第一个尚未开始解码的块；PENDING 表示等正在跑的任务。 */
    private fun selectBlockAhead(): Int? = synchronized(lock) {
        if (released) return null
        val frame = cursor.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
        val current = blockIndexForFrame(frame)
        // 当前块失败时跳过（旧行为），避免永远卡在坏块上。
        if (cache.state(current) == PcmBlockCache.State.FAILED) {
            cursor = blockStartFrame(current + 1).coerceAtMost(endFrameExclusive)
            lock.notifyAll()
            return null
        }
        for (d in 0..prefetchBlocks) {
            val index = current + d
            if (index >= blockCount) break
            when (cache.state(index)) {
                null -> return index
                PcmBlockCache.State.PENDING -> return null
                PcmBlockCache.State.FAILED -> Unit
                PcmBlockCache.State.READY -> Unit
            }
        }
        return null
    }

    private fun hasUnstartedBlockAhead(): Boolean = synchronized(lock) {
        if (released) return false
        val frame = cursor.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
        val current = blockIndexForFrame(frame)
        for (d in 0..prefetchBlocks) {
            val index = current + d
            if (index >= blockCount) break
            if (cache.state(index) == null) return true
        }
        false
    }

    private fun decodeBlock(block: Int): Boolean = synchronized(decodeLock) {
        if (released) return false
        if (!cache.markPending(block)) return false
        // 区外 seek 的感知延迟 = 单块解码耗时。前奏/尾奏解码是用户正在等的音频，
        // 用 AUDIO 优先级而不是默认 nice，避免被后台线程挤到小核（真机 1s 块 60~150ms）。
        // JVM 单测里 android.os.Process 是抛 "Stub!" 的桩，必须整体兜住。
        val tid = try { Process.myTid() } catch (_: Throwable) { -1 }
        val oldPriority = if (tid >= 0) {
            try { Process.getThreadPriority(tid) } catch (_: Throwable) { 0 }
        } else {
            0
        }
        if (tid >= 0) {
            try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}
        }
        val startFrame = blockStartFrame(block)
        val expectedFrames = blockFrames(block)
        val startUs = frameUs(startFrame)
        val endUs = frameUs(startFrame + expectedFrames)
        val decodeStartUs = (startUs - decodePrerollUs).coerceAtLeast(0L)
        val dropFrames = Pcm16Frames.frameAtOrAfterUs(startUs - decodeStartUs, sampleRate)
        val startedMs = System.currentTimeMillis()
        var segment: Pcm16Segment? = null
        var error: String? = null
        val isLastBlock = block == blockCount - 1
        val shortProvider = if (isLastBlock) provider as? PcmShortRangeProvider else null
        try {
            segment = if (shortProvider != null) {
                // 一次解码拿到真实可用帧（越过编码末尾不再抛异常）；不足部分下面静音补齐。
                val short = shortProvider.decodeAllowShort(decodeStartUs, endUs)
                logger(
                    "PcmRangeBlock last block allowShort block=" + block +
                        " frames=" + short.frames + " expected=" + expectedFrames +
                        " ms=" + (System.currentTimeMillis() - startedMs),
                )
                short
            } else {
                provider.decode(decodeStartUs, endUs)
            }
        } catch (t: Throwable) {
            error = "decode block " + block + " [" + startUs + "," + endUs + ") failed: " +
                (t.message ?: t.javaClass.simpleName)
        }
        if (segment == null && error != null && startUs == 0L && decodeStartUs == 0L && !released) {
            // 文件首个块的真机故障（issue 00029）：MP3 解码器从 0 seek 后首个输出 PTS
            // ≈40ms（编码器 priming），PcmDecoder 按"解码起点晚于区间起点"抛异常，
            // 旧逻辑把整块标记 FAILED 并把游标推进到 block 1 —— 进入无缝循环时
            // 前 1 秒被静默跳过，且首块永远解不出来。
            // 这里按异常里给出的 anchor 重解一次，把 [0, anchor) 补静音，只丢 priming 段。
            val anchorUs = ANCHOR_US_REGEX.find(error)?.groupValues?.get(1)?.toLongOrNull()
            if (anchorUs != null && anchorUs > 0L) {
                val leadFrames = Pcm16Frames.frameAtOrAfterUs(anchorUs, sampleRate)
                if (leadFrames in 1..sampleRate) {
                    try {
                        val rest = provider.decode(anchorUs, endUs)
                        if (rest.sampleRate == sampleRate && rest.channels == channels) {
                            val needFrames = leadFrames + expectedFrames
                            val needBytes = (needFrames * bytesPerFrame).toInt()
                            val data = ByteArray(needBytes)
                            System.arraycopy(
                                rest.data,
                                0,
                                data,
                                (leadFrames * bytesPerFrame).toInt(),
                                minOf(rest.data.size, needBytes - (leadFrames * bytesPerFrame).toInt()),
                            )
                            segment = Pcm16Segment(
                                data = data,
                                sampleRate = sampleRate,
                                channels = channels,
                                frames = needFrames,
                            )
                            error = null
                            logger(
                                "PcmRangeBlock first-block lead silence frames=" + leadFrames +
                                    " anchorUs=" + anchorUs + " block=" + block,
                            )
                        }
                    } catch (t: Throwable) {
                        error = "decode block " + block + " lead-retry failed: " +
                            (t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        }
        if (segment == null && block == blockCount - 1 && !released) {
            // 真实 PcmDecoder 在"请求区间越过最后一个编码样本"时会抛
            // "环缓冲解码不足: 需要 X 帧,实际 Y 帧"（而不是返回短段）。
            // 对最后一个块逐步缩短请求终点重试，保留尽可能多的真实音频；
            // 仍失败（整块都在文件尾之后）则按整块静音，交给下面的短块补齐逻辑。
            for (trimUs in LAST_BLOCK_TRIM_STEPS_US) {
                val candidateEnd = endUs - trimUs
                if (candidateEnd <= startUs) break
                try {
                    val candidate = provider.decode(decodeStartUs, candidateEnd)
                    if (candidate.frames > 0L) {
                        segment = candidate
                        error = null
                        logger(
                            "PcmRangeBlock last block trimmed request block=" + block +
                                " trimUs=" + trimUs + " endUs=" + candidateEnd +
                                " frames=" + candidate.frames,
                        )
                        break
                    }
                } catch (t: Throwable) {
                    error = "decode last block " + block + " trimmed " + trimUs +
                        "us failed: " + (t.message ?: t.javaClass.simpleName)
                }
            }
            if (segment == null) {
                // 最后一块完全落在真实音频之后：整块静音补齐，不能让 read 永久返回 null。
                segment = Pcm16Segment(ByteArray(0), sampleRate, channels, 0L)
                error = null
                logger(
                    "PcmRangeBlock last block unavailable -> pad silence block=" + block +
                        " expectedFrames=" + expectedFrames,
                )
            }
        }
        try {
        synchronized(lock) {
            if (released) {
                cache.markFailed(block)
                return true
            }
            val result = segment
            if (error != null || result == null) {
                cache.markFailed(block)
                lastError = error ?: "decode returned null at block $block"
                logger(lastError!!)
                lock.notifyAll()
                return true
            }
            if (result.sampleRate != sampleRate || result.channels != channels) {
                cache.markFailed(block)
                lastError = "format mismatch block=$block sr=" + result.sampleRate +
                    "/" + sampleRate + " ch=" + result.channels + "/" + channels
                logger(lastError!!)
                lock.notifyAll()
                return true
            }
            val expectedBytes = (expectedFrames * bytesPerFrame).toInt()
            val available = if (result.frames > dropFrames) {
                (result.frames - dropFrames).coerceAtMost(expectedFrames)
            } else {
                0L
            }
            val shortfall = expectedFrames - available
            // 容器时长可能略晚于最后一个编码样本（整曲 B/尾奏末常见几十~几百帧）。
            // 短解码不重试（否则会无限重开 codec）；在最后一个块用静音补齐，
            // 只有缺口超过 1 秒才认为是真错误。
            if (shortfall > 0L && (shortfall > sampleRate || block != blockCount - 1)) {
                cache.markFailed(block)
                lastError = "short decode block=$block got=" + result.frames +
                    " want=" + (dropFrames + expectedFrames) +
                    " shortfall=" + shortfall
                logger(lastError!!)
                lock.notifyAll()
                return true
            }
            val trimmed = result.dropFrames(dropFrames).takeFrames(available)
            val data = if (shortfall > 0L) {
                val padded = ByteArray(expectedBytes)
                System.arraycopy(trimmed.data, 0, padded, 0, trimmed.data.size)
                logger(
                    "PcmRangeBlock pad silence block=" + block +
                        " available=" + available + " expected=" + expectedFrames +
                        " shortfall=" + shortfall,
                )
                padded
            } else {
                trimmed.data
            }
            if (data.size != expectedBytes) {
                cache.markFailed(block)
                lastError = "byte size mismatch block=$block got=" + data.size +
                    " want=" + expectedBytes
                logger(lastError!!)
                lock.notifyAll()
                return true
            }
            cache.put(
                PcmBlock(
                    index = block,
                    startFrame = startFrame,
                    frames = expectedFrames.toInt(),
                    data = data,
                ),
            )
            decodedBlocks += 1L
            if (shortfall > 0L) cumulativeShortfallFrames += shortfall
            if (decodedBlocks % 30L == 0L) {
                logger(
                    logId + " frameAccounting blocks=" + decodedBlocks +
                        " shortfallTotal=" + cumulativeShortfallFrames +
                        " cursor=" + cursor,
                )
            }
            logger(
                logId + " decode done block=" + block + " frames=" + expectedFrames +
                    " ms=" + (System.currentTimeMillis() - startedMs) +
                    " decoded=" + decodedBlocks,
            )
            lock.notifyAll()
            true
        }
        } finally {
            if (tid >= 0) {
                try { Process.setThreadPriority(tid, oldPriority) } catch (_: Throwable) {}
            }
        }
    }

    private fun blockIndexForFrame(frame: Long): Int {
        if (totalFrames <= 0L) return 0
        return ((frame.coerceAtLeast(startFrame) - startFrame) / framesPerBlock).toInt()
            .coerceIn(0, blockCount - 1)
    }

    private fun blockStartFrame(block: Int): Long = startFrame + block.toLong() * framesPerBlock

    private fun blockFrames(block: Int): Long =
        minOf(framesPerBlock.toLong(), endFrameExclusive - blockStartFrame(block))

    private fun frameUs(frame: Long): Long =
        if (frame <= 0L) 0L else frame * 1_000_000L / sampleRate

    companion object {
        /** 1 秒块 × 2MB：约 11 秒立体声 44.1kHz 的 PCM（前后奏各一份，合计 ≤4MB）。 */
        const val DEFAULT_MAX_BYTES: Long = 2L * 1024L * 1024L

        /** 当前块 + 后 2 块；保证前奏到 A 前 1~2 秒已预热。 */
        const val DEFAULT_PREFETCH_BLOCKS: Int = 2

        /** 解码预热，裁掉后避免 MediaCodec priming 污染块首。 */
        const val DECODE_PREROLL_US: Long = 250_000L

        /** PcmDecoder 的"首输出晚于请求起点"异常里带 anchorUs。 */
        val ANCHOR_US_REGEX: Regex = Regex("anchorUs=(\\d+)")

        /**
         * 最后一个块解码"短于请求区间"时的回退终点缩减量。
         * 真实 PcmDecoder 是抛异常而非返回短段，必须缩短请求才能拿到结尾前的真实音频。
         */
        val LAST_BLOCK_TRIM_STEPS_US: LongArray = longArrayOf(
            1_000L, 5_000L, 10_000L, 20_000L, 50_000L, 100_000L,
            200_000L, 300_000L, 400_000L, 500_000L, 600_000L,
            700_000L, 800_000L, 900_000L,
        )
    }
}
