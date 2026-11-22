package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 00029：结果页轻量预热的块缓存只能被**同一窗口**消费。 */
class PreludeBlockPrewarmTest {

    @Before
    fun reset() {
        PreludeBlockPrewarm.resetForTest()
    }

    private fun key(aUs: Long = 1_000_000L, bUs: Long = 9_000_000L): PreludeBlockPrewarm.Key =
        PreludeBlockPrewarm.Key(
            songId = 42L,
            loopStartUs = aUs,
            loopEndUs = bUs,
            sampleRate = 44_100,
            channels = 2,
        )

    private fun block(index: Int = 0): PcmBlock =
        PcmBlock(index = index, startFrame = index * 44_100L, frames = 4, data = ByteArray(4 * 4))

    @Test
    fun `exact key is consumed once`() {
        val b = block()
        PreludeBlockPrewarm.put(key(), listOf(b))
        assertEquals(listOf(b), PreludeBlockPrewarm.take(key()))
        assertNull("取走后不能重复给第二个源", PreludeBlockPrewarm.take(key()))
    }

    @Test
    fun `different window or song does not match and keeps slot`() {
        val b = block()
        PreludeBlockPrewarm.put(key(), listOf(b))
        assertNull(PreludeBlockPrewarm.take(key(aUs = 2_000_000L, bUs = 9_000_000L)))
        assertNull(PreludeBlockPrewarm.take(key().copy(songId = 43L)))
        assertNull(PreludeBlockPrewarm.take(key().copy(sampleRate = 48_000)))
        assertEquals(listOf(b), PreludeBlockPrewarm.take(key()))
    }

    @Test
    fun `remove matches only exact key`() {
        val b = block()
        PreludeBlockPrewarm.put(key(), listOf(b))
        assertNull(PreludeBlockPrewarm.remove(key().copy(loopEndUs = 8_000_000L)))
        assertEquals(listOf(b), PreludeBlockPrewarm.remove(key()))
        assertTrue(PreludeBlockPrewarm.take(key()) == null)
    }
}
