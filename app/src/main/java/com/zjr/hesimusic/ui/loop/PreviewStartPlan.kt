package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection

/**
 * 试听四模式的**起播/回绕语义**（纯函数，可 JVM 单测）。
 *
 * 口径（契约 §2.3；与 UI reducer 的 `modeStartMs` 同源，避免两层漂移）：
 * ```
 * FULL       0 ──(前奏只播一次)──> B ──> A ──> B ...   第一次回绕 B→A
 * LOOP_ONLY  A−300ms ──> B ──> A ...                  只在 [A,B) 内环回
 * SEAM       max(A, B−5s) ──> B ──> A ...             接缝检查
 * FREE       0 ─────────────────────────> 时长        直通，不折返
 * ```
 *
 * **引擎路线**（issue 00007）：SOURCE 数据源的时间轴 T=0 **恒等于 A**
 * （`displayUs(T) = A + floorMod(T, L)`），结构上表达不了「0..A 的前奏」，因此：
 * - FULL 在前奏非空（A>0）时走 M3′：`raw < A` 的前奏原样播放，到 B 后由 sink 折回 A；
 * - FULL 在 A=0 时前奏为空，与「仅循环段」同语义，仍可走 SOURCE（长窗口不受 ring 上限约束）；
 * - LOOP_ONLY / SEAM 只描述 [A,B) 内的落点，SOURCE 可表达，继续走 SOURCE；
 * - FREE 没有窗口，走 M3′ 直通。
 */
object PreviewStartPlan {

    /** 接缝检查起播提前量（契约 §2.3：B−5s）。 */
    const val SEAM_LEAD_MS = 5_000L

    /**
     * 循环段起播预卷（M3′）：seek 落点有十几毫秒误差，正好从 A 起播会让 ring 缺一小段；
     * 从 A 前预卷 300ms 起播，让环缓冲区从 A 之前开始捕获。
     */
    const val LOOP_PREROLL_MS = 300L

    /**
     * 该模式在该窗口下是否有「0..A 的前奏」需要表达：只有 FULL 需要；A=0 时前奏为空。
     */
    fun hasPrelude(mode: PreviewMode, window: LoopWindow?): Boolean =
        mode == PreviewMode.FULL && (window?.loopStartUs ?: 0L) > 0L

    /**
     * 展示口径的起播位置（ms）：FULL/FREE 恒为 0（前奏/整曲从 0 起），
     * LOOP_ONLY 从 A 前 300ms 预卷，SEAM 从 max(A, B−5s) 起。
     */
    fun displayStartMs(mode: PreviewMode, aMs: Long, bMs: Long): Long = when (mode) {
        PreviewMode.FULL, PreviewMode.FREE -> 0L
        PreviewMode.LOOP_ONLY -> (aMs - LOOP_PREROLL_MS).coerceAtLeast(0L)
        // 极短循环段 B−5s < A 时取 A，保证落点仍在循环区内。
        PreviewMode.SEAM -> maxOf(aMs, bMs - SEAM_LEAD_MS)
    }

    /**
     * 该模式能否由 SOURCE 数据源表达：FREE 没有窗口；前奏非空的 FULL 需要「先 0..A 再环回」，
     * 而 SOURCE 的 T=0 恒等于 A，表达不了 → 必须走 M3′。
     */
    fun sourceExpressible(mode: PreviewMode, window: LoopWindow?): Boolean =
        mode != PreviewMode.FREE && !hasPrelude(mode, window)

    /** 实际是否走 SOURCE 数据源（= 引擎开关开 + 有窗口 + 模式可表达）。 */
    fun usesSource(mode: PreviewMode, window: LoopWindow?, sourceModeEnabled: Boolean): Boolean =
        sourceModeEnabled && window != null && sourceExpressible(mode, window)

    /**
     * SOURCE 时间轴上该模式的起播落点 T（ms）。T=0 对应 A。
     *
     * 前奏非空的 FULL 不会走 SOURCE（[usesSource] 为 false）；这里对 FULL 返回 0
     * （T=0 → 媒体 A），只作为「换引擎之前」的兜底值，真正的 FULL 起点由 M3′ 的展示口径 0 表达。
     */
    fun sourceStartMs(mode: PreviewMode, aMs: Long, bMs: Long, window: LoopWindow?): Long {
        if (window == null) return 0L
        val displayUs = displayStartMs(mode, aMs, bMs).coerceAtLeast(0L) * 1_000L
        return LoopSourceProjection.initialTimelineUs(displayUs, window) / 1_000L
    }
}
