package com.zjr.hesimusic.loop.audio.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 00029 方案 C：A 交界自校准默认关闭；关闭时偏移恒 0，不启动后台线程。 */
class RingBandSeamCalibrationSwitchTest {

    @Test
    fun productionSwitchIsOff() {
        assertFalse(RingBandExtractor.SEAM_CALIBRATION_ENABLED)
    }

    @Test
    fun disabledSwitchNeverAppliesOffset() {
        assertEquals(0, RingBandExtractor.seamOffsetForRead(false, true, 213))
        assertEquals(0, RingBandExtractor.seamOffsetForRead(false, false, -187))
    }

    @Test
    fun enabledSwitchAppliesOnlyWhenCalibrationSucceeded() {
        assertEquals(213, RingBandExtractor.seamOffsetForRead(true, true, 213))
        assertEquals(0, RingBandExtractor.seamOffsetForRead(true, false, 213))
    }
}
