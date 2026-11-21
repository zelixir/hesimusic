package com.zjr.hesimusic.core.invariants

/**
 * 00027 “claim 真值”不变量（纯逻辑，可 JVM 单测）。
 *
 * 采样口径（debug 的 [com.zjr.hesimusic.debug.PlaybackInvariantMonitor] 每 500ms）：
 * - [LoopClaimInvariantSample.claimed]：当前歌有意图窗口（用户/播放层声称要循环）；
 * - [LoopClaimInvariantSample.claimActive]：对用户/MediaSession 生效的 claim；
 * - [LoopClaimInvariantSample.sourceActive]：源真的在环回（SOURCE 已建成 / M3′ 已写 sink）。
 *
 * 规则：
 * 1. **claimActive && !sourceActive 必须记录**（claim 不得脱离源真值）；
 * 2. 同时记录“有意图、无源”的退化（claimed && !sourceActive），即 00027 的
 *    “窗口在、声音普通”中间态；reason 带上五种回退原因之一。
 *
 * 同一 (songId, claimed, claimActive, reason) 连续采样只报一次，避免 500ms 刷屏；
 * 变化后重新武装（与 [SourceInvariantEvaluator] 的确认策略一致）。
 */
class LoopClaimInvariantEvaluator {

    private var lastSignature: String? = null

    fun onSample(sample: LoopClaimInvariantSample): InvariantEvent? {
        val violated = (sample.claimActive && !sample.sourceActive) ||
            (sample.claimed && !sample.sourceActive)
        if (!violated) {
            lastSignature = null
            return null
        }
        val signature = sample.songId.toString() + ":" +
            sample.claimed + ":" + sample.claimActive + ":" + sample.reason
        if (signature == lastSignature) return null
        lastSignature = signature
        return InvariantEvent(
            code = InvariantCode.LOOP_CLAIM_WITHOUT_SOURCE,
            message = buildString {
                append("INVARIANT violated: ")
                append(InvariantCode.LOOP_CLAIM_WITHOUT_SOURCE)
                append(" songId=").append(sample.songId)
                append(" claimed=").append(sample.claimed)
                append(" claimActive=").append(sample.claimActive)
                append(" sourceActive=").append(sample.sourceActive)
                if (!sample.reason.isNullOrBlank()) append(" reason=").append(sample.reason)
            },
            atMs = sample.atMs,
        )
    }

    fun reset() {
        lastSignature = null
    }
}

/** 一次 claim 采样（来自 [com.zjr.hesimusic.service.LoopClaim]）。 */
data class LoopClaimInvariantSample(
    val atMs: Long,
    val songId: Long?,
    val claimed: Boolean,
    val claimActive: Boolean,
    val sourceActive: Boolean,
    val reason: String? = null,
)
