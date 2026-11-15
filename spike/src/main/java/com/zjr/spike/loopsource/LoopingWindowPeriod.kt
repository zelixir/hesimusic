package com.zjr.spike.loopsource

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSourceEventListener
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.util.concurrent.CopyOnWriteArrayList

/**
 * M1 核心：对外只有一个 MediaPeriod / 一个 SampleStream，内部链式维护“轮次”子 period。
 *
 * 每轮 = 一个裁剪到 [contentStart, bUs) 的 ClippingMediaSource 子 period：
 *  - index 0：前奏 [0, bUs)，base = 0；
 *  - index j>=1：[aUs, bUs)，base = bUs + (j-1)*loopUs。
 *
 * 同时存活 2 个轮次（当前 + 预取下一轮）。当前轮读到 EOS 时原地切到下一轮，
 * 时间戳按 “base + 切口内偏移” 改写、清掉 EOS 标志，对 renderer 永不返回 EOS。
 * readDiscontinuity() 恒为 C.TIME_UNSET。
 */
@UnstableApi
class LoopingWindowPeriod(
    private val config: LoopSourceConfig,
    private val childFactory: MediaSource.Factory,
    private val allocator: Allocator,
    private val ticker: IterationTicker?,
    private val sourceListeners: List<Pair<Handler, MediaSourceEventListener>>,
    private val drmListeners: List<Pair<Handler, DrmSessionEventListener>>,
    private val playerId: PlayerId,
    private val transferListener: TransferListener?,
) : MediaPeriod {

    private var callback: MediaPeriod.Callback? = null

    @Volatile
    private var current: Round? = null

    @Volatile
    private var next: Round? = null

    @Volatile
    private var selections: Array<ExoTrackSelection?> = emptyArray()

    @Volatile
    private var mayRetain: BooleanArray = BooleanArray(0)

    @Volatile
    private var wrappers: Array<LoopingSampleStream?> = emptyArray()

    @Volatile
    private var positionUs: Long = 0L

    @Volatile
    private var exhausted: Boolean = false

    @Volatile
    private var preparedNotified: Boolean = false

    private var lastFormat: Format? = null

    private val childCaller = object : MediaSource.MediaSourceCaller {
        override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) {
            // 时间线由 LoopingWindowMediaSource 自己给出，这里不需要转发。
        }
    }

    // ------------------------------------------------------------------ Round

    inner class Round(
        val index: Int,
        val baseUs: Long,
        val contentStartUs: Long,
        private val startPositionUs: Long,
        val reportSwitch: Boolean,
    ) {
        val mediaSource: MediaSource = ClippingMediaSource(
            childFactory.createMediaSource(config.mediaItem),
            contentStartUs,
            config.bUs,
            /* enableInitialDiscontinuity= */ true,
            /* allowDynamicClippingUpdates= */ false,
            /* relativeToDefaultPosition= */ false,
        )

        @Volatile
        var period: MediaPeriod? = null

        @Volatile
        var prepared: Boolean = false

        @Volatile
        var trackGroups: TrackGroupArray = TrackGroupArray.EMPTY

        @Volatile
        var childStreams: Array<SampleStream?> = emptyArray()

        /** 改写偏移：raw = childPts + deltaUs；首样本到达时按坐标形态确定。 */
        @Volatile
        var deltaUs: Long = Long.MIN_VALUE

        @Volatile
        var switchReported: Boolean = false

        val windowDurationUs: Long get() = if (index == 0) config.bUs else config.loopUs

        fun start() {
            for ((handler, listener) in sourceListeners) {
                mediaSource.addEventListener(handler, listener)
            }
            for ((handler, listener) in drmListeners) {
                mediaSource.addDrmEventListener(handler, listener)
            }
            mediaSource.prepareSource(childCaller, transferListener, playerId)
            val childPeriod = mediaSource.createPeriod(
                MediaSource.MediaPeriodId(index),
                allocator,
                startPositionUs,
            )
            period = childPeriod
            childPeriod.prepare(roundCallback(this), startPositionUs)
        }

        fun release() {
            val p = period ?: return
            period = null
            try {
                mediaSource.releasePeriod(p)
            } catch (_: Throwable) {
            }
            try {
                mediaSource.releaseSource(childCaller)
            } catch (_: Throwable) {
            }
        }
    }

    private fun roundCallback(round: Round): MediaPeriod.Callback = object : MediaPeriod.Callback {
        override fun onPrepared(mediaPeriod: MediaPeriod) {
            if (round !== current && round !== next) return
            round.prepared = true
            round.trackGroups = mediaPeriod.trackGroups
            if (selections.isNotEmpty()) {
                selectOnRound(round, if (round === current) positionUs else round.baseUs)
                if (round === current) bindWrappers()
            }
            if (round === current && !preparedNotified) {
                preparedNotified = true
                callback?.onPrepared(this@LoopingWindowPeriod)
            }
        }

        override fun onContinueLoadingRequested(source: MediaPeriod) {
            if (round === current) {
                callback?.onContinueLoadingRequested(this@LoopingWindowPeriod)
            } else if (round === next) {
                // 预取轮：当前轮还在播，直接驱动它的 loader（不经过 player）
                try {
                    source.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0L).build())
                } catch (_: Throwable) {
                }
            }
        }
    }

    // --------------------------------------------------------------- MediaPeriod

    override fun prepare(callback: MediaPeriod.Callback, minLoadPositionUs: Long) {
        this.callback = callback
        val startPos = if (minLoadPositionUs == C.TIME_UNSET) 0L else minLoadPositionUs.coerceAtLeast(0L)
        positionUs = startPos
        val index = roundIndexFor(startPos)
        val first = Round(
            index = index,
            baseUs = baseFor(index),
            contentStartUs = contentStartFor(index),
            startPositionUs = childPosFor(index, startPos),
            reportSwitch = false,
        )
        current = first
        first.start()
        val prefetch = Round(
            index = index + 1,
            baseUs = baseFor(index + 1),
            contentStartUs = config.aUs,
            startPositionUs = 0L,
            reportSwitch = true,
        )
        next = prefetch
        prefetch.start()
    }

    override fun maybeThrowPrepareError() {
        current?.period?.maybeThrowPrepareError()
    }

    override fun getTrackGroups(): TrackGroupArray = current?.trackGroups ?: TrackGroupArray.EMPTY

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        this.selections = selections
        this.mayRetain = mayRetainStreamFlags
        this.positionUs = positionUs
        val newWrappers = arrayOfNulls<LoopingSampleStream>(selections.size)
        for (i in selections.indices) {
            if (selections[i] == null) {
                streams[i] = null
                streamResetFlags[i] = false
                continue
            }
            val existing = streams.getOrNull(i) as? LoopingSampleStream
            val wrapper = existing ?: LoopingSampleStream(this, i)
            newWrappers[i] = wrapper
            // 关键：player 保留的是它传进来的 streams 数组，必须把包装流写回去。
            streams[i] = wrapper
            streamResetFlags[i] = existing == null
        }
        wrappers = newWrappers
        current?.let { selectOnRound(it, positionUs) }
        next?.let { if (it.prepared) selectOnRound(it, it.baseUs) }
        bindWrappers()
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        val round = current ?: return
        val childPos = (positionUs - round.baseUs).coerceAtLeast(0L)
        round.period?.discardBuffer(childPos, toKeyframe)
    }

    /** 对外表现为连续时间轴，永远不报告 discontinuity。 */
    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long {
        val index = roundIndexFor(positionUs)
        val base = baseFor(index)
        val contentStart = contentStartFor(index)
        val childPos = childPosFor(index, positionUs)
        this.positionUs = base + childPos
        val cur = current
        if (cur != null && cur.index == index && cur.period != null) {
            cur.deltaUs = Long.MIN_VALUE
            exhausted = false
            cur.period?.seekToUs(childPos)
            return base + childPos
        }
        for (wrapper in wrappers) {
            wrapper?.delegate = null
        }
        cur?.release()
        next?.release()
        current = null
        next = null
        val fresh = Round(index, base, contentStart, childPos, reportSwitch = false)
        current = fresh
        fresh.start()
        val prefetch = Round(index + 1, baseFor(index + 1), config.aUs, 0L, reportSwitch = true)
        next = prefetch
        prefetch.start()
        if (preparedNotified && selections.isNotEmpty()) {
            // 等 onPrepared 里 selectOnRound + bindWrappers；这里先不碰未就绪的子 period。
        }
        return base + childPos
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        positionUs

    override fun getBufferedPositionUs(): Long {
        val round = current ?: return C.TIME_UNSET
        val child = round.period?.bufferedPositionUs ?: return round.baseUs
        if (child == C.TIME_END_OF_SOURCE) return round.baseUs + round.windowDurationUs
        return round.baseUs + child
    }

    override fun getNextLoadPositionUs(): Long {
        val round = current ?: return C.TIME_UNSET
        val child = round.period?.nextLoadPositionUs ?: return round.baseUs
        if (child == C.TIME_END_OF_SOURCE) {
            val nextRound = next ?: return C.TIME_END_OF_SOURCE
            val nextChild = nextRound.period?.nextLoadPositionUs ?: return nextRound.baseUs
            return if (nextChild == C.TIME_END_OF_SOURCE) {
                nextRound.baseUs + nextRound.windowDurationUs
            } else {
                nextRound.baseUs + nextChild
            }
        }
        return round.baseUs + child
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        val round = current ?: return false
        val childPos = (positionUs - round.baseUs).coerceAtLeast(0L)
        var loading = round.period?.continueLoading(
            loadingInfo.buildUpon().setPlaybackPositionUs(childPos).build(),
        ) ?: false
        val nextRound = next
        if (nextRound != null && nextRound.prepared) {
            val nextLoading = nextRound.period?.continueLoading(
                LoadingInfo.Builder().setPlaybackPositionUs(0L).build(),
            ) ?: false
            loading = loading || nextLoading
        }
        return loading
    }

    override fun isLoading(): Boolean = (current?.period?.isLoading() ?: false) || (next?.period?.isLoading() ?: false)

    override fun reevaluateBuffer(positionUs: Long) {
        val round = current ?: return
        val childPos = (positionUs - round.baseUs).coerceAtLeast(0L)
        round.period?.reevaluateBuffer(childPos)
    }

    // ------------------------------------------------------------ SampleStream

    fun isStreamReady(index: Int): Boolean {
        val round = current ?: return false
        if (exhausted) {
            val nextRound = next ?: return false
            val stream = nextRound.childStreams.getOrNull(index) ?: return false
            return nextRound.prepared && stream.isReady()
        }
        return round.childStreams.getOrNull(index)?.isReady() ?: false
    }

    fun maybeThrowStreamError(index: Int) {
        current?.childStreams?.getOrNull(index)?.maybeThrowError()
    }

    fun skipData(index: Int, positionUs: Long): Int {
        val round = current ?: return 0
        val childPos = (positionUs - round.baseUs).coerceAtLeast(0L)
        return round.childStreams.getOrNull(index)?.skipData(childPos) ?: 0
    }

    fun readData(index: Int, formatHolder: FormatHolder, buffer: DecoderInputBuffer, flags: Int): Int {
        var attempts = 0
        while (attempts++ < 6) {
            if (exhausted) {
                if (!advanceRound(index)) {
                    requestPrefetchLoad(index)
                    return C.RESULT_NOTHING_READ
                }
            }
            val round = current ?: return C.RESULT_NOTHING_READ
            val stream = round.childStreams.getOrNull(index) ?: return C.RESULT_NOTHING_READ
            val result = stream.readData(formatHolder, buffer, flags)
            when (result) {
                C.RESULT_FORMAT_READ -> {
                    val format = formatHolder.format
                    if (lastFormat != null && lastFormat == format) {
                        // 同一格式跨轮重复下发：吞掉，避免 renderer 重新 configure AudioSink
                        continue
                    }
                    lastFormat = format
                    return result
                }
                C.RESULT_BUFFER_READ -> {
                    if (buffer.isEndOfStream) {
                        buffer.clear()
                        exhausted = true
                        continue
                    }
                    if (round.deltaUs == Long.MIN_VALUE) {
                        round.deltaUs = computeDeltaUs(round, buffer.timeUs)
                    }
                    buffer.timeUs += round.deltaUs
                    buffer.clearFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    if (!round.switchReported) {
                        round.switchReported = true
                        if (round.reportSwitch && round.index > 0) {
                            ticker?.onRoundSwitch(round.index, round.baseUs, buffer.timeUs)
                        }
                    }
                    return result
                }
                else -> return result
            }
        }
        return C.RESULT_NOTHING_READ
    }

    // ------------------------------------------------------------------ internals

    private fun advanceRound(index: Int): Boolean {
        val nextRound = next ?: return false
        if (!nextRound.prepared) {
            requestLoad(nextRound)
            return false
        }
        if (nextRound.childStreams.getOrNull(index) == null && selections.isNotEmpty()) {
            selectOnRound(nextRound, nextRound.baseUs)
        }
        val childStream = nextRound.childStreams.getOrNull(index) ?: return false
        if (!childStream.isReady()) {
            if (nextRound.period?.isLoading() != true) {
                requestLoad(nextRound)
            }
            // 仍可切换（后续 readData 会返回 NOTHING_READ 直到就绪），但先确保不丢轮次。
        }
        val old = current
        current = nextRound
        next = null
        exhausted = false
        bindWrappers()
        old?.release()
        val prefetch = Round(
            index = nextRound.index + 1,
            baseUs = baseFor(nextRound.index + 1),
            contentStartUs = config.aUs,
            startPositionUs = 0L,
            reportSwitch = true,
        )
        next = prefetch
        prefetch.start()
        if (selections.isNotEmpty()) {
            selectOnRound(prefetch, prefetch.baseUs)
        }
        return true
    }

    private fun requestLoad(round: Round?) {
        try {
            round?.period?.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0L).build())
        } catch (_: Throwable) {
        }
    }

    private fun requestPrefetchLoad(index: Int) {
        requestLoad(next)
    }

    private fun selectOnRound(round: Round, positionUs: Long) {
        val period = round.period ?: return
        if (!round.prepared) return
        val sel = selections
        if (sel.isEmpty()) return
        val childPos = (positionUs - round.baseUs).coerceIn(0L, round.windowDurationUs)
        val streams: Array<SampleStream?> =
            if (round.childStreams.size == sel.size) round.childStreams else arrayOfNulls(sel.size)
        period.selectTracks(
            sel,
            mayRetain,
            streams,
            BooleanArray(sel.size),
            childPos,
        )
        round.childStreams = streams
        if (round === current) {
            bindWrappers()
        }
    }

    private fun bindWrappers() {
        val round = current ?: return
        val streams = round.childStreams
        for (i in wrappers.indices) {
            wrappers[i]?.delegate = streams.getOrNull(i)
        }
    }

    /**
     * 子 period 的样本时间戳可能是“整曲坐标”（≈contentStartUs）或“切口坐标”（≈0）。
     * 用首样本判断，选择让该轮起点落在 baseUs 的偏移。
     */
    private fun computeDeltaUs(round: Round, rawPtsUs: Long): Long {
        val looksLikeSourceCoords =
            rawPtsUs >= round.contentStartUs - 100_000L && rawPtsUs <= round.contentStartUs + 2_000_000L
        return if (looksLikeSourceCoords) round.baseUs - round.contentStartUs else round.baseUs
    }

    private fun roundIndexFor(positionUs: Long): Int =
        if (positionUs < config.bUs) 0 else (1L + (positionUs - config.bUs) / config.loopUs).toInt()

    private fun baseFor(index: Int): Long =
        if (index <= 0) 0L else config.bUs + (index - 1).toLong() * config.loopUs

    private fun contentStartFor(index: Int): Long = if (index <= 0) 0L else config.aUs

    private fun childPosFor(index: Int, positionUs: Long): Long =
        if (index <= 0) positionUs.coerceAtLeast(0L)
        else Math.floorMod(positionUs - config.aUs, config.loopUs)

    fun release() {
        for (wrapper in wrappers) {
            wrapper?.delegate = null
        }
        current?.release()
        next?.release()
        current = null
        next = null
    }
}