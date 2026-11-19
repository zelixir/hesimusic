package com.zjr.hesimusic.core.invariants

/**
 * 输出侧不变量判定（issue 00020 回归；纯逻辑，可 JVM 单测）。
 *
 * 采样口径（由 debug 源集的 PlaybackInvariantMonitor 每 500ms 提供一次）：
 * - [OutputInvariantSample.playWhenReady]：**主播放器**的 playWhenReady；
 * - [OutputInvariantSample.writtenFrames]：主播放器 AudioSink 实际交给 delegate 的累计帧数
 *   （见 [AudioOutputFrames]；单调递增）；
 * - [OutputInvariantSample.buffering] / [OutputInvariantSample.playbackStateReady]：
 *   ExoPlayer 的 playbackState，用来避免「缓冲期没数据」被误判成 sink 静音。
 *
 * 规则：
 * 1. 暂停（playWhenReady=false）且本次采样 ΔwrittenFrames > 0 → PAUSED_BUT_SINK_WROTE；
 *    状态刚切换的那一次采样跳过（Δ 里可能还含暂停前的帧）。
 * 2. 播放（playWhenReady=true 且 READY、非 buffering）且连续 ≥ [silentThresholdMs]（默认 1s）
 *    ΔwrittenFrames == 0 → PLAYING_BUT_SINK_SILENT；同一次静音只报一次，重新写帧后才重新武装。
 */
class OutputInvariantEvaluator(
    private val silentThresholdMs: Long = DEFAULT_SILENT_THRESHOLD_MS,
) {

    private var previous: OutputInvariantSample? = null
    private var pausedWriteReported = false
    private var silentSinceMs: Long? = null
    private var silentReported = false

    fun onSample(sample: OutputInvariantSample): InvariantEvent? {
        val prev = previous
        previous = sample
        val delta = if (prev == null) {
            0L
        } else {
            (sample.writtenFrames - prev.writtenFrames).coerceAtLeast(0L)
        }
        val stateChanged = prev == null || prev.playWhenReady != sample.playWhenReady

        if (!sample.playWhenReady) {
            silentSinceMs = null
            silentReported = false
            if (stateChanged) {
                // 状态刚切换：这段 Δ 可能包含“暂停前还在写”的帧，不能归因于暂停仍写。
                pausedWriteReported = false
                return null
            }
            if (delta > 0L && !pausedWriteReported) {
                pausedWriteReported = true
                return InvariantEvent(
                    code = InvariantCode.PAUSED_BUT_SINK_WROTE,
                    message = "INVARIANT violated: " +
                        InvariantCode.PAUSED_BUT_SINK_WROTE + " frames=" + delta,
                    atMs = sample.atMs,
                )
            }
            return null
        }

        // 播放中：任何一次“暂停仍写”的连续段都结束了。
        pausedWriteReported = false
        if (stateChanged) {
            silentSinceMs = null
            silentReported = false
            return null
        }
        if (delta > 0L) {
            silentSinceMs = null
            silentReported = false
            return null
        }
        if (!sample.playbackStateReady || sample.buffering) {
            // 缓冲/未就绪没有帧是正常的，不参与静音判定。
            silentSinceMs = null
            silentReported = false
            return null
        }
        if (silentReported) return null
        val since = silentSinceMs ?: (prev?.atMs ?: sample.atMs).also { silentSinceMs = it }
        val silentForMs = sample.atMs - since
        if (silentForMs < silentThresholdMs) return null
        silentReported = true
        return InvariantEvent(
            code = InvariantCode.PLAYING_BUT_SINK_SILENT,
            message = "INVARIANT violated: " +
                InvariantCode.PLAYING_BUT_SINK_SILENT + " for=" + silentForMs + "ms",
            atMs = sample.atMs,
        )
    }

    fun reset() {
        previous = null
        pausedWriteReported = false
        silentSinceMs = null
        silentReported = false
    }

    companion object {
        const val DEFAULT_SILENT_THRESHOLD_MS: Long = 1_000L
    }
}

/** 一次 500ms 采样（输出侧事实）。 */
data class OutputInvariantSample(
    val atMs: Long,
    val playWhenReady: Boolean,
    val writtenFrames: Long,
    val buffering: Boolean = false,
    val playbackStateReady: Boolean = true,
)
