package com.zjr.hesimusic.core.invariants

import org.junit.Assert.assertEquals
import org.junit.Test

/** /state.invariants 的环形登记：最近 10 条 + 同 code 连续累加 count。 */
class InvariantRegistryTest {

    private fun event(code: String, atMs: Long): InvariantEvent =
        InvariantEvent(code = code, message = "INVARIANT violated: " + code, atMs = atMs)

    @Test
    fun sameCodeInARowMergesIntoOneRecordWithCount() {
        val registry = InvariantRegistry()
        registry.record(event("a", 1L))
        registry.record(event("a", 2L))
        val record = registry.record(event("a", 3L))
        assertEquals(3L, record.count)
        assertEquals(3L, record.atMs)
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun differentCodesAddSeparateRecords() {
        val registry = InvariantRegistry()
        registry.record(event("a", 1L))
        registry.record(event("b", 2L))
        val snapshot = registry.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("a", snapshot[0].code)
        assertEquals("b", snapshot[1].code)
        assertEquals(1L, snapshot[0].count)
    }

    @Test
    fun onlyTheMostRecentTenAreKept() {
        val registry = InvariantRegistry()
        for (i in 0 until 12) {
            registry.record(event("code-" + i, i.toLong()))
        }
        val snapshot = registry.snapshot()
        assertEquals(10, snapshot.size)
        assertEquals("code-2", snapshot.first().code)
        assertEquals("code-11", snapshot.last().code)
    }

    @Test
    fun toMapExposesCodeMessageAtMsCount() {
        val registry = InvariantRegistry()
        registry.record(event("paused-but-sink-wrote", 42L))
        val map = registry.snapshot().single().toMap()
        assertEquals("paused-but-sink-wrote", map["code"])
        assertEquals("INVARIANT violated: paused-but-sink-wrote", map["message"])
        assertEquals(42L, map["atMs"])
        assertEquals(1L, map["count"])
    }
}
