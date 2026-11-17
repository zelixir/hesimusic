package com.zjr.hesimusic.loop.audio.source

/**
 * 进程内预览播放调试桥。debug 源集可调用 [requestSeek]，
 * 生产源集只注册/清理，不依赖 debug 代码。
 */
object LoopPreviewDebugBridge {

    @Volatile
    var seekRequest: ((Long) -> Unit)? = null
        private set

    @Volatile
    var lastRequestedMs: Long? = null
        private set

    /**
     * 预览播放器用的是**独立**的 [com.zjr.hesimusic.loop.audio.LoopAudioController]
     * 实例，和主播放不共享。所以 /debug/tap 必须同时把 tap 设到预览实例上，
     * 否则 SOURCE 路径写 PCM 时 tapOut 永远是 null。
     */
    @Volatile
    var tapSink: ((java.io.File?) -> Unit)? = null
        private set

    fun registerTap(handler: ((java.io.File?) -> Unit)?) {
        tapSink = handler
    }

    fun setTap(file: java.io.File?) {
        tapSink?.invoke(file)
    }

    /** 切换引擎：一次性清空 seek/tap 回调，旧播放器句柄不可再被调用。 */
    fun release() {
        seekRequest = null
        tapSink = null
        lastRequestedMs = null
    }

    fun register(handler: (Long) -> Unit) {
        seekRequest = handler
    }

    fun unregister(handler: ((Long) -> Unit)? = null) {
        if (handler == null || seekRequest === handler) {
            seekRequest = null
        }
    }

    /** 返回是否已注册；positionMs 为展示口径。 */
    fun requestSeek(positionMs: Long): Boolean {
        val handler = seekRequest ?: return false
        lastRequestedMs = positionMs
        handler(positionMs)
        return true
    }
}
