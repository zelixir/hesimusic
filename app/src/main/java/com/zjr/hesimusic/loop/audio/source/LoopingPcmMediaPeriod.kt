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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 循环 PCM 的 [MediaPeriod]。
 *
 * - 一个 [LoopingPcmSourceCore] + 一个 [LoopingPcmSampleStream]；
 * - 单线程 executor 串行解码（MediaCodec 单实例约束）；
 * - 当前块优先、预取 2 块；首块就绪后 onPrepared；
 * - seek 只改 core cursor 并异步补目标块，不阻塞播放线程；
 * - 解码任务同一时刻最多一个（decodeScheduled 去重）；generation 只在 seek 时递增，
 *   不因 continueLoading/read 递增，也不会中断正在解的目标块。
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
    private val prefetchBlocks: Int = 6,
) : MediaPeriod {

    private var core = LoopingPcmSourceCore(
        timeline = timeline,
        provider = provider,
        maxCacheBytes = maxCacheBytes,
        prefetchBlocks = prefetchBlocks,
        logger = logger,
    )

    private val format: Format = Format.Builder()
        .setId("loop-pcm")
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setSampleRate(timeline.sampleRate)
        .setChannelCount(timeline.channels)
        .setMaxInputSize(64 * 1024)
        .build()

    private var stream = LoopingPcmSampleStream(core, format, logger)

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
    /** 只在 seek/窗口变化时递增；read/continueLoading 不改变它。 */
    private val requestGeneration = AtomicLong(0L)
    private val decodeScheduled = AtomicBoolean(false)
    private val activeTasks = AtomicInteger(0)
    private var dedupeSkips: Long = 0L
    private var lastDedupeBlock: Int = Int.MIN_VALUE
    private var lastDedupeLogMs: Long = 0L
    private var lastStartBlock: Int = Int.MIN_VALUE
    private var lastStartLogMs: Long = 0L
    private var pendingSinceMs: Long = 0L
    private var lastLoadLogMs: Long = 0L
    private var callback: MediaPeriod.Callback? = null
    private var prepared = false
    private var released = false
    private var prepareError: String? = null
    private var prepareTimeout: Runnable? = null
    private var decodeWatchdog: Runnable? = null

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
                callbackHandler.post {
                    callback.onPrepared(this)
                    callback.onContinueLoadingRequested(this)
                }
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

    private fun scheduleDecodeWatchdog() {
        decodeWatchdog?.let { callbackHandler.remove(it) }
        val watchdog = Runnable {
            val block = core.currentBlockIndex()
            if (core.failStuckPendingBlock("decode watchdog timeout")) {
                logE("decode watchdog marked block=" + block + " FAILED " + core.debugStateString())
                synchronized(stateLock) {
                    if (!prepared && prepareError == null) {
                        prepareError = "decode watchdog timeout block=" + block
                    }
                }
            }
        }
        decodeWatchdog = watchdog
        callbackHandler.postDelayed(watchdog, DECODE_STUCK_TIMEOUT_MS)
    }

    private fun clearDecodeWatchdog() {
        decodeWatchdog?.let { callbackHandler.remove(it) }
        decodeWatchdog = null
    }

    override fun maybeThrowPrepareError() {
        val error = synchronized(stateLock) { prepareError }
        if (error != null) throw IOException(error)
    }

    override fun getTrackGroups(): TrackGroupArray {
        val groups = TrackGroupArray(TrackGroup(format))
        logD(
            "getTrackGroups groups=" + groups.length +
                " mime=" + format.sampleMimeType + " pcm=" + format.pcmEncoding +
                " " + format.sampleRate + "Hz/" + format.channelCount + "ch",
        )
        return groups
    }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        // 注意：数组按 renderer 索引，不保证 audio 在 0（默认 renderers 中 video=0, audio=1）。
        // 只把唯一的音频流挂到第一个非空 selection 上，其余清空。
        val selected = BooleanArray(selections.size) { selections[it] != null }
        logD(
            "selectTracks renderers=" + selections.size +
                " selected=" + selected.indices.filter { selected[it] } +
                " streamsBefore=" + streams.indices.filter { streams[it] != null } +
                " format=" + format.sampleMimeType + "/pcm=" + format.pcmEncoding +
                "/" + format.sampleRate + "Hz/" + format.channelCount + "ch",
        )
        LoopTrackAssignment.assign(
            selected = selected,
            streams = streams,
            stream = stream,
            streamResetFlags = streamResetFlags,
        )
        logD(
            "selectTracks assigned audioIndex=" + streams.indexOfFirst { it === stream } +
                " streamReady=" + stream.isReady() + " " + core.debugStateString(),
        )
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        // 本实现没有独立的数据队列：seek 已把 cursor 指向新位置；这里无需搬数据。
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long {
        val generation = requestGeneration.incrementAndGet()
        val target = core.seekToTimelineUs(positionUs)
        stream.onSeek()
        logD("seekToUs positionUs=" + positionUs + " gen=" + generation + " " + core.debugStateString())
        requestDecode(firstBlock = false)
        return target
    }

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        positionUs

    override fun getBufferedPositionUs(): Long {
        val buffered = core.bufferedEndUs()
        maybeLogLoad(core.currentPtsUs(), buffered, core.nextLoadPositionUs())
        return buffered
    }

    override fun getNextLoadPositionUs(): Long {
        val next = core.nextLoadPositionUs()
        maybeLogLoad(core.currentPtsUs(), core.bufferedEndUs(), next)
        return next ?: C.TIME_END_OF_SOURCE
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        if (isReleased()) return false
        if (LoopSourcePauseGate.paused) return false
        maybeLogLoad(
            loadingInfo.playbackPositionUs,
            core.bufferedEndUs(),
            core.nextLoadPositionUs(),
        )
        if (!core.needsDecode() && core.isCurrentBlockReady()) return false
        requestDecode(firstBlock = false)
        return true
    }

    private fun maybeLogLoad(playbackPositionUs: Long, bufferedUs: Long, nextLoadUs: Long?) {
        val now = System.currentTimeMillis()
        if (now - lastLoadLogMs < 1_000L) return
        lastLoadLogMs = now
        logD(
            "load pos=" + playbackPositionUs + " buffered=" + bufferedUs +
                " bufferedDuration=" + (bufferedUs - playbackPositionUs) +
                " nextLoad=" + (nextLoadUs ?: "END") + " loading=" + isLoading() +
                " readyBlocks=" + core.cache.readyIndices().size + " " + core.debugStateString(),
        )
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
        clearDecodeWatchdog()
        worker.shutdownNow()
        core.release()
    }

    private fun isReleased(): Boolean = synchronized(stateLock) { released }

    private fun noteDedupe(firstBlock: Boolean) {
        dedupeSkips += 1
        val now = System.currentTimeMillis()
        val block = core.currentBlockIndex()
        if (block != lastDedupeBlock || now - lastDedupeLogMs >= 1_000L) {
            lastDedupeBlock = block
            lastDedupeLogMs = now
            logD(
                "requestDecode dedupe firstBlock=" + firstBlock +
                    " block=" + block + " skips=" + dedupeSkips + " " + core.debugStateString(),
            )
        }
    }

    private fun requestDecode(firstBlock: Boolean) {
        if (isReleased()) return
        val nowMs = System.currentTimeMillis()
        if (core.currentBlockState() == PcmBlockCache.State.PENDING) {
            if (pendingSinceMs == 0L) pendingSinceMs = nowMs
            if (nowMs - pendingSinceMs >= DECODE_STUCK_TIMEOUT_MS) {
                if (core.failStuckPendingBlock("pending " + (nowMs - pendingSinceMs) + "ms")) {
                    logE("decode pending timeout -> FAILED " + core.debugStateString())
                }
                pendingSinceMs = 0L
            }
        } else {
            pendingSinceMs = 0L
        }
        // 当前块已有人在解（或刚超时被置 FAILED 前的等待）：不要提交新任务，
        // 等它 READY/FAILED 或上面的 2s 兜底生效；否则会每 10ms 空转一次。
        if (core.currentBlockState() == PcmBlockCache.State.PENDING) {
            noteDedupe(firstBlock)
            return
        }
        if (!firstBlock && core.isCurrentBlockReady() && !core.needsDecode()) return

        // 去重：同一时刻只允许一个 worker 在跑。continueLoading 会被 ExoPlayer
        // 每 ~10ms 调一次；这里不能递增 generation，也不能重复提交任务。
        if (decodeScheduled.get()) {
            noteDedupe(firstBlock)
            return
        }

        if (!decodeScheduled.compareAndSet(false, true)) return
        activeTasks.incrementAndGet()
        scheduleDecodeWatchdog()
        val startBlock = core.currentBlockIndex()
        val startNow = System.currentTimeMillis()
        if (firstBlock || startBlock != lastStartBlock || startNow - lastStartLogMs >= 1_000L) {
            lastStartBlock = startBlock
            lastStartLogMs = startNow
            logD(
                "requestDecode start firstBlock=" + firstBlock + " gen=" + requestGeneration.get() +
                    " " + core.debugStateString(),
            )
        }
        try {
            worker.execute {
                var progressed = false
                try {
                    // 首块任务最多尝试 4 次（覆盖 prepare 期间的 seek 改目标）；
                    // 普通任务一次最多补 3 块（当前 + 2 预取）。
                    // 不再按 generation 中断：generation 只在 seek/窗口变化时变化，
                    // 不会因为 continueLoading 把正在解的目标块顶掉。
                    // prepare 窗口 = 当前块 + prefetchBlocks；够这一窗就立即 onPrepared。
                    // 不能多解一块：继续预取可能被 provider 门闩/慢 I/O 挡住，导致首块已 READY
                    // 却永远不发 onPrepared（Robolectric 的 gatedProvider 正是这个时序）。
                    val limit = if (firstBlock) minOf(prefetchBlocks + 1, 4) else 8
                    for (i in 0 until limit) {
                        if (isReleased()) break
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
                            callbackHandler.post {
                                cb.onPrepared(this)
                                // 有的路径 onPrepared 后 loader 要等下一次回调才继续；
                                // 主动踢一次，避免 prepared 后不再 continueLoading。
                                cb.onContinueLoadingRequested(this)
                            }
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
                    decodeScheduled.set(false)
                    clearDecodeWatchdog()
                    val cb = synchronized(stateLock) { callback }
                    if (!firstBlock && progressed && cb != null) {
                        callbackHandler.post { cb.onContinueLoadingRequested(this) }
                    }
                }
            }
        } catch (t: RejectedExecutionException) {
            activeTasks.decrementAndGet()
            decodeScheduled.set(false)
            clearDecodeWatchdog()
            synchronized(stateLock) {
                if (prepareError == null) prepareError = "decode worker rejected"
            }
            logE("decode worker rejected", t)
        } catch (t: Throwable) {
            activeTasks.decrementAndGet()
            decodeScheduled.set(false)
            clearDecodeWatchdog()
            synchronized(stateLock) {
                if (prepareError == null) prepareError = "decode worker submit failed: " + t
            }
            logE("decode worker submit failed", t)
        }
    }

    internal fun currentRequestGeneration(): Long = requestGeneration.get()
    internal fun dedupeSkipCount(): Long = dedupeSkips
}

private const val TAG = "LoopPcmSource"
private const val PREPARE_TIMEOUT_MS = 2_000L
private const val DECODE_STUCK_TIMEOUT_MS = 2_000L

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
