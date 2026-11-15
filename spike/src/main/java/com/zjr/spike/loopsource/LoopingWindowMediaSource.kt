package com.zjr.spike.loopsource

import android.os.Handler
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M1 对外入口：一个媒体项 / 一个窗口 / 一个 period，duration = 整曲时长（展示用），
 * 实际样本流永不结束（由 LoopingWindowPeriod 在内部按轮次续接）。
 */
@UnstableApi
class LoopingWindowMediaSource(
    private val config: LoopSourceConfig,
    private val childFactory: MediaSource.Factory,
    private val ticker: IterationTicker? = null,
) : MediaSource {

    private val timeline: Timeline = SinglePeriodTimeline(
        config.durationUs.coerceAtLeast(config.bUs),
        /* isSeekable= */ true,
        /* isDynamic= */ false,
        /* isLive= */ false,
        /* manifest= */ null,
        config.mediaItem,
    )

    private val listeners = CopyOnWriteArrayList<Pair<Handler, MediaSourceEventListener>>()
    private val drmListeners = CopyOnWriteArrayList<Pair<Handler, DrmSessionEventListener>>()

    private var transferListener: TransferListener? = null
    private var playerId: PlayerId = PlayerId.UNSET

    override fun getMediaItem(): MediaItem = config.mediaItem

    override fun prepareSource(
        caller: MediaSource.MediaSourceCaller,
        transferListener: TransferListener?,
        playerId: PlayerId,
    ) {
        this.transferListener = transferListener
        this.playerId = playerId
        caller.onSourceInfoRefreshed(this, timeline)
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // 无外部源信息可失败
    }

    override fun enable(caller: MediaSource.MediaSourceCaller) {
        // 无操作：子源在 createPeriod 时自行 prepare
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = LoopingWindowPeriod(
        config = config,
        childFactory = childFactory,
        allocator = allocator,
        ticker = ticker,
        sourceListeners = listeners.toList(),
        drmListeners = drmListeners.toList(),
        playerId = playerId,
        transferListener = transferListener,
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as? LoopingWindowPeriod)?.release()
    }

    override fun disable(caller: MediaSource.MediaSourceCaller) {
        // 无操作
    }

    override fun releaseSource(caller: MediaSource.MediaSourceCaller) {
        // 无操作
    }

    override fun addEventListener(handler: Handler, listener: MediaSourceEventListener) {
        listeners.add(handler to listener)
    }

    override fun removeEventListener(listener: MediaSourceEventListener) {
        listeners.removeAll { it.second === listener }
    }

    override fun addDrmEventListener(handler: Handler, listener: DrmSessionEventListener) {
        drmListeners.add(handler to listener)
    }

    override fun removeDrmEventListener(listener: DrmSessionEventListener) {
        drmListeners.removeAll { it.second === listener }
    }
}
