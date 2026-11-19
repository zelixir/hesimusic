package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource

/**
 * 包一层，确保 MediaSource 生命周期结束时回收环形 PCM、渲染线程和 codec 会话。
 *
 * media3 1.8 的释放入口是 releaseSourceInternal()（releaseSource 为 final）。
 * 同一 Player 换 source / 预览切歌时，Player 会走这里。
 */
@UnstableApi
internal class RingBandMediaSource(
    delegate: MediaSource,
    private val extractor: RingBandExtractor,
    ring: LoopRing,
) : WrappingMediaSource(delegate) {

    @Volatile
    private var ringRef: LoopRing? = ring

    @Volatile
    private var released = false

    internal fun ringForTest(): LoopRing? = ringRef

    override fun releaseSourceInternal() {
        releaseResources()
        super.releaseSourceInternal()
    }

    /** 单测用：不依赖 media3 caller。 */
    internal fun releaseForTest() {
        releaseResources()
    }

    private fun releaseResources() {
        if (released) return
        released = true
        val bytes = ringRef?.let { it.frames * it.bytesPerFrame } ?: 0L
        ringRef = null
        extractor.release()
        RingBandLog.i("RingBand release bandBytes=" + bytes + " released=true")
    }
}
