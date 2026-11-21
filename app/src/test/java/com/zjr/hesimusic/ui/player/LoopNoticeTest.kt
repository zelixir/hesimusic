package com.zjr.hesimusic.ui.player

import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaim
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.LoopExitNotice
import com.zjr.hesimusic.service.LoopExitReason
import com.zjr.hesimusic.service.LoopSourceFallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D05/D06/D13 共享提示通道的纯 JVM 单测。
 *
 * 关键判据：
 * - D06：五种回退原因都有用户文案 + 原因码；同一窗口/原因只提示一次（持久化）；
 * - D13：service 的一次性 LoopExitNotice 被消费后立即清空，不串歌；
 * - 队列/去重/TTL/64 键上限；
 * - 回退提示不得在“claim 生效”时出现。
 */
class LoopNoticeTest {

    private val window = LoopWindow(
        songId = 7L,
        loopStartUs = 10_000_000L,
        loopEndUs = 50_000_000L,
    )

    private fun claim(
        songId: Long? = 7L,
        window: LoopWindow? = this.window,
        sourceActive: Boolean = false,
        reason: LoopSourceFallback.Reason? = LoopSourceFallback.Reason.RING_TOO_LARGE,
    ) = LoopClaim(
        songId = songId,
        window = window,
        sourceActive = sourceActive,
        fallbackReason = reason,
    )

    private class FakeSeen(
        private val log: LoopNoticeSeenLog = LoopNoticeSeenLog(),
    ) : LoopNoticeSeenStore {
        override fun isSeen(id: String, nowMs: Long): Boolean = log.isSeen(id, nowMs)
        override fun mark(id: String, nowMs: Long) = log.mark(id, nowMs)
        fun size(): Int = log.size()
    }

    // ------------------------------------------------------------------ D06 文案

    @Test
    fun fallback_reason_messages_include_user_text_and_code() {
        val expected = mapOf(
            LoopSourceFallback.Reason.WINDOW_INVALID to "循环窗口无效",
            LoopSourceFallback.Reason.FILE_MISSING to "找不到音频文件",
            LoopSourceFallback.Reason.FORMAT_UNSUPPORTED to "音频格式不支持无缝循环",
            LoopSourceFallback.Reason.RING_TOO_LARGE to "该循环段过大",
            LoopSourceFallback.Reason.BUILD_FAILED to "循环环带构建失败",
        )
        assertEquals(5, expected.size)
        for ((reason, label) in expected) {
            val message = fallbackNoticeMessage(reason)
            assertTrue("missing label: " + message, message.contains(label))
            assertTrue("missing code: " + message, message.contains(reason.logName))
            assertTrue(
                "message must say it fell back to normal playback: " + message,
                message.contains("已按普通播放"),
            )
        }
    }

    @Test
    fun fallback_notice_uses_code_and_is_stable_for_same_window() {
        val a = fallbackNotice(7L, claim())
        val b = fallbackNotice(7L, claim())
        assertEquals(a, b)
        assertTrue(a!!.message.contains("ring-too-large"))
    }

    @Test
    fun fallback_notice_key_changes_when_window_or_reason_changes() {
        val base = fallbackNoticeId(7L, window, LoopSourceFallback.Reason.RING_TOO_LARGE)
        val otherWindow = LoopWindow(7L, 20_000_000L, 60_000_000L)
        val otherWindowId = fallbackNoticeId(7L, otherWindow, LoopSourceFallback.Reason.RING_TOO_LARGE)
        val otherReasonId = fallbackNoticeId(7L, window, LoopSourceFallback.Reason.FILE_MISSING)
        assertNotEquals(base, otherWindowId)
        assertNotEquals(base, otherReasonId)
    }

    @Test
    fun fallback_notice_without_window_still_visible() {
        // window-invalid 时 LoopClaim.window 为 null，但 reason 非空：不能吞掉提示。
        val notice = fallbackNotice(7L, claim(window = null, reason = LoopSourceFallback.Reason.WINDOW_INVALID))
        assertTrue(notice!!.message.contains("window-invalid"))
    }

    @Test
    fun active_claim_never_reports_fallback() {
        val active = claim(sourceActive = true, reason = null)
        assertTrue(active.claimActive)
        assertNull(fallbackNotice(7L, active))
    }

    @Test
    fun no_reason_never_reports_fallback() {
        assertNull(fallbackNotice(7L, claim(reason = null)))
        assertNull(fallbackNotice(null, claim(songId = null, reason = null)))
    }

    // ------------------------------------------------------------------ D13 notice

    @Test
    fun exit_notice_maps_reason_and_restored_mode() {
        val notice = LoopExitNotice(
            songId = 7L,
            reason = LoopExitReason.CONFIRMATION_REVOKED,
            window = window,
            previousRepeatMode = 1,
            nextRepeatMode = 2,
            previousShuffleEnabled = false,
            nextShuffleEnabled = false,
            notifiedAtMs = 123L,
        )
        val ui = notice.toLoopNotice()
        assertEquals(LoopNoticeKind.DEMOTED, ui.kind)
        assertEquals(7L, ui.songId)
        assertTrue(ui.message.contains("分析结果已取消"))
        assertTrue(ui.message.contains("confirmation-revoked"))
        assertTrue(ui.message.contains("列表循环"))
        assertTrue(ui.id.contains("123"))
    }

    @Test
    fun delete_and_restore_failure_have_their_own_text() {
        val deleted = LoopExitNotice(
            7L, LoopExitReason.RESULT_DELETED, window, 1, 0, false, false, 1L,
        ).toLoopNotice()
        val restore = LoopExitNotice(
            7L, LoopExitReason.RESTORE_UNAVAILABLE, null, 1, 0, false, false, 2L,
        ).toLoopNotice()
        assertTrue(deleted.message.contains("result-deleted"))
        assertTrue(deleted.message.contains("分析结果已删除"))
        assertTrue(restore.message.contains("restore-unavailable"))
        assertTrue(restore.message.contains("未能恢复"))
    }

    @Test
    fun service_exit_notice_is_consumed_once_and_only_for_matching_song() {
        LoopClaimStore.clearExitNotice()
        val notice = LoopExitNotice(
            7L, LoopExitReason.RESULT_DELETED, window, 1, 0, false, false, 9L,
        )
        LoopClaimStore.publishExit(notice)

        assertNull("别的歌不能吃掉这条提示", ServiceLoopExitNoticeFeed.consumeFor(8L))
        assertEquals(notice, LoopClaimStore.exitNoticeFlow.value)

        val ui = ServiceLoopExitNoticeFeed.consumeFor(7L)
        assertTrue(ui!!.message.contains("result-deleted"))
        assertNull("consume 是一次性的", LoopClaimStore.consumeExitNotice())
        assertNull(ServiceLoopExitNoticeFeed.consumeFor(7L))
    }

    @Test
    fun null_song_never_consumes_exit_notice() {
        LoopClaimStore.clearExitNotice()
        LoopClaimStore.publishExit(
            LoopExitNotice(7L, LoopExitReason.RESULT_DELETED, window, 1, 0, false, false, 9L),
        )
        assertNull(ServiceLoopExitNoticeFeed.consumeFor(null))
        assertEquals(7L, LoopClaimStore.exitNoticeFlow.value?.songId)
        LoopClaimStore.clearExitNotice()
    }

    // ------------------------------------------------------------------ 只提示一次

    @Test
    fun center_dedupes_same_id_and_marks_after_shown() {
        val seen = FakeSeen()
        val center = LoopNoticeCenter(seen, now = { 1_000L })
        val notice = fallbackNotice(7L, claim())!!

        center.offer(notice)
        center.offer(notice)
        assertEquals(1, center.notices.value.size)
        assertEquals(notice, center.pendingFor(7L))
        assertNull(center.pendingFor(8L))

        center.markShown(notice.id)
        assertTrue(center.notices.value.isEmpty())
        assertTrue(seen.isSeen(notice.id, 1_000L))

        // 再次 offer（例如重进页面/进程重启后读到同一个 claim）不得复弹。
        center.offer(notice)
        assertTrue(center.notices.value.isEmpty())
    }

    @Test
    fun center_reoffers_after_ttl_expiry() {
        val seen = FakeSeen(LoopNoticeSeenLog(ttlMs = 1_000L))
        var now = 5_000L
        val center = LoopNoticeCenter(seen, now = { now })
        val notice = fallbackNotice(7L, claim())!!

        center.offer(notice)
        center.markShown(notice.id)
        center.offer(notice)
        assertTrue(center.notices.value.isEmpty())

        now += 1_001L
        center.offer(notice)
        assertEquals("TTL 过后允许再次提示（默认 90 天）", 1, center.notices.value.size)
    }

    @Test
    fun center_keeps_notices_for_other_songs_in_queue() {
        val center = LoopNoticeCenter(FakeSeen(), now = { 1L })
        val a = fallbackNotice(7L, claim())!!
        val b = fallbackNotice(8L, claim(songId = 8L))!!
        center.offer(a)
        center.offer(b)
        assertEquals(a, center.pendingFor(7L))
        assertEquals(b, center.pendingFor(8L))
        // A 页消费 A 之后，B 仍在。
        center.markShown(a.id)
        assertEquals(b, center.pendingFor(8L))
    }

    @Test
    fun center_queue_is_bounded() {
        val center = LoopNoticeCenter(FakeSeen(), now = { 1L }, maxQueue = 2)
        for (i in 1..3) {
            center.offer(
                LoopNotice(
                    id = "n" + i,
                    songId = i.toLong(),
                    kind = LoopNoticeKind.EXITED,
                    message = "m" + i,
                ),
            )
        }
        assertEquals(2, center.notices.value.size)
        assertNull(center.pendingFor(1L))
        assertEquals(2L, center.notices.value.first().songId)
    }

    // ------------------------------------------------------------------ 持久化日志

    @Test
    fun seen_log_roundtrips_and_respects_ttl() {
        val log = LoopNoticeSeenLog(maxEntries = 64, ttlMs = 1_000L)
        log.mark("a", 100L)
        log.mark("b", 200L)
        assertTrue(log.isSeen("a", 300L))
        assertEquals(2, log.size())

        val restored = LoopNoticeSeenLog(maxEntries = 64, ttlMs = 1_000L)
        restored.load(log.encode(), 300L)
        assertTrue(restored.isSeen("a", 300L))
        assertTrue(restored.isSeen("b", 300L))
        assertFalse("超过 TTL 视为没提示过", restored.isSeen("a", 1_101L))
    }

    @Test
    fun seen_log_is_bounded_to_64_entries() {
        val log = LoopNoticeSeenLog()
        for (i in 1..70) log.mark("n" + i, i.toLong())
        assertEquals(LoopNoticeSeenLog.MAX_ENTRIES, log.size())
        assertFalse("最老的键被淘汰", log.isSeen("n1", 100L))
        assertTrue(log.isSeen("n70", 100L))
    }

    @Test
    fun seen_log_load_drops_expired_and_keeps_newest_first_eviction() {
        val log = LoopNoticeSeenLog(maxEntries = 2, ttlMs = 100L)
        log.load("old\t1\nmid\t50\nnew\t90", nowMs = 120L)
        assertFalse(log.isSeen("old", 120L))
        assertTrue(log.isSeen("mid", 120L))
        assertTrue(log.isSeen("new", 120L))
        log.mark("newest", 120L)
        assertEquals(2, log.size())
        assertFalse("按插入顺序淘汰 mid", log.isSeen("mid", 120L))
    }

    @Test
    fun seen_log_clock_skew_does_not_count_as_seen() {
        val log = LoopNoticeSeenLog(ttlMs = 1_000L)
        log.mark("a", 500L)
        assertFalse("now < markedAt（时钟回拨）时不认为已提示", log.isSeen("a", 400L))
    }
}
