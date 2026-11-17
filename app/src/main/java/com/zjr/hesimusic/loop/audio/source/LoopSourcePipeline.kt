package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * SOURCE 数据源实现路径。
 *
 * - [EXTRACTOR]：自定义 Extractor + ProgressiveMediaSource（默认，复用 media3 成熟生命周期）；
 * - [CUSTOM_PERIOD]：旧的手写 MediaPeriod 实现（保留回退/对照）。
 *
 * 真机调试：`adb shell settings put global hesi_loop_pipeline 0` 回退旧实现。
 */
enum class LoopSourcePipeline {
    EXTRACTOR,
    CUSTOM_PERIOD,
    ;

    companion object {
        private const val TAG = "LoopSourceMode"
        private const val SETTING_KEY = "hesi_loop_pipeline"

        @Volatile
        var current: LoopSourcePipeline = EXTRACTOR
            private set

        fun isExtractor(): Boolean = current == EXTRACTOR

        fun refreshFromSettings(context: Context) {
            current = try {
                if (Settings.Global.getInt(context.contentResolver, SETTING_KEY, 1) == 0) {
                    CUSTOM_PERIOD
                } else {
                    EXTRACTOR
                }
            } catch (_: Exception) {
                EXTRACTOR
            }
            Log.d(TAG, "loop source pipeline -> " + current)
        }
    }
}
