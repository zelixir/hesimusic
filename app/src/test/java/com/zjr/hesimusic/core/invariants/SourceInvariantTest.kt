package com.zjr.hesimusic.core.invariants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音源一致性不变量（issue 00021）纯 JVM 测试。
 *
 * 规则与任务约定一致：
 * - window!=null + tag==null → 触发；
 * - window!=null + tag!=null（且工厂最近一次 select=loop）→ 不触发；
 * - window==null + tag!=null → 反向触发。
 */
class SourceInvariantTest {

    private fun sample(
        atMs: Long = 0L,
        windowActive: Boolean,
        itemHasLoopTag: Boolean,
        factoryLastSelectLoop: Boolean = true,
    ): SourceInvariantSample = SourceInvariantSample(
        atMs = atMs,
        windowActive = windowActive,
        itemHasLoopTag = itemHasLoopTag,
        factoryLastSelectLoop = factoryLastSelectLoop,
    )

    @Test
    fun loopProjectionWithoutLoopTagTriggers() {
        val event = SourceInvariantEvaluator().onSample(
            sample(windowActive = true, itemHasLoopTag = false),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PROJECTION_LOOP_SOURCE_PLAIN, event!!.code)
        assertEquals("INVARIANT violated: projection=loop but source=plain", event.message)
    }

    @Test
    fun loopProjectionWithTagAndLoopSelectDoesNotTrigger() {
        assertNull(
            SourceInvariantEvaluator().onSample(
                sample(windowActive = true, itemHasLoopTag = true, factoryLastSelectLoop = true),
            ),
        )
    }

    @Test
    fun loopProjectionWithTagButPlainSelectTriggers() {
        // 00021 的真实形态：时间线 item 有 tag，但 MediaSourceFactory 最近一次是普通源。
        val event = SourceInvariantEvaluator().onSample(
            sample(windowActive = true, itemHasLoopTag = true, factoryLastSelectLoop = false),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PROJECTION_LOOP_SOURCE_PLAIN, event!!.code)
    }

    @Test
    fun plainProjectionWithLoopTagTriggers() {
        val event = SourceInvariantEvaluator().onSample(
            sample(windowActive = false, itemHasLoopTag = true),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PROJECTION_PLAIN_SOURCE_LOOP, event!!.code)
        assertEquals("INVARIANT violated: projection=plain but source=loop", event.message)
    }

    @Test
    fun plainProjectionWithoutLoopTagDoesNotTrigger() {
        assertNull(
            SourceInvariantEvaluator().onSample(
                sample(windowActive = false, itemHasLoopTag = false),
            ),
        )
    }
}
