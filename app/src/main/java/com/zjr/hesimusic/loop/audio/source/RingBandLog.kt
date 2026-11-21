package com.zjr.hesimusic.loop.audio.source

import android.util.Log

/**
 * SOURCE/RingBand 关键日志；JVM 测试里 android.util.Log 是 stub，统一兜底。
 *
 * 00028(#105)：所有行都带 `[t=<ms>]` 相对时间戳（进程内首次打点起算）。
 * 真机排查"接缝停顿"时需要把 fill / decode / seam / read 四条线按时间对齐：
 * logcat 的墙钟精度足够但跨进程缓冲会漂，行内相对时间戳可做二次校验。
 */
internal object RingBandLog {
    private const val TAG = "RingBand"

    private val originNanos: Long = System.nanoTime()

    /** 相对 [originNanos] 的毫秒数。 */
    fun elapsedMs(): Long = (System.nanoTime() - originNanos) / 1_000_000L

    fun i(message: String) {
        try {
            Log.i(TAG, "t=" + elapsedMs() + "ms " + message)
        } catch (_: Throwable) {
        }
    }

    fun w(message: String) {
        try {
            Log.w(TAG, "t=" + elapsedMs() + "ms " + message)
        } catch (_: Throwable) {
        }
    }
}
