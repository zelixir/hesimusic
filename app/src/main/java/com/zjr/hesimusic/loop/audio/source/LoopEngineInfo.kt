package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import com.zjr.hesimusic.service.LoopSourceFallback

/**
 * 运行时引擎标识，供 About 页面/日志显示。
 *
 * 纯判定集中在 [of]，不依赖 Android；[current] 只负责刷新两个持久化开关并读取
 * [LoopSourceFallback] 的最近回退原因。
 */
data class LoopEngineInfo(
    val engine: String,
    val loopPath: String,
    val bandBytes: Long,
    val fallbackReason: String?,
) {

    fun label(): String = buildString {
        append("ENGINE=").append(engine)
        append(" LOOP_PATH=").append(loopPath)
        append(" BAND_BYTES=").append(bandBytes)
        if (!fallbackReason.isNullOrBlank()) {
            append(" FALLBACK_REASON=").append(fallbackReason)
        }
    }

    companion object {
        const val ENGINE_SOURCE_RING = "SOURCE_RING"
        const val ENGINE_M3_PRIME = "M3_PRIME"
        const val PATH_SOURCE = "source"
        const val PATH_M3 = "m3"
        const val PATH_NONE = "none"

        @Volatile
        private var lastBandBytes: Long = 0L

        fun recordBandBytes(bytes: Long) {
            if (bytes > 0L) lastBandBytes = bytes
        }

        /** About/日志可直接用的单行文本。 */
        fun display(context: Context? = null): String = current(context).label()

        fun current(context: Context? = null): LoopEngineInfo {
            if (context != null) {
                LoopSourceMode.refreshFromSettings(context)
                LoopSourceMainPlayer.refreshFromSettings(context)
            }
            return of(
                sourceMode = LoopSourceMode.isSource(),
                mainEnabled = LoopSourceMainPlayer.enabled,
                bandBytes = lastBandBytes,
                fallbackReason = LoopSourceFallback.latestReason()?.logName,
            )
        }

        /** 纯判定：显式 m3 或任意回退原因存在时返回 M3_PRIME。 */
        fun of(
            sourceMode: Boolean,
            mainEnabled: Boolean,
            bandBytes: Long = 0L,
            fallbackReason: String? = null,
            loopPath: String? = null,
        ): LoopEngineInfo {
            val useSource = sourceMode && mainEnabled && fallbackReason.isNullOrBlank()
            val path = loopPath ?: when {
                useSource -> PATH_SOURCE
                !sourceMode -> PATH_M3
                !fallbackReason.isNullOrBlank() -> PATH_M3
                else -> PATH_NONE
            }
            return LoopEngineInfo(
                engine = if (useSource) ENGINE_SOURCE_RING else ENGINE_M3_PRIME,
                loopPath = path,
                bandBytes = if (useSource) bandBytes.coerceAtLeast(0L) else 0L,
                fallbackReason = fallbackReason?.takeIf { it.isNotBlank() },
            )
        }
    }
}
