package com.zjr.hesimusic.service

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import com.zjr.hesimusic.loop.analysis.PcmDecoder
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.source.LoopAwareMediaSourceFactory
import com.zjr.hesimusic.loop.audio.source.LoopSourceItemTag
import com.zjr.hesimusic.loop.audio.source.LoopSourceMediaItem
import com.zjr.hesimusic.loop.audio.source.LoopSourcePrewarm
import com.zjr.hesimusic.loop.audio.source.LoopingPcmTimeline
import com.zjr.hesimusic.loop.audio.source.PcmFileFormatProbe
import com.zjr.hesimusic.loop.audio.source.PcmBlock
import com.zjr.hesimusic.loop.audio.source.PcmRangeBlockReader
import com.zjr.hesimusic.loop.audio.source.PcmResidentRangeProvider
import com.zjr.hesimusic.loop.audio.source.PreludeBlockPrewarm
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 00029：入口**预热**（菜单打开 / 结果页 Ready 时，不播放）。
 *
 * 真机首声 403~421ms 里最大一块是前奏首块解码 174~220ms，而用户点确认前必然有
 * 300ms~数秒的看菜单/看结果时间。这里用这段时间：
 * 1. 后台建环带源（9~20ms）；
 * 2. 源构造时 `PcmRangeBlockReader(prefetchOnInit=true)` 会解前奏首块（174~220ms）；
 * 3. **不接播放器、不 play、不写帧**，只在 [LoopSourcePrewarm] 槽里等入口取走；
 * 4. 解码线程降到 BACKGROUND，且用独立单线程执行器，不抢正在播放的主源。
 *
 * 取消：菜单关闭/离开结果页调用 [cancel]；另有 TTL 兜底，绝不长期占着环/解码器。
 * 命中条件：入口 item 的 mediaId + tag(A/B/路径/整曲时长) 与预热时完全一致；
 * 否则工厂正常新建（没有预热路径也能工作，只是慢）。
 */
@Singleton
class LoopEntryPrewarmer @Inject constructor(
    private val pcmDecoder: PcmDecoder,
    private val audio: LoopAudioController,
) {

    data class Request(
        val songId: Long,
        val loopStartMs: Long,
        val loopEndMs: Long,
        val fullDurationUs: Long,
        val filePath: String,
        /** 入口会交给播放器的 item；mediaId 必须与入口时的当前项一致才可能命中。 */
        val mediaItem: MediaItem,
    )

    /** 00029：结果页轻量预热只描述"解哪首歌的前奏首块"，不涉及 MediaItem/源。 */
    data class PreludeRequest(
        val songId: Long,
        val loopStartMs: Long,
        val loopEndMs: Long,
        val fullDurationUs: Long,
        val filePath: String,
    )

    /** 轻量预热结果：skipReason 非空 = 预期降级（例如 A=0 没有前奏段），不是失败。 */
    private data class PreludeOutcome(
        val key: PreludeBlockPrewarm.Key? = null,
        val blocks: List<PcmBlock>? = null,
        val skipReason: String? = null,
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LoopEntryPrewarm").apply { isDaemon = true }
    }

    /** 当前预热目标（key + 请求）；null = 无。 */
    private var slot: Pair<LoopSourcePrewarm.Key, Request>? = null

    /** 轻量预热的槽（只有前奏首块，没有源/环/常驻会话）。 */
    private var lightSlot: PreludeBlockPrewarm.Key? = null

    /** 每次 start/cancel 自增；后台构建完成时 gen 不一致就丢弃并释放。 */
    private var generation = 0L

    fun prewarm(request: Request) {
        if (request.songId <= 0L || request.filePath.isBlank()) return
        val tag = LoopSourceItemTag(
            songId = request.songId,
            loopStartUs = request.loopStartMs * 1_000L,
            loopEndUs = request.loopEndMs * 1_000L,
            filePath = request.filePath,
            fullDurationUs = request.fullDurationUs,
        )
        val tagged = LoopSourceMediaItem.tagged(request.mediaItem, tag)
        val key = LoopSourcePrewarm.keyOf(tagged, tag)
        var displaced: MediaSource? = null
        val gen: Long
        synchronized(lock) {
            if (slot?.first == key && LoopSourcePrewarm.isSlotFor(key)) {
                Log.d(TAG, "entry prewarm skip (already ready) songId=" + request.songId)
                return
            }
            generation += 1L
            gen = generation
            displaced = detachLocked()
            // 完整预热和轻量预热互斥：同一时刻只留一种，避免结果页的块缓存
            // 被菜单/另一页的源重复消费。
            PreludeBlockPrewarm.clear()
            lightSlot = null
            slot = key to request
        }
        release(displaced)
        val startedMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "entry prewarm start songId=" + request.songId +
                " aMs=" + request.loopStartMs + " bMs=" + request.loopEndMs +
                " mediaId=" + request.mediaItem.mediaId,
        )
        executor.execute {
            // 独立线程 + BACKGROUND：只在前台播放的空档用 CPU，不抢主源。
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val source = try {
                val factory = LoopAwareMediaSourceFactory.defaultSourceFactory(
                    pcmDecoder = pcmDecoder,
                    onSeamOutputFrames = { frames -> audio.tapSeam(frames) },
                    backgroundDecode = true,
                )
                factory(tagged, tag)
            } catch (t: Throwable) {
                Log.w(TAG, "entry prewarm build failed songId=" + request.songId, t)
                null
            }
            synchronized(lock) {
                if (gen != generation || slot?.first != key) {
                    release(source)
                    Log.i(TAG, "entry prewarm cancelled (stale) songId=" + request.songId)
                    return@execute
                }
            }
            if (source == null) {
                synchronized(lock) {
                    if (generation == gen && slot?.first == key) slot = null
                }
                return@execute
            }
            val displacedSource = LoopSourcePrewarm.put(key, source)
            release(displacedSource)
            Log.i(
                TAG,
                "entry prewarm ready songId=" + request.songId +
                    " costMs=" + (SystemClock.elapsedRealtime() - startedMs),
            )
            scheduleExpiry(gen, key, request.songId)
        }
    }

    /**
     * 00029：结果页轻量预热——只解前奏首块，不建环带、不常驻。
     *
     * 解块用的 resident 会话在块 ready 后立刻 release；缓存里只留 1 个 PcmBlock
     * （44.1kHz 立体声约 176KB）。这样预览 seek 不会与一个 5.7MB 环/常驻解码器并发。
     * 用户确认时工厂正常建完整源（9~20ms），PcmRangeBlockReader 用 PreludeBlockPrewarm
     * 种的块直接起播。
     */
    fun prewarmPreludeOnly(request: PreludeRequest) {
        if (request.songId <= 0L || request.filePath.isBlank() ||
            request.filePath.startsWith("content:")
        ) {
            return
        }
        var displaced: MediaSource? = null
        val gen: Long
        synchronized(lock) {
            val current = lightSlot
            if (current != null &&
                current.songId == request.songId &&
                current.loopStartUs == request.loopStartMs * 1_000L &&
                current.loopEndUs == request.loopEndMs * 1_000L &&
                PreludeBlockPrewarm.has(current)
            ) {
                Log.d(TAG, "entry prewarm light skip (already ready) songId=" + request.songId)
                return
            }
            generation += 1L
            gen = generation
            displaced = detachLocked()
            PreludeBlockPrewarm.clear()
            lightSlot = null
        }
        release(displaced)
        val startedMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "entry prewarm light start songId=" + request.songId +
                " aMs=" + request.loopStartMs + " bMs=" + request.loopEndMs,
        )
        executor.execute {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val result = try {
                buildPrelude(request)
            } catch (t: Throwable) {
                Log.w(TAG, "entry prewarm light build failed songId=" + request.songId, t)
                PreludeOutcome()
            }
            synchronized(lock) {
                if (gen != generation) {
                    Log.i(TAG, "entry prewarm light cancelled (stale) songId=" + request.songId)
                    return@execute
                }
            }
            if (result.skipReason != null) {
                Log.i(
                    TAG,
                    "entry prewarm light skipped reason=" + result.skipReason +
                        " songId=" + request.songId,
                )
                return@execute
            }
            val key = result.key
            val blocks = result.blocks
            if (key == null || blocks == null) {
                Log.w(TAG, "entry prewarm light failed songId=" + request.songId)
                return@execute
            }
            PreludeBlockPrewarm.put(key, blocks)
            synchronized(lock) {
                if (gen != generation) {
                    PreludeBlockPrewarm.remove(key)
                    Log.i(TAG, "entry prewarm light cancelled (stale) songId=" + request.songId)
                    return@execute
                }
                lightSlot = key
            }
            Log.i(
                TAG,
                "entry prewarm light ready songId=" + request.songId +
                    " blocks=" + blocks.size +
                    " bytes=" + blocks.sumOf { it.bytes } +
                    " costMs=" + (SystemClock.elapsedRealtime() - startedMs),
            )
            scheduleLightExpiry(gen, key, request.songId)
        }
    }
    /** 用户关掉菜单/离开结果页：释放当前预热（完整源或轻量块，不命中该歌时 no-op）。 */
    fun cancel(songId: Long) {
        var removed: MediaSource? = null
        var removedLight = false
        synchronized(lock) {
            val current = slot
            if (current != null && current.first.songId == songId) {
                generation += 1L
                slot = null
                removed = LoopSourcePrewarm.remove(current.first)
            }
            val light = lightSlot
            if (light != null && light.songId == songId) {
                generation += 1L
                lightSlot = null
                removedLight = PreludeBlockPrewarm.remove(light) != null
            }
        }
        release(removed)
        if (removed != null || removedLight) {
            Log.i(
                TAG,
                "entry prewarm cancelled songId=" + songId +
                    " released=" + (removed != null) +
                    " light=" + removedLight,
            )
        }
    }

    /** 进程级清空（服务销毁/测试）。 */
    fun cancelAll() {
        var removed: MediaSource? = null
        var hadLight = false
        synchronized(lock) {
            generation += 1L
            removed = detachLocked()
            slot = null
            hadLight = lightSlot != null
            lightSlot = null
            PreludeBlockPrewarm.clear()
        }
        release(removed)
        if (removed != null || hadLight) Log.i(TAG, "entry prewarm cancelled (all)")
    }

    /** 必须在 lock 内调用：清 slot 并返回已建好的源（可能为 null，表示仍在后台构建）。 */
    private fun detachLocked(): MediaSource? {
        val key = slot?.first ?: return null
        slot = null
        return LoopSourcePrewarm.remove(key)
    }

    private fun scheduleExpiry(gen: Long, key: LoopSourcePrewarm.Key, songId: Long) {
        mainHandler.postDelayed({
            var expired: MediaSource? = null
            synchronized(lock) {
                if (generation != gen || !LoopSourcePrewarm.isSlotFor(key)) return@postDelayed
                slot = null
                expired = LoopSourcePrewarm.remove(key)
            }
            release(expired)
            Log.i(TAG, "entry prewarm expired songId=" + songId)
        }, PREWARM_TTL_MS)
    }

    /**
     * 只解前奏第一块：resident 会话只活到块 ready，随后 reader.release() 关闭；
     * 全程不建 LoopRing / 不启动 RingBandRenderer。
     */
    private fun buildPrelude(request: PreludeRequest): PreludeOutcome {
        val format = PcmFileFormatProbe.probe(request.filePath)
        val timeline = LoopingPcmTimeline(
            songId = request.songId,
            loopStartUs = request.loopStartMs * 1_000L,
            loopEndUs = request.loopEndMs * 1_000L,
            format = format,
            entryPositionUs = request.loopStartMs * 1_000L,
            fullDurationUs = request.fullDurationUs,
        )
        if (!timeline.wholeTimeline) return PreludeOutcome(skipReason = "no-whole-timeline")
        if (timeline.aFrame <= 0L) return PreludeOutcome(skipReason = "no-prelude")
        val provider = PcmResidentRangeProvider(
            path = request.filePath,
            expectedSampleRate = timeline.sampleRate,
            expectedChannels = timeline.channels,
            logger = { message -> Log.d(TAG, "prelude " + message) },
        )
        val reader = PcmRangeBlockReader(
            provider = provider,
            startFrame = 0L,
            endFrameExclusive = timeline.aFrame,
            sampleRate = timeline.sampleRate,
            channels = timeline.channels,
            // 只解首块（1s，44.1kHz 立体声约 176KB）；不预取下块。
            prefetchBlocks = 0,
            prefetchOnInit = true,
            decodePrerollUs = 0L,
            logger = { message -> Log.d(TAG, "prelude " + message) },
            threadPriority = Process.THREAD_PRIORITY_BACKGROUND,
            initialDecodePriority = Process.THREAD_PRIORITY_BACKGROUND,
        )
        return try {
            val block = reader.awaitReadyBlock(0, LIGHT_TIMEOUT_MS)
            if (block == null) {
                PreludeOutcome()
            } else {
                PreludeOutcome(
                    key = PreludeBlockPrewarm.keyOf(timeline),
                    blocks = listOf(block),
                )
            }
        } finally {
            reader.release()
        }
    }

    private fun scheduleLightExpiry(gen: Long, key: PreludeBlockPrewarm.Key, songId: Long) {
        mainHandler.postDelayed({
            var hadBlocks = false
            synchronized(lock) {
                if (generation != gen || lightSlot != key) return@postDelayed
                lightSlot = null
                hadBlocks = PreludeBlockPrewarm.remove(key) != null
            }
            if (hadBlocks) Log.i(TAG, "entry prewarm light expired songId=" + songId)
        }, PREWARM_TTL_MS)
    }
    private fun release(source: MediaSource?) {
        if (source == null) return
        runCatching {
            source.releaseSource(object : MediaSource.MediaSourceCaller {
                override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) = Unit
            })
        }.onFailure { Log.w(TAG, "entry prewarm release failed", it) }
    }

    private companion object {
        private const val TAG = "LoopEntryPrewarm"

        /** 预热源最长保留时间；菜单开着不点/页面停留过久就自动释放。 */
        private const val PREWARM_TTL_MS = 20_000L

        /** 轻量预热最多等首块多久；超时就放弃，确认时走正常解码。 */
        private const val LIGHT_TIMEOUT_MS = 1_500L
    }
}
