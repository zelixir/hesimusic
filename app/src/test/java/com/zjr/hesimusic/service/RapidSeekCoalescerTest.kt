package com.zjr.hesimusic.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #111 高频 seek 合并器测试（纯 JVM）。
 *
 * 判据来自需求：中间请求允许被合并/丢弃，但**最后一次必须落地**，且不能让播放器在连发后
 * 停在 buffering。这里把"落点收敛"钉成可重放的确定性测试。
 */
class RapidSeekCoalescerTest {

    private val quiet = RapidSeekCoalescer.DEFAULT_QUIET_MS

    @Test
    fun singleSeek_appliesImmediately() {
        val c = RapidSeekCoalescer(quiet)
        assertTrue(c.request(SeekRequest(0, 3_000L), nowMs = 1_000L))
        assertNull("单次 seek 不需要额外 flush", c.pendingRequest())
        assertNull(c.flush(nowMs = 1_000L + quiet + 1))
    }

    @Test
    fun twentySeeksAt100ms_applyAtMostTwice_andLastTargetWins() {
        val c = RapidSeekCoalescer(quiet)
        val targets = (0 until 20).map { 3_000L + it * 4_000L }
        val applied = ArrayList<Long>()
        var now = 1_000L

        targets.forEachIndexed { i, target ->
            val applyNow = c.request(SeekRequest(0, target), nowMs = now)
            if (applyNow) applied += target
            // 第 i 次请求在 t=i*100ms 发出；下一次在 100ms 后。
            now += 100L
        }

        // 连发结束（t = 1000 + 19*100 = 2900ms），静默期结束于 3020ms。
        val flushed = c.flush(nowMs = 3_020L)
        flushed?.let { applied += it.positionMs }

        assertEquals("中间请求被合并：首枪 + 末枪最多 2 次真实 seek", 2, applied.size)
        assertEquals("第一次立即生效", targets.first(), applied.first())
        assertEquals("最后一次必须落地", targets.last(), applied.last())
        assertTrue("中间目标必须被丢弃", applied.none { it in targets.subList(1, targets.size - 1) })
    }

    @Test
    fun burst_keepsOnlyLatestPending() {
        val c = RapidSeekCoalescer(quiet)
        assertTrue(c.request(SeekRequest(0, 1_000L), nowMs = 0L))
        assertFalse(c.request(SeekRequest(0, 2_000L), nowMs = 50L))
        assertFalse(c.request(SeekRequest(0, 3_000L), nowMs = 100L))
        assertFalse(c.request(SeekRequest(0, 4_000L), nowMs = 150L))
        assertEquals(SeekRequest(0, 4_000L), c.pendingRequest())
        assertEquals(SeekRequest(0, 4_000L), c.flush(nowMs = 300L))
    }

    @Test
    fun flushWithoutPending_isNoop() {
        val c = RapidSeekCoalescer(quiet)
        assertNull(c.flush(nowMs = 0L))
    }

    @Test
    fun flushOfSameTarget_doesNotRepeatTheSeek() {
        val c = RapidSeekCoalescer(quiet)
        assertTrue(c.request(SeekRequest(0, 5_000L), nowMs = 0L))
        assertFalse(c.request(SeekRequest(0, 5_000L), nowMs = 50L))
        assertNull("与已应用目标相同 → 不重复 seek", c.flush(nowMs = 200L))
    }

    @Test
    fun spacedSeeks_areNotMerged() {
        val c = RapidSeekCoalescer(quiet)
        val targets = (0 until 5).map { 1_000L + it * 500L }
        var applied = 0
        var now = 0L
        for (target in targets) {
            if (c.request(SeekRequest(0, target), nowMs = now)) applied += 1
            now += quiet + 200L
        }
        assertEquals("间隔大于静默期：每次都立即生效", targets.size, applied)
        assertNull(c.flush(nowMs = now))
    }

    @Test
    fun cancel_dropsPendingButKeepsAppliedTarget() {
        val c = RapidSeekCoalescer(quiet)
        assertTrue(c.request(SeekRequest(0, 1_000L), nowMs = 0L))
        assertFalse(c.request(SeekRequest(0, 9_000L), nowMs = 30L))
        c.cancel()
        assertNull(c.pendingRequest())
        assertNull(c.flush(nowMs = 1_000L))
    }

    @Test
    fun indexChange_requestCarriesTargetItem() {
        val c = RapidSeekCoalescer(quiet)
        assertTrue(c.request(SeekRequest(2, 1_000L), nowMs = 0L))
        assertFalse(c.request(SeekRequest(3, 2_000L), nowMs = 40L))
        assertEquals("换项请求同样保留最后一次目标", SeekRequest(3, 2_000L), c.flush(nowMs = 200L))
    }
}
