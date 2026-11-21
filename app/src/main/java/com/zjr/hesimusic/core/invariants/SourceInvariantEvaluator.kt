package com.zjr.hesimusic.core.invariants

/**
 * 音源一致性不变量判定（issue 00021 / 00027；纯逻辑，可 JVM 单测）。
 *
 * 00021 的现象：页面投影按 A + floorMod(T,L) 显示循环位置（会话认为“已进循环”），
 * 但 media3 复用了旧的普通 MP3 MediaSource，声音从原曲位置播——投影与实际音源不一致。
 *
 * 00027 之后，**唯一真值是 LoopClaim**：本判定器不再自己拼“窗口 + installed + factory
 * 最近一次 select”的近似，因为那个近似会把普通队列预取的 delegate select 当成当前源，
 * 在源其实正常循环时误报（场景 #26/#108）。采样口径：
 *
 * - [SourceInvariantSample.claimActive]：对用户/MediaSession 生效的循环 claim；
 * - [SourceInvariantSample.sourceActive]：源真的在环回（SOURCE 建成 / M3′ 写入 sink）。
 *
 * 规则：
 * - claim=loop + 源=plain → PROJECTION_LOOP_SOURCE_PLAIN（真 bug：投影说循环、源是普通）；
 * - claim=plain + 源=loop → PROJECTION_PLAIN_SOURCE_LOOP（源没退出）；
 * - 两者一致 → 不报。
 *
 * [SourceInvariantSample.itemHasLoopTag] / [SourceInvariantSample.factoryLastSelectLoop]
 * 仅作为 /state.invariantProbe 的诊断字段保留，**不再参与判定**：factory 的最近一次 select
 * 会被同 mediaId 的队列预取/恢复覆盖，不能当“当前源”的权威。
 */
class SourceInvariantEvaluator {

    fun onSample(sample: SourceInvariantSample): InvariantEvent? {
        if (sample.claimActive && !sample.sourceActive) {
            return InvariantEvent(
                code = InvariantCode.PROJECTION_LOOP_SOURCE_PLAIN,
                message = "INVARIANT violated: claim=loop but source=plain",
                atMs = sample.atMs,
            )
        }
        if (!sample.claimActive && sample.sourceActive) {
            return InvariantEvent(
                code = InvariantCode.PROJECTION_PLAIN_SOURCE_LOOP,
                message = "INVARIANT violated: claim=plain but source=loop",
                atMs = sample.atMs,
            )
        }
        return null
    }
}

/** 一次 500ms 采样（音源侧事实）。 */
data class SourceInvariantSample(
    val atMs: Long,
    val claimActive: Boolean,
    val sourceActive: Boolean,
    /** 仅诊断：当前 item 是否带 LoopSourceItemTag。 */
    val itemHasLoopTag: Boolean = false,
    /** 仅诊断：factory 对当前 mediaId 最近一次 select 是否为 loop（可能被预取覆盖）。 */
    val factoryLastSelectLoop: Boolean = false,
)
