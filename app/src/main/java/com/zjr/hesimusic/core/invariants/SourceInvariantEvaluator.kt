package com.zjr.hesimusic.core.invariants

/**
 * 音源一致性不变量判定（issue 00021 回归；纯逻辑，可 JVM 单测）。
 *
 * 00021 的现象：页面投影按 A + floorMod(T,L) 显示循环位置（会话认为“已进循环”），
 * 但 media3 复用了旧的普通 MP3 MediaSource，声音从原曲位置播——投影与实际音源不一致。
 *
 * 采样口径（debug 源集每 500ms 一次）：
 * - [SourceInvariantSample.windowActive]：会话/页面**确实按循环源投影**（SOURCE 路线、
 *   运行时窗口非空且与当前歌一致、SOURCE 接管已登记、没有被 LoopSourceFallback 判为不可用）；
 * - [SourceInvariantSample.itemHasLoopTag]：player.currentMediaItem 带 LoopSourceItemTag；
 * - [SourceInvariantSample.factoryLastSelectLoop]：
 *   LoopAwareMediaSourceFactory 针对**当前 mediaId** 最近一次 select 是 loop（而不是 delegate）。
 *
 * 规则：
 * - 循环投影 +（无 tag 或最近一次 select 不是 loop）→ PROJECTION_LOOP_SOURCE_PLAIN；
 * - 非循环投影 + 仍有 loop tag → PROJECTION_PLAIN_SOURCE_LOOP。
 */
class SourceInvariantEvaluator {

    fun onSample(sample: SourceInvariantSample): InvariantEvent? {
        if (sample.windowActive) {
            if (!sample.itemHasLoopTag || !sample.factoryLastSelectLoop) {
                return InvariantEvent(
                    code = InvariantCode.PROJECTION_LOOP_SOURCE_PLAIN,
                    message = "INVARIANT violated: projection=loop but source=plain",
                    atMs = sample.atMs,
                )
            }
            return null
        }
        if (sample.itemHasLoopTag) {
            return InvariantEvent(
                code = InvariantCode.PROJECTION_PLAIN_SOURCE_LOOP,
                message = "INVARIANT violated: projection=plain but source=loop",
                atMs = sample.atMs,
            )
        }
        return null
    }
}

/** 一次 500ms 采样（音源侧事实）。 */
data class SourceInvariantSample(
    val atMs: Long,
    val windowActive: Boolean,
    val itemHasLoopTag: Boolean,
    val factoryLastSelectLoop: Boolean,
)
