package com.zjr.hesimusic.core.invariants

import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 每个 AudioSink 属主（这里是 LoopAudioController 实例）实际写出的音频帧累计计数。
 *
 * 为什么按 controller 分桶：主播放器与预览播放器各有自己的 [com.zjr.hesimusic.loop.audio.LoopAudioController]
 * （主播放器用 Hilt 单例，预览页 new 一个），共用一个全局计数会让“主播放器暂停、预览页试听”
 * 被误判成 paused-but-sink-wrote。按 controller 分桶后，debug 只读主播放器那一个桶。
 *
 * 只做计数，不做任何播放操作；音频线程只做一次 AtomicLong.add（属主对象在 sink 构造时缓存）。
 */
object AudioOutputFrames {

    private val counters = WeakHashMap<Any, AtomicLong>()

    /** 取某个属主的计数器（sink 构造时调用一次并缓存，避免每 buffer 查表）。 */
    fun counterFor(owner: Any): AtomicLong = synchronized(counters) {
        counters.getOrPut(owner) { AtomicLong(0L) }
    }

    /** 当前累计帧数；没有计数器时为 0。 */
    fun totalFrames(owner: Any): Long = synchronized(counters) {
        counters[owner]?.get() ?: 0L
    }

    /** 单测隔离用：清空所有计数（生产不使用）。 */
    fun reset() {
        synchronized(counters) { counters.clear() }
    }
}
