package com.zjr.hesimusic.service

/**
 * 00029：`/state.invariantProbe.outputFrames` 的纯累加器（已渲染帧，单调）。
 *
 * 口径：按播放位置的**正增量**累加，而不是按 AudioSink 写入量：
 * - position 前进 1..2000ms -> rendered += delta * sampleRate / 1000；
 * - position 不变（暂停/缓冲）-> 不增长；
 * - position 回退（seek）-> 忽略，不扣减，保持单调；
 * - 单次增量 >2000ms 视为 seek/跳变，忽略。
 *
 * 为什么不读 AudioTrack playhead：主播放走 ExoPlayer 的 DefaultAudioSink，
 * 拿不到内部 AudioTrack；而 player.currentPosition 正是由 AudioSink 播放头推导，
 * 暂停不动、渲染前进，语义等价且不需要改 sink。
 */
class RenderedFramesCounter {

    private var lastPositionMs: Long = Long.MIN_VALUE
    private var frames: Long = 0L

    /** 喂一次位置采样，返回当前累计已渲染帧（可在采样线程与 /state 读线程并发调用）。 */
    @Synchronized
    fun onPosition(positionMs: Long, sampleRate: Int): Long {
        val last = lastPositionMs
        if (last != Long.MIN_VALUE && sampleRate > 0) {
            val deltaMs = positionMs - last
            if (deltaMs in 1..MAX_FORWARD_DELTA_MS) {
                frames += deltaMs * sampleRate / 1000L
            }
        }
        lastPositionMs = positionMs
        return frames
    }

    @Synchronized
    fun value(): Long = frames

    @Synchronized
    fun reset() {
        lastPositionMs = Long.MIN_VALUE
        frames = 0L
    }

    private companion object {
        const val MAX_FORWARD_DELTA_MS = 2_000L
    }
}
