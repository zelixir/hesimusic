package com.zjr.hesimusic.ui.about

import android.content.Context
import com.zjr.hesimusic.loop.audio.source.LoopEngineInfo
import java.util.Locale

/**
 * 关于页展示的循环引擎快照(与 `LoopEngineInfo` 解耦的 UI 视图模型)。
 *
 * 真实值来自 [LoopEngineInfo.current](运行时刷新 `LoopSourceMode` / `LoopSourceMainPlayer`
 * 两个开关,并读取 `LoopSourceFallback` 最近一次回退原因);这里只做字段搬运与
 * 「循环路径」的归一化,不写任何引擎状态。
 */
data class LoopEngineSnapshot(
    val engine: String = "",
    val loopPath: String = "",
    val bandBytes: Long = 0L,
    val fallbackReason: String? = null,
) {
    companion object {
        /** 引擎信息读取失败/未接入时的兜底(关于页显示「未知」)。 */
        val UNKNOWN = LoopEngineSnapshot(engine = "", loopPath = "none")
    }
}

/**
 * `LoopEngineInfo` 的读取入口。
 *
 * 之所以再包一层:关于页只需要三个口径(引擎、循环路径、回退原因),而
 * `LoopEngineInfo` 只有 engine/bandBytes/fallbackReason。`loopPath` 由 engine 归一化得到:
 * - `SOURCE_RING` → `source`;
 * - `M3_PRIME`   → `m3`;
 * - 其它/读取失败 → `none`。
 *
 * 若 `LoopEngineInfo` 后续补上 loopPath 字段,只需改这里的 [current] 一处。
 */
object LoopEngineInfoSource {

    /**
     * 读取当前引擎快照;任何异常(例如单测/预览里没有真实引擎)都退化为 [LoopEngineSnapshot.UNKNOWN],
     * 绝不让关于页崩溃。
     */
    fun current(context: Context?): LoopEngineSnapshot {
        val info = runCatching { LoopEngineInfo.current(context) }.getOrNull()
            ?: return LoopEngineSnapshot.UNKNOWN
        return LoopEngineSnapshot(
            engine = info.engine,
            loopPath = loopPathOf(info.engine),
            bandBytes = info.bandBytes,
            fallbackReason = info.fallbackReason,
        )
    }

    /** 引擎常量 → `/state.loopPath` 口径(source / m3 / none)。 */
    fun loopPathOf(engine: String?): String {
        return when (engine?.trim()?.lowercase(Locale.ROOT)) {
            LoopEngineInfo.ENGINE_SOURCE_RING.lowercase(Locale.ROOT), "source" -> "source"
            LoopEngineInfo.ENGINE_M3_PRIME.lowercase(Locale.ROOT), "m3" -> "m3"
            else -> "none"
        }
    }
}
