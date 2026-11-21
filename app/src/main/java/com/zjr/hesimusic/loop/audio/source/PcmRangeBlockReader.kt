package com.zjr.hesimusic.loop.audio.source

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

    /** seek 到绝对媒体帧 [absFrame]（钳在 [startFrame,endFrameExclusive)）。 */
    fun seekToFrame(absFrame: Long) {
        synchronized(lock) {
            val target = absFrame.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
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

    /** 触发后台解码当前块 + 预取后续块。 */
    fun kick() {
        if (released) return
        if (!running.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    var guard = 0
                    while (!released && guard < prefetchBlocks * 2) {
                        if (!needsDecode()) break
                        if (!decodeOne()) break
                        guard += 1
                    }
                } finally {
                    running.set(false)
                    if (!released && needsDecode()) kick()
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
    }

    private fun currentBlockReadyLocked(): Boolean {
        val frame = cursor.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
        return cache.state(blockIndexForFrame(frame)) == PcmBlockCache.State.READY
    }

    private fun needsDecode(): Boolean {
        if (released) return false
        synchronized(lock) {
            val frame = cursor.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
            val current = blockIndexForFrame(frame)
            if (cache.state(current) != PcmBlockCache.State.READY) return true
            for (d in 1..prefetchBlocks) {
                val index = current + d
                if (index >= blockCount) break
                val state = cache.state(index)
                if (state == null || state == PcmBlockCache.State.PENDING) return true
            }
            return false
        }
    }

    private fun decodeOne(): Boolean = synchronized(decodeLock) {
        if (released) return false
        val block = synchronized(lock) {
            val frame = cursor.coerceIn(startFrame, (endFrameExclusive - 1L).coerceAtLeast(startFrame))
            val current = blockIndexForFrame(frame)
            if (cache.state(current) == PcmBlockCache.State.FAILED) {
                // 失败块跳过：游标推进到下一块，避免永远卡住。
                val nextFrame = blockStartFrame(current + 1)
                cursor = nextFrame.coerceAtMost(endFrameExclusive)
                lock.notifyAll()
                return@synchronized null
            }
            if (cache.state(current) == null) current else {
                (1..prefetchBlocks).firstOrNull { d ->
                    val index = current + d
                    index < blockCount && cache.state(index) == null
                }
            }
        } ?: return false
        if (!cache.markPending(block)) return false

        val startFrame = blockStartFrame(block)
        val expectedFrames = blockFrames(block)
        val startUs = frameUs(startFrame)
        val endUs = frameUs(startFrame + expectedFrames)
        val decodeStartUs = (startUs - DECODE_PREROLL_US).coerceAtLeast(0L)
        val dropFrames = Pcm16Frames.frameAtOrAfterUs(startUs - decodeStartUs, sampleRate)
        val startedMs = System.currentTimeMillis()
        var segment: Pcm16Segment? = null
        var error: String? = null
        try {
            segment = provider.decode(decodeStartUs, endUs)
        } catch (t: Throwable) {
            error = "decode block " + block + " [" + startUs + "," + endUs + ") failed: " +
                (t.message ?: t.javaClass.simpleName)
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
            logger(
                "PcmRangeBlock decode done block=" + block + " frames=" + expectedFrames +
                    " ms=" + (System.currentTimeMillis() - startedMs) +
                    " decoded=" + decodedBlocks,
            )
            lock.notifyAll()
            true
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
