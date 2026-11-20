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

    /**
     * 00022/00023 真机计时探针：seek/起播请求时刻 → 首个 PCM buffer 进入 AudioSink 的耗时。
     *
     * 由 [RingBandExtractor.seek] 打点，[SourceRenderersFactory.handleBuffer] 消费并打
     * "LoopPerf first-audio" 日志；纯观测，不影响播放。
     */
    @Volatile
    private var seekProbeNanos: Long = 0L

    @Volatile
    private var seekProbeDisplayMs: Long = -1L

    fun noteSeekRequest(displayMs: Long) {
        seekProbeDisplayMs = displayMs
        seekProbeNanos = System.nanoTime()
    }

    /** 取走一次探针；返回 [elapsedMs, displayMs]，超过 [maxAgeMs] 视为过期。 */
    fun consumeSeekProbe(maxAgeMs: Long = 3_000L): LongArray? {
        val at = seekProbeNanos
        if (at <= 0L) return null
        seekProbeNanos = 0L
        val elapsedMs = (System.nanoTime() - at) / 1_000_000L
        if (elapsedMs < 0L || elapsedMs > maxAgeMs) return null
        return longArrayOf(elapsedMs, seekProbeDisplayMs)
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
