package com.zjr.hesimusic.service

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #112 预览/主播放并发两路状态机（纯 JVM）。
 *
 * 共享闸门是一个进程级布尔，任何一路 pause 都可能停住另一路的 PCM 读取。这里用一个
 * 两路仿真器复现真机序列：预览在播 → 主播放起播（抢焦点）→ 两路交替 pause/play →
 * 最后"主播放停后预览重新起播"。判据：
 * - 主播放暂停不能关掉正在出声的预览；
 * - 预览暂停不能把仍应出声的主播放永久静音（主播放侧守卫负责顶开闸门）；
 * - 两路都不出声后才允许关门（省解码）。
 */
class SourceAudioArbiterTest {

    /** 两路播放 + 一个共享闸门的确定性仿真。 */
    private class Sim {
        var gatePaused = false
        var previewPlaying = false
        var mainPlaying = false
        var mainSourceItem = true

        fun previewPlay() {
            previewPlaying = true
            gatePaused = false // PreviewPlayer.play() 开头 resume()
        }

        fun previewPause() {
            previewPlaying = false
            gatePaused = true // PreviewPlayer.pause() 无条件 pause()
        }

        /** 主播放 LoopSessionPlayer.pause()：不再立即关门，延迟复核。 */
        fun mainPause() {
            mainPlaying = false
        }

        /** 主播放暂停后的延迟复核（400ms/1500ms 两次）。 */
        fun mainPauseRecheck(otherAudible: Boolean) {
            if (SourceAudioArbiter.shouldPauseGateAfterMainPause(
                    playWhenReady = false,
                    otherSourceAudible = otherAudible,
                )
            ) {
                gatePaused = true
            }
        }

        fun mainPlay() {
            mainPlaying = true
            gatePaused = false // LoopSessionPlayer.play() → resume()
        }

        /** 主播放侧 120ms 守卫。 */
        fun mainGateGuard() {
            val wants = SourceAudioArbiter.mainWantsToSound(
                playWhenReady = mainPlaying,
                playbackState = if (mainPlaying) Player.STATE_READY else Player.STATE_IDLE,
                isSourceItem = mainSourceItem,
            )
            if (SourceAudioArbiter.shouldResumeGate(wants, gatePaused)) gatePaused = false
        }

        fun previewWritesFrames(): Boolean = previewPlaying && !gatePaused
        fun mainWritesFrames(): Boolean = mainPlaying && !gatePaused
    }

    @Test
    fun previewPauseWhileMainPlays_isRecoveredByMainGuard() {
        val sim = Sim()
        sim.previewPlay()
        sim.mainPlay()
        assertTrue(sim.mainWritesFrames())

        // 预览 pause 把闸门关了（它的实现无条件 pause）→ 主播放被静音。
        sim.previewPause()
        assertFalse("复现：共享闸门被预览关掉", sim.mainWritesFrames())

        // 主播放侧守卫在 120ms 内顶开。
        sim.mainGateGuard()
        assertFalse(sim.gatePaused)
        assertTrue("主播放必须恢复出声（不允许永久哑）", sim.mainWritesFrames())
    }

    @Test
    fun mainPauseWhilePreviewAudible_doesNotSilencePreview() {
        val sim = Sim()
        sim.previewPlay()
        sim.mainPause()

        // 延迟复核时预览还在出声 → 不允许关门。
        sim.mainPauseRecheck(otherAudible = true)
        assertFalse("主播放暂停不能把预览静音", sim.gatePaused)
        assertTrue(sim.previewWritesFrames())
    }

    @Test
    fun mainPauseWhenNothingElseAudible_pausesGateToStopPrefetch() {
        val sim = Sim()
        sim.previewPlay()
        sim.mainPlay()
        sim.previewPause()
        sim.mainPause()
        sim.mainPauseRecheck(otherAudible = false)
        assertTrue("两路都停了才允许关闸门（省解码）", sim.gatePaused)
        assertFalse(sim.previewWritesFrames())
        assertFalse(sim.mainWritesFrames())
    }

    @Test
    fun previewCanResumeAfterMainStops() {
        val sim = Sim()
        sim.previewPlay()
        sim.mainPlay()
        sim.previewPause() // 焦点被主播放抢走
        sim.mainPause()    // 用户暂停主播放
        sim.mainPauseRecheck(otherAudible = false)
        assertTrue(sim.gatePaused)

        // 用户重新点预览（自由/仅循环段）→ PreviewPlayer.play() 开头 resume()。
        sim.previewPlay()
        assertTrue("主播放停后预览必须能重新出声", sim.previewWritesFrames())
    }

    @Test
    fun mainWantsToSound_isSourceOnlyAndNotIdle() {
        assertTrue(SourceAudioArbiter.mainWantsToSound(true, Player.STATE_READY, true))
        assertTrue(SourceAudioArbiter.mainWantsToSound(true, Player.STATE_BUFFERING, true))
        assertFalse(SourceAudioArbiter.mainWantsToSound(true, Player.STATE_READY, false))
        assertFalse(SourceAudioArbiter.mainWantsToSound(false, Player.STATE_READY, true))
        assertFalse(SourceAudioArbiter.mainWantsToSound(true, Player.STATE_IDLE, true))
    }

    @Test
    fun resumeGate_onlyWhenWantedAndPaused() {
        assertTrue(SourceAudioArbiter.shouldResumeGate(mainWantsToSound = true, gatePaused = true))
        assertFalse(SourceAudioArbiter.shouldResumeGate(mainWantsToSound = false, gatePaused = true))
        assertFalse(SourceAudioArbiter.shouldResumeGate(mainWantsToSound = true, gatePaused = false))
    }
}
