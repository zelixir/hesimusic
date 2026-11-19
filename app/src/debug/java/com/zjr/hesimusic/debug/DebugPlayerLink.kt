package com.zjr.hesimusic.debug

/**
 * 播放器链路有效性口径(纯 Kotlin、无 Android 依赖,便于 JVM 单测)。
 *
 * 背景(问题回归):旧实现把 "queueSize=0 && idle && position=0" 当作 snapshot invalid,
 * 且 controllerAge >= 3s 就强制重建 controller。空闲/未点歌时快照天然就是这个形态,
 * 于是每个 /player、/loop/enter 请求都会拆掉 controller 重建 → /state 的 connecting
 * 永远为 true、/player 永远 503。
 *
 * 新口径:invalid 只由链路本身决定——没有 controller 或 controller 已断开;
 * age 仅用于日志,快照内容(queue/playbackState/position)只用于日志与展示,永不触发重建。
 */
internal object DebugPlayerLink {

    /** 尚无 controller(包括冷启动懒建连还没完成)。 */
    const val REASON_NOT_CONNECTED = "controller_not_connected"

    /** controller 曾存在但已断开(收到 Session 断连回调或主动发现 isConnected=false)。 */
    const val REASON_DISCONNECTED = "controller_disconnected"

    /** controller 已连接,但主线程读快照超时且没有可用缓存。 */
    const val REASON_SNAPSHOT_TIMEOUT = "snapshot_timeout"

    /** controller 已连接,但主线程读快照抛异常且没有可用缓存。 */
    const val REASON_SNAPSHOT_FAILED = "snapshot_failed"

    /** 服务端还没创建 DebugPlayerConnection(debug server 启动极早期)。 */
    const val REASON_NOT_INITIALIZED = "player_not_initialized"

    /** 链路正常但本次读取不可用的兜底原因。 */
    const val REASON_UNAVAILABLE = "snapshot_unavailable"

    /**
     * snapshot invalid 的唯一判定。
     *
     * @param hasController controller 引用是否存在
     * @param connected controller.isConnected(链路是否真的还在)
     */
    fun isInvalid(hasController: Boolean, connected: Boolean): Boolean =
        !hasController || !connected

    /**
     * 不可用原因:链路原因优先;链路正常时才采用读取失败兜底(fallback)。
     * controller 已断开后引用会被清空,此时用 fallback=REASON_DISCONNECTED 保留断连语义。
     */
    fun reason(
        hasController: Boolean,
        connected: Boolean,
        fallback: String? = null,
    ): String = when {
        hasController && !connected -> REASON_DISCONNECTED
        !hasController -> fallback ?: REASON_NOT_CONNECTED
        else -> fallback ?: REASON_UNAVAILABLE
    }

    /** 503 响应体里的可读 message(PC 脚本仍以 reason 字段为准)。 */
    fun message(reason: String): String = when (reason) {
        REASON_NOT_CONNECTED -> "player controller not connected"
        REASON_DISCONNECTED -> "player controller disconnected"
        REASON_SNAPSHOT_TIMEOUT -> "player snapshot timeout"
        REASON_SNAPSHOT_FAILED -> "player snapshot failed"
        REASON_NOT_INITIALIZED -> "player connection not initialized"
        else -> "player snapshot unavailable"
    }
}
