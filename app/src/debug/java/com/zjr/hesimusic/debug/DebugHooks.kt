package com.zjr.hesimusic.debug

/**
 * 无缝播放层 → debug 协议 的只读桥(仅 debug 源集)。
 *
 * 播放层(LoopRuntime/LoopAudioController/MusicService)由 lead 后续接线:
 *   DebugHooks.loopBridge = object : LoopDebugBridge {
 *       override fun snapshot(): Map<String, Any?> = ...
 *   }
 * 本接口刻意不依赖任何 main 源集类型,避免 debug/release 源集耦合。
 */
interface LoopDebugBridge {
    /** 返回可 JSON 序列化的快照;任意键值,原样出现在 GET /state 的 loop 字段。 */
    fun snapshot(): Map<String, Any?>
}

/**
 * debug 协议的可选接线点。全部 @Volatile,允许运行期任意线程写入。
 *
 * 重要:main 源集**不得**直接引用本对象 —— release 构建不包含 debug 源集,会编译失败。
 * 接线应由 debug 源集内的组件完成(或 main 用反射/可选入口,由 lead 决定)。
 */
object DebugHooks {

    /** 无缝播放状态桥;null 时 GET /state 的 loop 字段为 JSON null。 */
    @Volatile
    var loopBridge: LoopDebugBridge? = null

    /** 页面导航钩子(主线程回调);null 时 POST /navigate 返回 501。 */
    @Volatile
    var navigate: ((String) -> Unit)? = null
}
