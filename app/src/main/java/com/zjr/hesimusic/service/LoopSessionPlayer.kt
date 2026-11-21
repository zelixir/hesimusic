package com.zjr.hesimusic.service

import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.source.LoopSourcePauseGate
import com.zjr.hesimusic.loop.audio.source.LoopSourceProjection

/**
 * 给 MediaSession / UI / 通知用的**位置口径层**（ForwardingPlayer）。
 *
 * 只重写位置/时长/seek 五个方法，其余（play/pause/setMediaItem/setRepeatMode/shuffle/queue…）
 * 一律透传 —— 播放器状态机保持权威，本层不做任何媒体结构操作。
 *
 * 为什么需要它：环回发生在 PCM 层（M3'），ExoPlayer 看到的位置会一直增长到 B+kL；
 * 但对外必须表现为「落在 [A,B) 内的进度 + 整曲时长」。折返算术全部在纯函数
 * [LoopSessionProjection] / [LoopSourceProjection] 里，本类只负责取 raw、选窗口与单位换算（µs ↔ ms）。
 *
 * 路线（C 复核）：是否按 SOURCE 数据源时间轴投影由 [MainPlayerRouting] 的**同一个**
 * 构建时布尔决定；SOURCE 构建失败被 [LoopSourceFallback] 登记后，本层立即回到媒体时间透传
 * （绝不用带 offset 的 T 冒充进度）。
 *
 * 窗口来源（C2 修复）：优先 [LoopRuntime.window]（服务层派生态）；若它暂不可用
 * **且正在重放**，回退到 [LoopAudioController.window]（音频层同步写的权威值）——
 * 否则重放开始后的短窗口里会透传 ExoPlayer 原始位置，出现 B+1.4s 的冲高。
 * 窗口与 raw 位置用**同一个** isReplaying 快照，保证两者口径一致。
 *
 * 契约：docs/loop-v3-service-design.md §2.2。
 */
class LoopSessionPlayer(
    player: Player,
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
) : ForwardingPlayer(player) {

    /** 一次读取内共享的口径快照：窗口 + 是否重放 + SOURCE 是否已回退（三者必须同源）。 */
    private class Snapshot(
        val window: LoopWindow?,
        val isReplaying: Boolean,
        val sourceUnavailable: Boolean,
    )

    /** 回退日志节流：每次「runtime.window 不可用但仍在重放」的区间只打一次。 */
    @Volatile
    private var fallbackLogged: Boolean = false

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
        if (snapshot.sourceUnavailable) return super.getCurrentPosition()
        val window = snapshot.window ?: return super.getCurrentPosition()
        if (isSourceItem(snapshot)) {
            // T → 媒体时间：对外读到的是 A + floorMod(T,L)，不是带 offset 的数据源时间轴 T。
            return LoopSessionProjection.sourceDisplayMs(super.getCurrentPosition(), window)
        }
        val rawUs = currentRawUs(snapshot.isReplaying)
        return LoopSessionProjection.displayUs(rawUs, window) / LoopSessionProjection.US_PER_MS
    }

    override fun getBufferedPosition(): Long {
        val snapshot = snapshot()
        if (snapshot.sourceUnavailable) return super.getBufferedPosition()
        val window = snapshot.window ?: return super.getBufferedPosition()
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
            val clamped = if (durationUs > 0L) atLeastCurrent.coerceAtMost(durationUs) else atLeastCurrent
            return clamped / LoopSessionProjection.US_PER_MS
        }
        val durationUs = projectedDurationUs(window)
        val currentDisplayUs = LoopSessionProjection.displayUs(currentRawUs(snapshot.isReplaying), window)
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
        // 若在这里按 sourceUnavailable 提前 return，通知/锁屏/车机会拿到 -1。
        // 只要 coordinator 已写入整曲时长，就一律用整曲覆盖原生值。
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
     * seek 投影：窗口不适用（服务层/音频层都没有属于当前项的窗口）/ 目标不是当前项 / SOURCE 已回退
     * → 完全透传；否则走 [LoopSessionProjection.seekUs] 或 [LoopSourceProjection.timelineUsForDisplay]。
     */
    private fun seekToInternal(mediaItemIndex: Int, positionMs: Long) {
        val snapshot = snapshot()
        val window = snapshot.window
        if (snapshot.sourceUnavailable || window == null || mediaItemIndex != currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
            return
        }
        if (isSourceItem(snapshot)) {
            val targetT = LoopSourceProjection.timelineUsForDisplay(
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
     * 选窗口并拍快照（C2 修复）。
     *
     * - 优先 runtime.window（songId 归属校验）：正常路径；
     * - 它为 null/不属于当前项且**正在重放** → 回退 audio.window（同样校验归属）；
     * - 都不可用 → 返回 window = null，调用方完全透传。
     *
     * isReplaying 与 sourceUnavailable 都只读一次，窗口选择、raw 取值、投影开关基于同一份快照，
     * 避免「用 audio.window 折返、却用 player 位置当 raw」的混口径。
     */
    private fun snapshot(): Snapshot {
        val isReplaying = audio.isReplaying
        val songId = currentMediaItem?.mediaId?.let { mediaIdToSongId(it) }
        val resolved = LoopSessionProjection.resolveWindow(
            songId = songId,
            runtimeWindow = runtime.window.value,
            audioWindow = audio.window,
            isReplaying = isReplaying,
        )
        // SOURCE 构建失败已被登记：本窗口不再投影，位置/时长回到媒体时间。
        val sourceUnavailable = LoopSourceFallback.isUnavailable(songId, resolved.window)
        val usedAudioFallback = resolved.source == LoopSessionProjection.WindowSource.AUDIO_FALLBACK
        if (usedAudioFallback) {
            if (!fallbackLogged) {
                fallbackLogged = true
                // 便于确认 C2 修复生效：重放期 runtime.window 还没落值时才开始打
                Log.d(
                    TAG_PERF,
                    "window fallback to audio songId=" + songId + " playheadUs=" + audio.positionUs +
                        " window=" + resolved.window,
                )
            }
        } else {
            fallbackLogged = false
        }
        return Snapshot(resolved.window, isReplaying, sourceUnavailable)
    }

    /** 引擎原始位置（µs）：重放期取 sink 推进的位置，否则取播放器自身位置。 */
    private fun currentRawUs(isReplaying: Boolean): Long = LoopSessionProjection.rawUs(
        isReplaying = isReplaying,
        replayPositionUs = audio.positionUs,
        playerPositionMs = super.getCurrentPosition(),
    )

    /**
     * 当前项是否按循环数据源口径投影：由**同一个**路线快照 + 当前 item 的循环 tag 决定，
     * 且该窗口没有被登记为 SOURCE 不可用。
     */
    private fun isSourceItem(snapshot: Snapshot): Boolean =
        MainPlayerRouting.sessionProjectsSource(
            tag = currentMediaItem?.localConfiguration?.tag,
            sourceUnavailable = snapshot.sourceUnavailable,
        )

    /** 对外时长（µs）；0 = 不投影（整曲时长未知）。 */
    private fun projectedDurationUs(window: LoopWindow): Long =
        LoopSessionProjection.durationUs(window, audio.fullDurationUs)

    private companion object {
        private const val TAG_PERF = "LoopPerf"
    }
}
