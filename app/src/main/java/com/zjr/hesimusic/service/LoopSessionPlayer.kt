package com.zjr.hesimusic.service

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
 * [LoopSessionProjection] 里，本类只负责取 raw、校验窗口归属与单位换算（µs ↔ ms）。
 *
 * 契约：docs/loop-v3-service-design.md §2.2。
 */
class LoopSessionPlayer(
    player: Player,
    private val runtime: LoopRuntime,
    private val audio: LoopAudioController,
) : ForwardingPlayer(player) {

    override fun getCurrentPosition(): Long {
        val window = activeWindow() ?: return super.getCurrentPosition()
        return LoopSessionProjection.displayUs(currentRawUs(), window) / LoopSessionProjection.US_PER_MS
    }

    override fun getBufferedPosition(): Long {
        val window = activeWindow() ?: return super.getBufferedPosition()
        val durationUs = projectedDurationUs(window)
        val currentDisplayUs = LoopSessionProjection.displayUs(currentRawUs(), window)
        val bufferedUs = LoopSessionProjection.bufferedUs(
            rawBufferedUs = super.getBufferedPosition() * LoopSessionProjection.US_PER_MS,
            window = window,
            currentDisplayUs = currentDisplayUs,
            durationUs = durationUs,
        )
        return bufferedUs / LoopSessionProjection.US_PER_MS
    }

    override fun getDuration(): Long {
        val window = activeWindow() ?: return super.getDuration()
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
     * seek 投影：窗口不适用（为空 / 不属于当前项）/ 目标不是当前项 → 完全透传；
     * 否则走 [LoopSessionProjection.seekUs]（[LoopPosition.rawSeekUs] 三分支 +
     * ring 不完整时该轮起点前 250ms 的降级预卷）。
     */
    private fun seekToInternal(mediaItemIndex: Int, positionMs: Long) {
        val window = activeWindow()
        if (window == null || mediaItemIndex != currentMediaItemIndex) {
            super.seekTo(mediaItemIndex, positionMs)
            return
        }
        val rawUs = LoopSessionProjection.seekUs(
            targetUs = positionMs * LoopSessionProjection.US_PER_MS,
            window = window,
            currentRawUs = currentRawUs(),
            isRingComplete = audio.isRingComplete,
        )
        super.seekTo(mediaItemIndex, rawUs / LoopSessionProjection.US_PER_MS)
    }

    /**
     * 只认「属于当前 MediaItem」的窗口。
     *
     * runtime.window 在切歌瞬间可能仍是上一首的（refresh 是异步协程），若不加这层归属校验，
     * 新歌开头的进度会按上一首的 A/B 折返。
     */
    private fun activeWindow(): LoopWindow? {
        val window = runtime.window.value ?: return null
        val songId = currentMediaItem?.mediaId?.let { mediaIdToSongId(it) } ?: return null
        return if (window.songId == songId) window else null
    }

    /** 引擎原始位置（µs）：重放期取 sink 推进的位置，否则取播放器自身位置。 */
    private fun currentRawUs(): Long = LoopSessionProjection.rawUs(
        isReplaying = audio.isReplaying,
        replayPositionUs = audio.positionUs,
        playerPositionMs = super.getCurrentPosition(),
    )

    /** 对外时长（µs）；0 = 不投影（整曲时长未知）。 */
    private fun projectedDurationUs(window: LoopWindow): Long =
        LoopSessionProjection.durationUs(window, audio.fullDurationUs)
}
