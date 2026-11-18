package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMediaTag

/**
 * 主播放器路线快照：**一个布尔** + 四个接线点。
 *
 * 背景（曾经翻车）：renderers 看一个开关、MediaSourceFactory 看另一个、协调器与会话层又各自
 * 读全局开关，于是出现「换了输出通路（SOURCE renderers）但播放源还是普通文件」——卡成 PPT。
 *
 * 现在 [com.zjr.hesimusic.di.MediaModule] 构建主播放器时**只算一次**
 * [useSourceForMainPlayer]，存进 [MainPlayerRouting]，四个接线点全部从这一个快照取值：
 *
 * 1. [sourceRenderers]           —— di/MediaModule.kt 的 renderers 选择；
 * 2. [mediaSourceFactoryEnabled] —— LoopAwareMediaSourceFactory.enabled（只接管带 tag 的 item）；
 * 3. [coordinatorReplacesItem]   —— LoopPlaybackCoordinator.syncSourceItem 的替换分支；
 * 4. [sessionProjectsSource]     —— LoopSessionPlayer 的位置投影分支。
 *
 * 快照是**构建时**的：运行期改全局开关不会造成半切换（换 sink 必须重建播放器）。
 */
data class MainPlayerRoute(val useSourceForMainPlayer: Boolean) {

    /** ① renderers 是否用 SOURCE 版本（TapAudioSink，不改 PTS）。 */
    val sourceRenderers: Boolean get() = useSourceForMainPlayer

    /** ② LoopAwareMediaSourceFactory 是否接管带 [LoopSourceItemTag] 的 item。 */
    val mediaSourceFactoryEnabled: Boolean get() = useSourceForMainPlayer

    /** ③ 协调器是否把当前 item 换成循环数据源（进入循环）。 */
    val coordinatorReplacesItem: Boolean get() = useSourceForMainPlayer

    /** ④ 会话层是否按「数据源时间轴 T」投影对外位置/时长。 */
    val sessionProjectsSource: Boolean get() = useSourceForMainPlayer

    companion object {
        /** M3′ 路线（默认；与旧引擎行为完全一致）。 */
        val M3_PRIME: MainPlayerRoute = MainPlayerRoute(useSourceForMainPlayer = false)

        /**
         * 唯一入口：把「主播放器开关 **且** 预览/引擎开关」这一个布尔固化成快照。
         *
         * 生产侧的唯一来源是
         * [com.zjr.hesimusic.loop.audio.source.LoopSourceMainPlayer.shouldUseSourceForMainPlayer]；
         * 本工厂只做类型化，不重复判定，避免出现第二套开关语义。
         */
        fun of(useSourceForMainPlayer: Boolean): MainPlayerRoute =
            MainPlayerRoute(useSourceForMainPlayer = useSourceForMainPlayer)
    }
}

/**
 * 路线快照的进程内持有者 + 四个接线点的判定入口。
 *
 * 生产代码的四处各自调用**本对象**里对应的方法（而不是各自读全局开关），所以
 * 「四处一致」可以被单测直接钉住（见 MainPlayerRouteTest）。
 */
object MainPlayerRouting {

    /** 当前主播放器路线；只有 [activate] 能改（MediaModule 构建播放器时调用一次）。 */
    @Volatile
    var current: MainPlayerRoute = MainPlayerRoute.M3_PRIME
        private set

    fun activate(route: MainPlayerRoute) {
        current = route
    }

    /** 测试/调试复位（不重建播放器就恒为 M3′）。 */
    fun reset() {
        current = MainPlayerRoute.M3_PRIME
    }

    // ---------------------------------------------------------------- 四个接线点

    /** ① MediaModule：renderers 选择。 */
    fun renderersUseSource(route: MainPlayerRoute = current): Boolean = route.sourceRenderers

    /** ② MediaModule：LoopAwareMediaSourceFactory.enabled。 */
    fun mediaSourceFactoryEnabled(route: MainPlayerRoute = current): Boolean =
        route.mediaSourceFactoryEnabled

    /**
     * ③ 协调器：是否把当前 item 替换成循环数据源。
     *
     * 除了路线本身，还要求「有窗口」且「该窗口没有被登记为 SOURCE 不可用」。
     */
    fun coordinatorReplacesItem(
        window: LoopWindow?,
        sourceUnavailable: Boolean,
        route: MainPlayerRoute = current,
    ): Boolean = route.coordinatorReplacesItem && window != null && !sourceUnavailable

    /**
     * ④ 会话层：是否按 SOURCE 时间轴投影。
     *
     * 只有真的带循环 tag 的 item 才投影；SOURCE 不可用的窗口一律透传媒体时间。
     */
    fun sessionProjectsSource(
        tag: Any?,
        sourceUnavailable: Boolean,
        route: MainPlayerRoute = current,
    ): Boolean = route.sessionProjectsSource && !sourceUnavailable && isLoopSourceTag(tag)

    /** 循环数据源标记：正式播放的 [LoopSourceItemTag] 或预览页的 [LoopSourceMediaTag]。 */
    fun isLoopSourceTag(tag: Any?): Boolean =
        tag is LoopSourceItemTag || tag === LoopSourceMediaTag
}
