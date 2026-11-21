package com.zjr.hesimusic.core.invariants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 音源一致性不变量（issue 00021 / 00027）纯 JVM 测试。
 *
 * 关键回归（场景 #26/#108）：claim 与 source 都 true 时，**即使** factory 最近一次 select
 * 是 delegate、或当前 item 的 tag 不可见，也不能误报——factory select 会被队列预取覆盖。
 */
class SourceInvariantTest {

    private fun sample(
        atMs: Long = 0L,
        claimActive: Boolean,
        sourceActive: Boolean,
        itemHasLoopTag: Boolean = false,
        factoryLastSelectLoop: Boolean = false,
    ): SourceInvariantSample = SourceInvariantSample(
        atMs = atMs,
        claimActive = claimActive,
        sourceActive = sourceActive,
        itemHasLoopTag = itemHasLoopTag,
        factoryLastSelectLoop = factoryLastSelectLoop,
    )

    @Test
    fun `claim loop but plain source triggers`() {
        val event = SourceInvariantEvaluator().onSample(
            sample(claimActive = true, sourceActive = false),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PROJECTION_LOOP_SOURCE_PLAIN, event!!.code)
        assertEquals("INVARIANT violated: claim=loop but source=plain", event.message)
    }

    @Test
    fun `claim and source both loop does not trigger even with stale factory diagnostics`() {
        // #26 / #108 的假报警形态：源真的在循环（claim/source 都 true），
        // 但 factory 的最近一次 select 是 delegate 或 tag 不可见。
        assertNull(
            SourceInvariantEvaluator().onSample(
                sample(
                    claimActive = true,
                    sourceActive = true,
                    itemHasLoopTag = false,
                    factoryLastSelectLoop = false,
                ),
            ),
        )
    }

    @Test
    fun `plain claim but loop source triggers`() {
        val event = SourceInvariantEvaluator().onSample(
            sample(claimActive = false, sourceActive = true),
        )
        assertNotNull(event)
        assertEquals(InvariantCode.PROJECTION_PLAIN_SOURCE_LOOP, event!!.code)
        assertEquals("INVARIANT violated: claim=plain but source=loop", event.message)
    }

    @Test
    fun `plain claim and plain source does not trigger`() {
        assertNull(
            SourceInvariantEvaluator().onSample(
                sample(claimActive = false, sourceActive = false),
            ),
        )
    }

    @Test
    fun `diagnostic tag alone no longer drives the verdict`() {
        // 00021 旧判据会看 tag/select；现在只认 claim/source，避免诊断字段造成假报警。
        assertNull(
            SourceInvariantEvaluator().onSample(
                sample(
                    claimActive = false,
                    sourceActive = false,
                    itemHasLoopTag = true,
                    factoryLastSelectLoop = true,
                ),
            ),
        )
    }
}
