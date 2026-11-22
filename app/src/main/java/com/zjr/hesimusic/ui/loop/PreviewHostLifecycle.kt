package com.zjr.hesimusic.ui.loop

/**
 * 预览宿主(承载结果页的 Activity/composition)销毁时的处置(场景 #45)。
 */
enum class PreviewHostDestroyAction {
    /**
     * 配置变更重建(旋转/分屏/深色模式/字体缩放):**保留**预览播放器与 [PreviewProbe] 引用。
     *
     * 播放器属于 ViewModel(跨配置变更存活),位置/窗口/环带源都还在,重建后立即可用。
     */
    KEEP_ALIVE,

    /**
     * 真正离开页面(返回/删除/宿主结束):释放预览。
     *
     * 主播放是否恢复由调用方按 [PreviewMainPlaybackHold] 的记录决定(#107):
     * 进入结果页前在播才 resume,进入前本来暂停则保持暂停。
     */
    RELEASE_AND_RESUME,
}

/** 宿主重建后(新 composition 首帧)对预览的处置。 */
enum class PreviewHostCreateAction {
    /** 页面未 Ready(分析中)/ 没有可恢复的预览:只重新挂探针,不做恢复。 */
    NONE,

    /**
     * 常规路径:播放器实例与位置都还在 —— 只重新挂探针,**绝不 seek/重建**。
     *
     * 这是"位置连续(不重置到 A)"的关键:重建不是重新起播。
     */
    REATTACH_ONLY,

    /** 兜底:重建期间播放器丢了(旧 release 路径/异常):按页面状态从原位置重建续播。 */
    RESTORE_FROM_PAGE_STATE,
}

/**
 * [PreviewHostLifecycle] 需要的最小宿主面。
 *
 * 生产实现 = [PreviewPlayer];单测 = 假实现(不碰 ExoPlayer/Android)。
 */
interface PreviewHostTarget {
    /** 预览是否已 prepare(窗口已记录)。 */
    val isPrepared: Boolean

    /** 已 prepare 的歌曲 id;null = 尚未 prepare。 */
    val songId: Long?

    /** 重新把当前实例挂到 [PreviewProbe](幂等:重建后必须仍可被 debug/量测端点读到)。 */
    fun attachProbe()

    /** 真正离开页面时释放预览(幂等)。生产 = 取消预填 + 释放播放器。 */
    fun release()
}

/**
 * 预览宿主生命周期策略(纯函数,可单测)。
 *
 * 背景(场景 #45):结果页的 Activity 没有声明 `android:configChanges`,旋转 / 分屏 /
 * 深色模式 / 字体缩放都会**销毁并重建 Activity**,Compose 组合随之销毁重建;而
 * [PreviewPlayer] 由跨配置变更存活的 ViewModel 持有。旧实现在 composition 的
 * `onDispose` 里无条件 `release()`,于是重建后:
 * - `player = null`、[PreviewProbe] 已 detach → `/debug/preview` 恒 409
 *   "preview probe not attached";
 * - 页面状态仍是 Ready,没有任何路径会再 prepare → 预览永久不可用。
 *
 * 正确语义:配置变更 = **换宿主不换播放器**(KEEP_ALIVE);真正离开页面才释放。
 */
object PreviewHostLifecyclePolicy {

    /**
     * @param isChangingConfigurations `Activity.isChangingConfigurations()`:
     *   true = 宿主因配置变更销毁(旋转/分屏/深色/字体),必须保留预览。
     *   该标记在 ActivityThread 走 relaunch 路径时、调用 onDestroy **之前**置位,
     *   所以组合 `onDispose` 里读到的就是重建语义。
     */
    fun onHostDestroy(isChangingConfigurations: Boolean): PreviewHostDestroyAction =
        if (isChangingConfigurations) {
            PreviewHostDestroyAction.KEEP_ALIVE
        } else {
            PreviewHostDestroyAction.RELEASE_AND_RESUME
        }

    /**
     * 新宿主首帧的处置。
     *
     * @param pageReady 页面状态是否 Ready(有 A/B 与位置可恢复);
     * @param prepared 预览播放器是否仍 prepared;
     * @param preparedSongId 播放器里已 prepare 的歌曲;
     * @param pageSongId 页面当前歌曲。
     */
    fun onHostCreate(
        pageReady: Boolean,
        prepared: Boolean,
        preparedSongId: Long?,
        pageSongId: Long?,
    ): PreviewHostCreateAction = when {
        !pageReady -> PreviewHostCreateAction.NONE
        prepared && preparedSongId != null && preparedSongId == pageSongId ->
            PreviewHostCreateAction.REATTACH_ONLY
        else -> PreviewHostCreateAction.RESTORE_FROM_PAGE_STATE
    }

    /**
     * 重建恢复的落点(展示口径 ms)= **页面当前位置**,只按时长 clamp。
     *
     * 明确不是 A:重建不是重新起播,位置必须连续(验收:旋转后不重置到 A)。
     */
    fun restoreDisplayMs(pagePositionMs: Long, durationMs: Long): Long {
        val pos = pagePositionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) pos.coerceAtMost(durationMs) else pos
    }
}

/**
 * 预览宿主生命周期状态机(生产接线:[LoopViewModel];单测:直接喂 onDestroy/onCreate 序列)。
 *
 * 决策后立即落地:配置变更 → 重挂探针(KEEP_ALIVE);真正离开 → 释放预览
 * (RELEASE_AND_RESUME)。重建后的**恢复**(需要页面 A/B 与 Song)由 ViewModel 执行。
 */
class PreviewHostLifecycle(
    private val target: PreviewHostTarget,
) {

    /** 宿主代数:首次进入 = 1,每次配置变更重建 +1(日志/断言用)。 */
    var generation: Int = 1
        private set

    /** 最近一次宿主销毁的处置(诊断用)。 */
    var lastDestroyAction: PreviewHostDestroyAction? = null
        private set

    fun onHostDestroyed(isChangingConfigurations: Boolean): PreviewHostDestroyAction {
        val action = PreviewHostLifecyclePolicy.onHostDestroy(isChangingConfigurations)
        lastDestroyAction = action
        if (action == PreviewHostDestroyAction.KEEP_ALIVE) {
            // 配置变更重建:播放器原样保留,只确保探针仍指着它(幂等)。
            generation += 1
            target.attachProbe()
        } else {
            // 真正离开:释放预览,调用方随后按主播放交接记录决定是否 resume。
            target.release()
        }
        return action
    }

    /**
     * 新宿主首帧。**无论哪种处置都先重新挂探针** —— 重建后探针必须指向仍活着的那一份
     * 播放器实例(幂等;旧引用若在重建期间被 detach 过也在这里自愈)。
     */
    fun onHostCreated(pageReady: Boolean, pageSongId: Long?): PreviewHostCreateAction {
        val action = PreviewHostLifecyclePolicy.onHostCreate(
            pageReady = pageReady,
            prepared = target.isPrepared,
            preparedSongId = target.songId,
            pageSongId = pageSongId,
        )
        target.attachProbe()
        return action
    }
}
