package com.zjr.hesimusic.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.source.LoopSourcePauseGate

/**
 * 给 MediaSession / UI / 通知用的**位置口径层**（ForwardingPlayer）。
 *
 * 只重写位置/时长/seek/next/prev 与 play/pause，其余（setMediaItem/setRepeatMode/shuffle/queue…）
 * 一律透传 —— 播放器状态机保持权威，本层不做任何媒体结构操作。
 *
 * 00027：窗口/投影的唯一真值是 [LoopClaim]（来自 [LoopClaimStore]）。
 * - `claimActive` 才折返、才按 SOURCE 时间轴投影；
 * - 源未生效（五种回退原因）→ 位置/缓冲/seek 全部透传媒体时间；
 * - 时长即使回退也报整曲（SOURCE 原生 duration 是 TIME_UNSET）。
 *
 * #106：循环中 next/prev 必须真的切歌。ExoPlayer 对 repeat=ONE 的导航本身会降级成 OFF，
 * 多曲队列能换项；真正的问题是循环武装时 next/prev 不会退场（单曲队列会一直停在旧 A/B）。
 * 所以本层在「意图/claim 命中当前歌」时先同步彻底退出旧循环（回调服务层），
 * 再把换项交回原生 seekToNext/Previous —— 见 [LoopNavigationPolicy] 与 [navigateOutsideLoop]。
 *
 * #111：0.1s x 20 连发 seek 不能把播放器留在 buffering。同项连续 seek 走
 * [RapidSeekCoalescer]：首个立刻应用，静默期内的请求只保留最后一次（中间允许丢弃），
 * 静默期结束把**最后一次**落地；每次落地后恢复播放意图（playWhenReady=true 时）。
 *
 * #112：预览与主播放共用 [LoopSourcePauseGate]（进程级布尔）。本类不再在主播放暂停时
 * 立即关门，而是延迟复核「另一路是否还在出声」；主播放恢复时立即开门。主播放侧的
 * 周期性守卫见 MusicService（预览 pause 不能把主播放静音）。
 *
 * 契约：docs/loop-v3-service-design.md §2.2。
 */
class LoopSessionPlayer(
    player: Player,
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
    /** 显式无缝意图；null = 纯 JVM 测试/未接线（next/prev 保持原生语义）。 */
    private val loopIntent: LoopIntent? = null,
    /** 循环中切歌前的强制退场（唯一写点在 LoopPlaybackCoordinator）。 */
    private val onExitLoopForNavigation: (() -> Unit)? = null,
    /** 另一路（预览）是否可能正在出声；用于决定主播放暂停后要不要关共享闸门。 */
    private val otherSourceAudible: () -> Boolean = { false },
) : ForwardingPlayer(player) {

    /** 一次读取内共享的 claim 快照；position/buffered/duration/seek 基于同一份。 */
    private class Snapshot(
        val claim: LoopClaim,
        val isReplaying: Boolean,
    ) {
        val window: LoopWindow? get() = claim.window
        val sourceUnavailable: Boolean get() = !claim.sourceActive
    }

    private val handler = Handler(Looper.getMainLooper())
    private val seekCoalescer = RapidSeekCoalescer(RapidSeekCoalescer.DEFAULT_QUIET_MS)

    /** 挂起的 seek 落地（静默期结束触发）。 */
    private val seekFlushRunnable = Runnable { flushPendingSeek() }

    /** 主播放暂停后延迟复核共享闸门（给"另一路还在出声"留出观察窗口）。 */
    private val gatePauseRunnable = Runnable {
        val snapshot = snapshot()
        if (SourceAudioArbiter.shouldPauseGateAfterMainPause(
                playWhenReady = playWhenReady,
                otherSourceAudible = otherSourceAudible(),
            ) && isSourceItem(snapshot)
        ) {
            Log.d(TAG, "source gate pause (main paused, no other source audible)")
            LoopSourcePauseGate.pause()
        }
    }

    override fun play() {
        LoopSourcePauseGate.resume()
        super.play()
    }

    override fun pause() {
        val sourceItem = isSourceItem(snapshot())
        super.pause()
        if (!sourceItem) return
        // #112：不立即关门 —— 预览可能正在出声（预览 pause 也会写同一个闸门）。
        // 400ms/1500ms 两次复核，仍然只有主播放暂停且没有别的音频活动才关。
        handler.removeCallbacks(gatePauseRunnable)
        handler.postDelayed(gatePauseRunnable, GATE_PAUSE_RECHECK_MS)
        handler.postDelayed(gatePauseRunnable, GATE_PAUSE_RECHECK_LATE_MS)
        handler.postDelayed(gatePauseRunnable, GATE_PAUSE_RECHECK_ULTIMATE_MS)
    }

    override fun getCurrentPosition(): Long {
        val snapshot = snapshot()
        val claim = snapshot.claim
        val window = claim.window
        if (!claim.claimActive || window == null) return super.getCurrentPosition()
        if (isSourceItem(snapshot)) {
            // T → 媒体时间：对外读到的是 A + floorMod(T,L)，不是带 offset 的数据源时间轴 T。
            return LoopSessionProjection.sourceDisplayMs(super.getCurrentPosition(), window)
        }
        val rawUs = currentRawUs(snapshot.isReplaying)
        return claim.displayPositionUs(rawUs) / LoopSessionProjection.US_PER_MS
    }

    override fun getBufferedPosition(): Long {
        val snapshot = snapshot()
        val claim = snapshot.claim
        val window = claim.window
        if (!claim.claimActive || window == null) return super.getBufferedPosition()
        if (isSourceItem(snapshot)) {
            val currentDisplayUs = LoopSessionProjection.sourceDisplayUs(
                super.getCurrentPosition(),
                window,
            )
            val bufferedUs = LoopSessionProjection.sourceDisplayUs(
                super.getBufferedPosition(),
                window,
            )
            val atLeastCurrent = maxOf(bufferedUs, currentDisplayUs)
            val durationUs = projectedDurationUs(window)
            val clamped = if (durationUs > 0L) {
                atLeastCurrent.coerceAtMost(durationUs)
            } else {
                atLeastCurrent
            }
            return clamped / LoopSessionProjection.US_PER_MS
        }
        val durationUs = projectedDurationUs(window)
        val currentDisplayUs = claim.displayPositionUs(currentRawUs(snapshot.isReplaying))
        val bufferedUs = LoopSessionProjection.bufferedUs(
            rawBufferedUs = super.getBufferedPosition() * LoopSessionProjection.US_PER_MS,
            window = window,
            currentDisplayUs = currentDisplayUs,
            durationUs = durationUs,
        )
        return bufferedUs / LoopSessionProjection.US_PER_MS
    }

    override fun getDuration(): Long {
        val snapshot = snapshot()
        // 00027/P0-2：回退时也要报整曲时长。SOURCE 时间轴的原生 duration 恒为 TIME_UNSET，
        // 若按 sourceUnavailable 提前 return，通知/锁屏/车机会拿到 -1。
        return LoopSessionProjection.sessionDurationMs(
            sourceUnavailable = snapshot.sourceUnavailable,
            window = snapshot.window,
            fullDurationUs = audio.fullDurationUs,
            nativeDurationMs = super.getDuration(),
        )
    }

    override fun seekTo(positionMs: Long) {
        // 与 Player 语义一致：positionMs 作用在当前项上；当前项无效时直接透传
        val index = currentMediaItemIndex
        if (index < 0) {
            super.seekTo(positionMs)
            return
        }
        seekToInternal(index, positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        seekToInternal(mediaItemIndex, positionMs)
    }

    // ------------------------------------------------------------------ #106 next/prev

    /**
     * #106：循环中 next/prev 必须真的切歌，并且旧循环完全退出。
     *
     * 关键事实（Robolectric 契约测试 LoopNavigationContractTest）：
     * - ExoPlayer 的 seekToNext/Previous 对 repeat=ONE 走 **导航口径**（ONE 降级为 OFF），
     *   所以**队列里有下一项时**原生换项本来就能换；
     * - 真正的"原地不动"来自「循环武装 + 当前队列只有这一首」：那时没有下一项，
     *   原生语义退化成重播当前项，而且**循环意图不会被清掉**。
     *
     * 因此这里不再自己算 index（避免重复一套导航语义），只做一件原生不会做的事：
     * 在 next/prev 之前**同步、彻底退出旧循环**（意图 / repeat+shuffle / 窗口 / SOURCE item），
     * 然后把换项/首尾绕回完全交回原生命令。这样：
     * - 多曲队列：换到下一首，并保证旧循环不残留（不依赖异步 transition 是否及时）；
     * - 单曲队列：原生绕回当前项重播，但已经是普通播放（不留在旧 A/B 循环里）。
     */
    override fun seekToNext() {
        navigateOutsideLoop { super.seekToNext() }
    }

    // MediaSession 的 COMMAND_SEEK_TO_NEXT/PREVIOUS 走的是 seekToNextMediaItem/
    // seekToPreviousMediaItem（不是 seekToNext）；两个入口都必须接管，否则 /player/next
    // 与通知栏"下一首"会绕过退场逻辑（真机 1271 实测：只覆盖 seekToNext 时无日志）。
    override fun seekToNextMediaItem() {
        navigateOutsideLoop { super.seekToNextMediaItem() }
    }

    override fun seekToPrevious() {
        navigateOutsideLoop { super.seekToPrevious() }
    }

    override fun seekToPreviousMediaItem() {
        navigateOutsideLoop { super.seekToPreviousMediaItem() }
    }

    // 单曲队列/最后一项时，真实 UI 的 skipToNext/skipToPrevious 与 debug /player/next 的
    // 回退都走 seekToDefaultPosition（见 MusicViewModel.skipToNext）。这里同样先退场，
    // 否则"下一首"在单曲队列里只会原地重播并一直留在旧循环。
    override fun seekToDefaultPosition() {
        navigateOutsideLoop { super.seekToDefaultPosition() }
    }

    override fun seekToDefaultPosition(mediaItemIndex: Int) {
        navigateOutsideLoop { super.seekToDefaultPosition(mediaItemIndex) }
    }

    /**
     * 循环命中时先退场再执行 [nativeCommand]；未命中则直接执行（完全保持原生语义）。
     */
    private fun navigateOutsideLoop(nativeCommand: () -> Unit) {
        val armed = loopIntent?.armed?.value
        val songId = currentMediaItem?.mediaId?.let { mediaIdToSongId(it) }
        val claimActive = snapshot().claim.claimActive
        if (!LoopNavigationPolicy.shouldExitBeforeNavigation(
                repeatMode = repeatMode,
                currentSongId = songId,
                armedSongId = armed?.songId,
                claimActive = claimActive,
            )
        ) {
            nativeCommand()
            return
        }
        val wasPlaying = playWhenReady
        val indexBefore = currentMediaItemIndex
        Log.i(
            TAG,
            "loop navigation exit-before-step songId=" + songId + " index=" + indexBefore +
                " count=" + mediaItemCount + " repeat=" + repeatMode +
                " wasPlaying=" + wasPlaying + " armed=" + (armed?.songId ?: -1L) +
                " claim=" + claimActive,
        )
        // 唯一写点：清意图、恢复 repeat/shuffle、把当前 SOURCE item 还原成普通项、清窗口/claim。
        onExitLoopForNavigation?.invoke()
        nativeCommand()
        if (wasPlaying) {
            if (playbackState == Player.STATE_IDLE) super.prepare()
            play()
        }
        Log.i(
            TAG,
            "loop navigation after-step index=" + currentMediaItemIndex +
                " songId=" + currentMediaItem?.mediaId?.let { mediaIdToSongId(it) } +
                " repeat=" + repeatMode + " state=" + playbackState +
                " pwr=" + playWhenReady,
        )
    }

    // ------------------------------------------------------------------ #111 seek 合并

    /**
     * seek 投影 + 高频合并：
     * - 换项（mediaItemIndex != 当前项）是导航语义，立即透传投影，不被合并；
     * - 同项连续 seek 交给 [RapidSeekCoalescer]：首个立即应用，后续只保留最后一次，
     *   静默期结束由 [flushPendingSeek] 把最后一次落地（中间请求允许被丢弃）。
     */
    private fun seekToInternal(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex < 0) {
            super.seekTo(positionMs)
            return
        }
        if (mediaItemIndex != currentMediaItemIndex) {
            cancelPendingSeek()
            applySeekNow(mediaItemIndex, positionMs)
            return
        }
        val request = SeekRequest(mediaItemIndex, positionMs)
        if (seekCoalescer.request(request, SystemClock.uptimeMillis())) {
            cancelPendingSeekRunnableOnly()
            applySeekNow(mediaItemIndex, positionMs)
        } else {
            scheduleSeekFlush()
        }
    }

    private fun scheduleSeekFlush() {
        handler.removeCallbacks(seekFlushRunnable)
        handler.postDelayed(seekFlushRunnable, RapidSeekCoalescer.DEFAULT_QUIET_MS)
    }

    private fun flushPendingSeek() {
        val request = seekCoalescer.flush(SystemClock.uptimeMillis()) ?: return
        if (request.index != currentMediaItemIndex) {
            // 期间切歌了：旧项的目标作废（否则会把新歌 seek 到旧位置）。
            Log.d(TAG, "flush seek dropped index=" + request.index + " current=" + currentMediaItemIndex)
            return
        }
        Log.d(TAG, "flush seek positionMs=" + request.positionMs + " (latest of a burst)")
        applySeekNow(request.index, request.positionMs)
    }

    private fun cancelPendingSeek() {
        handler.removeCallbacks(seekFlushRunnable)
        seekCoalescer.cancel()
    }

    private fun cancelPendingSeekRunnableOnly() {
        handler.removeCallbacks(seekFlushRunnable)
    }

    /**
     * 落地一次 seek（投影口径不变），并在 playWhenReady=true 时恢复播放意图：
     * 合并后的最后一次 seek 若把播放器打到 IDLE/BUFFERING，不能让"播放"停在半路。
     */
    private fun applySeekNow(mediaItemIndex: Int, positionMs: Long) {
        val snapshot = snapshot()
        val claim = snapshot.claim
        val window = claim.window
        if (!claim.claimActive || window == null || mediaItemIndex != currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
        } else if (isSourceItem(snapshot)) {
            val targetT = LoopSessionProjection.sourceTimelineUsForDisplay(
                displayUs = positionMs * LoopSessionProjection.US_PER_MS,
                currentTimelineUs = super.getCurrentPosition() * LoopSessionProjection.US_PER_MS,
                window = window,
            )
            super.seekTo(mediaItemIndex, targetT / LoopSessionProjection.US_PER_MS)
        } else {
            val rawUs = LoopSessionProjection.seekUs(
                targetUs = positionMs * LoopSessionProjection.US_PER_MS,
                window = window,
                currentRawUs = currentRawUs(snapshot.isReplaying),
                isRingComplete = audio.isRingComplete,
            )
            super.seekTo(mediaItemIndex, rawUs / LoopSessionProjection.US_PER_MS)
        }
        ensurePlaybackAfterSeek()
    }

    private fun ensurePlaybackAfterSeek() {
        if (!playWhenReady) return
        if (playbackState == Player.STATE_IDLE) {
            Log.i(TAG, "seek settle: player idle -> prepare")
            super.prepare()
        }
        // play() 幂等；同时重新打开共享闸门（预览 pause 可能在我们 seek 期间把它关了）。
        play()
    }

    /**
     * 选 claim 并拍快照。
     *
     * 生产路径由 `MusicService` 安装 provider（[LoopClaimStore]）；provider 未安装
     * （纯 JVM 测试/初始化前）时直接用同一个 [LoopClaimResolver] 计算，绝不另写一套。
     * isReplaying 与 claim 都只读一次，窗口选择/raw 取值/投影开关基于同一份快照。
     */
    private fun snapshot(): Snapshot {
        val isReplaying = audio.isReplaying
        val songId = currentMediaItem?.mediaId?.let { mediaIdToSongId(it) }
        val claim = if (LoopClaimStore.isInstalled()) {
            LoopClaimStore.claimFor(songId)
        } else {
            LoopClaimResolver.fromProcess(songId, runtime, audio)
        }
        return Snapshot(claim, isReplaying)
    }

    /** 引擎原始位置（µs）：重放期取 sink 推进的位置，否则取播放器自身位置。 */
    private fun currentRawUs(isReplaying: Boolean): Long = LoopSessionProjection.rawUs(
        isReplaying = isReplaying,
        replayPositionUs = audio.positionUs,
        playerPositionMs = super.getCurrentPosition(),
    )

    /**
     * 当前项是否按循环数据源口径投影：由**同一个**路线快照 + claim 真值决定。
     */
    private fun isSourceItem(snapshot: Snapshot): Boolean =
        MainPlayerRouting.sessionProjectsSource(
            tag = currentMediaItem?.localConfiguration?.tag,
            sourceUnavailable = snapshot.sourceUnavailable,
        )

    /** 对外时长（µs）；0 = 不投影（整曲时长未知）。 */
    private fun projectedDurationUs(window: LoopWindow): Long =
        LoopSessionProjection.durationUs(window, audio.fullDurationUs)

    /** 服务销毁时清掉挂起的 seek/闸门任务（幂等）。 */
    fun dispose() {
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        private const val TAG = "LoopSessionPlayer"

        /** 暂停后复核共享闸门的三个观察点（给另一路"还在出声"留出观察窗口）。 */
        private const val GATE_PAUSE_RECHECK_MS = 400L
        private const val GATE_PAUSE_RECHECK_LATE_MS = 1500L
        private const val GATE_PAUSE_RECHECK_ULTIMATE_MS = 5_000L
    }
}

/** 一次 seek 请求（项 + 展示口径位置 ms）。 */
internal data class SeekRequest(
    val index: Int,
    val positionMs: Long,
)

/**
 * #111 高频 seek 合并器（纯逻辑，无 Android 依赖，单测见 RapidSeekCoalescerTest）。
 *
 * 语义：
 * - 空闲后的第一个请求**立即应用**（用户单次拖动/点按不增加延迟）；
 * - 应用后 [quietMs] 内的后续请求全部合并成"最新目标"，由调用方在静默期结束后 flush；
 * - flush 返回的永远是**最后一次请求**，因此 20 连发也不会丢落点；
 * - 请求序列相同（flush 目标 == 已应用目标）时返回 null，避免重复 seek。
 */
internal class RapidSeekCoalescer(private val quietMs: Long = DEFAULT_QUIET_MS) {

    private var pending: SeekRequest? = null
    private var lastApplied: SeekRequest? = null
    private var lastAppliedAtMs = Long.MIN_VALUE

    /** @return true = 立即应用；false = 已合并，调用方需安排 flush。 */
    fun request(request: SeekRequest, nowMs: Long): Boolean {
        val recentApply = lastApplied != null && nowMs - lastAppliedAtMs <= quietMs
        if (pending == null && !recentApply) {
            lastApplied = request
            lastAppliedAtMs = nowMs
            return true
        }
        pending = request
        return false
    }

    /** @return 需要应用的最新目标；没有 pending / 与已应用相同 → null。 */
    fun flush(nowMs: Long): SeekRequest? {
        val request = pending ?: return null
        pending = null
        if (request == lastApplied) return null
        lastApplied = request
        lastAppliedAtMs = nowMs
        return request
    }

    fun cancel() {
        pending = null
    }

    /** 诊断/测试：当前挂起目标。 */
    fun pendingRequest(): SeekRequest? = pending

    companion object {
        /** 连发窗口：0.1s 间隔的连续 seek 会全部落到同一个窗口里。 */
        const val DEFAULT_QUIET_MS = 120L
    }
}

/**
 * #106 循环中切歌的前置退场策略（纯函数）。
 *
 * 为什么不是"自己算目标 index"：ExoPlayer 的 seekToNext/Previous 已经把 repeat=ONE
 * 降级成 OFF 来导航（见 LoopNavigationContractTest），多曲队列原生就能换项。
 * 唯一需要本层接管的是**退场**：循环武装时 next/prev 必须先把旧循环（意图/repeat/
 * 窗口/SOURCE item）清干净，否则单曲队列会一直停在旧 A/B 循环里。
 */
internal object LoopNavigationPolicy {

    /**
     * next/prev 之前是否要先强制退出循环。
     *
     * 只有 repeat 恰好是 ONE **且** 当前歌命中循环（武装意图或 claim 生效）才退场；
     * 用户单纯把播放模式设成"单曲循环"（没有无缝意图）时保持原生语义，不改变既有行为。
     */
    fun shouldExitBeforeNavigation(
        repeatMode: Int,
        currentSongId: Long?,
        armedSongId: Long?,
        claimActive: Boolean,
    ): Boolean {
        if (repeatMode != Player.REPEAT_MODE_ONE) return false
        if (currentSongId == null) return false
        return armedSongId == currentSongId || claimActive
    }
}

/**
 * #112 预览/主播放共享闸门的仲裁（纯函数，单测见 SourceAudioArbiterTest）。
 *
 * [LoopSourcePauseGate] 是进程级布尔：任何一路 pause 都会停住两路的 PCM 读取。
 * 因此：
 * - 主播放暂停时不能立即关门 —— 预览可能正在出声；延迟复核后仍无其它音频活动才关；
 * - 主播放只要还在出声，就必须把闸门顶开（预览 pause 不能把主播放永久静音）。
 */
internal object SourceAudioArbiter {

    /** 主播放现在是否应该出声（SOURCE item + playWhenReady + 非 IDLE）。 */
    fun mainWantsToSound(playWhenReady: Boolean, playbackState: Int, isSourceItem: Boolean): Boolean =
        isSourceItem && playWhenReady && playbackState != Player.STATE_IDLE

    /** 主播放要出声但共享闸门被另一路关掉了 → 必须重新打开。 */
    fun shouldResumeGate(mainWantsToSound: Boolean, gatePaused: Boolean): Boolean =
        mainWantsToSound && gatePaused

    /** 主播放暂停后的复核：仍然暂停且没有其它音频在响，才允许关闭闸门。 */
    fun shouldPauseGateAfterMainPause(playWhenReady: Boolean, otherSourceAudible: Boolean): Boolean =
        !playWhenReady && !otherSourceAudible
}
