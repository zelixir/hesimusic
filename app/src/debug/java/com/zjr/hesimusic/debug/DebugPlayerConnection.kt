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
 * 设计要点(对应上版崩溃/卡顿教训):
 * - 未就绪时**绝不阻塞等待**:controllerOrNull() 只投递建连请求后立即返回 null,
 *   调用方转 503;3s 内未建连则放弃本次尝试并 2s 后重试;
 * - 已就绪时所有 Player 访问都投递到主线程,读 1.5s / 写 2.5s 超时,
 *   超时用最近缓存兜底(只读)或 503(控制),不会把 server 线程拖死;
 * - Player.Listener 持续维护快照缓存,主线程繁忙时 /state 仍能返回。
 */
internal class DebugPlayerConnection(private val context: Context) {

    companion object {
        private const val TAG = "LoopDebug"
        private const val CONNECT_TIMEOUT_MS = 3_000L
        private const val RETRY_DELAY_MS = 2_000L
        private const val MAIN_READ_TIMEOUT_MS = 1_500L
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

    private fun startConnectLocked(now: Long) {
        connecting = true
        connectStartedAt = now
        val seq = ++connectSeq
        mainHandler.post {
            try {
                val token = SessionToken(context, ComponentName(context, MusicService::class.java))
                val f = MediaController.Builder(context, token).buildAsync()
                synchronized(lock) {
                    if (seq == connectSeq) future = f
                }
                f.addListener({
                    var created: MediaController? = null
                    var failure: Throwable? = null
                    try {
                        created = f.get()
                    } catch (t: Throwable) {
                        failure = t
                    }
                    synchronized(lock) {
                        if (created != null) {
                            controller = created
                            connecting = false
                            nextRetryAt = 0L
                            future = null
                            cache = runCatching { readSnapshot(created) }.getOrNull()
                            created.addListener(listener)
                            Log.i(TAG, "player controller connected")
                        } else if (seq == connectSeq) {
                            connecting = false
                            nextRetryAt = SystemClock.elapsedRealtime() + RETRY_DELAY_MS
                            Log.w(TAG, "player controller connect failed: ${failure?.message}", failure)
                        } else {
                            Log.w(TAG, "stale player connect failed: ${failure?.message}")
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

    /** 只读快照:未建连返回 null;主线程超时用缓存兜底(标记 stale)。 */
    fun snapshotForRead(): PlayerRead? {
        val c = controllerOrNull() ?: return null
        return try {
            PlayerRead(callOnMain(MAIN_READ_TIMEOUT_MS) { readSnapshot(c) }, stale = false)
        } catch (e: PlayerTimeout) {
            Log.w(TAG, "player snapshot timed out; fallback to cache")
            cache?.let { PlayerRead(it, stale = true) }
        } catch (t: Throwable) {
            Log.w(TAG, "player snapshot failed", t)
            cache?.let { PlayerRead(it, stale = true) }
        }
    }

    /**
     * 执行一条主线程 Player 命令。未建连 → PlayerUnavailable(503);
     * 主线程超时 → PlayerUnavailable("player timeout")(仍 503,快速失败)。
     */
    fun <T> command(timeoutMs: Long = MAIN_WRITE_TIMEOUT_MS, block: (MediaController) -> T): T {
        val c = controllerOrNull() ?: throw PlayerUnavailable()
        return try {
            callOnMain(timeoutMs) {
                val result = block(c)
                cache = runCatching { readSnapshot(c) }.getOrNull()
                result
            }
        } catch (e: PlayerTimeout) {
            throw PlayerUnavailable("player timeout")
        }
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
}
