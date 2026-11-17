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
