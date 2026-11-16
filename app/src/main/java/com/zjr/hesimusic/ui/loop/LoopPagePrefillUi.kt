package com.zjr.hesimusic.ui.loop

import com.zjr.hesimusic.ui.loop.state.PrefillState

/**
 * 预填进度文案(纯函数,便于单测):
 * - InProgress(total>0):「准备环缓冲 8s/12s」;
 * - InProgress(total=0):「正在准备环缓冲…」(首片落地前);
 * - Partial / Ready / Failed / NotStarted:不显示(前缀已就绪或失败走其它反馈)。
 */
internal fun prefillProgressLabel(prefill: PrefillState): String? = when (prefill) {
    is PrefillState.InProgress -> {
        val totalSec = (prefill.totalMs / 1000L).coerceAtLeast(0L)
        val coveredSec = (prefill.coveredMs / 1000L).coerceIn(0L, totalSec)
        if (totalSec > 0L) {
            "准备环缓冲 " + coveredSec + "s/" + totalSec + "s"
        } else {
            "正在准备环缓冲…"
        }
    }

    else -> null
}

/** 前缀是否已可播(长窗口只填前缀时的 Partial,或整环 Ready)。 */
internal fun prefillPrefixReady(prefill: PrefillState): Boolean =
    prefill is PrefillState.Partial || prefill == PrefillState.Ready
