package com.zjr.hesimusic.loop.audio.source

/**
 * 00029：**轻量预热**的前奏首块缓存（结果页 Ready 用）。
 *
 * 结果页与预览 seek 共用设备/解码器：不能再像菜单那样建 5.7MB 环带 + 常驻解码会话。
 * 这里只保留前奏第一块 PCM（44.1kHz 立体声约 176KB，2 块 <360KB），
 * 建块时用的解码会话在块 ready 后立刻 release——**不建环、不常驻**。
 *
 * 用户点「确认并保存」时 [RingBandSource] 仍按原逻辑建完整源（9~20ms）；
 * 若同一窗口的块缓存命中，[PcmRangeBlockReader] 构造时直接种块，首声不再等 174~220ms。
 * 块由轻量预热用**同样的 resident view + decodePreroll=0** 解出，字节与正式源一致。
 */
internal object PreludeBlockPrewarm {

    data class Key(
        val songId: Long,
        val loopStartUs: Long,
        val loopEndUs: Long,
        val sampleRate: Int,
        val channels: Int,
    )

    private val lock = Any()
    private var slot: Pair<Key, List<PcmBlock>>? = null

    fun keyOf(timeline: LoopingPcmTimeline): Key = Key(
        songId = timeline.songId,
        loopStartUs = timeline.loopStartUs,
        loopEndUs = timeline.loopEndUs,
        sampleRate = timeline.sampleRate,
        channels = timeline.channels,
    )

    fun put(key: Key, blocks: List<PcmBlock>) {
        if (blocks.isEmpty()) return
        synchronized(lock) { slot = key to blocks }
    }

    /** 建完整源时取走（消费），避免同一个块被第二个源重复使用。 */
    fun take(key: Key): List<PcmBlock>? = synchronized(lock) {
        val current = slot ?: return null
        if (current.first != key) return null
        slot = null
        current.second
    }

    fun remove(key: Key): List<PcmBlock>? = synchronized(lock) {
        if (slot?.first != key) return null
        val blocks = slot!!.second
        slot = null
        blocks
    }

    fun clear() {
        synchronized(lock) { slot = null }
    }

    fun has(key: Key): Boolean = synchronized(lock) { slot?.first == key }

    /** 仅供单测隔离。 */
    fun resetForTest() = clear()
}
