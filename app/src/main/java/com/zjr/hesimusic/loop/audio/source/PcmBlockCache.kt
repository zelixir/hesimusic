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
 * - 预算按进程堆自适应（maxMemory/10，夹 16~32MB），绝不使用固定 128MB；
 * - **插入前**先淘汰离播放头最远的 READY 块，插入后再检查一次；
 * - 当前播放块（[playheadBlock]）不会被淘汰；即使单块大于预算也至少保留它。
 */
class PcmBlockCache(
    val key: PcmBlockCacheKey,
    val framesPerBlock: Int,
    val bytesPerFrame: Int,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
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

    /** 安装解码成功的块；插入前先腾空间，插入后再按预算淘汰。 */
    fun put(block: PcmBlock) {
        synchronized(lock) {
            val expectedBytes = framesPerBlock * bytesPerFrame
            require(block.data.size == block.frames * bytesPerFrame) {
                "block data size mismatch: ${block.data.size} != " +
                    "${block.frames} * $bytesPerFrame"
            }
            require(block.data.size <= expectedBytes || block.frames < framesPerBlock) {
                "block data larger than a full block: ${block.data.size} > $expectedBytes"
            }
            evictForIncomingLocked(block)
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

    fun cachedBytes(): Long = synchronized(lock) { readyBytesLocked() }

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

    private fun readyBytesLocked(): Long = entries.values.asSequence()
        .filter { it.state == State.READY }
        .sumOf { (it.block?.bytes ?: 0).toLong() }

    private fun evictForIncomingLocked(block: PcmBlock) {
        val existing = entries[block.index]?.block?.bytes ?: 0
        var total = readyBytesLocked()
        val incoming = (block.bytes - existing).coerceAtLeast(0)
        while (total + incoming > budgetBytes) {
            val victim = selectVictimLocked(excludeIndex = block.index) ?: break
            total -= (entries[victim]?.block?.bytes ?: 0).toLong()
            entries.remove(victim)
        }
    }

    private fun evictLocked() {
        var total = readyBytesLocked()
        while (total > budgetBytes) {
            val victim = selectVictimLocked(excludeIndex = -1) ?: break
            total -= (entries[victim]?.block?.bytes ?: 0).toLong()
            entries.remove(victim)
        }
    }

    private fun selectVictimLocked(excludeIndex: Int): Int? {
        var victim: Int? = null
        var bestDistance = -1
        var bestAccess = Long.MAX_VALUE
        for ((index, entry) in entries) {
            if (entry.state != State.READY) continue
            if (index == playheadBlock || index == excludeIndex) continue
            val distance = abs(index - playheadBlock)
            if (distance > bestDistance ||
                (distance == bestDistance && entry.lastAccess < bestAccess)
            ) {
                victim = index
                bestDistance = distance
                bestAccess = entry.lastAccess
            }
        }
        return victim
    }

    companion object {
        const val MIN_BYTES: Long = 16L * 1024L * 1024L
        const val MAX_BYTES: Long = 32L * 1024L * 1024L

        /** 402MB 堆 → 32MB；低堆至少 16MB，最多 32MB。 */
        fun maxBytesForHeap(maxMemoryBytes: Long): Long =
            (maxMemoryBytes / 10L).coerceIn(MIN_BYTES, MAX_BYTES)

        /** 进程默认预算由当前堆决定，不再是固定 128MB。 */
        val DEFAULT_MAX_BYTES: Long
            get() = maxBytesForHeap(Runtime.getRuntime().maxMemory())
    }
}
