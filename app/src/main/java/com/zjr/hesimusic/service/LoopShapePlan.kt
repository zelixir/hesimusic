package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow

/**
 * 形态应用计划（纯函数，无 Android / Media3 依赖，可在纯 JVM 单测）。
 *
 * 生产路径**不做任何裁剪**：环回完全由音频层（PCM 折返）实现，服务层只写
 * LoopAudioController.window 这一个值。因此「进入/退出无缝」大多数情况下是**纯状态写**，
 * 不需要 replaceMediaItem、不需要重建时间线、也不需要 seek。只有两种例外需要一次 seek：
 *
 * 1. **进入 / A/B 变化（PRE_ROLL）**：起播位置落在 [A,B) 内、而 ring 尚未覆盖该区间时，
 *    回跳到「该轮起点 − 250ms」预卷一轮，让本轮完整流经 [A,B) 把 ring 填满；
 * 2. **退出无缝（EXIT_SEEK）**：若当前处于重放态，输入已被丢弃、播放器位置已越过 B，
 *    只清窗口会让 sink 停止喂 PCM（听感上卡住），必须 seek 到展示位置对应的 raw 续播。
 *
 * 判定只看「目标窗口 vs 已应用窗口」与当前 raw 位置，不做任何时序推断。
 */
object LoopShapePlan {

    /** 本次形态变化的种类（日志 + 单测断言用，不影响行为）。 */
    enum class Kind {
        /** 目标与已应用一致：不做事（连 audio.window 都不重复写）。 */
        NOOP,
        /** 进入/切换窗口：只写 audio.window，不 seek。 */
        ENTER,
        /** 进入/切换窗口 + 预卷一次（ring 未覆盖 [A,B)）。 */
        PRE_ROLL,
        /** 退出无缝：只写 audio.window = null，不 seek。 */
        EXIT,
        /** 退出无缝 + seek 回展示位置（重放态必须）。 */
        EXIT_SEEK,
    }

    /**
     * @param window 要写入音频层的窗口（null = 退出无缝）
     * @param seekRawUs 需要的一次 seek（µs，引擎原始口径）；null = 不 seek
     */
    data class Plan(
        val window: LoopWindow?,
        val seekRawUs: Long?,
        val kind: Kind,
    )

    /** 窗口只对「当前正在播的那一项」生效（事件可能早于/晚于 transition）。 */
    fun targetWindow(songId: Long?, runtimeWindow: LoopWindow?): LoopWindow? =
        runtimeWindow?.takeIf { songId != null && it.songId == songId }

    /**
     * @param songId 当前 MediaItem 的 songId
     * @param targetWindow 目标窗口（已按 songId 归属过滤）
     * @param appliedWindow 当前已生效的窗口
     * @param rawUs 引擎当前原始位置（µs；重放期 = B+(k-1)L+环内偏移）
     * @param ringCoveredFrames 音频层 ring 已连续覆盖的帧数(前缀,**写入新窗口之后**读)。
     *   分片预填让「未覆盖完整」成为常态,只有**完全没覆盖**(0)才值得预卷;有前缀覆盖时
     *   应当让播放继续并靠捕获/后续分片补齐,否则会把用户刚拖到的位置拽回区段开始。
     * @param wasReplaying 写入新窗口**之前**音频层是否处于重放态（写 null 会立刻复位状态）
     */
    fun plan(
        songId: Long?,
        targetWindow: LoopWindow?,
        appliedWindow: LoopWindow?,
        rawUs: Long,
        ringCoveredFrames: Long,
        wasReplaying: Boolean,
    ): Plan {
        if (targetWindow == appliedWindow) {
            // 形态应用 = 纯状态写：窗口值没变就什么都不做
            return Plan(window = targetWindow, seekRawUs = null, kind = Kind.NOOP)
        }

        // 展示位置一律按**旧口径**换算（旧窗口可能为 null = 普通播放位置）
        val displayUs = LoopSessionProjection.displayUs(rawUs, appliedWindow)

        if (targetWindow == null) {
            // 退出无缝。重放态必须 seek：无裁剪时重放期输入已被丢弃、播放器位置已越过 B，
            // 只清窗口 → sink 回到 PASS_THROUGH 不再喂 PCM，播放会卡在 B+kL 处。
            // 只对「同一个 item 的窗口」生效：切歌时 appliedWindow 属于上一首，
            // 不能拿它的 A/B 去折返新歌的位置。
            val exitingSameItem = appliedWindow != null && songId != null && appliedWindow.songId == songId
            return if (exitingSameItem && wasReplaying) {
                Plan(window = null, seekRawUs = displayUs, kind = Kind.EXIT_SEEK)
            } else {
                Plan(window = null, seekRawUs = null, kind = Kind.EXIT)
            }
        }

        // 进入 / A/B 变化:**只写窗口,绝不 seek**。
        // 历史:这里曾在 ring 未覆盖时预卷到「该轮起点 − 250ms」把 ring 填满。分片预填上线后
        // 「未覆盖」是前几十秒的常态,预卷就变成「用户拖到区间中→被拽回区段开始」的元凶;
        // 而缺口本来就会被「捕获 + 后续分片」补齐。因此彻底移除向后预卷:
        // 任何会让播放头倒退的 seek 都不允许由本层发起。
        return Plan(window = targetWindow, seekRawUs = null, kind = Kind.ENTER)
    }
}
