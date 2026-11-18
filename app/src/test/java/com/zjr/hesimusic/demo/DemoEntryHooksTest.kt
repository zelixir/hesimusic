package com.zjr.hesimusic.demo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * demo 冷启动「仅循环段」自动试听意图的一次性消费语义。
 *
 * 纯 JVM:不依赖 Android/播放器;只验证 DemoBootstrap 每次启动 arm 后,
 * LoopViewModel 能且只能在目标歌曲进入 Ready 时消费一次。
 */
class DemoEntryHooksTest {

    @After
    fun reset() {
        DemoEntryHooks.clearAutoAudition()
    }

    @Test
    fun `arms every launch and consumes matching song exactly once`() {
        // 模拟每次冷启动都 arm(覆盖旧值)。
        DemoEntryHooks.armAutoAudition(42L)
        DemoEntryHooks.armAutoAudition(42L)

        assertEquals(42L, DemoEntryHooks.autoAudition.value)
        assertTrue(DemoEntryHooks.consumeAutoAudition(42L))
        assertNull(DemoEntryHooks.autoAudition.value)
        assertFalse(DemoEntryHooks.consumeAutoAudition(42L))
    }

    @Test
    fun `does not consume a different song`() {
        DemoEntryHooks.armAutoAudition(42L)

        assertFalse(DemoEntryHooks.consumeAutoAudition(7L))
        assertEquals(42L, DemoEntryHooks.autoAudition.value)
        assertTrue(DemoEntryHooks.consumeAutoAudition(42L))
    }

    @Test
    fun `does nothing when no intent is pending`() {
        assertNull(DemoEntryHooks.autoAudition.value)
        assertFalse(DemoEntryHooks.consumeAutoAudition(1L))
    }
}
