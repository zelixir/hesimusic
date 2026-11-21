package com.zjr.hesimusic.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 00028：新一次进入无缝循环必须从曲目开头（T=0）起播。
 *
 * 真机回归：确认并保存时若当前 item 已经是循环源，旧逻辑按 A/B 变化保留当前
 * 展示位置，出现 pos=15904ms（不是 0）；现在由 LoopEntryStartPolicy 统一裁决。
 */
class LoopEntryStartPolicyTest {

    @Test
    fun freshArmForConfirmOrMenu_startsAtPrelude() {
        assertTrue(LoopEntryStartPolicy.shouldForcePreludeStart(true, "intent", "restore"))
    }

    @Test
    fun restoreAfterProcessDeath_keepsSavedPosition() {
        assertFalse(LoopEntryStartPolicy.shouldForcePreludeStart(true, "restore", "restore"))
    }

    @Test
    fun sameIntentRefresh_doesNotResetEveryTick() {
        assertFalse(LoopEntryStartPolicy.shouldForcePreludeStart(false, "row", "restore"))
        assertFalse(LoopEntryStartPolicy.shouldForcePreludeStart(false, "repeat", "restore"))
        assertFalse(LoopEntryStartPolicy.shouldForcePreludeStart(false, "transition", "restore"))
    }

    @Test
    fun freshArmDuringNonIntentRefreshStillStartsAtPrelude() {
        assertTrue(LoopEntryStartPolicy.shouldForcePreludeStart(true, "row", "restore"))
    }
}
