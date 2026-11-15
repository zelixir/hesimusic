package com.zjr.hesimusic.service

import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.zjr.hesimusic.data.mapper.mediaIdToSongId
import com.zjr.hesimusic.loop.LoopPosition
import com.zjr.hesimusic.loop.LoopRuntime
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.LoopAudioController

/**
 * 给 MediaSession / UI / 通知用的**位置口径层**（ForwardingPlayer）。
 *
 * 只重写位置/时长/seek 五个方法，其余（play/pause/setMediaItem/setRepeatMode/shuffle/queue…）
 * 一律透传 —— 播放器状态机保持权威，本层不做任何媒体结构操作。
 *
 * 为什么需要它：环回发生在 PCM 层（M3'），ExoPlayer 看到的位置会一直增长到 B+kL；
 * 但对外必须表现为「落在 [A,B) 内的进度 + 整曲时长」。折返算术全部在纯函数
 * [LoopSessionProjection] 里，本类只负责取 raw、选窗口与单位换算（µs ↔ ms）。
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

    /** 一次读取内共享的口径快照：窗口 + 是否重放（两者必须同源）。 */
    private class Snapshot(
        val window: LoopWindow?,
        val isReplaying: Boolean,
    )

    /** 回退日志节流：每次「runtime.window 不可用但仍在重放」的区间只打一次。 */
    @Volatile
    private var fallbackLogged: Boolean = false

    override fun getCurrentPosition(): Long {
        val snapshot = snapshot()
        val window = snapshot.window ?: return super.getCurrentPosition()
        val rawUs = currentRawUs(snapshot.isReplaying)
        return LoopSessionProjection.displayUs(rawUs, window) / LoopSessionProjection.US_PER_MS
    }

    override fun getBufferedPosition(): Long {
        val snapshot = snapshot()
        val window = snapshot.window ?: return super.getBufferedPosition()
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
        val window = snapshot().window ?: return super.getDuration()
        val projectedUs = projectedDurationUs(window)
        // 防御性覆盖：不裁剪时播放器原生时长本来就等于整曲时长，这里只是保证「窗口生效期间
        // 对外时长恒为整曲」（整曲时长未知时返回 0 → 透传原生值）
        return if (projectedUs > 0L) projectedUs / LoopSessionProjection.US_PER_MS else super.getDuration()
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
     * seek 投影：窗口不适用（服务层/音频层都没有属于当前项的窗口）/ 目标不是当前项 → 完全透传；
     * 否则走 [LoopSessionProjection.seekUs]（[LoopPosition.rawSeekUs] 三分支 +
     * ring 不完整时该轮起点前 250ms 的降级预卷）。
     */
    private fun seekToInternal(mediaItemIndex: Int, positionMs: Long) {
        val snapshot = snapshot()
        val window = snapshot.window
        if (window == null || mediaItemIndex != currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
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
     * isReplaying 只读一次，窗口选择与 raw 取值都基于它，避免
     * 「用 audio.window 折返、却用 player 位置当 raw」的混口径。
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
        return Snapshot(resolved.window, isReplaying)
    }

    /** 引擎原始位置（µs）：重放期取 sink 推进的位置，否则取播放器自身位置。 */
    private fun currentRawUs(isReplaying: Boolean): Long = LoopSessionProjection.rawUs(
        isReplaying = isReplaying,
        replayPositionUs = audio.positionUs,
        playerPositionMs = super.getCurrentPosition(),
    )

    /** 对外时长（µs）；0 = 不投影（整曲时长未知）。 */
    private fun projectedDurationUs(window: LoopWindow): Long =
        LoopSessionProjection.durationUs(window, audio.fullDurationUs)

    private companion object {
        private const val TAG_PERF = "LoopPerf"
    }
}
