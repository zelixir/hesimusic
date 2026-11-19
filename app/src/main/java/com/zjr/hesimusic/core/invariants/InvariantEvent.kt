package com.zjr.hesimusic.core.invariants

/**
 * 运行期不变量（纯 Kotlin，零 Android 依赖，可 JVM 单测）。
 *
 * 为什么需要：状态机/影子只比较「页面投影 vs 会话投影」，两者可以一起错——
 * - 00020：页面投影说暂停、影子也说暂停，但 AudioSink 仍每秒写 96ms 音频；
 * - 00021：页面投影说循环（A + floorMod(T,L)）、时间线 item 也带 loop tag，
 *   但真正出声的 MediaSource 还是普通 MP3。
 *
 * 本包把「输出侧事实」（实际写进 AudioSink 的帧数）与「音源侧事实」（当前 item 的 tag
 * + MediaSourceFactory 最近一次 select）直接拿来做判定，因此不依赖投影/影子是否自洽。
 *
 * 判定器只做纯逻辑；采样、Log.e、写 /state 由 debug 源集的 PlaybackInvariantMonitor 负责。
 */
object InvariantCode {

    /** 播放器暂停（playWhenReady=false），但 sink 仍在写音频帧。 */
    const val PAUSED_BUT_SINK_WROTE: String = "paused-but-sink-wrote"

    /** 播放器在播放（playWhenReady=true / READY / 非 buffering），但连续 1s 一帧都没写。 */
    const val PLAYING_BUT_SINK_SILENT: String = "playing-but-sink-silent"

    /** 会话/页面按循环投影，但当前 item 没有 loop tag，或工厂最近一次 select 不是 loop。 */
    const val PROJECTION_LOOP_SOURCE_PLAIN: String = "projection-loop-source-plain"

    /** 会话/页面不投影循环，但当前 item 仍带 loop tag（循环源没有退出）。 */
    const val PROJECTION_PLAIN_SOURCE_LOOP: String = "projection-plain-source-loop"
}

/** 一次不变量违规（纯数据，可直接序列化进 /state.invariants）。 */
data class InvariantEvent(
    val code: String,
    val message: String,
    val atMs: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "code" to code,
        "message" to message,
        "atMs" to atMs,
    )
}

/** /state.invariants 的一条：最近一次发生时间 + 累计次数。 */
data class InvariantRecord(
    val code: String,
    val message: String,
    val atMs: Long,
    val count: Long,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "code" to code,
        "message" to message,
        "atMs" to atMs,
        "count" to count,
    )
}

/**
 * 最近 N 条不变量违规（默认 10）。
 *
 * 同一个 code 连续重复时只累加 count 并刷新 atMs/message（避免每 500ms 采样刷屏），
 * 但换一个 code 就会新增一条；超出容量从最旧开始丢。
 */
class InvariantRegistry(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<InvariantRecord>()

    @Synchronized
    fun record(event: InvariantEvent): InvariantRecord {
        val last = entries.lastOrNull()
        val record = if (last != null && last.code == event.code) {
            entries.removeLast()
            InvariantRecord(
                code = event.code,
                message = event.message,
                atMs = event.atMs,
                count = last.count + 1L,
            )
        } else {
            InvariantRecord(
                code = event.code,
                message = event.message,
                atMs = event.atMs,
                count = 1L,
            )
        }
        entries.addLast(record)
        while (entries.size > capacity) {
            entries.removeFirst()
        }
        return record
    }

    @Synchronized
    fun snapshot(): List<InvariantRecord> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 10
    }
}
