package com.zjr.hesimusic.loop.audio.source

import android.util.Log

/** SOURCE/RingBand 关键日志；JVM 测试里 android.util.Log 是 stub，统一兜底。 */
internal object RingBandLog {
    private const val TAG = "RingBand"

    fun i(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: Throwable) {
        }
    }

    fun w(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
        }
    }
}
