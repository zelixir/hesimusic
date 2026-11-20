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

    /**
     * 一次 seek 的逐段计时（issue 00023 后续）。
     *
     * 时间基准统一用 [System.nanoTime]（真机与 JVM 单测都能用，单调钟）；
     * playout 例外：media3 `onAudioPositionAdvancing` 给的是**墙钟**（System.currentTimeMillis
     * 同域），所以与请求时刻的墙钟相减。
     * 打点方：
     * - [PreviewPlayer]：请求（[begin]）、seekTo 落地（[noteApplied]）、READY（[noteReady]）、
     *   AudioTrack 真正开始出声（[notePlayout]）；
     * - [RingBandExtractor.seek]：[noteExtractorSeek]（media3 loading 线程）；
     * - [TapAudioSink]：首个 PCM buffer 进 sink（[noteFirstBuffer]）、sink.play（[noteSinkPlay]）。
     *
     * 只保留最近一次 seek 的分段；同一段重复打点取第一次。
     */
    class SeekStages {

        @Volatile
        private var generation = 0L

        @Volatile
        private var requestNanos = 0L

        @Volatile
        private var requestElapsedMs = 0L

        /**
         * 请求时刻的墙钟（System.currentTimeMillis，ms）。
         *
         * media3 的 `onAudioPositionAdvancing(playoutStartSystemTimeMs)` 给的是**墙钟**
         * （真机实测 1.79e12 量级），不是 elapsedRealtime，所以出声时长必须与墙钟相减。
         */
        @Volatile
        private var requestWallMs = 0L

        @Volatile
        private var appliedNanos = 0L

        @Volatile
        private var extractorSeekNanos = 0L

        @Volatile
        private var firstBufferNanos = 0L

        @Volatile
        private var sinkPlayNanos = 0L

        @Volatile
        private var readyNanos = 0L

        @Volatile
        private var playoutElapsedMs = 0L

        @Volatile
        private var playoutObservedNanos = 0L

        /** 新一次 seek 请求；清空上一轮各段。 */
        fun begin(requestElapsedMs: Long, requestWallMs: Long = System.currentTimeMillis()) {
            generation += 1L
            this.requestElapsedMs = requestElapsedMs
            this.requestWallMs = requestWallMs
            requestNanos = System.nanoTime()
            appliedNanos = 0L
            extractorSeekNanos = 0L
            firstBufferNanos = 0L
            sinkPlayNanos = 0L
            readyNanos = 0L
            playoutElapsedMs = 0L
            playoutObservedNanos = 0L
        }

        fun noteApplied() {
            if (requestNanos > 0L && appliedNanos == 0L) appliedNanos = System.nanoTime()
        }

        fun noteExtractorSeek() {
            if (requestNanos > 0L && extractorSeekNanos == 0L) extractorSeekNanos = System.nanoTime()
        }

        fun noteFirstBuffer() {
            // 只认 media3 已经调到 Extractor.seek 之后进来的 buffer：seek 请求刚发出时，
            // sink 里可能还有上一个位置的 buffer 被重复递进来（consumed=false 重试），会把
            // firstBuffer 记成 0~1ms。
            if (requestNanos > 0L && extractorSeekNanos > 0L && firstBufferNanos == 0L) {
                firstBufferNanos = System.nanoTime()
            }
        }

        fun noteSinkPlay() {
            if (requestNanos > 0L && sinkPlayNanos == 0L) sinkPlayNanos = System.nanoTime()
        }

        fun noteReady() {
            if (requestNanos > 0L && readyNanos == 0L) readyNanos = System.nanoTime()
        }

        /**
         * media3 `onAudioPositionAdvancing(eventTime, playoutStartSystemTimeMs)`：
         * playoutStartSystemTimeMs 是 AudioTrack 播放头开始推进的 elapsedRealtime 时刻，
         * 也就是"真正出声"的应用侧代理（比播放器位置轮询稳定：轮询会被 seek 未确认的旧位置骗到）。
         */
        fun notePlayout(playoutStartSystemTimeMs: Long) {
            if (requestNanos <= 0L || playoutElapsedMs != 0L) return
            val request = requestWallMs
            if (request <= 0L) return
            val delta = playoutStartSystemTimeMs - request
            // 拒绝明显不属于本次 seek 的事件（上一轮迟到的出声、或时钟域不对）。
            if (delta < 0L || delta > PLAYOUT_MAX_AGE_MS) return
            playoutElapsedMs = playoutStartSystemTimeMs
            playoutObservedNanos = System.nanoTime()
        }

        private fun msSince(nanos: Long): Long? {
            val base = requestNanos
            if (base <= 0L || nanos <= 0L || nanos < base) return null
            return (nanos - base) / 1_000_000L
        }

        private companion object {
            /** 出声事件超过这个时长就不算本次 seek（防止上一轮迟到的事件串台）。 */
            const val PLAYOUT_MAX_AGE_MS = 3_000L
        }

        fun snapshot(): Map<String, Any?> = linkedMapOf(
            "generation" to generation,
            // 请求时刻的 elapsedRealtime（与 lastSeekRequestAtMs 同口径，便于和旧指标对齐）
            "requestElapsedMs" to requestElapsedMs,
            // 请求 → ExoPlayer.seekTo 落地（主线程应用延迟）
            "applyMs" to msSince(appliedNanos),
            // 请求 → media3 真正调到 Extractor.seek
            "extractorSeekMs" to msSince(extractorSeekNanos),
            // 请求 → 首个 PCM buffer 进入 AudioSink（数据侧终点）
            "firstBufferMs" to msSince(firstBufferNanos),
            // 请求 → sink.play()（新建 AudioTrack 之后、调用 AudioTrack.start 之前）
            "sinkPlayMs" to msSince(sinkPlayNanos),
            // 请求 → 播放器 READY
            "readyMs" to msSince(readyNanos),
            // 请求 → AudioTrack 播放头开始推进（应用侧"真正出声"代理）
            "playoutMs" to if (playoutElapsedMs > 0L && requestWallMs > 0L) {
                (playoutElapsedMs - requestWallMs).coerceIn(0L, PLAYOUT_MAX_AGE_MS)
            } else {
                null
            },
            "playoutObservedMs" to msSince(playoutObservedNanos),
        )
    }

    /** 最近一次 seek 的逐段计时（[SeekStages]）。 */
    val seekStages = SeekStages()

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
