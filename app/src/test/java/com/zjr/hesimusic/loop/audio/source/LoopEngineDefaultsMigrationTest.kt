package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopEngineDefaultsMigrationTest {

    @Test
    fun `old false_m3 prefs migrate once to source`() {
        val plan = LoopEngineDefaultsMigration.plan(0, "m3", "m3")
        assertTrue(plan.changed)
        assertEquals(2, plan.toVersion)
        assertEquals(true, plan.mainEnabled)
        assertEquals("source", plan.engine)
    }

    @Test
    fun `empty prefs migrate to source and write version`() {
        val plan = LoopEngineDefaultsMigration.plan(0, null, null)
        assertTrue(plan.changed)
        assertEquals(2, plan.toVersion)
        assertEquals("source", plan.engine)
    }

    @Test
    fun `already migrated keeps explicit m3`() {
        val plan = LoopEngineDefaultsMigration.plan(2, "m3", "m3")
        assertFalse(plan.changed)
        assertEquals(2, plan.toVersion)
        assertEquals(null, plan.mainEnabled)
        assertEquals(null, plan.engine)
    }

    @Test
    fun `explicit m3 after migration is not overwritten`() {
        // 版本 2 表示迁移已完成，后续 select(m3) 的键值不会再被一次性迁移覆盖。
        val plan = LoopEngineDefaultsMigration.plan(2, "m3", "m3")
        assertFalse(plan.changed)
        assertEquals(2, plan.fromVersion)
    }
}
