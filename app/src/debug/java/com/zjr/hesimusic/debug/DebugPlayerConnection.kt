package com.zjr.hesimusic.debug

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.service.MusicService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** /player 队列摘要中的一项。 */
internal data class PlayerQueueItem(
    val index: Int,
    val mediaId: String?,
    val songId: Long?,
    val title: String?,
    val artist: String?,
)

/** 一次播放态快照(所有字段均在主线程读取)。 */
internal data class PlayerSnapshot(
    val songId: Long?,
    val title: String?,
    val artist: String?,
    val repeatMode: Int,
    val shuffle: Boolean,
    val playing: Boolean,
    val playWhenReady: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val buffering: Boolean,
    val playbackState: String,
    val queueSize: Int,
    val currentIndex: Int,
    val queue: List<PlayerQueueItem>,
)

/** 读取结果:stale=true 表示主线程超时,返回的是最近一次缓存。 */
internal data class PlayerRead(val snapshot: PlayerSnapshot, val stale: Boolean)

/**
 * debug 协议专用 MediaController 懒连接(不依赖/不修改任何 main 类)。
 *
 * 设计要点(对应“snapshot 永远 invalid、/player 永远 503”的回归):
 * - **有效性只看链路**:controller == null 或 controller.isConnected == false 才算 invalid;
 *   age、queueSize、playbackState、position 都不参与判定,只写日志。空闲时 age 增长不会再触发重建;
 * - **只在拿不到 controller 或收到断连回调时重建**:MediaController.Listener.onDisconnected
 *   或读取时发现 isConnected=false 才丢弃旧 controller,并按 2s 退避重新建连;
 *   POST /debug/reconnect 仍可显式强制重建;
 * - 未就绪时**绝不阻塞等待**:controllerOrNull() 只投递建连请求后立即返回 null,
 *   调用方转 503(带 reason);3s 内未建连则放弃本次尝试并 2s 后重试;
 * - 已连接时所有 Player 访问都投递到主线程,读 1.5s / 写 2.5s 超时,
 *   超时用最近缓存兜底(只读)或 503(控制),不会把 server 线程拖死;
 * - Player.Listener 持续维护快照缓存,并输出 "player state updated songId=... pos=... at=..."
 *   心跳日志(500ms 节流),用于确认 collector 真的在更新。
 */
@UnstableApi
internal class DebugPlayerConnection(private val context: Context) {

    companion object {
        private const val TAG = "LoopDebug"
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val RECONNECT_WAIT_MS = 2_000L
        private const val RECONNECT_POLL_MS = 100L
        private const val MAIN_READ_TIMEOUT_MS = 1_500L
        private const val MAIN_VALIDATE_TIMEOUT_MS = 500L
        private const val MAIN_WRITE_TIMEOUT_MS = 2_500L
        private const val MAX_QUEUE_ITEMS = 200
        private const val STATE_LOG_MIN_INTERVAL_MS = 500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var controller: MediaController? = null

    @Volatile
    private var connecting = false

    @Volatile
    private var cache: PlayerSnapshot? = null

    /** 最近一次快照写入时刻(elapsedRealtime);null/0 表示还没有可用快照。 */
    @Volatile
    private var cacheUpdatedAt = 0L

    /** controller 建立时刻(elapsedRealtime);null/0 表示当前无 controller。 */
    @Volatile
    private var controllerCreatedAt = 0L

    /** 上一次读取不可用的原因(链路正常时才会用于 503 reason 兜底)。 */
    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var lastStateLogAt = 0L

    @Volatile
    private var lastStateLogSongId: Long? = null

    private var future: ListenableFuture<MediaController>? = null
    private var connectStartedAt = 0L
    private var nextRetryAt = 0L
    private var connectSeq = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            // 旧 controller 的迟到事件不得覆盖新链路的缓存。
            if (player !== controller) return
            val snapshot = runCatching { readSnapshot(player) }.getOrNull() ?: return
            updateCache(snapshot)
        }
    }

    /** Session 断连回调:这是“收到断连回调才重建”的唯一自动入口。 */
    private val disconnectListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            handleDisconnected(controller, "session callback")
        }
    }

    fun isConnecting(): Boolean = connecting

    /** controller 是否存在且仍与服务端相连——这是唯一的“可用”口径。 */
    fun isConnected(): Boolean = controller?.isConnected == true

    /**
     * 主线程读取 controller.availableCommands（A-NEW-11 不变量采样）。
     *
     * 未连接/读失败返回 null；命令以 media3 的 int 常量返回，由 debug 采样侧映射成可读名字。
     */
    fun availableCommandIds(): Set<Int>? {
        val current = controllerOrNull() ?: return null
        if (!current.isConnected) return null
        return try {
            callOnMain(MAIN_READ_TIMEOUT_MS) {
                val commands = current.availableCommands
                val ids = HashSet<Int>(commands.size())
                for (i in 0 until commands.size()) {
                    ids.add(commands.get(i))
                }
                ids
            }
        } catch (t: Throwable) {
            Log.d(TAG, "availableCommands read failed: " + t.message)
            null
        }
    }

    /** 当前 controller 建立时刻到现在;无 controller 时 null。仅供日志/诊断,不参与有效性判定。 */
    fun controllerAgeMs(): Long? {
        val current = controller ?: return null
        val created = controllerCreatedAt
        if (created <= 0L || !current.isConnected) return null
        return (SystemClock.elapsedRealtime() - created).coerceAtLeast(0L)
    }

    /** 最近一次快照写入时刻到现在;无快照时 null。 */
    fun snapshotAgeMs(): Long? {
        if (cache == null) return null
        val updated = cacheUpdatedAt
        if (updated <= 0L) return null
        return (SystemClock.elapsedRealtime() - updated).coerceAtLeast(0L)
    }

    /** 当前 503 的原因(见 DebugPlayerLink.REASON_*)。 */
    fun unavailableReason(): String = DebugPlayerLink.reason(
        hasController = controller != null,
        connected = isConnected(),
        fallback = lastFailure,
    )

    /** 当前 503 的可读 message;PC 脚本仍以 reason 字段为准。 */
    fun unavailableMessage(): String = DebugPlayerLink.message(unavailableReason())

    /**
     * 是否仍处于“没连上”的状态(/state.connecting 与 /analyze 的 pause 跳过共用)。
     * 新口径:只看链路(controller 缺失或已断开),与连接时长、队列内容无关。
     */
    fun isStartingUp(): Boolean = DebugPlayerLink.isInvalid(
        hasController = controller != null,
        connected = isConnected(),
    )

    /** 最近一次 Player.Listener 事件缓存里的当前歌 id(不阻塞主线程;供 loop bridge 判 engine)。 */
    fun cachedSongId(): Long? = cache?.songId

    /**
     * 非阻塞取 controller:未建连时触发一次后台建连并返回 null。
     * 3s 未完成则放弃该次 future(释放),2s 后允许重试。
     * 若已建连但 isConnected=false(漏掉了断连回调),在此丢弃并按退避重建。
     */
    fun controllerOrNull(): MediaController? {
        val current = controller
        if (current != null) {
            if (current.isConnected) return current
            handleDisconnected(current, "controller reported disconnected")
        }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val latest = controller
            if (latest != null && latest.isConnected) return latest
            if (connecting && now - connectStartedAt >= CONNECT_TIMEOUT_MS) {
                Log.w(TAG, "player connect timeout after ${now - connectStartedAt}ms; retry in ${RETRY_DELAY_MS}ms")
                connecting = false
                nextRetryAt = now + RETRY_DELAY_MS
                val staleFuture = future
                future = null
                if (staleFuture != null) {
                    mainHandler.post { runCatching { MediaController.releaseFuture(staleFuture) } }
                }
            }
            if (!connecting && now >= nextRetryAt) {
                startConnectLocked(now)
            }
        }
        return controller?.takeIf { it.isConnected }
    }

    /** 触发建连(不等待)。 */
    fun ensureConnecting() {
        controllerOrNull()
    }

    /** 强制丢弃当前 controller/future 并重新建连(不等待结果)。仅供显式调试入口使用。 */
    fun forceReconnect(reason: String = "explicit") {
        var old: MediaController? = null
        var oldFuture: ListenableFuture<MediaController>? = null
        synchronized(lock) {
            old = controller
            oldFuture = future
            controller = null
            cache = null
            cacheUpdatedAt = 0L
            future = null
            connecting = false
            connectStartedAt = 0L
            nextRetryAt = 0L
            controllerCreatedAt = 0L
            lastFailure = null
            connectSeq++
        }
        Log.i(TAG, "player controller force reconnect: $reason")
        mainHandler.post {
            runCatching { old?.removeListener(listener) }
            runCatching { old?.release() }
            oldFuture?.let { stale -> runCatching { MediaController.releaseFuture(stale) } }
        }
        ensureConnecting()
    }

    /** 强制重建并最多等待 timeoutMs 直到新 controller 连接成功(不再要求快照内容有效)。 */
    fun forceReconnectAndAwait(timeoutMs: Long = RECONNECT_WAIT_MS): Boolean {
        forceReconnect("explicit reconnect")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = controllerOrNull()
            if (candidate != null && candidate.isConnected) {
                val snapshot = readSnapshotCached(candidate)
                Log.i(
                    TAG,
                    "player controller reconnected (songId=${snapshot?.songId}, queue=${snapshot?.queueSize}, " +
                        "state=${snapshot?.playbackState})",
                )
                return true
            }
            sleepQuietly(RECONNECT_POLL_MS)
        }
        Log.w(TAG, "player controller reconnect timed out after ${timeoutMs}ms")
        return false
    }

    private fun startConnectLocked(now: Long) {
        connecting = true
        connectStartedAt = now
        val seq = ++connectSeq
        mainHandler.post {
            try {
                val token = SessionToken(context, ComponentName(context, MusicService::class.java))
                val built = MediaController.Builder(context, token)
                    .setListener(disconnectListener)
                    .buildAsync()
                synchronized(lock) {
                    if (seq == connectSeq) future = built
                }
                built.addListener({
                    var created: MediaController? = null
                    var failure: Throwable? = null
                    try {
                        created = built.get()
                    } catch (t: Throwable) {
                        failure = t
                    }
                    val createdController = created
                    if (createdController != null) {
                        var accepted = false
                        synchronized(lock) {
                            if (seq == connectSeq) {
                                controller = createdController
                                controllerCreatedAt = SystemClock.elapsedRealtime()
                                connecting = false
                                nextRetryAt = 0L
                                future = null
                                lastFailure = null
                                createdController.addListener(listener)
                                accepted = true
                            }
                        }
                        if (accepted) {
                            Log.i(TAG, "player controller connected")
                            val initial = runCatching { readSnapshot(createdController) }.getOrNull()
                            if (initial != null) updateCache(initial)
                        } else {
                            Log.w(TAG, "stale player connect success; releasing")
                            runCatching { createdController.release() }
                        }
                    } else {
                        synchronized(lock) {
                            if (seq == connectSeq) {
                                connecting = false
                                future = null
                                nextRetryAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
                                lastFailure = DebugPlayerLink.REASON_NOT_CONNECTED
                                Log.w(TAG, "player controller connect failed: ${failure?.message}", failure)
                            } else {
                                Log.w(TAG, "stale player connect failed: ${failure?.message}")
                            }
                        }
                    }
                }, ContextCompat.getMainExecutor(context))
            } catch (t: Throwable) {
                Log.e(TAG, "player controller build failed", t)
                synchronized(lock) {
                    if (seq == connectSeq) {
                        connecting = false
                        nextRetryAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
                        lastFailure = DebugPlayerLink.REASON_NOT_CONNECTED
                    }
                }
            }
        }
    }

    /**
     * 只读快照:controller 缺失/已断开返回 null(调用方 503,带 reason);
     * 主线程超时/异常用缓存兜底(标记 stale)。
     * **不再**因为快照内容或 age 判定 invalid,更不会在这里重建 controller。
     */
    fun snapshotForRead(): PlayerRead? {
        val current = controllerOrNull()
        if (current == null) return null
        if (!current.isConnected) {
            handleDisconnected(current, "snapshot read")
            return null
        }
        val read = try {
            val snapshot = callOnMain(MAIN_READ_TIMEOUT_MS) { readSnapshot(current) }
            if (controller === current) updateCache(snapshot)
            PlayerRead(snapshot, stale = false)
        } catch (e: PlayerTimeout) {
            Log.w(TAG, "player snapshot timed out; fallback to cache")
            val cached = cache
            if (cached == null) {
                lastFailure = DebugPlayerLink.REASON_SNAPSHOT_TIMEOUT
                return null
            }
            PlayerRead(cached, stale = true)
        } catch (t: Throwable) {
            Log.w(TAG, "player snapshot failed", t)
            val cached = cache
            if (cached == null) {
                lastFailure = DebugPlayerLink.REASON_SNAPSHOT_FAILED
                return null
            }
            PlayerRead(cached, stale = true)
        }
        lastFailure = null
        return read
    }

    /**
     * 执行一条主线程 Player 命令。controller 缺失/已断开 → PlayerUnavailable(503,带 reason);
     * 已连接即执行,不再因为“restore 还没灌队列/age 超过 3s”而重建或拒绝。
     */
    fun <T> command(timeoutMs: Long = MAIN_WRITE_TIMEOUT_MS, block: (MediaController) -> T): T {
        val ready = readyController() ?: throw PlayerUnavailable(unavailableMessage(), unavailableReason())
        return try {
            callOnMain(timeoutMs) {
                val result = block(ready)
                runCatching { readSnapshot(ready) }.getOrNull()?.let { snapshot ->
                    if (controller === ready) updateCache(snapshot)
                }
                result
            }
        } catch (e: PlayerTimeout) {
            throw PlayerUnavailable("player timeout", DebugPlayerLink.REASON_SNAPSHOT_TIMEOUT)
        }
    }

    /** 有 controller 且 isConnected 才算就绪;否则返回 null 让调用方快速 503(不阻塞、不重建)。 */
    private fun readyController(): MediaController? {
        val current = controllerOrNull() ?: return null
        if (!current.isConnected) {
            handleDisconnected(current, "player command")
            return null
        }
        return current
    }

    /**
     * 丢弃一个已断开的 controller 并安排重建。
     * 只接受“当前 controller”的断连,避免 forceReconnect/release 的迟到回调误伤新链路。
     */
    private fun handleDisconnected(source: MediaController, reason: String) {
        var current = false
        synchronized(lock) {
            if (controller === source) {
                controller = null
                controllerCreatedAt = 0L
                connecting = false
                future = null
                nextRetryAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
                cache = null
                cacheUpdatedAt = 0L
                lastFailure = DebugPlayerLink.REASON_DISCONNECTED
                current = true
            }
        }
        if (!current) return
        Log.w(TAG, "player controller disconnected ($reason); reconnect after ${RETRY_DELAY_MS}ms")
        mainHandler.post {
            runCatching { source.removeListener(listener) }
            runCatching { source.release() }
        }
    }

    /** 主线程读快照并把结果写入缓存;超时返回旧缓存。 */
    private fun readSnapshotCached(candidate: MediaController): PlayerSnapshot? {
        return try {
            val snapshot = callOnMain(MAIN_VALIDATE_TIMEOUT_MS) { readSnapshot(candidate) }
            if (controller === candidate) updateCache(snapshot)
            snapshot
        } catch (t: Throwable) {
            Log.d(TAG, "player validate read failed: ${t.message}")
            cache
        }
    }

    /** 写入最近快照(Listener / 读路径 / 命令路径共用),并输出 collector 心跳日志。 */
    private fun updateCache(snapshot: PlayerSnapshot) {
        cache = snapshot
        val now = SystemClock.elapsedRealtime()
        cacheUpdatedAt = now
        maybeLogPlayerState(snapshot, now)
    }

    /**
     * "player state updated songId=... pos=... at=..." 心跳:默认 500ms 节流,
     * 切歌时立即输出,便于确认 collector 是否真的在更新。
     */
    private fun maybeLogPlayerState(snapshot: PlayerSnapshot, nowElapsed: Long) {
        val songChanged = snapshot.songId != lastStateLogSongId
        if (!songChanged && nowElapsed - lastStateLogAt < STATE_LOG_MIN_INTERVAL_MS) return
        lastStateLogAt = nowElapsed
        lastStateLogSongId = snapshot.songId
        Log.i(
            TAG,
            "player state updated songId=${snapshot.songId} pos=${snapshot.positionMs} " +
                "at=${System.currentTimeMillis()}",
        )
    }

    private fun <T> callOnMain(timeoutMs: Long, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<Any?>(1)
        var failure: Throwable? = null
        val posted = mainHandler.post {
            try {
                holder[0] = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!posted) throw PlayerTimeout("main thread unavailable")
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw PlayerTimeout("main thread call timed out after ${timeoutMs}ms")
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    private fun readSnapshot(player: Player): PlayerSnapshot {
        val count = player.mediaItemCount
        val limited = minOf(count, MAX_QUEUE_ITEMS)
        val queue = ArrayList<PlayerQueueItem>(limited)
        for (i in 0 until limited) {
            val item = player.getMediaItemAt(i)
            queue += PlayerQueueItem(
                index = i,
                mediaId = item.mediaId,
                songId = mediaIdToSongId(item.mediaId),
                title = item.mediaMetadata.title?.toString(),
                artist = item.mediaMetadata.artist?.toString(),
            )
        }
        val currentId = player.currentMediaItem?.mediaId
        val duration = player.duration
        return PlayerSnapshot(
            songId = currentId?.let { mediaIdToSongId(it) },
            title = player.currentMediaItem?.mediaMetadata?.title?.toString(),
            artist = player.currentMediaItem?.mediaMetadata?.artist?.toString(),
            repeatMode = player.repeatMode,
            shuffle = player.shuffleModeEnabled,
            playing = player.isPlaying,
            playWhenReady = player.playWhenReady,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration < 0L) 0L else duration,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            playbackState = playbackStateName(player.playbackState),
            queueSize = count,
            currentIndex = player.currentMediaItemIndex,
            queue = queue,
        )
    }

    private fun playbackStateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        Player.STATE_ENDED -> "ended"
        else -> "unknown"
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
