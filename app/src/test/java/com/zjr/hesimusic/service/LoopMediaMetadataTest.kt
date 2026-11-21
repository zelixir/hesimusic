package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D10 的纯逻辑：MediaSession metadata 装饰 + session extras。
 *
 * 判据（与 lead 裁决一致）：
 * - 通知/锁屏正文只显示“无缝循环”，不显示 A..B / 动态 phase（避免暂停时过期）；
 * - A..B / phase / 回退原因放 session extras；
 * - 原始 artist 进 extras，App 内 UI 仍显示原始值（Robolectric 测试另证）；
 * - 普通播放（无 claim）原样返回，不产生 metadata 事件。
 */
class LoopMediaMetadataTest {

    private val window = LoopWindow(7L, 10_000_000L, 50_000_000L)

    private fun claim(
        sourceActive: Boolean = true,
        window: LoopWindow? = this.window,
        reason: LoopSourceFallback.Reason? = null,
        songId: Long? = 7L,
    ) = LoopClaim(
        songId = songId,
        window = window,
        sourceActive = sourceActive,
        fallbackReason = reason,
    )

    @Test
    fun status_for_active_claim_has_window_but_no_reason() {
        val status = LoopMediaMetadata.statusFor(claim())
        assertTrue(status!!.active)
        assertEquals(10_000L, status.startMs)
        assertEquals(50_000L, status.endMs)
        assertNull(status.reasonCode)
    }

    @Test
    fun status_for_fallback_claim_carries_reason_code() {
        val status = LoopMediaMetadata.statusFor(
            claim(sourceActive = false, reason = LoopSourceFallback.Reason.RING_TOO_LARGE),
        )
        assertFalse(status!!.active)
        assertEquals("ring-too-large", status.reasonCode)
        assertEquals(10_000L, status.startMs)
    }

    @Test
    fun status_for_window_invalid_without_window_still_visible() {
        val status = LoopMediaMetadata.statusFor(
            claim(sourceActive = false, window = null, reason = LoopSourceFallback.Reason.WINDOW_INVALID),
        )
        assertFalse(status!!.active)
        assertNull(status.startMs)
        assertEquals("window-invalid", status.reasonCode)
    }

    @Test
    fun status_for_plain_playback_is_null() {
        assertNull(LoopMediaMetadata.statusFor(LoopClaim.none(7L)))
        assertNull(LoopMediaMetadata.statusFor(claim(sourceActive = false, reason = null)))
    }

    @Test
    fun active_artist_is_decorated_without_range_or_phase() {
        val status = LoopMediaMetadata.statusFor(claim())!!
        val artist = LoopMediaMetadata.decoratedArtist("Original", status)
        assertEquals("Original · 无缝循环", artist)
        assertFalse("锁屏不显示区间（避免暂停时过期）", artist!!.contains("0:10"))
        assertFalse(artist.contains("prelude"))
    }

    @Test
    fun blank_artist_uses_loop_marker_only() {
        val status = LoopMediaMetadata.statusFor(claim())!!
        assertEquals("无缝循环", LoopMediaMetadata.decoratedArtist("", status))
        assertEquals("无缝循环", LoopMediaMetadata.decoratedArtist(null, status))
    }

    @Test
    fun fallback_artist_shows_reason_code() {
        val status = LoopMediaMetadata.statusFor(
            claim(sourceActive = false, reason = LoopSourceFallback.Reason.RING_TOO_LARGE),
        )!!
        assertEquals(
            "Original · 未进入无缝循环（ring-too-large）",
            LoopMediaMetadata.decoratedArtist("Original", status),
        )
    }

    @Test
    fun already_decorated_artist_is_idempotent() {
        val status = LoopMediaMetadata.statusFor(claim())!!
        assertNull(LoopMediaMetadata.decoratedArtist("Original · 无缝循环", status))
    }

    @Test
    fun plain_playback_never_touches_artist() {
        assertNull(LoopMediaMetadata.decoratedArtist("Original", null))
        assertTrue(LoopMediaMetadata.extrasFor(null, "Original").isEmpty())
    }

    @Test
    fun extras_keep_raw_artist_and_structured_loop_info() {
        val status = LoopMediaMetadata.statusFor(claim())!!
        val extras = LoopMediaMetadata.extrasFor(status, "Original")
        assertEquals("Original", extras[LoopMediaMetadata.EXTRA_RAW_ARTIST])
        assertEquals(true, extras[LoopMediaMetadata.EXTRA_LOOP_ACTIVE])
        assertEquals(10_000L, extras[LoopMediaMetadata.EXTRA_LOOP_START_MS])
        assertEquals(50_000L, extras[LoopMediaMetadata.EXTRA_LOOP_END_MS])
        assertNull(extras[LoopMediaMetadata.EXTRA_LOOP_REASON])
    }

    @Test
    fun fallback_extras_carry_reason() {
        val status = LoopMediaMetadata.statusFor(
            claim(sourceActive = false, reason = LoopSourceFallback.Reason.FILE_MISSING),
        )!!
        val extras = LoopMediaMetadata.extrasFor(status, "Original")
        assertEquals(false, extras[LoopMediaMetadata.EXTRA_LOOP_ACTIVE])
        assertEquals("file-missing", extras[LoopMediaMetadata.EXTRA_LOOP_REASON])
    }

    @Test
    fun session_extras_active_includes_range_and_phase() {
        val status = LoopMediaMetadata.statusFor(claim())!!
        val extras = LoopSessionExtras.extrasFor(status, "loop")
        assertEquals(true, extras[LoopSessionExtras.KEY_LOOP_ACTIVE])
        assertEquals(10_000L, extras[LoopSessionExtras.KEY_LOOP_START_MS])
        assertEquals(50_000L, extras[LoopSessionExtras.KEY_LOOP_END_MS])
        assertEquals("loop", extras[LoopSessionExtras.KEY_LOOP_PHASE])
        assertNull(extras[LoopSessionExtras.KEY_LOOP_REASON])
    }

    @Test
    fun session_extras_fallback_has_reason_but_no_phase() {
        val status = LoopMediaMetadata.statusFor(
            claim(sourceActive = false, reason = LoopSourceFallback.Reason.BUILD_FAILED),
        )!!
        val extras = LoopSessionExtras.extrasFor(status, "loop")
        assertEquals(false, extras[LoopSessionExtras.KEY_LOOP_ACTIVE])
        assertEquals("build-failed", extras[LoopSessionExtras.KEY_LOOP_REASON])
        assertNull(extras[LoopSessionExtras.KEY_LOOP_PHASE])
    }

    @Test
    fun session_extras_plain_playback_only_clears_active() {
        val extras = LoopSessionExtras.extrasFor(null, null)
        assertEquals(false, extras[LoopSessionExtras.KEY_LOOP_ACTIVE])
        assertEquals(1, extras.size)
    }
}
