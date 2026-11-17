package com.zjr.hesimusic.loop.audio.source

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 一个已解码的 1 秒块（PCM16 交错、小端）。 */
class PcmBlock(
    val index: Int,
    val startFrame: Long,
    val frames: Int,
    val data: ByteArray,
) {
    init {
        require(index >= 0) { "index must be >= 0" }
        require(frames > 0) { "frames must be > 0" }
        require(data.isNotEmpty() && data.size % frames == 0) {
            "data size must be a multiple of frames"
        }
    }

    val bytes: Int get() = data.size
}

/**
 * 线程安全的 PCM 块缓存（纯 JVM）。
 *
 * - 块号按 [PcmBlockCacheKey] + 1 秒网格计算；A/B/采样率/声道任一变化 = 新 key；
 * - 块状态机：PENDING（解码中）→ READY / FAILED；
 * - 预算内保留所有块；超预算时**优先淘汰离播放头最远的块**，同距离按 LRU；
 * - 当前播放块（[playheadBlock]）不会被淘汰；即使单块大于预算也至少保留它，
 *   保证不会因为预算配置过小直接 OOM 或死锁。
 */
class PcmBlockCache(
    val key: PcmBlockCacheKey,
    val framesPerBlock: Int,
    val bytesPerFrame: Int,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    enum class State {
        PENDING,
        READY,
        FAILED,
    }

    private class Entry(
        var state: State,
        var block: PcmBlock? = null,
        var lastAccess: Long = 0L,
    )

    init {
        require(framesPerBlock > 0) { "framesPerBlock must be > 0" }
        require(bytesPerFrame > 0) { "bytesPerFrame must be > 0" }
        require(maxBytes > 0L) { "maxBytes must be > 0" }
    }

    private val lock = Any()
    private val entries = ConcurrentHashMap<Int, Entry>()
    private var accessCounter = 0L

    /** 当前播放头块；淘汰时保护它。 */
    @Volatile
    var playheadBlock: Int = 0

    /** 实际预算：至少容纳一个块，避免配置过小导致永远无法起播。 */
    val budgetBytes: Long = maxOf(maxBytes, framesPerBlock.toLong() * bytesPerFrame)

    fun state(index: Int): State? = synchronized(lock) { entries[index]?.state }

    /** 读取一个 READY 块；未命中/未就绪返回 null。 */
    fun get(index: Int): PcmBlock? = synchronized(lock) {
        val entry = entries[index] ?: return null
        if (entry.state != State.READY) return null
        entry.lastAccess = ++accessCounter
        entry.block
    }

    /** 原子地标记块为 PENDING；已存在（PENDING/READY/FAILED）返回 false。 */
    fun markPending(index: Int): Boolean = synchronized(lock) {
        if (entries.containsKey(index)) return false
        entries[index] = Entry(State.PENDING, lastAccess = ++accessCounter)
        true
    }

    /** 安装解码成功的块，然后按预算淘汰。 */
    fun put(block: PcmBlock) {
        synchronized(lock) {
            val expectedBytes = framesPerBlock * bytesPerFrame
            require(block.data.size <= expectedBytes || block.frames < framesPerBlock) {
                "block data larger than a full block: ${block.data.size} > $expectedBytes"
            }
            entries[block.index] = Entry(
                state = State.READY,
                block = block,
                lastAccess = ++accessCounter,
            )
            evictLocked()
        }
    }

    fun markFailed(index: Int) {
        synchronized(lock) {
            entries[index] = Entry(State.FAILED, lastAccess = ++accessCounter)
        }
    }

    /** 允许 seek 到失败的块时重试一次。 */
    fun retryFailed(index: Int) {
        synchronized(lock) {
            if (entries[index]?.state == State.FAILED) entries.remove(index)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            accessCounter = 0L
        }
    }

    /** 只返回 READY 块号（诊断/测试用）。 */
    fun readyIndices(): Set<Int> = synchronized(lock) {
        entries.filterValues { it.state == State.READY }.keys.toSet()
    }

    fun cachedBytes(): Long = synchronized(lock) {
        entries.values.asSequence()
            .filter { it.state == State.READY }
            .sumOf { (it.block?.bytes ?: 0).toLong() }
    }

    fun activeBlockCount(): Int = synchronized(lock) {
        entries.values.count { it.state == State.READY }
    }

    fun pendingBlockCount(): Int = synchronized(lock) {
        entries.values.count { it.state == State.PENDING }
    }

    fun failedBlockCount(): Int = synchronized(lock) {
        entries.values.count { it.state == State.FAILED }
    }

    /** 设置播放头并尝试把 READY 块压回预算内。 */
    fun trimToBudget(playhead: Int) {
        synchronized(lock) {
            playheadBlock = playhead
            evictLocked()
        }
    }

    private fun evictLocked() {
        var total = entries.values.asSequence()
            .filter { it.state == State.READY }
            .sumOf { (it.block?.bytes ?: 0).toLong() }
        if (total <= budgetBytes) return

        val candidates = entries.entries.asSequence()
            .filter { (index, entry) -> entry.state == State.READY && index != playheadBlock }
            .sortedWith(
                compareByDescending<Map.Entry<Int, Entry>> { abs(it.key - playheadBlock) }
                    .thenBy { it.value.lastAccess },
            )
            .toList()
        for ((index, entry) in candidates) {
            if (total <= budgetBytes) break
            total -= (entry.block?.bytes ?: 0).toLong()
            entries.remove(index)
        }
    }

    companion object {
        /** 默认 128MB；48kHz/2ch/16bit 约 682 块（≈11 分钟）。 */
        const val DEFAULT_MAX_BYTES: Long = 128L * 1024L * 1024L
    }
}
