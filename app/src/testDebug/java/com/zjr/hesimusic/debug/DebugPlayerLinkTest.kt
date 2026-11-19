package com.zjr.hesimusic.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试:快照有效性只看链路(controller 是否存在且 isConnected),
 * 不看连接时长、也不看 queue/playbackState/position。
 *
 * 放在 src/testDebug 而不是 src/test:被测对象 DebugPlayerLink 只在 debug 源集存在,
 * 若放共享 test 源集,testProdReleaseUnitTest 会因缺类而编译失败。
 */
class DebugPlayerLinkTest {

    @Test
    fun `connected controller with idle empty snapshot is valid`() {
        // 空闲时 queue=0/idle/pos=0 且 age 再大也不得判 invalid(否则每个请求都会重建 controller)。
        assertFalse(DebugPlayerLink.isInvalid(hasController = true, connected = true))
    }

    @Test
    fun `missing controller is invalid`() {
        assertTrue(DebugPlayerLink.isInvalid(hasController = false, connected = false))
        assertEquals(
            DebugPlayerLink.REASON_NOT_CONNECTED,
            DebugPlayerLink.reason(hasController = false, connected = false),
        )
    }

    @Test
    fun `disconnected controller is invalid`() {
        assertTrue(DebugPlayerLink.isInvalid(hasController = true, connected = false))
        assertEquals(
            DebugPlayerLink.REASON_DISCONNECTED,
            DebugPlayerLink.reason(hasController = true, connected = false, fallback = "ignored"),
        )
    }

    @Test
    fun `disconnect keeps its reason after the controller reference is cleared`() {
        assertEquals(
            DebugPlayerLink.REASON_DISCONNECTED,
            DebugPlayerLink.reason(
                hasController = false,
                connected = false,
                fallback = DebugPlayerLink.REASON_DISCONNECTED,
            ),
        )
    }

    @Test
    fun `connected controller only falls back to a snapshot failure reason`() {
        assertEquals(
            DebugPlayerLink.REASON_SNAPSHOT_TIMEOUT,
            DebugPlayerLink.reason(
                hasController = true,
                connected = true,
                fallback = DebugPlayerLink.REASON_SNAPSHOT_TIMEOUT,
            ),
        )
        assertEquals(
            DebugPlayerLink.REASON_UNAVAILABLE,
            DebugPlayerLink.reason(hasController = true, connected = true, fallback = null),
        )
    }

    @Test
    fun `every reason maps to a non empty message`() {
        val reasons = listOf(
            DebugPlayerLink.REASON_NOT_CONNECTED,
            DebugPlayerLink.REASON_DISCONNECTED,
            DebugPlayerLink.REASON_SNAPSHOT_TIMEOUT,
            DebugPlayerLink.REASON_SNAPSHOT_FAILED,
            DebugPlayerLink.REASON_NOT_INITIALIZED,
            DebugPlayerLink.REASON_UNAVAILABLE,
        )
        reasons.forEach { reason ->
            assertTrue(
                "reason=" + reason + " should map to a readable message",
                DebugPlayerLink.message(reason).isNotBlank(),
            )
        }
    }
}
