package com.zjr.hesimusic.loop.audio.source

import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 循环 PCM 数据源的 [MediaSource] 壳。
 *
 * Timeline 是单窗口、未知时长、动态、可 seek 的无限时间轴：
 * `durationUs = C.TIME_UNSET`，播放器不钳制位置；只要 SampleStream 不返回 EOS，
 * 播放就不会结束。B→A 发生在 [LoopingPcmSourceCore] 内部，播放器 never seek。
 *
 * 原型只提供最小的 MediaSource 生命周期；事件监听器只记录不派发（不影响播放）。
 * 生产接线时应补 `MediaSourceEventListener.EventDispatcher`。
 */
@UnstableApi
class LoopingPcmMediaSource(
    private val item: MediaItem,
    private val timeline: LoopingPcmTimeline,
    private val provider: PcmRangeProvider,
    private val maxCacheBytes: Long = PcmBlockCache.DEFAULT_MAX_BYTES,
    private val logger: (String) -> Unit = { message -> Log.w("LoopPcmSource", message) },
) : MediaSource {

    private val lock = Any()
    private val callers = CopyOnWriteArrayList<MediaSource.MediaSourceCaller>()
    private val periods = CopyOnWriteArrayList<LoopingPcmMediaPeriod>()
    private val sourceEventListeners = CopyOnWriteArrayList<Pair<Handler, MediaSourceEventListener>>()
    private val drmEventListeners = CopyOnWriteArrayList<Pair<Handler, DrmSessionEventListener>>()
    private var enabled = false
    private var released = false
    private var sourceError: String? = null

    /** 单窗口、动态、未知时长；isSeekable=true 让 Player 接受 seek 并落到 MediaPeriod。 */
    private val sourceTimeline: Timeline = SinglePeriodTimeline(
        /* periodDurationUs= */ C.TIME_UNSET,
        /* windowDurationUs= */ C.TIME_UNSET,
        /* windowPositionInPeriodUs= */ 0L,
        /* windowDefaultStartPositionUs= */ 0L,
        /* isSeekable= */ true,
        /* isDynamic= */ true,
        /* useLiveConfiguration= */ false,
        /* manifest= */ null,
        /* mediaItem= */ item,
    )

    override fun prepareSource(
        caller: MediaSource.MediaSourceCaller,
        mediaTransferListener: TransferListener?,
        playerId: PlayerId,
    ) {
        synchronized(lock) {
            if (released) return
            callers.add(caller)
        }
        caller.onSourceInfoRefreshed(this, sourceTimeline)
    }

    override fun maybeThrowSourceInfoRefreshError() {
        val error = synchronized(lock) { sourceError }
        if (error != null) throw IOException(error)
    }

    override fun enable(caller: MediaSource.MediaSourceCaller) {
        synchronized(lock) { enabled = true }
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        synchronized(lock) {
            if (!enabled) throw IllegalStateException("MediaSource is not enabled")
        }
        val period = LoopingPcmMediaPeriod(
            timeline = timeline,
            provider = provider,
            maxCacheBytes = maxCacheBytes,
            startPositionUs = startPositionUs,
            logger = logger,
        )
        periods.add(period)
        return period
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as? LoopingPcmMediaPeriod)?.release()
        periods.remove(mediaPeriod)
    }

    override fun disable(caller: MediaSource.MediaSourceCaller) {
        synchronized(lock) { enabled = false }
    }

    override fun releaseSource(caller: MediaSource.MediaSourceCaller) {
        callers.remove(caller)
        if (callers.isEmpty()) {
            synchronized(lock) { released = true }
            periods.forEach { it.release() }
            periods.clear()
        }
    }

    override fun getInitialTimeline(): Timeline = sourceTimeline

    override fun isSingleWindow(): Boolean = true

    override fun getMediaItem(): MediaItem = item

    override fun addEventListener(handler: Handler, eventListener: MediaSourceEventListener) {
        sourceEventListeners.add(handler to eventListener)
    }

    override fun removeEventListener(eventListener: MediaSourceEventListener) {
        sourceEventListeners.removeAll { it.second === eventListener }
    }

    override fun addDrmEventListener(handler: Handler, eventListener: DrmSessionEventListener) {
        drmEventListeners.add(handler to eventListener)
    }

    override fun removeDrmEventListener(eventListener: DrmSessionEventListener) {
        drmEventListeners.removeAll { it.second === eventListener }
    }
}
