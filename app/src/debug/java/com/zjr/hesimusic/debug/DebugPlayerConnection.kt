package com.zjr.hesimusic.debug

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
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
 * 设计要点(对应上版崩溃/卡顿教训 + 冷启动抢跑):
 * - 未就绪时**绝不阻塞等待**:controllerOrNull() 只投递建连请求后立即返回 null,
 *   调用方转 503;3s 内未建连则放弃本次尝试并 2s 后重试;
 * - 冷启动 invalid:controller 已连但 3s 内仍是 idle/queue=0/pos=0(应用侧 restore 还没灌队列),
 *   控制命令快速 503;超过 3s 仍空 → 丢弃并重建 controller,单请求最多等重建 2s;
 * - 已就绪时所有 Player 访问都投递到主线程,读 1.5s / 写 2.5s 超时,
 *   超时用最近缓存兜底(只读)或 503(控制),不会把 server 线程拖死;
 * - Player.Listener 持续维护快照缓存,主线程繁忙时 /state 仍能返回。
 */
internal class DebugPlayerConnection(private val context: Context) {

    companion object {
        private const val TAG = "LoopDebug"
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val COLD_START_GRACE_MS = 3_000L
        private const val RECONNECT_WAIT_MS = 2_000L
        private const val RECONNECT_POLL_MS = 100L
        private const val MAIN_READ_TIMEOUT_MS = 1_500L
        private const val MAIN_VALIDATE_TIMEOUT_MS = 500L
        private const val MAIN_WRITE_TIMEOUT_MS = 2_500L
        private const val MAX_QUEUE_ITEMS = 200
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var controller: MediaController? = null

    @Volatile
    private var connecting = false

    @Volatile
    private var cache: PlayerSnapshot? = null

    /** controller 建立时刻(elapsedRealtime);null/0 表示当前无 controller。 */
    @Volatile
    private var controllerCreatedAt = 0L

    private var future: ListenableFuture<MediaController>? = null
    private var connectStartedAt = 0L
    private var nextRetryAt = 0L
    private var connectSeq = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            cache = runCatching { readSnapshot(player) }.getOrElse { cache }
        }
    }

    fun isConnecting(): Boolean = connecting

    /** 当前 controller 建立时刻到现在;无 controller 时 null。 */
    fun controllerAgeMs(): Long? {
        if (controller == null) return null
        val created = controllerCreatedAt
        if (created <= 0L) return null
        return (SystemClock.elapsedRealtime() - created).coerceAtLeast(0L)
    }

    /**
     * 是否仍处于“刚连上、restore 还没把队列灌进来”的状态:
     * controller 存在且快照仍是 idle/queue=0/pos=0,且建立未超过 3s。
     * 供 /state 的 connecting 字段与 /analyze 的 pause 快速跳过使用。
     */
    fun isStartingUp(): Boolean {
        if (controller == null) return connecting
        val snapshot = cache ?: return true
        if (isValidSnapshot(snapshot)) return false
        return (controllerAgeMs() ?: 0L) < COLD_START_GRACE_MS
    }

    /** 最近一次 Player.Listener 事件缓存里的当前歌 id(不阻塞主线程;供 loop bridge 判 engine)。 */
    fun cachedSongId(): Long? = cache?.songId

    /**
     * 非阻塞取 controller:未建连时触发一次后台建连并返回 null。
     * 3s 未完成则放弃该次 future(释放),2s 后允许重试。
     */
    fun controllerOrNull(): MediaController? {
        controller?.let { return it }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            controller?.let { return it }
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
        return controller
    }

    /** 触发建连(不等待)。 */
    fun ensureConnecting() {
        controllerOrNull()
    }

    /** 强制丢弃当前 controller/future 并重新建连(不等待结果)。 */
    fun forceReconnect(reason: String = "explicit") {
        var old: MediaController? = null
        var oldFuture: ListenableFuture<MediaController>? = null
        synchronized(lock) {
            old = controller
            oldFuture = future
            controller = null
            cache = null
            future = null
            connecting = false
            connectStartedAt = 0L
            nextRetryAt = 0L
            controllerCreatedAt = 0L
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

    /** 强制重建并最多等待 timeoutMs 直到新 controller 报告有效播放态。 */
    fun forceReconnectAndAwait(timeoutMs: Long = RECONNECT_WAIT_MS): Boolean {
        forceReconnect("explicit reconnect")
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = controllerOrNull()
            if (candidate != null) {
                val snapshot = readSnapshotCached(candidate)
                if (snapshot != null && isValidSnapshot(snapshot)) {
                    Log.i(TAG, "player controller reconnected and valid")
                    return true
                }
            }
            sleepQuietly(RECONNECT_POLL_MS)
        }
        return false
    }

    private fun startConnectLocked(now: Long) {
        connecting = true
        connectStartedAt = now
        val seq = ++connectSeq
        mainHandler.post {
            try {
                val token = SessionToken(context, ComponentName(context, MusicService::class.java))
                val built = MediaController.Builder(context, token).buildAsync()
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
                                cache = runCatching { readSnapshot(createdController) }.getOrNull()
                                createdController.addListener(listener)
                                accepted = true
                            }
                        }
                        if (accepted) {
                            Log.i(TAG, "player controller connected")
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
                    }
                }
            }
        }
    }

    /**
     * 只读快照:未建连 / 冷启动无效返回 null;主线程超时用缓存兜底(标记 stale)。
     * 无效超过 3s 时丢弃 controller 并触发重建(不在本调用里等待)。
     */
    fun snapshotForRead(): PlayerRead? {
        val current = controllerOrNull() ?: return null
        val read = try {
            PlayerRead(callOnMain(MAIN_READ_TIMEOUT_MS) { readSnapshot(current) }, stale = false)
        } catch (e: PlayerTimeout) {
            Log.w(TAG, "player snapshot timed out; fallback to cache")
            cache?.let { PlayerRead(it, stale = true) }
        } catch (t: Throwable) {
            Log.w(TAG, "player snapshot failed", t)
            cache?.let { PlayerRead(it, stale = true) }
        } ?: return null

        if (!isValidSnapshot(read.snapshot)) {
            val age = controllerAgeMs() ?: 0L
            if (age >= COLD_START_GRACE_MS) {
                Log.w(TAG, "snapshot invalid (age=${age}ms); rebuilding controller")
                forceReconnect("invalid snapshot")
            }
            return null
        }
        cache = read.snapshot
        return read
    }

    /**
     * 执行一条主线程 Player 命令。controller 未就绪 / 冷启动无效 → PlayerUnavailable(503);
     * 无效超过 3s 时先重建(最多 2s),仍无效则 503。
     */
    fun <T> command(timeoutMs: Long = MAIN_WRITE_TIMEOUT_MS, block: (MediaController) -> T): T {
        val ready = readyController() ?: throw PlayerUnavailable()
        return try {
            callOnMain(timeoutMs) {
                val result = block(ready)
                cache = runCatching { readSnapshot(ready) }.getOrNull()
                result
            }
        } catch (e: PlayerTimeout) {
            throw PlayerUnavailable("player timeout")
        }
    }

    /**
     * 有效性判定 + 必要时重建:
     * - 有效 → 返回 controller;
     * - 无效但 age < 3s → null(让调用方快速 503,等 restore);
     * - 无效且 age >= 3s → 丢弃重建,最多等 2s,有效才返回。
     */
    private fun readyController(): MediaController? {
        val current = controllerOrNull() ?: return null
        val snapshot = readSnapshotCached(current)
        if (snapshot != null && isValidSnapshot(snapshot)) return current

        val age = controllerAgeMs() ?: 0L
        if (age < COLD_START_GRACE_MS) {
            Log.d(
                TAG,
                "player controller restoring (age=${age}ms, queue=${snapshot?.queueSize}, state=${snapshot?.playbackState})",
            )
            return null
        }

        Log.w(
            TAG,
            "player controller invalid (age=${age}ms, queue=${snapshot?.queueSize}, " +
                "state=${snapshot?.playbackState}, pos=${snapshot?.positionMs}); rebuilding",
        )
        forceReconnect("invalid player")
        val deadline = SystemClock.elapsedRealtime() + RECONNECT_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val candidate = controllerOrNull()
            if (candidate != null) {
                val candidateSnapshot = readSnapshotCached(candidate)
                if (candidateSnapshot != null && isValidSnapshot(candidateSnapshot)) return candidate
            }
            sleepQuietly(RECONNECT_POLL_MS)
        }
        return null
    }

    /** 主线程读快照并把结果写入缓存;超时返回旧缓存。 */
    private fun readSnapshotCached(candidate: MediaController): PlayerSnapshot? {
        return try {
            val snapshot = callOnMain(MAIN_VALIDATE_TIMEOUT_MS) { readSnapshot(candidate) }
            cache = snapshot
            snapshot
        } catch (t: Throwable) {
            Log.d(TAG, "player validate read failed: ${t.message}")
            cache
        }
    }

    /**
     * 冷启动无效态:queue=0 && state=idle && position=0。
     * 任一非默认即视为有效(应用侧 restore 已灌入队列/开始播放/seek)。
     */
    private fun isValidSnapshot(snapshot: PlayerSnapshot): Boolean =
        !(snapshot.queueSize == 0 && snapshot.playbackState == "idle" && snapshot.positionMs == 0L)

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
