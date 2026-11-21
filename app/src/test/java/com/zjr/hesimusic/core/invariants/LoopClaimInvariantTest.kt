package com.zjr.hesimusic.core.invariants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 00027：claimActive && !sourceActive（以及 claimed && !sourceActive）必须被记录。 */
class LoopClaimInvariantTest {

    private val evaluator = LoopClaimInvariantEvaluator()

    private fun sample(
        songId: Long? = 7L,
        claimed: Boolean = true,
        claimActive: Boolean = false,
        sourceActive: Boolean = false,
        reason: String? = null,
        atMs: Long = 1_000L,
    ) = LoopClaimInvariantSample(
        atMs = atMs,
        songId = songId,
        claimed = claimed,
        claimActive = claimActive,
        sourceActive = sourceActive,
        reason = reason,
    )

    @Test
    fun `every fallback reason records a claim without source`() {
        for (reason in listOf(
            "window-invalid",
            "file-missing",
            "format-unsupported",
            "ring-too-large",
            "build-failed",
        )) {
            val event = evaluator.onSample(sample(reason = reason))
            assertEquals(reason, InvariantCode.LOOP_CLAIM_WITHOUT_SOURCE, event?.code)
            assertEquals(true, event?.message?.contains("reason=" + reason))
        }
    }

    @Test
    fun `claimActive without source is recorded even if claimed is false`() {
        // 防御性：构造一个本不该出现的状态，验证判定器不依赖 claimed。
        val event = evaluator.onSample(sample(claimed = false, claimActive = true, sourceActive = false))
        assertEquals(InvariantCode.LOOP_CLAIM_WITHOUT_SOURCE, event?.code)
    }

    @Test
    fun `a backed claim or a plain song emits nothing`() {
        assertNull(evaluator.onSample(sample(claimed = true, claimActive = true, sourceActive = true)))
        assertNull(evaluator.onSample(sample(claimed = false, claimActive = false, sourceActive = false)))
        assertNull(evaluator.onSample(sample(claimed = false, claimActive = false, sourceActive = true)))
    }

    @Test
    fun `repeated identical samples are deduplicated and reset re-arms`() {
        val first = evaluator.onSample(sample(reason = "build-failed", atMs = 1_000L))
        assertEquals(InvariantCode.LOOP_CLAIM_WITHOUT_SOURCE, first?.code)
        assertNull("同一签名 500ms 重复采样不再刷屏", evaluator.onSample(sample(reason = "build-failed", atMs = 1_500L)))

        // reason 变化 = 新证据，必须再报。
        val changed = evaluator.onSample(sample(reason = "ring-too-large", atMs = 2_000L))
        assertEquals(InvariantCode.LOOP_CLAIM_WITHOUT_SOURCE, changed?.code)
        assertEquals(true, changed?.message?.contains("ring-too-large"))

        evaluator.reset()
        assertEquals(
            InvariantCode.LOOP_CLAIM_WITHOUT_SOURCE,
            evaluator.onSample(sample(reason = "ring-too-large", atMs = 3_000L))?.code,
        )
    }
}
