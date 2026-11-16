package com.zjr.hesimusic.loop.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zjr.hesimusic.loop.LoopWindow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务层 / sink 之间的唯一接口。
 *
 * 公开 API 刻意保持很小：服务层只写 [window] / [fullDurationUs]，UI 与服务层只读
 * 位置与 debug 计数；音频线程通过 internal 方法与 [engine] 交互。
 */
@Singleton
class LoopAudioController @Inject constructor() {

    internal val engine = LoopPcmEngine()

    private companion object {
        const val TAG = "LoopAudio"
    }

    private val preallocGeneration = AtomicLong(0L)

    /** 测试可替换为同步执行；生产默认单线程 daemon executor。 */
    internal var preallocDispatcher: ((Runnable) -> Unit)? = null

    private val preallocExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LoopRingPrealloc").apply { isDaemon = true }
    }

    @Volatile
    private var currentFormat: LoopPcmEngine.Format? = null

    /**
     * 无缝窗口；null = 纯直通。仅服务层写。
     *
     * 写入时同步到引擎（会重建 ring），并在后台按 window/format 预分配 ring；
     * sink 在音频回调里只做容量校验，不会做大分配。
     */
    @Volatile
    var window: LoopWindow? = null
        set(value) {
            field = value
            engine.setWindow(value)
            schedulePreallocation()
            synchronized(tapLock) {
                // tap 运行时:窗口变化写入新的 run 快照(analyzer A2/A3 需要 aMs/bMs)。
                writeTapRunLocked()
            }
            // 窗口变更/清空 → 旧窗口的预填失效；同窗口的 pending 保留并尝试安装
            // （典型顺序:预填解码先完成 → 之后 prepare 才把窗口写给 controller）。
            synchronized(seedLock) {
                val pending = pendingSeed
                if (pending != null && pending.window != value) {
                    pendingSeed = null
                }
            }
            installPendingSeed()
        }

    /**
     * 降级回调（主线程触发）：targetRawUs = 当前轮起点 - 250ms（下限 0）。
     *
     * 服务层应在这里 seekTo(targetRawUs)；回调绝不会在音频线程上执行。
     * 同一个降级事件只回调一次；flush 后可再次触发。
     *
     * 仅“ring 不完整 / 重放失败”这类可通过 seek 回补的降级会回调；
     * 容量超限、预分配失败只递增 [degradeCount]（seek 无法修复，服务层应清窗口/提示）。
     */
    @Volatile
    var onDegradeRequested: ((targetRawUs: Long) -> Unit)? = null
        set(value) {
            field = value
            if (value == null) return
            val pending = synchronized(degradeLock) {
                val target = pendingDegradeTargetUs
                pendingDegradeTargetUs = null
                target
            }
            if (pending != null) {
                dispatchDegrade { value(pending) }
            }
        }

    private val degradeLock = Any()
    private var pendingDegradeTargetUs: Long? = null

    /** 测试可替换为同步/队列执行；生产默认主线程 Handler。 */
    internal var degradeDispatcher: ((Runnable) -> Unit)? = null

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    init {
        engine.requirePreallocation()
        engine.onDegrade = { targetRawUs -> handleEngineDegrade(targetRawUs) }
        engine.onSeam = { iter, outReplayFrames, ptsUs ->
            onEngineSeam(iter, outReplayFrames, ptsUs)
        }
    }

    /** 当前歌整曲时长（µs），会话层 getDuration 投影用。 */
    @Volatile
    var fullDurationUs: Long = 0L

    /** 引擎侧媒体位置（µs）：重放期 = B+(k-1)L+环内偏移，由 sink 每次 getCurrentPositionUs 更新。 */
    @Volatile
    private var lastPositionUs: Long = 0L

    val positionUs: Long
        get() = lastPositionUs

    val isReplaying: Boolean
        get() = engine.state == LoopPcmEngine.State.REPLAYING

    val seamCount: Long
        get() = engine.seamCount

    val degradeCount: Long
        get() = engine.degradeCount

    /** 分片预填追不上时“原地等待”的次数；不伴随 seek/降级。 */
    val stallCount: Long
        get() = engine.stallCount

    val isRingComplete: Boolean
        get() = engine.isRingComplete

    /** 连续前缀覆盖帧数；分片预填只从这里追加。 */
    val coveredFrames: Long
        get() = engine.coveredFrames

    /** 当前 ring 是否由解码预填（[seedRing]）安装而非播放捕获。 */
    val isRingPrefilled: Boolean
        get() = engine.isRingPrefilled

    // ------------------------------------------------------------ 预填 ring（seed）

    /**
     * 解码侧预填：把 [window] 的 PCM16（交错小端，[sampleRate]/[channels]）直接装进 ring。
     *
     * 可从任意线程调用。sink 尚未 configure（输出格式未知）或窗口尚未写入时先暂存，
     * 在 [onFormatConfigured]/window 写入后校验 sampleRate/channels 与当前窗口再安装；
     * 不一致则丢弃并打日志（走兜底捕获路径）。
     */
    fun seedRing(
        window: LoopWindow,
        pcm16: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): LoopPcmEngine.SeedResult {
        val result = engine.seedRing(window, pcm16, sampleRate, channels)
        when (result.status) {
            LoopPcmEngine.SeedStatus.INSTALLED -> {
                synchronized(seedLock) { pendingSeed = null }
            }
            LoopPcmEngine.SeedStatus.PENDING -> {
                stagePendingSeed(window, pcm16, sampleRate, channels)
            }
            LoopPcmEngine.SeedStatus.REJECTED_WINDOW -> {
                // 唯一可等待的 REJECTED_WINDOW：窗口还没写入（sink 先 configure、
                // prepare 后写窗口）。窗口已存在但不是同一个 → 换歌/换窗口，直接丢弃。
                if (this.window == null) {
                    stagePendingSeed(window, pcm16, sampleRate, channels)
                }
            }
            else -> Unit
        }
        logSeedResult("seedRing", window, result)
        return result
    }

    /**
     * 分片预填：从连续前缀尾部追加，不重置播放相位。
     * 首片在 sink configure 前会返回 PENDING，调用方（预填服务）短暂重试即可。
     */
    fun seedRingRange(
        window: LoopWindow,
        startFrame: Long,
        pcm16: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): LoopPcmEngine.SeedResult {
        val result = engine.seedRingRange(window, startFrame, pcm16, sampleRate, channels)
        logSeedResult("seedRingRange", window, result)
        return result
    }

    /** 窗口写入/格式变化后重试安装暂存的预填（大块拷贝放到后台线程，避免主线程/音频线程卡顿）。 */
    private fun installPendingSeed() {
        if (synchronized(seedLock) { pendingSeed } == null) return
        val task = Runnable { installPendingSeedNow() }
        val dispatcher = preallocDispatcher
        if (dispatcher != null) {
            dispatcher(task)
        } else {
            try {
                preallocExecutor.execute(task)
            } catch (_: Throwable) {
                // executor 已关闭（进程退出）：直接丢弃
            }
        }
    }

    private fun installPendingSeedNow() {
        val pending = synchronized(seedLock) { pendingSeed } ?: return
        val result = engine.seedRing(pending.window, pending.pcm16, pending.sampleRate, pending.channels)
        when (result.status) {
            LoopPcmEngine.SeedStatus.INSTALLED -> {
                synchronized(seedLock) { if (pendingSeed === pending) pendingSeed = null }
                logSeedResult("seedRing(install)", pending.window, result)
            }
            LoopPcmEngine.SeedStatus.PENDING -> Unit
            LoopPcmEngine.SeedStatus.REJECTED_WINDOW -> {
                if (window != null) {
                    synchronized(seedLock) { if (pendingSeed === pending) pendingSeed = null }
                    logSeedResult("seedRing(drop)", pending.window, result)
                }
            }
            else -> {
                synchronized(seedLock) { if (pendingSeed === pending) pendingSeed = null }
                logSeedResult("seedRing(drop)", pending.window, result)
            }
        }
    }

    private fun stagePendingSeed(
        window: LoopWindow,
        pcm16: ByteArray,
        sampleRate: Int,
        channels: Int,
    ) {
        if (pcm16.size.toLong() > LoopPcmEngine.MAX_RING_BYTES) {
            try { Log.w(TAG, "seedRing not staged: pcm bytes=" + pcm16.size) } catch (_: Throwable) {}
            return
        }
        synchronized(seedLock) {
            pendingSeed = PendingSeed(window, pcm16, sampleRate, channels)
        }
    }

    private fun logSeedResult(
        tag: String,
        window: LoopWindow,
        result: LoopPcmEngine.SeedResult,
    ) {
        if (result.isInstalled) return
        val message = tag + " " + result.status + " window=" + window +
            " frames=" + result.frames + " detail=" + result.detail
        try { Log.w(TAG, message) } catch (_: Throwable) {}
    }

    private val seedLock = Any()

    @Volatile
    private var pendingSeed: PendingSeed? = null

    private class PendingSeed(
        val window: LoopWindow,
        val pcm16: ByteArray,
        val sampleRate: Int,
        val channels: Int,
    )

    /** 单测用：是否还有等 sink configure 的预填。 */
    internal val hasPendingSeed: Boolean
        get() = synchronized(seedLock) { pendingSeed != null }

    // ------------------------------------------------------------------ PCM tap

    private val tapLock = Any()
    private var tapFile: File? = null
    private var tapOut: RandomAccessFile? = null
    private var tapFormat: TapFormat? = null
    private var tapDataBytes: Long = 0L
    private var tapFailed = false

    // tap 侧车事件: <file.name>.jsonl
    private var tapEventFile: File? = null
    private var tapEventOut: BufferedWriter? = null
    private var tapEventSeq: Long = 0L
    private var tapEventStartNanos: Long = 0L
    private var tapRunWritten: Boolean = false
    private var tapRunWindow: LoopWindow? = null
    private var tapFlushCount: Long = 0L

    private data class TapFormat(
        val sampleRate: Int,
        val channels: Int,
        val bytesPerFrame: Int,
        val pcmIsFloat: Boolean,
    )

    /**
     * debug 取证开关：把**实际输出**到 delegate 的 PCM 落成 WAV（44 字节头 + raw PCM）。
     * null 关闭。文件在 configure 时创建，release 时补写 WAV 头长度。
     */
    fun setPcmTap(file: File?) {
        synchronized(tapLock) {
            if (tapFile == file) return
            closeTapLocked()
            tapFile = file
            tapEventFile = file?.let {
                val name = it.name + ".jsonl"
                it.parentFile?.let { parent -> File(parent, name) } ?: File(name)
            }
            tapEventOut = null
            tapEventSeq = 0L
            tapEventStartNanos = 0L
            tapRunWritten = false
            tapRunWindow = null
            tapFlushCount = 0L
            tapFailed = false
            if (file != null && tapFormat != null) {
                openTapLocked()
                openTapEventsLocked()
                writeTapConfigureLocked()
                writeTapRunLocked()
            }
        }
    }

    // ------------------------------------------------------------ sink 回调入口

    internal fun onFormatConfigured(
        sampleRate: Int,
        channels: Int,
        bytesPerFrame: Int,
        pcmIsFloat: Boolean,
    ) {
        val fmt = LoopPcmEngine.Format(
            sampleRate = sampleRate,
            channelCount = channels,
            bytesPerFrame = bytesPerFrame,
            pcmIsFloat = pcmIsFloat,
        )
        engine.configure(fmt)
        engine.setWindow(window)
        currentFormat = fmt
        schedulePreallocation()
        // 预填解码可能先于 sink configure 完成：格式就绪后校验并安装。
        installPendingSeed()
        synchronized(tapLock) {
            val newFormat = TapFormat(
                sampleRate = sampleRate,
                channels = channels,
                bytesPerFrame = bytesPerFrame,
                pcmIsFloat = pcmIsFloat,
            )
            val changed = tapFormat != newFormat
            tapFormat = newFormat
            if (tapFile != null && (changed || tapOut == null)) {
                closeTapLocked()
                tapFailed = false
                openTapLocked()
                openTapEventsLocked()
                writeTapConfigureLocked()
                if (window != null) writeTapRunLocked()
            } else if (tapFile != null) {
                // 同一 WAV 继续写；新 sink/流只追加一条 configure。
                writeTapConfigureLocked()
                if (window != null) writeTapRunLocked()
            }
        }
    }

    internal fun updatePositionUs(mediaUs: Long) {
        lastPositionUs = mediaUs
    }

    internal fun onSinkFlush() {
        engine.onFlush()
        synchronized(tapLock) {
            if (tapEventOut != null) {
                tapFlushCount += 1L
                writeTapEventLocked("flush")
            }
        }
    }

    internal fun onSinkReset() {
        engine.onReset()
    }

    internal fun onSinkRelease() {
        preallocGeneration.incrementAndGet()
        currentFormat = null
        // sink 释放后不再有搭载点，丢弃等待中的预填。
        synchronized(seedLock) { pendingSeed = null }
        engine.onRelease()
        synchronized(tapLock) {
            closeTapLocked()
        }
    }

    // ------------------------------------------------------------ ring 预分配

    /**
     * window/format 写入后调用：后台按 planRing 分配 ring，完成后安装到 engine。
     * 音频线程只做容量校验；预分配未完成时由 sink 施加背压。
     */
    private fun schedulePreallocation() {
        val generation = preallocGeneration.incrementAndGet()
        val w = window ?: return
        val f = currentFormat ?: return
        if (engine.hasRingFor(w, f)) return
        val plan = LoopPcmEngine.planRing(w, f) ?: return

        val task = Runnable {
            if (preallocGeneration.get() != generation) return@Runnable
            if (window != w || currentFormat != f) return@Runnable
            if (engine.hasRingFor(w, f)) return@Runnable
            val bytes = try {
                ByteArray(plan.byteSize.toInt())
            } catch (t: Throwable) {
                return@Runnable
            }
            if (preallocGeneration.get() != generation) return@Runnable
            if (window != w || currentFormat != f) return@Runnable
            engine.offerPreallocatedRing(w, f, bytes)
        }

        val dispatcher = preallocDispatcher
        if (dispatcher != null) {
            dispatcher(task)
        } else {
            preallocExecutor.execute(task)
        }
    }

    // ------------------------------------------------------------ 降级回调

    private fun handleEngineDegrade(targetRawUs: Long) {
        synchronized(tapLock) {
            writeTapEventLocked("degrade", "targetRawUs" to targetRawUs)
        }
        var callback: ((Long) -> Unit)? = null
        synchronized(degradeLock) {
            callback = onDegradeRequested
            if (callback == null) {
                pendingDegradeTargetUs = targetRawUs
                return
            }
        }
        val cb = callback ?: return
        dispatchDegrade { cb(targetRawUs) }
    }

    private fun dispatchDegrade(block: () -> Unit) {
        val dispatcher = degradeDispatcher
        if (dispatcher != null) {
            dispatcher(Runnable(block))
        } else {
            mainHandler.post(Runnable(block))
        }
    }

    /** 把 delegate 实际消费的一段 PCM 写入 tap（offset/length 相对 buffer 绝对位置）。 */
    internal fun tapPcm(buffer: ByteBuffer, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(tapLock) {
            val out = tapOut ?: return
            if (tapFailed) return
            if (offset < 0 || length <= 0) return
            if (offset + length > buffer.limit()) return
            val data = ByteArray(length)
            val dup = buffer.duplicate()
            dup.position(offset)
            dup.limit(offset + length)
            dup.get(data)
            // 第一段实际输出前补 run 快照（若 configure 时窗口尚未写入）。
            if (!tapRunWritten) writeTapRunLocked()
            try {
                out.write(data)
                tapDataBytes += length.toLong()
            } catch (e: IOException) {
                tapFailed = true
                closeTapLocked()
            }
        }
    }

    // ------------------------------------------------------------------ WAV 写盘

    private fun openTapLocked() {
        val file = tapFile ?: return
        val fmt = tapFormat ?: return
        if (tapFailed) return
        try {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0L)
            // 先写 44 字节占位头（data size 0），release 时回填。
            raf.write(buildWavHeader(fmt, 0L))
            raf.seek(44L)
            tapOut = raf
            tapDataBytes = 0L
        } catch (e: IOException) {
            tapFailed = true
            tapOut = null
        }
    }

    private fun closeTapLocked() {
        val fmt = tapFormat
        val bpf = fmt?.bytesPerFrame ?: 0
        val totalFrames = if (bpf > 0) tapDataBytes / bpf else 0L
        writeTapEventLocked(
            "end",
            "totalFrames" to totalFrames,
            "flushCount" to tapFlushCount,
            "degradeCount" to engine.degradeCount,
        )
        closeTapEventLocked()
        tapFlushCount = 0L
        tapRunWritten = false
        tapRunWindow = null

        val out = tapOut
        tapOut = null
        if (out != null) {
            try {
                if (fmt != null) {
                    out.seek(0L)
                    out.write(buildWavHeader(fmt, tapDataBytes))
                }
            } catch (e: IOException) {
                tapFailed = true
            } finally {
                try {
                    out.close()
                } catch (e: IOException) {
                    // ignore
                }
                tapDataBytes = 0L
            }
        } else {
            tapDataBytes = 0L
        }
    }

    private fun onEngineSeam(iter: Long, engineOutFrames: Long, ptsUs: Long) {
        synchronized(tapLock) {
            val bpf = tapFormat?.bytesPerFrame ?: 0
            val outFrames = if (bpf > 0 && tapDataBytes > 0L) {
                tapDataBytes / bpf
            } else {
                engineOutFrames
            }
            writeTapEventLocked(
                "seam",
                "iter" to iter,
                "outFrames" to outFrames,
                "ptsUs" to ptsUs,
            )
        }
    }

    private fun openTapEventsLocked() {
        val file = tapEventFile ?: return
        try {
            file.parentFile?.mkdirs()
            val stream = FileOutputStream(file, false)
            tapEventOut = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
            tapEventStartNanos = System.nanoTime()
            tapEventSeq = 0L
            tapRunWritten = false
            tapRunWindow = null
            tapFlushCount = 0L
        } catch (e: IOException) {
            tapEventOut = null
        }
    }

    private fun closeTapEventLocked() {
        val out = tapEventOut ?: return
        tapEventOut = null
        try {
            out.flush()
            out.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    private fun writeTapConfigureLocked() {
        val fmt = tapFormat ?: return
        writeTapEventLocked(
            "configure",
            "sr" to fmt.sampleRate,
            "ch" to fmt.channels,
            "bpf" to fmt.bytesPerFrame,
        )
    }

    /**
     * A2/A3 门禁需要的 run 快照：aMs/bMs 来自当前 controller.window；
     * window 为 null 时写 null。窗口变化时允许写新的 run（代表新的环回段）。
     */
    private fun writeTapRunLocked() {
        if (tapEventOut == null) return
        val w = window
        if (tapRunWritten && tapRunWindow == w) return
        val sampleRate = tapFormat?.sampleRate ?: 0
        val aMs = w?.let { it.loopStartUs / 1000L }
        val bMs = w?.let { it.loopEndUs / 1000L }
        writeTapEventLocked(
            "run",
            "mode" to "sink",
            "aMs" to aMs,
            "bMs" to bMs,
            "sampleRate" to sampleRate,
        )
        tapRunWritten = true
        tapRunWindow = w
    }

    private fun writeTapEventLocked(event: String, vararg fields: Pair<String, Any?>) {
        val out = tapEventOut ?: return
        val now = System.nanoTime()
        val tMs = if (tapEventStartNanos == 0L) 0L else (now - tapEventStartNanos) / 1_000_000L
        tapEventSeq += 1L
        val sb = StringBuilder(128)
        sb.append("{\"t\":").append(tMs)
        sb.append(",\"seq\":").append(tapEventSeq)
        sb.append(",\"ev\":\"").append(escapeJson(event)).append('"')
        for ((key, value) in fields) {
            sb.append(",\"").append(escapeJson(key)).append("\":")
            when (value) {
                null -> sb.append("null")
                is Number -> sb.append(value)
                is Boolean -> sb.append(value)
                else -> sb.append('"').append(escapeJson(value.toString())).append('"')
            }
        }
        sb.append('}')
        try {
            out.write(sb.toString())
            out.newLine()
            out.flush()
        } catch (e: IOException) {
            tapEventOut = null
            try {
                out.close()
            } catch (e: IOException) {
                // ignore
            }
        }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun buildWavHeader(fmt: TapFormat, dataBytes: Long): ByteArray {
        val header = ByteArray(44)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = fmt.channels.coerceAtLeast(1)
        val bpf = fmt.bytesPerFrame.coerceAtLeast(1)
        val bitsPerSample = (bpf * 8 / channels).coerceIn(8, 32)
        val dataSize32 = dataBytes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize32)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort((if (fmt.pcmIsFloat) 3 else 1).toShort())
        bb.putShort(channels.toShort())
        bb.putInt(fmt.sampleRate)
        bb.putInt(fmt.sampleRate * bpf)
        bb.putShort(bpf.toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize32)
        return header
    }
}
