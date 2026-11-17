package com.zjr.hesimusic.loop.audio.source

/**
 * 播放器暂停闸门：pause() 后数据源不再 read/解码，resume 后恢复。
 *
 * 预览页和主播放器共用这个进程内闸门；任一 SOURCE 播放器暂停都会停住
 * 数据源预取，保证 pause 后不会继续无声解码/占内存。
 */
object LoopSourcePauseGate {
    @Volatile
    var paused: Boolean = false
        private set

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun reset() {
        paused = false
    }
}
