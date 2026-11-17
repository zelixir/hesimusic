package com.zjr.hesimusic.loop.audio.source

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 循环 PCM 的 [MediaPeriod]。
 *
 * - 一个 [LoopingPcmSourceCore] + 一个 [LoopingPcmSampleStream]；
 * - 单线程 executor 串行解码（MediaCodec 单实例约束）；
 * - 当前块优先、预取 2 块；首块就绪后 onPrepared；
 * - seek 只改 core cursor 并异步补目标块，不阻塞播放线程；
 * - 解码任务带 generation，连续拖动只有最后一次目标会继续解码。
 */
/** 回调投递抽象：默认 Handler，JVM 测试可注入假 dispatcher。 */
interface CallbackDispatcher {
    fun post(runnable: Runnable)
    fun postDelayed(runnable: Runnable, delayMs: Long)
    fun remove(runnable: Runnable)
}

private class AndroidCallbackDispatcher(private val handler: Handler) : CallbackDispatcher {
    override fun post(runnable: Runnable) {
        handler.post(runnable)
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
    }

    override fun remove(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

@UnstableApi
class LoopingPcmMediaPeriod(
    timeline: LoopingPcmTimeline,
    private val provider: PcmRangeProvider,
    private val maxCacheBytes: Long = PcmBlockCache.DEFAULT_MAX_BYTES,
    startPositionUs: Long = 0L,
    dispatcher: CallbackDispatcher? = null,
    private val logger: (String) -> Unit = { message -> Log.w(TAG, message) },
) : MediaPeriod {

    private var core = LoopingPcmSourceCore(
        timeline = timeline,
        provider = provider,
        maxCacheBytes = maxCacheBytes,
        logger = logger,
    )

    private val format: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(timeline.sampleRate)
        .setChannelCount(timeline.channels)
        .build()

    private var stream = LoopingPcmSampleStream(core, format)

    private val callbackHandler: CallbackDispatcher = dispatcher
        ?: AndroidCallbackDispatcher(Handler(Looper.myLooper() ?: Looper.getMainLooper()))

    private fun logD(message: String) = logger("D: " + message)
    private fun logE(message: String, throwable: Throwable? = null) {
        logger(
            "E: " + message + if (throwable == null) "" else
                " throwable=" + throwable.javaClass.name + ": " + throwable.message,
        )
    }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "loop-pcm-decode").apply {
            isDaemon = true
            priority = Process.THREAD_PRIORITY_BACKGROUND
        }
    }

    private val stateLock = Any()
    private val requestGeneration = AtomicLong(0L)
    private val activeTasks = AtomicInteger(0)
    private var callback: MediaPeriod.Callback? = null
    private var prepared = false
    private var released = false
    private var prepareError: String? = null
    private var prepareTimeout: Runnable? = null

    init {
        core.seekToTimelineUs(startPositionUs)
        logD("period created startPositionUs=" + startPositionUs + " " + core.debugStateString())
    }

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        logD("prepare start positionUs=" + positionUs + " " + core.debugStateString())
        synchronized(stateLock) {
            this.callback = callback
            if (prepared) {
                logD("prepare already ready; notify")
                callbackHandler.post { callback.onPrepared(this) }
                return
            }
        }
        core.seekToTimelineUs(positionUs)
        logD("prepare after seek positionUs=" + positionUs + " " + core.debugStateString())
        requestDecode(firstBlock = true)
        schedulePrepareTimeout()
    }

    private fun schedulePrepareTimeout() {
        val timeout = Runnable {
            val ready = core.isCurrentBlockReady()
            if (!ready) {
                val message = "source first block timeout after " + PREPARE_TIMEOUT_MS +
                    "ms " + core.debugStateString()
                synchronized(stateLock) {
                    if (prepareError == null) prepareError = message
                    grabbedCallback()?.let { cb ->
                        // 让 ExoPlayer 在下次 maybeThrowPrepareError 时拿到错误，而不是无限 BUFFERING。
                        logE(message)
                        callbackHandler.post { cb.onContinueLoadingRequested(this@LoopingPcmMediaPeriod) }
                    }
                }
                logE(message)
            } else {
                logD("prepare timeout check: already ready")
            }
        }
        prepareTimeout?.let { callbackHandler.remove(it) }
        prepareTimeout = timeout
        callbackHandler.postDelayed(timeout, PREPARE_TIMEOUT_MS)
    }

    private fun grabbedCallback(): MediaPeriod.Callback? = synchronized(stateLock) { callback }

    private fun clearPrepareTimeout() {
        prepareTimeout?.let { callbackHandler.remove(it) }
        prepareTimeout = null
    }

    override fun maybeThrowPrepareError() {
        val error = synchronized(stateLock) { prepareError }
        if (error != null) throw IOException(error)
    }

    override fun getTrackGroups(): TrackGroupArray = TrackGroupArray(TrackGroup(format))

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        // 注意：数组按 renderer 索引，不保证 audio 在 0（默认 renderers 中 video=0, audio=1）。
        // 只把唯一的音频流挂到第一个非空 selection 上，其余清空。
        LoopTrackAssignment.assign(
            selected = BooleanArray(selections.size) { selections[it] != null },
            streams = streams,
            stream = stream,
            streamResetFlags = streamResetFlags,
        )
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        // 本实现没有独立的数据队列：seek 已把 cursor 指向新位置；这里无需搬数据。
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long {
        val target = core.seekToTimelineUs(positionUs)
        stream.onSeek()
        requestDecode(firstBlock = false)
        return target
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        positionUs

    override fun getBufferedPositionUs(): Long = core.bufferedEndUs()

    override fun getNextLoadPositionUs(): Long =
        if (core.needsDecode()) core.bufferedEndUs() else C.TIME_END_OF_SOURCE

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        if (isReleased()) return false
        if (!core.needsDecode() && core.isCurrentBlockReady()) return false
        requestDecode(firstBlock = false)
        return true
    }

    override fun isLoading(): Boolean = activeTasks.get() > 0

    override fun reevaluateBuffer(positionUs: Long) {
        if (core.needsDecode()) requestDecode(firstBlock = false)
    }

    fun release() {
        synchronized(stateLock) {
            if (released) return
            released = true
            callback = null
        }
        clearPrepareTimeout()
        worker.shutdownNow()
        core.release()
    }

    private fun isReleased(): Boolean = synchronized(stateLock) { released }

    private fun requestDecode(firstBlock: Boolean) {
        if (isReleased()) return
        val generation = requestGeneration.incrementAndGet()
        activeTasks.incrementAndGet()
        logD("requestDecode firstBlock=" + firstBlock + " gen=" + generation + " " + core.debugStateString())
        try {
            worker.execute {
                var progressed = false
                try {
                    // 首块任务最多尝试 4 次（覆盖 prepare 期间的 seek 改目标）；
                    // 普通任务一次最多补 3 块（当前 + 2 预取），且只有最新 generation 继续。
                    val limit = if (firstBlock) 4 else 3
                    for (i in 0 until limit) {
                        if (isReleased()) break
                        if (!firstBlock && requestGeneration.get() != generation) break
                        val pumped = core.pumpDecode()
                        logD(
                            "pumpDecode firstBlock=" + firstBlock + " i=" + i +
                                " pumped=" + pumped + " " + core.debugStateString(),
                        )
                        if (!pumped) break
                        progressed = true
                    }
                    if (firstBlock) {
                        val ready = core.isCurrentBlockReady()
                        logD(
                            "first block check ready=" + ready + " " + core.debugStateString(),
                        )
                        synchronized(stateLock) {
                            if (!ready && prepareError == null) {
                                prepareError = core.error() ?: "first loop block not ready"
                                logE("prepare error: " + prepareError)
                            }
                            if (ready && prepareError == null) {
                                prepared = true
                                clearPrepareTimeout()
                            }
                        }
                        val cb = synchronized(stateLock) { callback }
                        if (ready && prepareError == null && cb != null) {
                            logD("first block ready -> onPrepared")
                            callbackHandler.post { cb.onPrepared(this) }
                        }
                    }
                } catch (t: Throwable) {
                    val message = "decode worker failed: " + t.javaClass.name + ": " + t.message
                    synchronized(stateLock) {
                        if (prepareError == null) prepareError = message
                    }
                    logE(message, t)
                } finally {
                    activeTasks.decrementAndGet()
                    val cb = synchronized(stateLock) { callback }
                    if (!firstBlock &&
                        progressed &&
                        requestGeneration.get() == generation &&
                        cb != null
                    ) {
                        callbackHandler.post { cb.onContinueLoadingRequested(this) }
                    }
                }
            }
        } catch (t: RejectedExecutionException) {
            activeTasks.decrementAndGet()
            synchronized(stateLock) {
                if (prepareError == null) prepareError = "decode worker rejected"
            }
            logE("decode worker rejected", t)
        } catch (t: Throwable) {
            activeTasks.decrementAndGet()
            synchronized(stateLock) {
                if (prepareError == null) prepareError = "decode worker submit failed: " + t
            }
            logE("decode worker submit failed", t)
        }
    }
}

private const val TAG = "LoopPcmSource"
private const val PREPARE_TIMEOUT_MS = 2_000L

/** 把唯一的音频 SampleStream 挂到第一个被选中的 renderer 槽位（纯逻辑，可 JVM 单测）。 */
internal object LoopTrackAssignment {
    fun assign(
        selected: BooleanArray,
        streams: Array<SampleStream?>,
        stream: SampleStream,
        streamResetFlags: BooleanArray,
    ) {
        var assigned = false
        for (i in selected.indices) {
            if (i >= streams.size) break
            if (selected[i] && !assigned) {
                assigned = true
                if (streams[i] !== stream) {
                    streams[i] = stream
                    streamResetFlags[i] = true
                }
            } else if (streams[i] != null) {
                streams[i] = null
            }
        }
    }
}
