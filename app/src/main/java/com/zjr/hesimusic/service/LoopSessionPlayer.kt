package com.zjr.hesimusic.service

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.source.LoopSourcePauseGate

/**
 * 给 MediaSession / UI / 通知用的**位置口径层**（ForwardingPlayer）。
 *
 * 只重写位置/时长/seek 五个方法，其余（play/pause/setMediaItem/setRepeatMode/shuffle/queue…）
 * 一律透传 —— 播放器状态机保持权威，本层不做任何媒体结构操作。
 *
 * 00027：窗口/投影的唯一真值是 [LoopClaim]（来自 [LoopClaimStore]）。
 * - `claimActive` 才折返、才按 SOURCE 时间轴投影；
 * - 源未生效（五种回退原因）→ 位置/缓冲/seek 全部透传媒体时间；
 * - 时长即使回退也报整曲（SOURCE 原生 duration 是 TIME_UNSET）。
 *
 * 契约：docs/loop-v3-service-design.md §2.2。
 */
class LoopSessionPlayer(
    player: Player,
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
) : ForwardingPlayer(player) {

    /** 一次读取内共享的 claim 快照；position/buffered/duration/seek 基于同一份。 */
    private class Snapshot(
        val claim: LoopClaim,
        val isReplaying: Boolean,
    ) {
        val window: LoopWindow? get() = claim.window
        val sourceUnavailable: Boolean get() = !claim.sourceActive
    }

    override fun play() {
        LoopSourcePauseGate.resume()
        super.play()
    }

    override fun pause() {
        if (isSourceItem(snapshot())) LoopSourcePauseGate.pause()
        super.pause()
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

    /**
     * seek 投影：claim 未生效 / 目标不是当前项 → 完全透传；
     * 否则 SOURCE 走数据源时间轴，M3′ 走 [LoopSessionProjection.seekUs]。
     */
    private fun seekToInternal(mediaItemIndex: Int, positionMs: Long) {
        val snapshot = snapshot()
        val claim = snapshot.claim
        val window = claim.window
        if (!claim.claimActive || window == null || mediaItemIndex != currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
            return
        }
        if (isSourceItem(snapshot)) {
            val targetT = LoopSessionProjection.sourceTimelineUsForDisplay(
                displayUs = positionMs * LoopSessionProjection.US_PER_MS,
                currentTimelineUs = super.getCurrentPosition() * LoopSessionProjection.US_PER_MS,
                window = window,
            )
            super.seekTo(mediaItemIndex, targetT / LoopSessionProjection.US_PER_MS)
            return
        }
        val rawUs = LoopSessionProjection.seekUs(
            targetUs = positionMs * LoopSessionProjection.US_PER_MS,
            window = window,
            currentRawUs = currentRawUs(snapshot.isReplaying),
            isRingComplete = audio.isRingComplete,
        )
        super.seekTo(mediaItemIndex, rawUs / LoopSessionProjection.US_PER_MS)
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
}
