package com.zjr.hesimusic.loop.audio.source

import android.content.Context
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.LoopSourceFallback
import com.zjr.hesimusic.service.LoopSourceRuntime

/**
 * 运行时引擎标识，供 About 页面/日志显示。
 *
 * 纯判定集中在 [of]，不依赖 Android；[current] 负责刷新两个持久化开关，并读取
 * [com.zjr.hesimusic.service.LoopClaimStore] 的 claim 真值（源是否真的在环回）与
 * per-song 回退原因。
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

        /** 没有生效的循环（普通播放 / 回退）。AboutInfo 映射为「无循环 (普通播放)」。 */
        const val ENGINE_NONE = "EXO"
        const val PATH_SOURCE = "source"
        const val PATH_M3 = "m3"
        const val PATH_NONE = "none"

        @Volatile
        private var lastBandBytes: Long = 0L

        fun recordBandBytes(bytes: Long) {
            lastBandBytes = bytes.coerceAtLeast(0L)
        }

        /**
         * 最近一次安装请求记录的环带字节数(与"当前是否真的接管"无关)。
         *
         * 00027 现场排查用:debug /state.loop 同时输出 bandBytes / budgetBytes /
         * sourceAvailable,就能一眼看出"窗口在、但环没建成"。
         */
        fun lastRecordedBandBytes(): Long = lastBandBytes

        /** About/日志可直接用的单行文本。 */
        fun display(context: Context? = null): String = current(context).label()

        fun current(context: Context? = null): LoopEngineInfo {
            if (context != null) {
                LoopSourceMode.refreshFromSettings(context)
                LoopSourceMainPlayer.refreshFromSettings(context)
            }
            // 00027/P2：About 也必须读 claim 真值。窗口只是意图；SOURCE 回退（五种原因）
            // 时实际是普通整曲，不能再写成 M3′。per-song reason 由 claim/reasonFor 给出，
            // provider 未安装（单测/初始化前）才退回全局 latest。
            val claim = LoopClaimStore.claimFor(null)
            val installed = LoopSourceRuntime.installedWindow
            val effectiveSongId = claim.songId ?: installed?.songId
            val effectiveWindow = claim.window ?: installed
            val fallbackReason = claim.fallbackReason
                ?: LoopSourceFallback.reasonFor(effectiveSongId, effectiveWindow)
                ?: if (!LoopClaimStore.isInstalled()) LoopSourceFallback.latestReason() else null
            // true/false = 有当前 claim 证据；null = 空闲/还没放歌 → 维持“按配置显示引擎”。
            val claimEvidence: Boolean? = when {
                claim.window != null || claim.sourceActive -> claim.claimActive
                fallbackReason != null -> false
                else -> null
            }
            return of(
                sourceMode = LoopSourceMode.isSource(),
                mainEnabled = LoopSourceMainPlayer.enabled,
                bandBytes = if (claim.claimActive) lastBandBytes else 0L,
                fallbackReason = fallbackReason?.logName,
                claimActive = claimEvidence,
            )
        }

        /**
         * 纯判定：有明确 claim 证据时，只有 [claimActive]（源真的在环回）才显示 source/m3；
         * 回退或 claimActive=false → [ENGINE_NONE] + [PATH_NONE]（普通播放）。
         * [claimActive]=null 表示空闲/无证据，维持“按配置显示引擎”的旧行为。
         */
        fun of(
            sourceMode: Boolean,
            mainEnabled: Boolean,
            bandBytes: Long = 0L,
            fallbackReason: String? = null,
            loopPath: String? = null,
            /** true/false = 当前 claim 的明确真值；null = 空闲/无证据，按配置显示。 */
            claimActive: Boolean? = null,
        ): LoopEngineInfo {
            val sourceRoute = sourceMode && mainEnabled
            val useSource = sourceRoute && fallbackReason.isNullOrBlank() &&
                (claimActive == null || claimActive)
            val path = loopPath ?: when {
                useSource -> PATH_SOURCE
                claimActive == true -> PATH_M3
                claimActive == false -> PATH_NONE
                !sourceMode -> PATH_M3
                !fallbackReason.isNullOrBlank() -> PATH_NONE
                else -> PATH_NONE
            }
            return LoopEngineInfo(
                engine = when {
                    useSource -> ENGINE_SOURCE_RING
                    claimActive == true -> ENGINE_M3_PRIME
                    claimActive == false -> ENGINE_NONE
                    fallbackReason != null -> ENGINE_NONE
                    else -> ENGINE_M3_PRIME
                },
                loopPath = path,
                bandBytes = if (useSource) bandBytes.coerceAtLeast(0L) else 0L,
                fallbackReason = fallbackReason?.takeIf { it.isNotBlank() },
            )
        }
    }
}
