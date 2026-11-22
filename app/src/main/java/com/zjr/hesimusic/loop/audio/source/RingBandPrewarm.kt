package com.zjr.hesimusic.loop.audio.source

import java.util.concurrent.CopyOnWriteArrayList

/**
 * seek 意图到达时的**目标块预热**钩子（issue 00029）。
 *
 * 区外 seek 慢在没有预热：目标块要等 extractor 的首次 read 才进入解码队列，单块
 * 解码 60~150ms 全落在用户等待里。LoopSessionPlayer.applySeekNow 在调用
 * super.seekTo **之前**把目标 timeline 位置广播给当前活跃的循环源；源里的
 * prelude/tail reader 立刻解码目标块（不改变播放游标），seek 真正落地时缓存已 READY。
 *
 * 多源（主播放 + 预览）同时存在时逐个通知：位置只对各自 timeline 有效的源会命中，
 * 其余源最多多解一个块。异常全部吞掉，预热永远不能影响播放/seek 本身。
 */
object RingBandPrewarm {

    class Token internal constructor(internal val handler: (Long) -> Unit)

    private val handlers = CopyOnWriteArrayList<Token>()

    fun register(handler: (Long) -> Unit): Token {
        val token = Token(handler)
        handlers += token
        return token
    }

    fun unregister(token: Token?) {
        if (token != null) handlers.remove(token)
    }

    fun prewarmTimelineUs(timelineUs: Long) {
        if (timelineUs < 0L) return
        for (token in handlers) {
            try {
                token.handler(timelineUs)
            } catch (_: Throwable) {
                // 预热是纯优化，任何异常都不能影响 seek。
            }
        }
    }
}
