package com.zjr.hesimusic.debug

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.zjr.hesimusic.data.model.Song
import com.zjr.hesimusic.data.model.SongLoop
import com.zjr.hesimusic.data.mapper.toMediaItem
import com.zjr.hesimusic.loop.audio.SeamBufferMode
import com.zjr.hesimusic.loop.audio.source.LoopEngineInfo
import com.zjr.hesimusic.loop.audio.source.LoopPreviewDebugBridge
import com.zjr.hesimusic.loop.audio.source.LoopSourceMainPlayer
import com.zjr.hesimusic.loop.audio.source.LoopSourceMode
import com.zjr.hesimusic.loop.audio.source.RingBandSource
import com.zjr.hesimusic.service.LoopClaim
import com.zjr.hesimusic.service.LoopClaimResolver
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.LoopSourceFallback
import com.zjr.hesimusic.service.MainPlayerRouting
import com.zjr.hesimusic.loop.analysis.AnalysisState
import com.zjr.hesimusic.loop.analysis.LoopAnalysisManager
import com.zjr.hesimusic.loop.analysis.LoopCandidateKt
import com.zjr.hesimusic.loop.analysis.ParallelDecodeHooks
import com.zjr.hesimusic.service.LibraryQueueTarget
import com.zjr.hesimusic.ui.loop.PreviewProbe
import com.zjr.hesimusic.ui.player.state.PlayerPageShadowDiagnostics
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * debug 专属 HTTP 调试服务(契约 docs/loop-seamless-v2.md §3.5/§3.6 + docs/loop-v3-plan.md §6.1)。
 *
 * 硬约束:
 * - 仅 debug 源集编译;release 不含本类(靠 src/debug + manifest provider 保证);
 * - 仅监听 127.0.0.1:8765,PC 侧 adb forward tcp:18765 tcp:8765;
 * - 手写 ServerSocket,无第三方 HTTP/JSON 依赖(JSON 用平台 org.json);
 * - 单连接异常/Error 只结束该连接,绝不穿透到进程;accept 线程失败自动退避重启;
 * - 播放器未就绪立即 503,不阻塞;所有 Player 访问主线程化且带超时;
 * - 每个请求 Log.d("LoopDebug", "REQ ... -> code (ms)")。
 */
object LoopDebugServer {

    private const val TAG = "LoopDebug"
    private const val HOST = "127.0.0.1"
    private const val PORT = 8765
    private const val VERSION = 1

    private const val SOCKET_READ_TIMEOUT_MS = 5_000
    private const val SOCKET_WRITE_TIMEOUT_MS = 5_000L
    private const val REQUEST_TIMEOUT_MS = 10_000L
    private const val PCM_COMPARE_TIMEOUT_MS = 300_000L
    private const val DEPS_RETRY_DEADLINE_MS = 3_000L
    private const val MAX_HEADER_LINE_BYTES = 8 * 1024
    private const val MAX_HEADER_TOTAL_BYTES = 32 * 1024
    private const val MAX_HEADERS = 100
    private const val MAX_BODY_BYTES = 512 * 1024
    private const val DEFAULT_LIMIT = 100
    private const val MAX_LIMIT = 500

    private val startedAtEpochMs = System.currentTimeMillis()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val analysisGuard = AtomicBoolean(false)

    /** 每次真正启动分析自增一次;旧 job 的 finally 只在仍是最新一代时才清理共享状态。 */
    private val analysisGeneration = AtomicLong(0L)
    private val connectionPool = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "LoopDebugConn").apply { isDaemon = true }
    }
    private val writeWatchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "LoopDebugWrite").apply { isDaemon = true }
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var entryPoint: DebugEntryPoint? = null

    @Volatile
    private var player: DebugPlayerConnection? = null

    @Volatile
    private var analyzingSongId: Long? = null

    @Volatile
    private var analyzeJob: Job? = null

    @Volatile
    private var started = false

    // /state 高频轮询:library 摘要是全表 flow,缓存 2s(与上一版实现同口径)。
    @Volatile
    private var libraryCacheAt = 0L

    @Volatile
    private var libraryCache: Map<String, Any?>? = null

    /** 最近一次成功的 /navigate route;初始 "unknown"。/state.screen 与 loop.snapshot.screen 共用。 */
    @Volatile
    private var lastRoute: String = "unknown"

    // ------------------------------------------------------------ 生命周期

    /** 启动监听线程(幂等)。只做 socket 监听,不解析 Hilt 依赖,避免拖慢 app 启动。 */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        appContext = app
        player = DebugPlayerConnection(app)
        Thread({ acceptLoop() }, "LoopDebugServer").apply { isDaemon = true }.start()
        Log.i(TAG, "debug server starting on $HOST:$PORT")
    }

    /** 懒解析 Hilt 依赖:首次请求时重试最多 3s(provider 早于 Application.onCreate)。 */
    @Synchronized
    private fun deps(): DebugEntryPoint {
        entryPoint?.let { return it }
        val ctx = appContext ?: throw HttpError(503, "debug server initializing")
        val deadline = SystemClock.elapsedRealtime() + DEPS_RETRY_DEADLINE_MS
        var last: Throwable? = null
        while (true) {
            try {
                val entry = EntryPointAccessors.fromApplication(ctx, DebugEntryPoint::class.java)
                entryPoint = entry
                installLoopBridge(entry)
                Log.i(TAG, "Hilt entry point resolved")
                return entry
            } catch (t: Throwable) {
                last = t
                if (SystemClock.elapsedRealtime() >= deadline) break
                sleepQuietly(100L)
            }
        }
        Log.e(TAG, "Hilt entry point unavailable", last)
        throw HttpError(503, "debug server initializing")
    }

    /**
     * 安装播放层只读桥(debug 源集内完成,main 不反向引用 debug):
     * - engine:M3′ 音频窗口**或** SOURCE 接管窗口命中当前歌 → "loop",否则 "exo";
     *   （SOURCE 路线故意不写 M3′ 的 controller.window,只看它会把正在循环的 SOURCE 误报成 exo）
     * - loopPath:当前走 m3 / source / none（engine=loop 时区分是哪条通路）;
     * - window: M3′ 窗口优先,缺失时回退 LoopRuntime.window(唯一窗口来源);
     * - positionUs/seam/degrade/ring 直接读 LoopAudioController 的 volatile 计数。
     */
    private fun installLoopBridge(d: DebugEntryPoint) {
        if (DebugHooks.loopBridge != null) return
        // 不变量采样器与 bridge 同生命周期：deps 就绪即开始每 500ms 采样（幂等）。
        val ctx = appContext
        if (ctx != null) {
            player?.let { PlaybackInvariantMonitor.start(d, it, ctx) }
        }
        DebugHooks.loopBridge = object : LoopDebugBridge {
            override fun snapshot(): Map<String, Any?> {
                val runtime = d.loopRuntime()
                val controller = d.loopAudioController()
                val audioWindow = controller.window
                val currentSongId = player?.cachedSongId()
                // 00027：claim 是唯一真值。当前歌未知时用意图窗口的 songId（冷启动 debug）。
                val truthSongId = currentSongId ?: runtime.window.value?.songId ?: audioWindow?.songId
                val claim = claimFor(truthSongId)
                val window = claim.window ?: audioWindow ?: runtime.window.value
                val routeSource = MainPlayerRouting.current.coordinatorReplacesItem
                val claimFields = claim.debugFields()
                val engine = if (claimFields.claimActive) "loop" else "exo"
                val loopPath = when {
                    claimFields.claimActive && routeSource -> "source"
                    claimFields.claimActive -> "m3"
                    else -> "none"
                }
                val sourceUnavailable = claim.window != null && !claimFields.sourceActive
                return linkedMapOf(
                    "engine" to engine,
                    "loopPath" to loopPath,
                    // 00027 一等字段：意图是否声称循环 / 源是否真的在环回 / 原因。
                    "claimed" to claimFields.claimed,
                    "claimActive" to claimFields.claimActive,
                    "sourceActive" to claimFields.sourceActive,
                    "claimReason" to claimFields.claimReason,
                    "window" to window?.let {
                        linkedMapOf(
                            "songId" to it.songId,
                            "loopStartUs" to it.loopStartUs,
                            "loopEndUs" to it.loopEndUs,
                        )
                    },
                    // 兼容旧字段：display 是否应该折返，以 claim 真值为准（不是 window 非空）。
                    "sourceAvailable" to claimFields.sourceActive,
                    "sourceUnavailable" to sourceUnavailable,
                    "fallbackReason" to (
                        claimFields.claimReason
                            ?: LoopSourceFallback.latestReason()?.logName
                        ),
                    "bandBytes" to LoopEngineInfo.lastRecordedBandBytes(),
                    "budgetBytes" to RingBandSource.budgetBytes(
                        Runtime.getRuntime().maxMemory(),
                        RingBandSource.BUDGET_DIVISOR_MAIN,
                    ),
                    "positionUs" to controller.positionUs,
                    "isReplaying" to controller.isReplaying,
                    "seamCount" to controller.seamCount,
                    "degradeCount" to controller.degradeCount,
                    "isRingComplete" to controller.isRingComplete,
                    "screen" to lastRoute,
                    // 显式意图(issue 00004):{songId, previousRepeatMode} 或 null。
                    "intent" to d.loopIntent().armed.value?.let { armed ->
                        linkedMapOf(
                            "songId" to armed.songId,
                            "previousRepeatMode" to armed.previousRepeatMode,
                        )
                    },
                )
            }
        }
        Log.i(TAG, "loop bridge installed")
    }

    private fun acceptLoop() {
        var backoffMs = 500L
        while (true) {
            var socket: ServerSocket? = null
            try {
                socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(HOST), PORT))
                serverSocket = socket
                backoffMs = 500L
                Log.i(TAG, "listening on $HOST:$PORT")
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        if (socket.isClosed) break
                        Log.w(TAG, "accept failed: ${e.message}")
                        sleepQuietly(200L)
                        continue
                    } catch (t: Throwable) {
                        if (socket.isClosed) break
                        Log.e(TAG, "accept crashed", t)
                        sleepQuietly(200L)
                        continue
                    }
                    try {
                        connectionPool.execute { handleSafely(client) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "dispatch failed", t)
                        closeQuietly(client)
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "server socket failed; retry in ${backoffMs}ms", t)
            } finally {
                closeQuietly(socket)
                serverSocket = null
            }
            sleepQuietly(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun handleSafely(client: Socket) {
        try {
            handleConnection(client)
        } catch (t: Throwable) {
            Log.e(TAG, "connection handler crashed", t)
            closeQuietly(client)
        }
    }

    // ------------------------------------------------------------ HTTP 连接

    private fun handleConnection(client: Socket) {
        val startMs = System.currentTimeMillis()
        var method = "?"
        var rawTarget = "?"
        var status = 500
        try {
            client.tcpNoDelay = true
            client.soTimeout = SOCKET_READ_TIMEOUT_MS
            val request = parseRequest(client)
            method = request.method
            rawTarget = request.rawTarget
            val responseBody = try {
                val result = dispatch(request)
                status = 200
                serializeJson(result)
            } catch (e: HttpError) {
                status = e.status
                errorJson(e.message ?: "error")
            } catch (e: PlayerUnavailable) {
                status = 503
                // 不能是空 body:reason 让 PC 端区分 controller 缺失/断连/读超时。
                playerUnavailableJson(e.message ?: "player connecting", e.reason)
            } catch (e: PlayerTimeout) {
                status = 503
                playerUnavailableJson("player timeout", DebugPlayerLink.REASON_SNAPSHOT_TIMEOUT)
            } catch (e: CancellationException) {
                status = 500
                Log.w(TAG, "request cancelled: $method $rawTarget", e)
                errorJson("cancelled")
            } catch (t: Throwable) {
                status = 500
                Log.e(TAG, "handler failed: $method $rawTarget", t)
                errorJson(t.message ?: t.javaClass.simpleName)
            }
            writeResponse(client, status, responseBody)
        } catch (e: HttpError) {
            status = e.status
            safeWrite(client, status, errorJson(e.message ?: "bad request"))
        } catch (t: Throwable) {
            status = 500
            Log.e(TAG, "connection failed: $method $rawTarget", t)
            safeWrite(client, status, errorJson(t.message ?: "internal error"))
        } finally {
            val elapsed = System.currentTimeMillis() - startMs
            runCatching { Log.d(TAG, "REQ $method $rawTarget -> $status (${elapsed}ms)") }
            closeQuietly(client)
        }
    }

    private fun dispatch(request: DebugRequest): Any = runBlocking {
        try {
            withTimeout(routeTimeoutMs(request)) { route(request) }
        } catch (e: TimeoutCancellationException) {
            throw HttpError(504, "request timeout")
        }
    }

    /** pcm-compare 要整曲解码两遍(248s 曲目约 25s+),单独放宽超时。 */
    private fun routeTimeoutMs(request: DebugRequest): Long =
        if (request.path == "/debug/pcm-compare") PCM_COMPARE_TIMEOUT_MS else REQUEST_TIMEOUT_MS

    private suspend fun route(request: DebugRequest): Any {
        val segments = pathSegments(request.path)
        val method = request.method
        val params = request.query
        val body = request.body
        return when {
            method == "GET" && segments == listOf("state") -> handleState()
            method == "GET" && segments == listOf("library", "songs") -> handleLibrarySongs(params)
            method == "GET" && segments == listOf("library", "playlists") -> handleLibraryPlaylists()
            method == "GET" && segments.size == 2 && segments[0] == "song" -> handleSongDetail(segments[1])
            method == "GET" && segments == listOf("analysis") -> handleAnalysis()
            method == "POST" && segments == listOf("analyze") -> handleAnalyze(body)
            method == "POST" && segments == listOf("confirm") -> handleConfirm(body)
            method == "POST" && segments == listOf("demote") -> handleDemote(body)
            method == "POST" && segments == listOf("delete") -> handleDelete(body)
            method == "POST" && segments == listOf("cancelAnalysis") -> handleCancelAnalysis()
            method == "GET" && segments == listOf("player") -> handlePlayerState()
            method == "POST" && segments.size == 2 && segments[0] == "player" ->
                handlePlayerCommand(segments[1], body)
            method == "POST" && segments == listOf("loop", "enter") -> handleLoopEnter(body)
            method == "POST" && segments == listOf("loop", "exit") -> handleLoopExit()
            method == "POST" && segments == listOf("navigate") -> handleNavigate(body)
            method == "POST" && segments == listOf("debug", "tap") -> handleDebugTap(body)
            method == "POST" && segments == listOf("debug", "reconnect") -> handleDebugReconnect()
            method == "POST" && segments == listOf("debug", "pcm-compare") -> handlePcmCompare(body)
            method == "POST" && segments == listOf("debug", "decode-range") -> handleDecodeRange(body)
            method == "POST" && segments == listOf("debug", "seam") -> handleSeamToggle(body)
            method == "POST" && segments == listOf("debug", "source-mode") -> handleSourceModeToggle(body)
            method == "POST" && segments == listOf("debug", "preview-seek") -> handlePreviewSeek(body)
            method == "GET" && segments == listOf("debug", "preview") -> handlePreviewSnapshot()
            method == "POST" && segments == listOf("debug", "invariant") -> handleInvariantFault(body)
            else -> throw HttpError(404, "unknown endpoint: $method ${request.path}")
        }
    }

    // ------------------------------------------------------------ /state

    private suspend fun handleState(): Map<String, Any?> {
        val d = deps()
        val library: Any = try {
            librarySummary(d)
        } catch (t: Throwable) {
            Log.w(TAG, "library summary failed", t)
            linkedMapOf("error" to (t.message ?: t.javaClass.simpleName))
        }
        val loop: Any? = try {
            DebugHooks.loopBridge?.snapshot()
        } catch (t: Throwable) {
            Log.w(TAG, "loopBridge snapshot failed", t)
            linkedMapOf("error" to (t.message ?: t.javaClass.simpleName))
        }
        return linkedMapOf(
            "screen" to lastRoute,
            "analysis" to analysisSummary(d),
            "playback" to playbackMapForState().withShadowDiagnostics(),
            "loop" to loop,
            // 运行期不变量（00020/00021 加固）：最近 10 条 {code,message,atMs,count}。
            "invariants" to PlaybackInvariantMonitor.snapshotMaps(),
            // 最近一次 500ms 采样的原始事实，便于真机确认“为什么没触发/为什么触发”。
            "invariantProbe" to PlaybackInvariantMonitor.lastSampleMap(),
            "library" to library,
            "server" to linkedMapOf(
                "version" to VERSION,
                "host" to HOST,
                "port" to PORT,
                "startedAtEpochMs" to startedAtEpochMs,
                "uptimeMs" to (System.currentTimeMillis() - startedAtEpochMs),
                // E2E 诊断:controllerAgeMs 只看连接时长,不参与有效性判定。
                "controllerAgeMs" to (player?.controllerAgeMs() ?: null),
                "connected" to (player?.isConnected() ?: false),
                "snapshotAgeMs" to (player?.snapshotAgeMs() ?: null),
            ),
        )
    }

    /** 503 响应体(controller 未连接/读取超时):error + reason + 三个链路诊断字段,绝不空 body。 */
    private fun playerUnavailableJson(message: String, reason: String): String {
        val p = player
        return serializeJson(
            linkedMapOf(
                "error" to message,
                "reason" to reason,
                "connected" to (p?.isConnected() ?: false),
                "controllerAgeMs" to p?.controllerAgeMs(),
                "snapshotAgeMs" to p?.snapshotAgeMs(),
            ),
        )
    }

    /** 播放页状态机影子双跑计数（只读；main 源集的纯 Kotlin 诊断对象）。 */
    private fun Map<String, Any?>.withShadowDiagnostics(): Map<String, Any?> =
        LinkedHashMap(this).apply {
            put("shadow", PlayerPageShadowDiagnostics.snapshotMap())
        }

    private fun analysisSummary(d: DebugEntryPoint): Map<String, Any?> {
        val state = d.loopAnalysisManager().state.value
        val songId = analyzingSongId
        return when (state) {
            is AnalysisState.Idle -> linkedMapOf(
                "phase" to "idle",
                "progress" to 0.0,
                "songId" to songId,
                "analyzing" to false,
                "candidateCount" to 0,
                "message" to null,
            )
            is AnalysisState.Decoding -> linkedMapOf(
                "phase" to "decoding",
                "progress" to state.progress.toDouble(),
                "songId" to songId,
                "analyzing" to true,
                "candidateCount" to 0,
                "message" to null,
            )
            is AnalysisState.Analyzing -> linkedMapOf(
                "phase" to "analyzing",
                "progress" to state.progress.toDouble(),
                "songId" to songId,
                "analyzing" to true,
                "candidateCount" to 0,
                "message" to null,
            )
            is AnalysisState.Done -> linkedMapOf(
                "phase" to "done",
                "progress" to 1.0,
                "songId" to state.songId,
                "analyzing" to false,
                "candidateCount" to state.candidates.size,
                "message" to null,
            )
            is AnalysisState.Failed -> linkedMapOf(
                "phase" to "failed",
                "progress" to 0.0,
                "songId" to songId,
                "analyzing" to false,
                "candidateCount" to 0,
                "message" to state.message,
            )
        }
    }

    private fun playbackMapForState(): Map<String, Any?> {
        val p = player ?: return notConnectedPlayback(null)
        val read = p.snapshotForRead() ?: return notConnectedPlayback(p)
        // /state 是高频轮询端点:不吐 200 条 queue(每条约 3 字段),只给 queueSize/currentIndex。
        return LinkedHashMap(snapshotMap(read.snapshot, includeQueue = false)).apply {
            put("connecting", false)
            put("stale", read.stale)
            put("connected", true)
            put("controllerAgeMs", p.controllerAgeMs())
            put("snapshotAgeMs", p.snapshotAgeMs())
            put("reason", null)
        }
    }

    private fun notConnectedPlayback(p: DebugPlayerConnection?): Map<String, Any?> = linkedMapOf(
        "songId" to null,
        "title" to null,
        "artist" to null,
        "repeatMode" to null,
        "shuffle" to null,
        "playing" to false,
        "playWhenReady" to false,
        "positionMs" to null,
        "durationMs" to null,
        "buffering" to null,
        "playbackState" to null,
        "queueSize" to null,
        "currentIndex" to null,
        "queue" to emptyList<Any?>(),
        "engine" to "exo",
        "connecting" to (p?.isStartingUp() ?: true),
        "connected" to (p?.isConnected() ?: false),
        "controllerAgeMs" to p?.controllerAgeMs(),
        "snapshotAgeMs" to p?.snapshotAgeMs(),
        // 为什么拿不到播放态:controller_not_connected / controller_disconnected / snapshot_timeout ...
        "reason" to (p?.unavailableReason() ?: DebugPlayerLink.REASON_NOT_INITIALIZED),
        "stale" to true,
    )

    // ------------------------------------------------------------ /analysis

    private suspend fun handleAnalysis(): Map<String, Any?> {
        val d = deps()
        val state = d.loopAnalysisManager().state.value
        val result = LinkedHashMap<String, Any?>(analysisSummary(d))
        if (state is AnalysisState.Done) {
            result["sampleRate"] = state.sampleRate
            result["durationMs"] = state.durationMs
            result["candidates"] = state.candidates.map { candidateMap(it) }
            result["peaks"] = linkedMapOf(
                "buckets" to state.peaks.buckets,
                "mn" to state.peaks.mn.toList(),
                "mx" to state.peaks.mx.toList(),
            )
        }
        val songId = (result["songId"] as? Number)?.toLong()
        if (songId != null && songId > 0L) {
            result["stored"] = d.songLoopDao().getBySongId(songId)?.let { songLoopMap(it) }
        }
        return result
    }

    private fun candidateMap(candidate: LoopCandidateKt): Map<String, Any?> = linkedMapOf(
        "startSamples" to candidate.startSamples,
        "endSamples" to candidate.endSamples,
        "score" to candidate.score,
        "noteDistance" to candidate.noteDistance,
        "loudnessDifference" to candidate.loudnessDifference,
    )

    private fun isAnalysisActive(manager: LoopAnalysisManager): Boolean {
        if (analysisGuard.get()) return true
        return when (manager.state.value) {
            is AnalysisState.Decoding, is AnalysisState.Analyzing -> true
            else -> false
        }
    }

    // ------------------------------------------------------------ 分析写操作

    /**
     * 开始分析前暂停当前播放。player 未连接/冷启动 restore 中直接跳过,
     * 不让分析因为播放器状态而失败(返回 false)。
     */
    private fun pausePlaybackForAnalysis(): Boolean {
        val p = player ?: return false
        if (p.isStartingUp()) {
            Log.d(TAG, "analyze pause skipped: player starting up")
            return false
        }
        return try {
            p.command { controller -> controller.pause() }
            Log.d(TAG, "analyze pause ok")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "analyze pause skipped: ${t.message}")
            false
        }
    }

    private suspend fun handleAnalyze(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        val songId = body.requireLong("songId")
        if (songId <= 0L) throw HttpError(400, "songId required")
        val manager = d.loopAnalysisManager()
        if (isAnalysisActive(manager)) throw HttpError(409, "已有分析进行中")
        if (!analysisGuard.compareAndSet(false, true)) throw HttpError(409, "已有分析进行中")
        val generation = analysisGeneration.incrementAndGet()
        var launched = false
        try {
            val song = d.songRepository().getSongsByIds(listOf(songId)).firstOrNull()
                ?: throw HttpError(404, "song not found: $songId")
            // 产品口径:开始分析即暂停正在播的歌(UI 进页时已 pause,协议路径补齐)。
            // player 未就绪/冷启动无效时跳过 pause,绝不让分析因此失败。
            val paused = pausePlaybackForAnalysis()
            analyzingSongId = songId
            analyzeJob = analysisScope.launch {
                try {
                    manager.analyze(song)
                } catch (e: CancellationException) {
                    Log.d(TAG, "analysis cancelled song=$songId")
                } catch (t: Throwable) {
                    Log.e(TAG, "analysis crashed song=$songId", t)
                } finally {
                    // 旧 job(已取消/被新分析取代)不得清掉新一代的 guard/songId。
                    if (analysisGeneration.get() == generation) {
                        analysisGuard.set(false)
                        analyzingSongId = null
                    }
                    // 分析成功会自动落库临时结果:让 /state 的 loopCount 立刻可见。
                    libraryCache = null
                }
            }
            launched = true
            return linkedMapOf("started" to true, "songId" to songId, "paused" to paused)
        } finally {
            if (!launched && analysisGeneration.get() == generation) {
                analysisGuard.set(false)
                analyzingSongId = null
            }
        }
    }

    private suspend fun handleConfirm(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        val obj = body ?: throw HttpError(400, "JSON body required")
        val songId = obj.requireLong("songId")
        if (songId <= 0L) throw HttpError(400, "songId required")
        val song = d.songRepository().getSongsByIds(listOf(songId)).firstOrNull()
            ?: throw HttpError(404, "song not found: $songId")
        val manager = d.loopAnalysisManager()
        val existing = d.songLoopDao().getBySongId(songId)
        val done = (manager.state.value as? AnalysisState.Done)?.takeIf { it.songId == songId }
        val aSamples = obj.optLongOrNull("aSamples")
        val bSamples = obj.optLongOrNull("bSamples")

        val candidate: LoopCandidateKt
        val sampleRate: Int
        if (aSamples != null && bSamples != null) {
            if (aSamples < 0L || bSamples <= aSamples) {
                throw HttpError(400, "invalid A/B samples: a=$aSamples b=$bSamples")
            }
            sampleRate = done?.sampleRate ?: existing?.sampleRate
                ?: throw HttpError(409, "显式 A/B 需要采样率:先 POST /analyze 或已有分析结果")
            candidate = LoopCandidateKt(
                startSamples = aSamples,
                endSamples = bSamples,
                score = existing?.score ?: 0.0,
                noteDistance = existing?.noteDistance ?: 0.0,
                loudnessDifference = existing?.loudnessDifference ?: 0.0,
            )
        } else if (done != null) {
            if (done.candidates.isEmpty()) throw HttpError(409, "无可用候选:分析结果为空")
            val index = obj.optInt("useCandidate", 0).coerceIn(0, done.candidates.size - 1)
            candidate = done.candidates[index]
            sampleRate = done.sampleRate
        } else if (existing != null) {
            candidate = LoopCandidateKt(
                startSamples = existing.loopStartSamples,
                endSamples = existing.loopEndSamples,
                score = existing.score,
                noteDistance = existing.noteDistance,
                loudnessDifference = existing.loudnessDifference,
            )
            sampleRate = existing.sampleRate
        } else {
            throw HttpError(409, "无可用候选:先 POST /analyze 并等待 phase=done")
        }

        val unchanged = existing != null &&
            existing.confirmed &&
            existing.loopStartSamples == candidate.startSamples &&
            existing.loopEndSamples == candidate.endSamples &&
            existing.sampleRate == sampleRate
        if (!unchanged) {
            manager.confirmAndSave(songId, candidate, sampleRate, song.duration)
        }
        libraryCache = null
        val autoPlay = tryAutoPlay(d, song)
        val stored = d.songLoopDao().getBySongId(songId)
        return linkedMapOf(
            "saved" to true,
            "songId" to songId,
            "changed" to !unchanged,
            "confirmed" to true,
            "loopStartSamples" to candidate.startSamples,
            "loopEndSamples" to candidate.endSamples,
            "sampleRate" to sampleRate,
            "autoPlay" to autoPlay,
            "loop" to stored?.let { songLoopMap(it) },
        )
    }

    /** 全库可见歌曲队列 + 目标歌 index(/confirm autoPlay 与 /loop/enter 共用)。 */
    private suspend fun libraryQueueFor(d: DebugEntryPoint, songId: Long): Pair<List<MediaItem>, Int>? {
        val songs = runCatching { d.libraryRepository().getAllSongs().first() }.getOrNull()
            ?: return null
        // 定位口径收口在 LibraryQueueTarget：index 必须指向同一份 items 里的目标歌
        // （场景 12 的根因是"进循环后 replaceMediaItem 把当前项甩走"，但 index 计算也一并单测锁住）。
        val located = LibraryQueueTarget.locate(songs, songId) ?: return null
        return located.items to located.index
    }

    /**
     * confirm 的 "先播放该歌 + REPEAT_ONE" 最佳努力实现。
     *
     * 关键:必须与 UI 的 MusicViewModel.playList 等价 —— 用**完整上下文列表**
     * setMediaItems(items, index, 0) 定位,而不是 setMediaItem(单项),否则会把
     * 播放队列压成一首,E2E 的队列/恢复用例全部失真。
     */
    private suspend fun tryAutoPlay(d: DebugEntryPoint, song: Song): Boolean {
        val p = player ?: return false
        if (p.controllerOrNull() == null) return false
        val queue = libraryQueueFor(d, song.id) ?: return false
        val mediaItems = queue.first
        val index = queue.second
        if (mediaItems.isEmpty()) return false
        val prefs = runCatching { d.playbackPreferences() }.getOrNull()
        val loopIntent = d.loopIntent()
        return try {
            p.command { controller ->
                // 与 UI「确认并保存」一致:先 arm 记录进入前 repeat,再 setRepeatMode(ONE)。
                val previousRepeat = controller.repeatMode
                val previousShuffle = controller.shuffleModeEnabled
                loopIntent.arm(song.id, previousRepeat, previousShuffle)
                // 与 UI 一致:无缝 = 单曲循环 + 关随机(显示与实际必须一致)
                controller.shuffleModeEnabled = false
                controller.repeatMode = Player.REPEAT_MODE_ONE
                prefs?.saveRepeatMode(Player.REPEAT_MODE_ONE)
                controller.setMediaItems(mediaItems, index, 0L)
                controller.prepare()
                controller.play()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "confirm autoplay skipped: ${t.message}")
            false
        }
    }

    private suspend fun handleDemote(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        val songId = body.requireLong("songId")
        if (songId <= 0L) throw HttpError(400, "songId required")
        val existing = d.songLoopDao().getBySongId(songId)
            ?: throw HttpError(404, "song_loop row not found: $songId")
        if (!existing.confirmed) {
            return linkedMapOf(
                "demoted" to false,
                "songId" to songId,
                "alreadyTemporary" to true,
                "loop" to songLoopMap(existing),
            )
        }
        d.loopAnalysisManager().demoteFinal(songId)
        libraryCache = null
        return linkedMapOf(
            "demoted" to true,
            "songId" to songId,
            "alreadyTemporary" to false,
            "loop" to d.songLoopDao().getBySongId(songId)?.let { songLoopMap(it) },
        )
    }

    private suspend fun handleDelete(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        val songId = body.requireLong("songId")
        if (songId <= 0L) throw HttpError(400, "songId required")
        val existed = d.songLoopDao().getBySongId(songId) != null
        d.loopAnalysisManager().deleteResult(songId)
        libraryCache = null
        return linkedMapOf("deleted" to true, "songId" to songId, "existed" to existed)
    }

    private fun handleCancelAnalysis(): Map<String, Any?> {
        val d = deps()
        val active = isAnalysisActive(d.loopAnalysisManager())
        val songId = analyzingSongId
        d.loopAnalysisManager().cancel()
        analyzeJob?.cancel()
        return linkedMapOf("cancelled" to true, "wasAnalyzing" to active, "songId" to songId)
    }

    // ------------------------------------------------------------ 媒体库

    private suspend fun handleLibrarySongs(params: Map<String, String>): Map<String, Any?> {
        val d = deps()
        val queryText = params["query"]?.trim().orEmpty()
        val playlistKey = params["playlist"]?.trim()
        val offset = (params["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val all = if (playlistKey.isNullOrEmpty()) {
            d.libraryRepository().getAllSongs().first()
        } else {
            resolvePlaylistSongs(d, playlistKey)
        }
        val filtered = if (queryText.isEmpty()) {
            all
        } else {
            all.filter { song ->
                song.title.contains(queryText, ignoreCase = true) ||
                    song.artist.contains(queryText, ignoreCase = true) ||
                    song.album.contains(queryText, ignoreCase = true) ||
                    song.filePath.contains(queryText, ignoreCase = true)
            }
        }
        val page = filtered.drop(offset).take(limit)
        val loops = loadLoopInfos(page.map { it.id })
        return linkedMapOf(
            "total" to filtered.size,
            "count" to page.size,
            "offset" to offset,
            "limit" to limit,
            "query" to queryText,
            "playlist" to playlistKey,
            "songs" to page.map { songMap(it, loops[it.id]) },
        )
    }

    private suspend fun handleLibraryPlaylists(): List<Map<String, Any?>> {
        val d = deps()
        val normalRepository = d.playlistRepository()
        val normal = normalRepository.getPlaylists().first().map { playlist ->
            linkedMapOf<String, Any?>(
                "id" to playlist.id,
                "name" to playlist.name,
                "songCount" to normalRepository.getPlaylistSongCount(playlist.id).first(),
                "createdAt" to playlist.createdAt,
                "smart" to false,
                "sqlText" to null,
            )
        }

        // 智能歌单与普通歌单同表返回(D1 直接用协议断言,不必走 adbdb):
        // smart=true + sqlText;songCount 实时求值,SQL 失效/执行失败按 0。
        val smartRepository = d.smartPlaylistRepository()
        val smartRows = runCatching { smartRepository.getAllList() }.getOrElse { emptyList() }
        val smart = smartRows.map { playlist ->
            val songCount = runCatching {
                smartRepository.observeSongCount(playlist.id).first()
            }.getOrDefault(0)
            linkedMapOf<String, Any?>(
                "id" to playlist.id,
                "name" to playlist.name,
                "songCount" to songCount,
                "createdAt" to playlist.createdAt,
                "smart" to true,
                "sqlText" to playlist.sqlText,
            )
        }
        return normal + smart
    }

    private suspend fun handleSongDetail(idText: String): Map<String, Any?> {
        val id = idText.toLongOrNull() ?: throw HttpError(400, "invalid song id: $idText")
        if (id <= 0L) throw HttpError(400, "invalid song id: $idText")
        val d = deps()
        val song = d.songRepository().getSongsByIds(listOf(id)).firstOrNull()
            ?: throw HttpError(404, "song not found: $id")
        val loop = d.songLoopDao().getBySongId(id)
        return songMap(song, loop?.let { LoopInfo.from(it) })
    }

    private suspend fun resolvePlaylistSongs(d: DebugEntryPoint, key: String): List<Song> {
        val playlists = d.playlistRepository().getPlaylists().first()
        val playlist = playlists.firstOrNull { it.id.toString() == key }
            ?: playlists.firstOrNull { it.name == key }
            ?: playlists.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: throw HttpError(404, "playlist not found: $key")
        return d.playlistRepository().getSongsByPlaylist(playlist.id).first()
    }

    private suspend fun librarySummary(d: DebugEntryPoint): Map<String, Any?> {
        val now = SystemClock.elapsedRealtime()
        libraryCache?.let { cached ->
            if (now - libraryCacheAt < 2_000L) return cached
        }
        val songs = d.libraryRepository().getAllSongs().first()
        val playlists = d.playlistRepository().getPlaylists().first()
        val result = linkedMapOf<String, Any?>(
            "songCount" to songs.size,
            "playlistCount" to playlists.size,
            "loopCount" to countQuery("SELECT COUNT(*) FROM song_loop"),
            "confirmedLoopCount" to countQuery("SELECT COUNT(*) FROM song_loop WHERE confirmed = 1"),
        )
        libraryCache = result
        libraryCacheAt = now
        return result
    }

    private fun countQuery(sql: String): Long {
        val db = deps().appDatabase().openHelper.readableDatabase
        return db.query(sql).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun loadLoopInfos(ids: Collection<Long>): Map<Long, LoopInfo> {
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it as Any? }.toTypedArray()
        val result = HashMap<Long, LoopInfo>(ids.size)
        try {
            val db = deps().appDatabase().openHelper.readableDatabase
            db.query(
                "SELECT songId, loopStartSamples, loopEndSamples, sampleRate, loopStartMs, loopEndMs, " +
                    "score, noteDistance, loudnessDifference, createdAt, confirmed " +
                    "FROM song_loop WHERE songId IN ($placeholders)",
                args,
            ).use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow("songId")
                while (cursor.moveToNext()) {
                    val info = LoopInfo(
                        songId = cursor.getLong(idIdx),
                        loopStartSamples = cursor.getLong(cursor.getColumnIndexOrThrow("loopStartSamples")),
                        loopEndSamples = cursor.getLong(cursor.getColumnIndexOrThrow("loopEndSamples")),
                        sampleRate = cursor.getInt(cursor.getColumnIndexOrThrow("sampleRate")),
                        loopStartMs = cursor.getLong(cursor.getColumnIndexOrThrow("loopStartMs")),
                        loopEndMs = cursor.getLong(cursor.getColumnIndexOrThrow("loopEndMs")),
                        score = cursor.getDouble(cursor.getColumnIndexOrThrow("score")),
                        noteDistance = cursor.getDouble(cursor.getColumnIndexOrThrow("noteDistance")),
                        loudnessDifference = cursor.getDouble(cursor.getColumnIndexOrThrow("loudnessDifference")),
                        createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")),
                        confirmed = cursor.getInt(cursor.getColumnIndexOrThrow("confirmed")) != 0,
                    )
                    result[info.songId] = info
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load loop infos failed", t)
        }
        return result
    }

    private fun songMap(song: Song, loop: LoopInfo?): Map<String, Any?> = linkedMapOf(
        "id" to song.id,
        "title" to song.title,
        "artist" to song.artist,
        "album" to song.album,
        "path" to song.filePath,
        "durationMs" to song.duration,
        "mimeType" to song.mimeType,
        "size" to song.size,
        "isCue" to song.isCue,
        "startPosition" to song.startPosition,
        "endPosition" to song.endPosition,
        "folderPath" to song.folderPath,
        "hasLoop" to (loop != null),
        "loopConfirmed" to loop?.confirmed,
        "loopStartSamples" to loop?.loopStartSamples,
        "loopEndSamples" to loop?.loopEndSamples,
        "sampleRate" to loop?.sampleRate,
        "loopStartMs" to loop?.loopStartMs,
        "loopEndMs" to loop?.loopEndMs,
        "score" to loop?.score,
    )

    private fun songLoopMap(row: SongLoop): Map<String, Any?> = linkedMapOf(
        "songId" to row.songId,
        "loopStartSamples" to row.loopStartSamples,
        "loopEndSamples" to row.loopEndSamples,
        "sampleRate" to row.sampleRate,
        "loopStartMs" to row.loopStartMs,
        "loopEndMs" to row.loopEndMs,
        "score" to row.score,
        "noteDistance" to row.noteDistance,
        "loudnessDifference" to row.loudnessDifference,
        "createdAt" to row.createdAt,
        "confirmed" to row.confirmed,
    )

    // ------------------------------------------------------------ 播放控制

    private fun handlePlayerState(): Map<String, Any?> {
        val p = player ?: throw PlayerUnavailable(
            DebugPlayerLink.message(DebugPlayerLink.REASON_NOT_INITIALIZED),
            DebugPlayerLink.REASON_NOT_INITIALIZED,
        )
        // controller 未连接/已断开 → 503 并附 reason;已连接即返回快照(idle/queue=0 也合法)。
        val read = p.snapshotForRead()
            ?: throw PlayerUnavailable(p.unavailableMessage(), p.unavailableReason())
        // 确保 loop bridge/entrypoint 已就绪,使 /player 的 engine 与 positionMs 折返
        // 在“先打 /player、后打 /state”的顺序下也生效;player 已连说明 Hilt 早已就绪。
        runCatching { deps() }
        return LinkedHashMap(snapshotMap(read.snapshot)).apply {
            put("connecting", false)
            put("stale", read.stale)
            put("connected", true)
            put("controllerAgeMs", p.controllerAgeMs())
            put("snapshotAgeMs", p.snapshotAgeMs())
            put("reason", null)
        }
    }

    private fun requirePlayer(): DebugPlayerConnection {
        val p = player ?: throw PlayerUnavailable(
            DebugPlayerLink.message(DebugPlayerLink.REASON_NOT_INITIALIZED),
            DebugPlayerLink.REASON_NOT_INITIALIZED,
        )
        p.controllerOrNull() ?: throw PlayerUnavailable(p.unavailableMessage(), p.unavailableReason())
        return p
    }

    private suspend fun handlePlayerCommand(action: String, body: JSONObject?): Map<String, Any?> {
        val p = requirePlayer()
        val d = deps()
        val prefs = d.playbackPreferences()
        return when (action) {
            "play" -> {
                val songId = body.requireLong("songId")
                if (songId <= 0L) throw HttpError(400, "songId required")
                val song = d.songRepository().getSongsByIds(listOf(songId)).firstOrNull()
                    ?: throw HttpError(404, "song not found: $songId")
                val repeatOne = body.optBooleanOrNull("repeatOne")
                p.command { controller ->
                    if (repeatOne != null) {
                        val mode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                        controller.repeatMode = mode
                        prefs.saveRepeatMode(mode)
                    }
                    controller.setMediaItem(song.toMediaItem())
                    controller.prepare()
                    controller.play()
                }
                linkedMapOf("playing" to true, "songId" to songId, "repeatOne" to repeatOne)
            }
            "play-pause" -> {
                val playing = p.command { controller ->
                    if (controller.isPlaying) controller.pause() else controller.play()
                    controller.playWhenReady
                }
                linkedMapOf("playing" to playing)
            }
            "next" -> {
                p.command { controller -> controller.seekToNext() }
                linkedMapOf("ok" to true, "action" to "next")
            }
            "previous" -> {
                p.command { controller -> controller.seekToPrevious() }
                linkedMapOf("ok" to true, "action" to "previous")
            }
            "seek" -> {
                val requested = body.requireLong("positionMs")
                if (requested < 0L) throw HttpError(400, "positionMs must be >= 0")
                val applied = p.command { controller ->
                    val duration = controller.duration
                    val target = if (duration > 0L) requested.coerceAtMost(duration) else requested
                    controller.seekTo(target)
                    target
                }
                linkedMapOf("positionMs" to applied, "requestedMs" to requested)
            }
            "repeat" -> {
                val modeText = body?.let { obj ->
                    when {
                        obj.has("mode") && !obj.isNull("mode") -> obj.optString("mode", "")
                        obj.has("repeatMode") && !obj.isNull("repeatMode") -> obj.optString("repeatMode", "")
                        else -> ""
                    }
                }.orEmpty().trim().lowercase()
                val mode = when (modeText) {
                    "off", "0" -> Player.REPEAT_MODE_OFF
                    "one", "1" -> Player.REPEAT_MODE_ONE
                    "all", "2" -> Player.REPEAT_MODE_ALL
                    else -> throw HttpError(400, "mode must be off|one|all")
                }
                p.command { controller ->
                    controller.repeatMode = mode
                    prefs.saveRepeatMode(mode)
                }
                linkedMapOf("repeatMode" to repeatModeName(mode))
            }
            "shuffle" -> {
                val on = body.optBooleanOrNull("on") ?: body.optBooleanOrNull("enabled")
                    ?: throw HttpError(400, "on required")
                p.command { controller ->
                    controller.shuffleModeEnabled = on
                    prefs.saveShuffleModeEnabled(on)
                }
                linkedMapOf("shuffle" to on)
            }
            else -> throw HttpError(404, "unknown player command: $action")
        }
    }

    /**
     * POST /loop/enter {songId, play=true} —— 等价于菜单「无缝循环」。
     *
     * 顺序与 UI 一致:读当前 repeat 作为 previous → LoopIntent.arm(songId, previous)
     * → setRepeatMode(ONE) + 持久化 → play=true 时按 /confirm 的保队列方式播放该歌。
     */
    private suspend fun handleLoopEnter(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        val obj = body ?: throw HttpError(400, "JSON body required")
        val songId = obj.requireLong("songId")
        if (songId <= 0L) throw HttpError(400, "songId required")
        val play = obj.optBooleanOrNull("play") ?: true
        // restart=true（默认）= 与旧行为一致：setMediaItems 重建队列并从头播。
        // restart=false = 模拟"正在播放这首歌时开启无缝循环"：不重建媒体源、不重置位置，
        // 只 arm 意图（00020 的 flush 风暴 / 00021 的声音起点都用这个口径验证）。
        val restart = obj.optBooleanOrNull("restart") ?: true

        d.songRepository().getSongsByIds(listOf(songId)).firstOrNull()
            ?: throw HttpError(404, "song not found: $songId")
        val p = requirePlayer()
        // MediaController 只能在 main 线程读;这里必须走 command 通道,否则会抛
        // "MediaController method is called from a wrong thread"。
        val currentMediaId = p.command { controller -> controller.currentMediaItem?.mediaId }
        val sameSongPlaying = !restart && play && currentMediaId != null &&
            com.zjr.hesimusic.data.mapper.mediaIdToSongId(currentMediaId) == songId
        val queue = if (play && !sameSongPlaying) {
            libraryQueueFor(d, songId)
                ?: throw HttpError(409, "song not in library queue: $songId")
        } else {
            null
        }
        val prefs = d.playbackPreferences()
        val loopIntent = d.loopIntent()
        val result = p.command { controller ->
            val previousRepeat = controller.repeatMode
            val previousShuffle = controller.shuffleModeEnabled
            loopIntent.arm(songId, previousRepeat, previousShuffle)
            // 与 UI 一致:无缝 = 单曲循环 + 关随机(显示与实际必须一致)
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_ONE
            prefs.saveRepeatMode(Player.REPEAT_MODE_ONE)
            if (queue != null) {
                controller.setMediaItems(queue.first, queue.second, 0L)
                controller.prepare()
                controller.play()
            } else if (sameSongPlaying) {
                // 同一首歌：不重建源/不 seek，仅保证在播（暂停态则继续）。
                if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
                controller.play()
            }
            previousRepeat to ((queue != null || sameSongPlaying) && controller.playWhenReady)
        }
        return linkedMapOf(
            "entered" to true,
            "songId" to songId,
            "previousRepeatMode" to result.first,
            "playing" to result.second,
        )
    }

    /** POST /loop/exit —— 只清空显式意图(与 UI 的「退出无缝」一致,不在此恢复 repeat)。 */
    private fun handleLoopExit(): Map<String, Any?> {
        val d = deps()
        d.loopIntent().clear()
        Log.i(TAG, "loop intent cleared via /loop/exit")
        return linkedMapOf("exited" to true)
    }

    private fun snapshotMap(snapshot: PlayerSnapshot, includeQueue: Boolean = true): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>(
            "songId" to snapshot.songId,
            "title" to snapshot.title,
            "artist" to snapshot.artist,
            "repeatMode" to repeatModeName(snapshot.repeatMode),
            "shuffle" to snapshot.shuffle,
            "playing" to snapshot.playing,
            "playWhenReady" to snapshot.playWhenReady,
            // 展示口径:MediaController 在 PlaybackState 刷新间会本地外推,重放跨 B 会冲出 B;
            // 与 UI 一致地只按 LoopClaim 折返（claim 未生效 = 原样媒体时间，绝不再自己拼窗口）。
            "positionMs" to displayPositionMs(snapshot),
            "durationMs" to snapshot.durationMs,
            "buffering" to snapshot.buffering,
            "playbackState" to snapshot.playbackState,
            "queueSize" to snapshot.queueSize,
            "currentIndex" to snapshot.currentIndex,
            "engine" to engineName(),
        )
        if (includeQueue) {
            map["queue"] = snapshot.queue.map { item ->
                linkedMapOf(
                    "index" to item.index,
                    "mediaId" to item.mediaId,
                    "songId" to item.songId,
                    "title" to item.title,
                    "artist" to item.artist,
                )
            }
        }
        return map
    }

    /**
     * /state、/player 的 positionMs **完全依赖 LoopClaim**（00027）。
     *
     * 回退（五种原因）时 claimActive=false → 原样媒体时间；源生效时折返是幂等的
     * （MediaController 读到的已经是 session 投影后的值）。绝不再自己拼窗口/loopPath。
     */
    private fun displayPositionMs(snapshot: PlayerSnapshot): Long =
        claimFor(snapshot.songId).displayPositionMs(snapshot.positionMs)

    /**
     * claim 读取：provider 已安装时走 [LoopClaimStore]；未安装（debug 先于 MusicService /
     * 纯 JVM 单测）时用同一个 [LoopClaimResolver] 直接算，绝不另写一套口径。
     */
    private fun claimFor(songId: Long?): LoopClaim {
        if (LoopClaimStore.isInstalled()) return LoopClaimStore.claimFor(songId)
        val entry = entryPoint ?: return LoopClaim.none(songId)
        return try {
            LoopClaimResolver.fromProcess(songId, entry.loopRuntime(), entry.loopAudioController())
        } catch (t: Throwable) {
            Log.w(TAG, "loop claim unavailable", t)
            LoopClaim.none(songId)
        }
    }

    private fun engineName(): String {
        val snapshot = try {
            DebugHooks.loopBridge?.snapshot()
        } catch (t: Throwable) {
            null
        }
        val engine = snapshot?.get("engine")?.toString()
        return if (engine.isNullOrBlank()) "exo" else engine
    }

    private fun repeatModeName(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    private fun handleNavigate(body: JSONObject?): Map<String, Any?> {
        val route = if (body == null || !body.has("route") || body.isNull("route")) {
            ""
        } else {
            body.optString("route", "").trim()
        }
        if (route.isEmpty()) throw HttpError(400, "route required")
        val hook = resolveNavigateHook()
            ?: throw HttpError(501, "navigate 未接线(main 侧 UiNavigationHooks.navigate == null)")
        // 先记 lastRoute:POST 返回后立刻 GET /state 就能断言页面;导航失败只记日志(主线程 runCatching)。
        lastRoute = route
        mainHandler.post {
            runCatching { hook.invoke(route) }
                .onFailure { Log.e(TAG, "navigate hook failed", it) }
        }
        return linkedMapOf("navigated" to true, "route" to route)
    }

    /**
     * POST /debug/decode-range {path, aMs, bMs}
     *
     * 用真实 MediaCodec 解 [aMs,bMs) 并返回指纹,供"格式矩阵"脚本在真机上比对:
     * 同一区间解两次指纹应一致;不同容器/格式的样本对齐差异由此量化。
     */
    private suspend fun handleDecodeRange(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        val obj = body ?: throw HttpError(400, "JSON body required")
        val path = obj.optString("path", "").trim()
        if (path.isEmpty()) throw HttpError(400, "path required")
        val aMs = obj.optLong("aMs", 0L)
        val bMs = obj.optLong("bMs", 0L)
        if (bMs <= aMs) throw HttpError(400, "bMs must be > aMs")
        val t0 = System.currentTimeMillis()
        val seg = d.pcmDecoder().decodePcm16Range(path, aMs * 1000L, bMs * 1000L)
        val elapsed = System.currentTimeMillis() - t0
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(seg.data)
        val hex = md.digest().joinToString("") { String.format("%02x", it) }
        // 可选:返回尾部 PCM(base64),供格式矩阵脚本比对跨窗口裁剪后的样本一致性。
        val tailFrames = obj.optInt("tailFrames", 0).coerceIn(0, 65536)
        val tailBase64 = if (tailFrames > 0) {
            val bpf = seg.channels * 2
            val tailBytes = minOf(seg.data.size, tailFrames * bpf)
            android.util.Base64.encodeToString(
                seg.data.copyOfRange(seg.data.size - tailBytes, seg.data.size),
                android.util.Base64.NO_WRAP,
            )
        } else {
            null
        }
        return linkedMapOf(
            "path" to path,
            "aMs" to aMs,
            "bMs" to bMs,
            "sampleRate" to seg.sampleRate,
            "channels" to seg.channels,
            "frames" to seg.frames,
            "bytes" to seg.data.size,
            "sha256" to hex,
            "elapsedMs" to elapsed,
            "tailFrames" to tailFrames,
            "tailPcmBase64" to tailBase64,
        )
    }

    /**
     * POST /debug/pcm-compare {path, segments?, maxSeconds?}
     *
     * 手工真机验证入口(不必跑 instrumented):同一文件先单线程、再 N 段并行解码,
     * 逐样本比较并返回 identical / firstDiffIndex / usedParallel / 两阶段耗时。
     * segments=0 用设备默认(2~4);1 只跑单线程。会清空并行能力缓存并强制重试。
     */
    private suspend fun handlePcmCompare(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        val obj = body ?: throw HttpError(400, "JSON body required")
        val path = obj.optString("path", "").trim()
        if (path.isEmpty()) throw HttpError(400, "path required")
        val segments = obj.optInt("segments", 0)
        if (segments < 0 || segments > 4) throw HttpError(400, "segments must be 0..4")
        val maxSeconds = obj.optInt("maxSeconds", 900).coerceIn(10, 900)
        if (!path.startsWith("content:")) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) throw HttpError(404, "file not readable: $path")
        }
        val decoder = d.pcmDecoder()
        decoder.clearParallelCaches()
        val out = linkedMapOf<String, Any?>(
            "path" to path,
            "requestedSegments" to segments,
        )
        try {
            ParallelDecodeHooks.disableParallel = true
            ParallelDecodeHooks.segmentCountOverride = null
            val serialStarted = System.currentTimeMillis()
            val serial = try {
                decoder.decodeMono(path, maxSeconds)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw HttpError(500, "serial decode failed: " + (t.message ?: t.javaClass.simpleName))
            }
            out["serialMs"] = System.currentTimeMillis() - serialStarted
            out["serialFrames"] = serial.frames
            out["serialSampleRate"] = serial.sampleRate
            System.gc()
            sleepQuietly(100L)

            ParallelDecodeHooks.disableParallel = false
            if (segments > 0) ParallelDecodeHooks.segmentCountOverride = segments
            val parallelStarted = System.currentTimeMillis()
            val par = try {
                decoder.decodeMono(path, maxSeconds)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw HttpError(500, "parallel decode failed: " + (t.message ?: t.javaClass.simpleName))
            }
            out["parallelMs"] = System.currentTimeMillis() - parallelStarted
            out["parallelFrames"] = par.frames
            out["parallelSampleRate"] = par.sampleRate
            out["usedParallel"] = decoder.lastDecodeUsedParallel
            out["actualSegments"] = decoder.lastDecodeSegmentCount
            out["samples"] = serial.data.size

            if (serial.sampleRate != par.sampleRate) {
                out["identical"] = false
                out["firstDiffIndex"] = -1
                out["reason"] = "sampleRate " + serial.sampleRate + " != " + par.sampleRate
            } else if (serial.frames != par.frames || serial.data.size != par.data.size) {
                out["identical"] = false
                out["firstDiffIndex"] = -1
                out["reason"] = "shape serial=" + serial.frames + " parallel=" + par.frames
            } else {
                var firstDiff = -1
                for (i in serial.data.indices) {
                    if (serial.data[i] != par.data[i]) {
                        firstDiff = i
                        break
                    }
                }
                out["identical"] = firstDiff < 0
                out["firstDiffIndex"] = firstDiff
            }
            Log.i(
                TAG,
                "pcm-compare path=" + path + " segments=" + segments + " usedParallel=" +
                    decoder.lastDecodeUsedParallel + " identical=" + out["identical"] +
                    " serialMs=" + out["serialMs"] + " parallelMs=" + out["parallelMs"],
            )
            return out
        } finally {
            ParallelDecodeHooks.disableParallel = false
            ParallelDecodeHooks.segmentCountOverride = null
        }
    }

    /**
     * POST /debug/seam {enable?: Boolean, route?: Boolean} —— 打开/关闭接缝拼接缓冲(issue 00017)。
     *
     * 两项都默认关闭(生产路径走 M3′);这里只用于真机验证。
     * - enable: seamBufferMode = ON/OFF
     * - route:  seamSinkRouteEnabled = true/false(真正把 PCM 路由切到 S)
     */
    @Suppress("UNUSED_PARAMETER")
    private fun handleSeamToggle(body: JSONObject?): Map<String, Any?> {
        val controller = deps().loopAudioController()
        val enable = body?.optBoolean("enable", controller.seamBufferMode == SeamBufferMode.ON) ?: (controller.seamBufferMode == SeamBufferMode.ON)
        val route = body?.optBoolean("route", controller.seamSinkRouteEnabled) ?: controller.seamSinkRouteEnabled
        controller.seamBufferMode = if (enable) SeamBufferMode.ON else SeamBufferMode.OFF
        controller.seamSinkRouteEnabled = route
        Log.i(TAG, "debug/seam enable=" + enable + " route=" + route)
        return linkedMapOf(
            "seamBufferMode" to controller.seamBufferMode.name,
            "seamSinkRouteEnabled" to controller.seamSinkRouteEnabled,
            "windowEligible" to controller.seamWindowEligible,
            "activeForWindow" to controller.seamActiveForWindow,
        )
    }

    /**
     * POST /debug/source-mode {on: Boolean} —— 切换循环实现路线(issue 00017)。
     *
     * on=true 走循环 PCM 数据源;false 走 M3′(默认)。
     * 真机上 settings put 缺 WRITE_SECURE_SETTINGS,这里直接写进程内开关。
     * 注意:已有播放器不会自动换 sink,需重新进入结果页/重建播放器才生效。
     */
    private fun handleSourceModeToggle(body: JSONObject?): Map<String, Any?> {
        val on = body?.optBoolean("on", LoopSourceMode.isSource()) ?: LoopSourceMode.isSource()
        val target = if (on) LoopSourceMode.SOURCE else LoopSourceMode.M3_PRIME
        // 主播放器开关同步(自测里要能双向切换)
        runCatching {
            val app = appContext
            if (app != null) LoopSourceMainPlayer.select(app, on) else LoopSourceMainPlayer.setEnabledForTest(on)
            LoopSourceMainPlayer.activateMainPlayer(on)
        }
        // 覆盖 refreshFromSettings:真机 ROM 常拒绝 adb settings put。
        LoopSourceMode.debugOverride = target
        LoopSourceMode.current = target
        Log.i(TAG, "debug/source-mode on=" + on)
        return linkedMapOf(
            "loopSourceMode" to LoopSourceMode.current.name,
            "note" to "reopen the loop page for it to take effect",
        )
    }

    /**
     * POST /debug/invariant {kind} —— 不变量故障注入（仅 debug，不影响真实播放）。
     *
     * kind:
     * - "paused-write"：每 500ms 采样注入一次「暂停但仍写 96ms 帧」→ paused-but-sink-wrote；
     * - "silent"：注入「播放但零帧」→ playing-but-sink-silent（约 1s 后触发）；
     * - "source-plain"：注入「循环投影但 item 无 tag / 工厂选普通源」→ projection=loop but source=plain；
     * - "source-loop"：注入「非循环投影但 item 带 tag」→ projection=plain but source=loop；
     * - §12.2 的 12 个 kind（每个 code 一个）：
     *   "period-continue-loading" / "extractor-track-after-end" / "sink-empty-buffer" /
     *   "sink-configure-split" / "timeline-frame-us-roundtrip" / "projection-duration" /
     *   "sink-position-domain" / "seek-same-position" / "prepare-error" /
     *   "sink-configure-no-flush" / "controller-commands" / "notification-gone"；
     * - "off"：关闭注入（判定器状态复位）；
     * - "clear"：清空 /state.invariants 历史并关闭注入。
     *
     * 每种 kind 都喂**真实判定器**合成采样，验证「采样 → 判定 → Log.e → /state.invariants」；
     * "prepare-error" 例外：它记录注入时刻，然后观察真实 onPlayerError 是否在 3s 内到达。
     * 对应的 code 清单见 docs/runtime-invariants-undefined.md。
     */
    private fun handleInvariantFault(body: JSONObject?): Map<String, Any?> {
        // 确保 Hilt 依赖已解析 + 采样器已启动；顺手拉起 player controller（注入生效需要它在跑）。
        deps()
        player?.ensureConnecting()
        val kind = body?.optString("kind", "")?.trim()?.lowercase().orEmpty()
        if (kind.isEmpty()) throw HttpError(400, "kind required")
        if (kind != "off" && kind != "clear" && kind !in PlaybackInvariantMonitor.FAULT_KINDS) {
            throw HttpError(
                400,
                "unknown kind: " + kind + " (allowed=" +
                    (setOf("off", "clear") + PlaybackInvariantMonitor.FAULT_KINDS) + ")",
            )
        }
        PlaybackInvariantMonitor.setFaultInjection(kind)
        Log.i(TAG, "debug/invariant kind=" + kind)
        return linkedMapOf(
            "injected" to kind,
            "kind" to kind,
            "faultKinds" to PlaybackInvariantMonitor.FAULT_KINDS.sorted(),
        )
    }

    /** POST /debug/reconnect —— 显式丢弃当前 controller 并重建(最多等 2s)。 */
    private fun handleDebugReconnect(): Map<String, Any?> {
        val p = player ?: throw PlayerUnavailable(
            DebugPlayerLink.message(DebugPlayerLink.REASON_NOT_INITIALIZED),
            DebugPlayerLink.REASON_NOT_INITIALIZED,
        )
        val reconnected = p.forceReconnectAndAwait(2_000L)
        Log.i(TAG, "explicit reconnect -> $reconnected")
        return linkedMapOf(
            "reconnected" to reconnected,
            "controllerAgeMs" to p.controllerAgeMs(),
            "connected" to p.isConnected(),
            "snapshotAgeMs" to p.snapshotAgeMs(),
            "connecting" to p.isStartingUp(),
        )
    }

    /**
     * POST /debug/tap {path?: String} —— L3 PCM 取证开关。
     * path 非空 → LoopAudioController.setPcmTap(File(path));空/缺省 → 关闭。
     * 相对路径按 filesDir 解析;返回 tapping/path,便于 PC 用 run-as 拉文件。
     */
    /**
     * GET /debug/preview —— 预览播放器只读快照(issue 00023 量测)。
     *
     * 返回当前页面那一份 PreviewPlayer 的现场:engine/sourceActive/窗口/播放器位置(原始 T)/展示位置/
     * 播放状态/isPlaying/真正写出的音频帧数(outputFrames)/最近一次 seek 的请求与应用时刻。
     * 409 = 结果页还没 prepare 出预览播放器。
     */
    private fun handlePreviewSnapshot(): Map<String, Any?> {
        val snapshot = PreviewProbe.snapshot()
            ?: throw HttpError(409, "preview probe not attached (result page not open)")
        return linkedMapOf(
            "available" to true,
            "bridgeRegistered" to (LoopPreviewDebugBridge.seekRequest != null),
            "snapshot" to snapshot,
        )
    }

    /**
     * POST /debug/preview-seek {positionMs} —— 脚本触发预览 seek,并**同步测量**到落点/出声的延迟。
     *
     * 测量口径(issue 00023 对照表):
     * - `applyMs`: 请求 → PreviewPlayer 真正调用 ExoPlayer.seekTo 的耗时(主线程应用延迟);
     * - `advanceMs`: 请求 → 播放器位置推进到落点之后 30ms 且 state=READY 的耗时。
     *   **注意(00023 后续实测)**: seek 未确认前 currentPosition 可能还是旧位置,advanceMs
     *   会被提前满足(真机出现 67ms 而真正出声 160ms 的偏差),只作参考;
     * - `stages`: 逐段耗时(请求 → apply → extractorSeek → firstBuffer → sinkPlay → ready → playout)。
     *   `stages.playoutMs` = media3 `onAudioPositionAdvancing` 给出的 AudioTrack 播放头开始
     *   推进时刻,是**真正出声**的应用侧口径,验收以它为准;
     * - `framesDelta`: 同窗口内 TapAudioSink 真正写出给 AudioTrack 的帧数增量(出声的旁证);
     * - `displayAfterMs`: 稳定后的展示位置 —— 区外 seek 若被夹回会在这一项立刻暴露。
     *
     * 轮询 4ms 一次,最多 2.5s;暂停态只测 applyMs(不会推进);播放态会额外等到 playout
     * (最多再 800ms),让 stages 里的出声时间可直接读。
     */
    private fun handlePreviewSeek(body: JSONObject?): Map<String, Any?> {
        val requested = body?.requireLong("positionMs")
            ?: throw HttpError(400, "positionMs is required")
        if (requested < 0L) throw HttpError(400, "positionMs must be >= 0")
        val before = PreviewProbe.snapshot()
            ?: throw HttpError(409, "preview probe not attached (result page not open)")
        val seqBefore = (before["seekRequests"] as? Number)?.toLong() ?: 0L
        val framesBefore = (before["outputFrames"] as? Number)?.toLong() ?: 0L
        val wasPlaying = before["isPlaying"] == true
        val t0 = SystemClock.elapsedRealtime()
        val ok = LoopPreviewDebugBridge.requestSeek(requested)
        if (!ok) throw HttpError(409, "preview seek bridge is not registered (SOURCE player not created)")

        var last: Map<String, Any?> = before
        var applyMs: Long? = null
        var advanceMs: Long? = null
        var appliedTarget: Long? = null
        var landingDisplay: Long? = null
        var framesAtApply: Long? = null
        var advanceSatisfiedAtMs = 0L
        val deadline = t0 + 2_500L
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = SystemClock.elapsedRealtime()
            val s = try {
                PreviewProbe.snapshot()
            } catch (t: Throwable) {
                null
            } ?: break
            last = s
            val seq = (s["seekRequests"] as? Number)?.toLong() ?: 0L
            if (applyMs == null && seq > seqBefore) {
                applyMs = now - t0
                appliedTarget = (s["lastSeekPlayerTargetMs"] as? Number)?.toLong()
                // seek 刚落地时的实时展示位置 = 真正的落点(不受"位置开始前进"采样延迟影响)。
                landingDisplay = ((s["liveDisplayMs"] ?: s["displayMs"]) as? Number)?.toLong()
                framesAtApply = (s["outputFrames"] as? Number)?.toLong()
                if (!wasPlaying) break
            }
            val target = appliedTarget
            if (advanceMs == null && target != null) {
                val pos = (s["playerPositionMs"] as? Number)?.toLong() ?: 0L
                val state = s["playbackStateName"] as? String
                if (pos >= target + 10L && state == "READY") {
                    advanceMs = now - t0
                    advanceSatisfiedAtMs = now
                }
            }
            // advanceMs 会被"seek 未确认时的旧位置"提前满足，所以继续等到真正出声
            // （onAudioPositionAdvancing → stages.playoutMs），最多再等 800ms。
            val stages = s["seekStages"] as? Map<*, *>
            val playoutMs = (stages?.get("playoutMs") as? Number)?.toLong()
            if (applyMs != null) {
                val graceBase = if (advanceSatisfiedAtMs > 0L) {
                    advanceSatisfiedAtMs
                } else {
                    t0 + (applyMs ?: 0L)
                }
                if (playoutMs != null || now - graceBase >= 800L) break
            }
            try {
                Thread.sleep(4L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        val framesEnd = (last["outputFrames"] as? Number)?.toLong() ?: framesBefore
        return linkedMapOf(
            "ok" to true,
            "requestedMs" to requested,
            "wasPlaying" to wasPlaying,
            "applyMs" to applyMs,
            "advanceMs" to advanceMs,
            "appliedPlayerTargetMs" to appliedTarget,
            "framesBefore" to framesBefore,
            "framesAtApply" to framesAtApply,
            "framesEnd" to framesEnd,
            "framesDelta" to (framesEnd - framesBefore),
            // 落点用实时口径(liveDisplayMs):UI ticker 有最多 100ms 滞后,会把 ±100ms 判据挤爆。
            // 落点:seek 落地那一刻的实时展示位置(精确落点判据)。
            "landingDisplayAfterMs" to landingDisplay,
            // 观测口径:播放头开始前进时的位置(带 target+10~130ms 的采样偏移,只作参考)。
            "displayAfterMs" to ((last["liveDisplayMs"] ?: last["displayMs"]) as? Number)?.toLong(),
            "tickedDisplayAfterMs" to (last["displayMs"] as? Number)?.toLong(),
            "playerPositionAfterMs" to (last["playerPositionMs"] as? Number)?.toLong(),
            "playbackStateAfter" to last["playbackStateName"],
            "isPlayingAfter" to last["isPlaying"],
            "engine" to last["engine"],
            "sourceActive" to last["sourceActive"],
            "windowAfterA_ms" to last["windowA_ms"],
            "windowAfterB_ms" to last["windowB_ms"],
            "outsideLoopAfter" to last["outsideLoop"],
            "sourceWindowAfterA_ms" to last["sourceWindowA_ms"],
            "sourceWindowAfterB_ms" to last["sourceWindowB_ms"],
            "sourceKindAfter" to last["sourceKind"],
            "mode" to last["mode"],
            "outputFrames" to framesEnd,
            // 00023 后续：逐段耗时（请求→落地→extractor.seek→首 PCM→sink.play→READY→出声）。
            // playoutMs 用 media3 的 onAudioPositionAdvancing（AudioTrack 播放头开始推进），
            // 比 advanceMs 的位置轮询稳定：seek 未确认时 currentPosition 可能是旧位置。
            "stages" to last["seekStages"],
            "playoutMs" to ((last["seekStages"] as? Map<*, *>)?.get("playoutMs") as? Number)?.toLong(),
            "elapsedMs" to (SystemClock.elapsedRealtime() - t0),
        )
    }

    private suspend fun handleDebugTap(body: JSONObject?): Map<String, Any?> {
        val d = deps()
        // JSON null 时 optString 会返回字面量 "null",必须显式判 isNull。
        val rawPath = if (body == null || !body.has("path") || body.isNull("path")) {
            ""
        } else {
            body.optString("path", "").trim()
        }
        val file = if (rawPath.isEmpty()) {
            null
        } else {
            val candidate = File(rawPath)
            val resolved = if (candidate.isAbsolute) {
                candidate
            } else {
                File(appContext?.filesDir ?: throw HttpError(503, "debug server initializing"), rawPath)
            }
            resolved.parentFile?.mkdirs()
            resolved
        }
        try {
            d.loopAudioController().setPcmTap(file)
            // 预览页用的是独立 controller，必须同步镜像，否则 SOURCE 路径写不到 PCM。
            com.zjr.hesimusic.loop.audio.source.LoopPreviewDebugBridge.setTap(file)
        } catch (t: Throwable) {
            Log.e(TAG, "pcm tap setup failed", t)
            throw HttpError(500, "tap failed: ${t.message}")
        }
        return linkedMapOf(
            "tapping" to (file != null),
            "path" to file?.absolutePath,
        )
    }

    /**
     * 导航钩子来源:main 源集的 com.zjr.hesimusic.ui.common.UiNavigationHooks.navigate
     * (由 ui-a 新建、MainActivity 注册);DebugHooks.navigate 作为 debug 侧备用。
     *
     * 用反射而非直接引用:UiNavigationHooks 可能尚未落盘,直接引用会把 debug 编译锁死;
     * release 不编译本类,反射也不会出现在 release 包。
     */
    private fun resolveNavigateHook(): ((String) -> Unit)? {
        try {
            val cls = Class.forName("com.zjr.hesimusic.ui.common.UiNavigationHooks")
            val getter = cls.getMethod("getNavigate")
            // Kotlin object 的成员 getter 是实例方法,需要 INSTANCE;若未来改成 @JvmStatic
            // (static 方法)传非 null receiver 也会被忽略,两种形态都兼容。
            val instance = try {
                cls.getField("INSTANCE").get(null)
            } catch (_: Throwable) {
                null
            }
            @Suppress("UNCHECKED_CAST")
            val hook = getter.invoke(instance) as? (String) -> Unit
            if (hook != null) return hook
        } catch (t: Throwable) {
            Log.w(TAG, "UiNavigationHooks.resolve failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return DebugHooks.navigate
    }

    // ------------------------------------------------------------ 请求解析

    private data class DebugRequest(
        val method: String,
        val rawTarget: String,
        val path: String,
        val query: Map<String, String>,
        val body: JSONObject?,
    )

    private data class LoopInfo(
        val songId: Long,
        val loopStartSamples: Long,
        val loopEndSamples: Long,
        val sampleRate: Int,
        val loopStartMs: Long,
        val loopEndMs: Long,
        val score: Double,
        val noteDistance: Double,
        val loudnessDifference: Double,
        val createdAt: Long,
        val confirmed: Boolean,
    ) {
        companion object {
            fun from(row: SongLoop): LoopInfo = LoopInfo(
                songId = row.songId,
                loopStartSamples = row.loopStartSamples,
                loopEndSamples = row.loopEndSamples,
                sampleRate = row.sampleRate,
                loopStartMs = row.loopStartMs,
                loopEndMs = row.loopEndMs,
                score = row.score,
                noteDistance = row.noteDistance,
                loudnessDifference = row.loudnessDifference,
                createdAt = row.createdAt,
                confirmed = row.confirmed,
            )
        }
    }

    private fun parseRequest(client: Socket): DebugRequest {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input, MAX_HEADER_LINE_BYTES)
            ?: throw HttpError(400, "empty request")
        val parts = requestLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) throw HttpError(400, "bad request line: $requestLine")
        val method = parts[0].uppercase()
        val rawTarget = normalizeTarget(parts[1])
        val queryIndex = rawTarget.indexOf('?')
        val path = if (queryIndex >= 0) rawTarget.substring(0, queryIndex) else rawTarget
        val query = if (queryIndex >= 0) parseQuery(rawTarget.substring(queryIndex + 1)) else emptyMap()

        var contentLength = 0
        var chunked = false
        var expectContinue = false
        var headerBytes = 0
        var headerCount = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_LINE_BYTES) ?: break
            if (line.isEmpty()) break
            headerBytes += line.length
            headerCount++
            if (headerBytes > MAX_HEADER_TOTAL_BYTES) throw HttpError(400, "headers too large")
            if (headerCount > MAX_HEADERS) throw HttpError(400, "too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (name) {
                "content-length" -> {
                    contentLength = value.toIntOrNull()
                        ?: throw HttpError(400, "invalid Content-Length")
                    if (contentLength < 0) throw HttpError(400, "invalid Content-Length")
                }
                "transfer-encoding" -> if (value.contains("chunked", ignoreCase = true)) chunked = true
                "expect" -> if (value.contains("100-continue", ignoreCase = true)) expectContinue = true
            }
        }

        // curl 对 >1KB 的 POST 会先发 Expect: 100-continue;不回 100 会白等约 1s。
        if (expectContinue && (chunked || contentLength > 0)) {
            try {
                val out = client.getOutputStream()
                out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            } catch (_: Throwable) {
                // 客户端提前断开:继续读 body 时自然会失败
            }
        }

        val bodyBytes: ByteArray = when {
            chunked -> readChunkedBody(input)
            contentLength > MAX_BODY_BYTES -> throw HttpError(400, "body too large: $contentLength")
            contentLength > 0 -> readFully(input, contentLength)
            else -> ByteArray(0)
        }
        val bodyText = if (bodyBytes.isEmpty()) "" else String(bodyBytes, Charsets.UTF_8)
        val body = if (bodyText.isBlank()) {
            null
        } else {
            try {
                JSONObject(bodyText)
            } catch (e: Exception) {
                throw HttpError(400, "invalid JSON body: ${e.message}")
            }
        }
        return DebugRequest(method, rawTarget, path, query, body)
    }

    private fun normalizeTarget(target: String): String {
        if (target.startsWith("/")) return target
        val scheme = target.indexOf("://")
        if (scheme >= 0) {
            val slash = target.indexOf('/', scheme + 3)
            if (slash >= 0) return target.substring(slash)
        }
        return "/" + target
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = decodeComponent(if (eq >= 0) pair.substring(0, eq) else pair)
            val value = if (eq >= 0) decodeComponent(pair.substring(eq + 1)) else ""
            result[key] = value
        }
        return result
    }

    private fun pathSegments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }.map { decodeComponent(it) }

    private fun decodeComponent(text: String): String = try {
        URLDecoder.decode(text, "UTF-8")
    } catch (e: Exception) {
        text
    }

    private fun readLine(input: InputStream, maxBytes: Int): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) {
                if (buffer.size() == 0) return null
                break
            }
            if (value == '\n'.code) break
            if (buffer.size() >= maxBytes) throw HttpError(400, "line too long")
            buffer.write(value)
        }
        var line = buffer.toString("ISO-8859-1")
        if (line.endsWith("\r")) line = line.dropLast(1)
        return line
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) throw HttpError(400, "unexpected end of body")
            offset += read
        }
        return data
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input, 128) ?: throw HttpError(400, "bad chunked body")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw HttpError(400, "bad chunk size: $sizeLine")
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input, MAX_HEADER_LINE_BYTES) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (output.size() + size > MAX_BODY_BYTES) throw HttpError(400, "body too large")
            output.write(readFully(input, size))
            readLine(input, 2)
        }
        return output.toByteArray()
    }

    // ------------------------------------------------------------ 响应

    private fun writeResponse(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        val watchdog = try {
            writeWatchdog.schedule({ closeQuietly(client) }, SOCKET_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            null
        }
        try {
            val out = BufferedOutputStream(client.getOutputStream())
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } finally {
            watchdog?.cancel(false)
        }
    }

    private fun safeWrite(client: Socket, status: Int, body: String) {
        try {
            writeResponse(client, status, body)
        } catch (t: Throwable) {
            Log.w(TAG, "write response failed: ${t.message}")
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        409 -> "Conflict"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status"
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }

    private fun closeQuietly(socket: ServerSocket?) {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ------------------------------------------------------------ JSON 参数

    private fun JSONObject?.requireLong(key: String): Long {
        val obj = this ?: throw HttpError(400, "JSON body required")
        if (!obj.has(key) || obj.isNull(key)) throw HttpError(400, "$key required")
        return parseLong(obj.opt(key)) ?: throw HttpError(400, "$key must be a number")
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else parseLong(opt(key))

    private fun JSONObject?.optBooleanOrNull(key: String): Boolean? {
        val obj = this ?: return null
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    private fun parseLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }
}
