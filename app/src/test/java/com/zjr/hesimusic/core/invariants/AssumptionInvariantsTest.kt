package com.zjr.hesimusic.core.invariants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12.2 每条运行时断言至少两个用例：触发 / 不触发。
 *
 * 这里只测纯判定逻辑（不依赖 Android / media3）；采样与真机注入链路由
 * app/src/debug/.../PlaybackInvariantMonitor 负责，注入命令见
 * docs/runtime-invariants-undefined.md。
 */
class AssumptionInvariantsTest {

    // ------------------------------------------------------------ A-025

    private fun periodSample(
        atMs: Long = 2_000L,
        preparedAtMs: Long = 1_000L,
        continueLoadingAfterPrepare: Long = 0L,
        continueLoadingAtManualKick: Long = 0L,
        manualKicks: Long = 0L,
    ) = PeriodContinueLoadingSample(
        atMs = atMs,
        preparedAtMs = preparedAtMs,
        continueLoadingAfterPrepare = continueLoadingAfterPrepare,
        continueLoadingAtManualKick = continueLoadingAtManualKick,
        manualKicks = manualKicks,
    )

    @Test
    fun periodContinueLoadingMissingTriggersAfterGrace() {
        val event = PeriodContinueLoadingEvaluator().evaluate(periodSample())
        assertEquals(AssumptionInvariantCode.PERIOD_CONTINUE_LOADING_MISSING, event?.code)
    }

    @Test
    fun periodContinueLoadingDoesNotTriggerWhenMedia3AlreadyCalled() {
        val evaluator = PeriodContinueLoadingEvaluator()
        assertNull(evaluator.evaluate(periodSample(continueLoadingAfterPrepare = 1L)))
    }

    @Test
    fun periodContinueLoadingDoesNotTriggerInsideGraceWindow() {
        val evaluator = PeriodContinueLoadingEvaluator()
        assertNull(evaluator.evaluate(periodSample(atMs = 1_200L)))
    }

    @Test
    fun periodContinueLoadingReportsOncePerPreparedPeriod() {
        val evaluator = PeriodContinueLoadingEvaluator()
        assertNotNull(evaluator.evaluate(periodSample()))
        assertNull(evaluator.evaluate(periodSample()))
    }

    // ------------------------------------------------------------ A-037

    private fun extractorSample(
        generation: Long = 1L,
        tracksCreated: Int = 1,
        endTracksSeen: Boolean = false,
        seekMapBeforeEndTracks: Boolean = true,
        trackAfterEndTracksCount: Long = 0L,
        seekMapAfterEndTracksCount: Long = 0L,
        seekMapMissingCount: Long = 0L,
    ) = ExtractorTrackSample(
        atMs = 1_000L,
        generation = generation,
        tracksCreated = tracksCreated,
        endTracksSeen = endTracksSeen,
        seekMapBeforeEndTracks = seekMapBeforeEndTracks,
        trackAfterEndTracksCount = trackAfterEndTracksCount,
        seekMapAfterEndTracksCount = seekMapAfterEndTracksCount,
        seekMapMissingCount = seekMapMissingCount,
    )

    @Test
    fun extractorDuplicateTrackTriggers() {
        val event = ExtractorTrackEvaluator().evaluate(extractorSample(tracksCreated = 2))
        assertEquals(AssumptionInvariantCode.EXTRACTOR_TRACK_AFTER_END_TRACKS, event?.code)
    }

    @Test
    fun extractorTrackAfterEndTracksTriggers() {
        val event = ExtractorTrackEvaluator().evaluate(
            extractorSample(endTracksSeen = true, trackAfterEndTracksCount = 1L),
        )
        assertEquals(AssumptionInvariantCode.EXTRACTOR_TRACK_AFTER_END_TRACKS, event?.code)
    }

    @Test
    fun extractorSeekMapMissingAtEndTracksTriggers() {
        val event = ExtractorTrackEvaluator().evaluate(
            extractorSample(endTracksSeen = true, seekMapMissingCount = 1L),
        )
        assertEquals(AssumptionInvariantCode.EXTRACTOR_TRACK_AFTER_END_TRACKS, event?.code)
    }

    @Test
    fun extractorHealthyLifecycleDoesNotTrigger() {
        val evaluator = ExtractorTrackEvaluator()
        assertNull(evaluator.evaluate(extractorSample(endTracksSeen = true)))
    }

    // ------------------------------------------------------------ A-049

    private fun emptySample(
        total: Long = 1L,
        replaying: Long = 0L,
        paused: Long = 0L,
        active: Long = 1L,
    ) = EmptyBufferSample(
        atMs = 1_000L,
        total = total,
        whileReplaying = replaying,
        whilePaused = paused,
        whileActive = active,
    )

    @Test
    fun emptyBufferWhilePlayingTriggers() {
        val event = EmptyBufferEvaluator().evaluate(emptySample())
        assertEquals(AssumptionInvariantCode.SINK_EMPTY_BUFFER_WHILE_PLAYING, event?.code)
    }

    @Test
    fun emptyBufferWhileReplayingDoesNotTrigger() {
        assertNull(EmptyBufferEvaluator().evaluate(emptySample(replaying = 1L, active = 0L)))
    }

    @Test
    fun emptyBufferWhilePausedDoesNotTrigger() {
        assertNull(EmptyBufferEvaluator().evaluate(emptySample(paused = 1L, active = 0L)))
    }

    @Test
    fun emptyBufferReportsOnlyNewOccurrences() {
        val evaluator = EmptyBufferEvaluator()
        assertNotNull(evaluator.evaluate(emptySample()))
        assertNull(evaluator.evaluate(emptySample()))
    }

    // ------------------------------------------------------------ A-055

    private fun configureSample(
        configureCount: Long = 3L,
        windowSplitCount: Long = 3L,
        jumpCount: Long = 0L,
        lastJumpUs: Long = 0L,
    ) = ConfigureSplitSample(
        atMs = 1_000L,
        configureCount = configureCount,
        windowSplitCount = windowSplitCount,
        configurePtsJumpCount = jumpCount,
        lastConfigurePtsJumpUs = lastJumpUs,
    )

    @Test
    fun configureShortfallTriggers() {
        val event = ConfigureSplitEvaluator().evaluate(
            configureSample(configureCount = 2L, windowSplitCount = 3L),
        )
        assertEquals(AssumptionInvariantCode.SINK_CONFIGURE_SPLIT_MISMATCH, event?.code)
    }

    @Test
    fun configureFirstPtsJumpTriggers() {
        val event = ConfigureSplitEvaluator().evaluate(
            configureSample(jumpCount = 1L, lastJumpUs = 500_000L),
        )
        assertEquals(AssumptionInvariantCode.SINK_CONFIGURE_SPLIT_MISMATCH, event?.code)
    }

    @Test
    fun configureMatchingWindowSplitsDoesNotTrigger() {
        assertNull(ConfigureSplitEvaluator().evaluate(configureSample()))
    }

    @Test
    fun configureShortfallInsideWindowSplitGraceDoesNotTrigger() {
        assertNull(
            ConfigureSplitEvaluator().evaluate(
                ConfigureSplitSample(
                    atMs = 1_000L,
                    configureCount = 2L,
                    windowSplitCount = 3L,
                    configurePtsJumpCount = 0L,
                    lastConfigurePtsJumpUs = 0L,
                    lastWindowSplitAtMs = 500L,
                ),
            ),
        )
    }

    @Test
    fun configureReportsNewBadCountsOnly() {
        val evaluator = ConfigureSplitEvaluator()
        assertNotNull(evaluator.evaluate(configureSample(configureCount = 2L, windowSplitCount = 3L)))
        assertNull(evaluator.evaluate(configureSample(configureCount = 2L, windowSplitCount = 3L)))
        assertNotNull(evaluator.evaluate(configureSample(configureCount = 3L, windowSplitCount = 4L)))
    }

    // ------------------------------------------------------------ A-091

    private fun frameSample(
        sampleRate: Int = 48_000,
        frameDurationUs: Long = 21L,
        roundtripErrorUs: Long = 0L,
        abDeltas: List<Long> = listOf(48_000L, 48_000L, 48_000L),
    ) = FrameUsRoundtripSample(
        atMs = 1_000L,
        sampleRate = sampleRate,
        frameDurationUs = frameDurationUs,
        roundtripErrorUs = roundtripErrorUs,
        abFrameDeltas = abDeltas,
    )

    @Test
    fun frameUsRoundtripErrorAtOneFrameTriggers() {
        val event = FrameUsRoundtripEvaluator().evaluate(
            frameSample(roundtripErrorUs = 21L),
        )
        assertEquals(AssumptionInvariantCode.TIMELINE_FRAME_US_ROUNDTRIP_DRIFT, event?.code)
    }

    @Test
    fun frameUsVaryingAbDeltasTriggers() {
        val event = FrameUsRoundtripEvaluator().evaluate(
            frameSample(abDeltas = listOf(48_000L, 48_001L, 48_000L)),
        )
        assertEquals(AssumptionInvariantCode.TIMELINE_FRAME_US_ROUNDTRIP_DRIFT, event?.code)
    }

    @Test
    fun frameUsHealthyRoundtripDoesNotTrigger() {
        assertNull(FrameUsRoundtripEvaluator().evaluate(frameSample(roundtripErrorUs = 5L)))
    }

    @Test
    fun frameUsProductionMathStaysBelowOneFrame() {
        val rates = listOf(44_100, 48_000, 96_000)
        val anchors = listOf(0L, 1_000_000L, 1_000_001L, 12_345_678L, 200_000_000L)
        for (rate in rates) {
            val frameUs = AssumptionFrameUs.frameDurationUs(rate)
            assertTrue("frame duration must be positive for rate=$rate", frameUs > 0L)
            for (anchor in anchors) {
                val error = AssumptionFrameUs.roundtripErrorUs(anchor, rate)
                assertTrue(
                    "roundtrip error=$error frameUs=$frameUs rate=$rate anchor=$anchor",
                    error < frameUs,
                )
            }
            val delta = AssumptionFrameUs.abFrameDelta(1_000_000L, 5_000_001L, rate)
            assertEquals(
                delta,
                AssumptionFrameUs.abFrameDelta(1_000_000L, 5_000_001L, rate),
            )
        }
    }

    // ------------------------------------------------------------ A-093

    private fun durationSample(
        durationUs: Long,
        branch: DurationBranch,
        projectedUs: Long,
        displayedPositionUs: Long = 0L,
        windowActive: Boolean = true,
    ) = ProjectionDurationSample(
        atMs = 1_000L,
        windowActive = windowActive,
        durationUs = durationUs,
        branch = branch,
        projectedUs = projectedUs,
        displayedPositionUs = displayedPositionUs,
    )

    @Test
    fun zeroDurationTreatedAsFallbackTriggers() {
        val event = ProjectionDurationEvaluator().evaluate(
            durationSample(0L, DurationBranch.FALLBACK, 0L),
        )
        assertEquals(AssumptionInvariantCode.PROJECTION_DURATION_BRANCH_MISMATCH, event?.code)
    }

    @Test
    fun unknownDurationClampedAsPositiveTriggers() {
        val event = ProjectionDurationEvaluator().evaluate(
            durationSample(-1L, DurationBranch.CLAMP, 1_000L),
        )
        assertEquals(AssumptionInvariantCode.PROJECTION_DURATION_BRANCH_MISMATCH, event?.code)
    }

    @Test
    fun unknownDurationFallbackDoesNotTrigger() {
        assertNull(
            ProjectionDurationEvaluator().evaluate(
                durationSample(-1L, DurationBranch.FALLBACK, 0L),
            ),
        )
    }

    @Test
    fun positiveDurationClampDoesNotTrigger() {
        assertNull(
            ProjectionDurationEvaluator().evaluate(
                durationSample(
                    durationUs = 200_000_000L,
                    branch = DurationBranch.CLAMP,
                    projectedUs = 200_000_000L,
                    displayedPositionUs = 10_000L,
                ),
            ),
        )
    }

    @Test
    fun displayPositionBeyondDurationTriggers() {
        val event = ProjectionDurationEvaluator().evaluate(
            durationSample(
                durationUs = 200_000_000L,
                branch = DurationBranch.CLAMP,
                projectedUs = 200_000_000L,
                displayedPositionUs = 200_000_001L,
            ),
        )
        assertEquals(AssumptionInvariantCode.PROJECTION_DURATION_BRANCH_MISMATCH, event?.code)
    }

    // ------------------------------------------------------------ A-NEW-01

    private fun positionSample(
        offsetUs: Long = 1_000_000_000_000L,
        lastDelegatePtsUs: Long = 1_000_000_005_000_000L,
        rawPositionUs: Long = 5_000_000L,
        returnedPositionUs: Long = 5_000_000L,
    ) = SinkPositionDomainSample(
        atMs = 1_000L,
        offsetUs = offsetUs,
        lastDelegatePtsUs = lastDelegatePtsUs,
        rawPositionUs = rawPositionUs,
        returnedPositionUs = returnedPositionUs,
    )

    @Test
    fun mediaDomainPositionWithOffsetDomainPtsTriggers() {
        val event = SinkPositionDomainEvaluator().evaluate(positionSample())
        assertEquals(AssumptionInvariantCode.SINK_POSITION_DOMAIN_MISMATCH, event?.code)
    }

    @Test
    fun sameOffsetDomainDoesNotTrigger() {
        assertNull(
            SinkPositionDomainEvaluator().evaluate(
                positionSample(returnedPositionUs = 1_000_000_005_100_000L),
            ),
        )
    }

    @Test
    fun notSetPositionDoesNotTrigger() {
        assertNull(
            SinkPositionDomainEvaluator().evaluate(positionSample(returnedPositionUs = 0L)),
        )
    }

    @Test
    fun positionDomainReportsOnceUntilItRecovers() {
        val evaluator = SinkPositionDomainEvaluator()
        assertNotNull(evaluator.evaluate(positionSample()))
        assertNull(evaluator.evaluate(positionSample()))
        assertNull(evaluator.evaluate(positionSample(returnedPositionUs = 1_000_000_005_200_000L)))
        assertNotNull(evaluator.evaluate(positionSample()))
    }

    // ------------------------------------------------------------ A-NEW-03

    private fun seekSample(
        requestedMs: Long = 10_000L,
        positionAtRequestMs: Long = 10_000L,
        positionNowMs: Long = 10_000L,
        discontinuitySeen: Boolean = false,
        elapsedSinceRequestMs: Long = 2_000L,
        overlayPending: Boolean = true,
        requestAtMs: Long = 500L,
    ) = SamePositionSeekSample(
        atMs = 2_500L,
        requestAtMs = requestAtMs,
        requestedMs = requestedMs,
        positionAtRequestMs = positionAtRequestMs,
        positionNowMs = positionNowMs,
        discontinuitySeen = discontinuitySeen,
        elapsedSinceRequestMs = elapsedSinceRequestMs,
        overlayPending = overlayPending,
    )

    @Test
    fun samePositionOverlayStillPendingAfterTimeoutTriggers() {
        val event = SamePositionSeekEvaluator().evaluate(seekSample())
        assertEquals(
            AssumptionInvariantCode.SEEK_SAME_POSITION_DISCONTINUITY_TIMEOUT,
            event?.code,
        )
    }

    @Test
    fun differentPositionSeekDoesNotTrigger() {
        assertNull(
            SamePositionSeekEvaluator().evaluate(
                seekSample(positionAtRequestMs = 5_000L),
            ),
        )
    }

    @Test
    fun samePositionWithDiscontinuityDoesNotTrigger() {
        assertNull(
            SamePositionSeekEvaluator().evaluate(seekSample(discontinuitySeen = true)),
        )
    }

    @Test
    fun samePositionReachedWithoutOverlayDoesNotTrigger() {
        assertNull(
            SamePositionSeekEvaluator().evaluate(seekSample(overlayPending = false)),
        )
    }

    @Test
    fun samePositionInsideTimeoutDoesNotTrigger() {
        assertNull(
            SamePositionSeekEvaluator().evaluate(seekSample(elapsedSinceRequestMs = 500L)),
        )
    }

    // ------------------------------------------------------------ A-NEW-07

    private fun prepareErrorSample(
        atMs: Long = 4_500L,
        injectedAtMs: Long = 1_000L,
        playerErrorAtMs: Long = 0L,
    ) = PrepareErrorSample(
        atMs = atMs,
        injectedAtMs = injectedAtMs,
        playerErrorAtMs = playerErrorAtMs,
    )

    @Test
    fun prepareErrorNotSurfacedAfterDeadlineTriggers() {
        val event = PrepareErrorEvaluator().evaluate(prepareErrorSample())
        assertEquals(AssumptionInvariantCode.ERROR_PREPARE_NOT_SURFACED, event?.code)
    }

    @Test
    fun prepareErrorSurfacedInsideDeadlineDoesNotTrigger() {
        assertNull(
            PrepareErrorEvaluator().evaluate(prepareErrorSample(playerErrorAtMs = 2_500L)),
        )
    }

    @Test
    fun prepareErrorBeforeDeadlineDoesNotTrigger() {
        assertNull(PrepareErrorEvaluator().evaluate(prepareErrorSample(atMs = 2_000L)))
    }

    // ------------------------------------------------------------ A-NEW-09

    private fun configurePendingSample(
        configureCount: Long = 1L,
        withoutFlush: Long = 1L,
        dropped: Long = 1L,
        continued: Long = 0L,
    ) = ConfigurePendingReplaySample(
        atMs = 1_000L,
        configureCount = configureCount,
        configureWithoutFlushCount = withoutFlush,
        pendingReplayDroppedCount = dropped,
        pendingReplayContinuedCount = continued,
    )

    @Test
    fun configureDroppingPendingReplayTriggers() {
        val event = ConfigurePendingReplayEvaluator().evaluate(configurePendingSample())
        assertEquals(
            AssumptionInvariantCode.SINK_CONFIGURE_DROPPED_PENDING_REPLAY,
            event?.code,
        )
    }

    @Test
    fun configureContinuingPendingReplayDoesNotTrigger() {
        assertNull(
            ConfigurePendingReplayEvaluator().evaluate(
                configurePendingSample(dropped = 0L, continued = 1L),
            ),
        )
    }

    @Test
    fun configurePendingReplayReportsOnlyNewDrops() {
        val evaluator = ConfigurePendingReplayEvaluator()
        assertNotNull(evaluator.evaluate(configurePendingSample()))
        assertNull(evaluator.evaluate(configurePendingSample()))
    }

    // ------------------------------------------------------------ A-NEW-11

    private fun commandsSample(
        available: Set<String> = ExpectedControllerCommands.SET,
        connected: Boolean = true,
        generation: Long = 1L,
    ) = AvailableCommandsSample(
        atMs = 1_000L,
        controllerGeneration = generation,
        connected = connected,
        available = available,
    )

    @Test
    fun missingSeekToNextCommandTriggers() {
        val event = AvailableCommandsEvaluator().evaluate(
            commandsSample(available = ExpectedControllerCommands.SET - "seek-to-next"),
        )
        assertEquals(
            AssumptionInvariantCode.CONTROLLER_AVAILABLE_COMMANDS_MISSING,
            event?.code,
        )
    }

    @Test
    fun allExpectedCommandsPresentDoesNotTrigger() {
        assertNull(AvailableCommandsEvaluator().evaluate(commandsSample()))
    }

    @Test
    fun disconnectedControllerDoesNotTrigger() {
        assertNull(
            AvailableCommandsEvaluator().evaluate(
                commandsSample(available = emptySet(), connected = false),
            ),
        )
    }

    @Test
    fun missingCommandsReportAgainForNewController() {
        val evaluator = AvailableCommandsEvaluator()
        val missing = ExpectedControllerCommands.SET - "seek-to-next"
        assertNotNull(evaluator.evaluate(commandsSample(available = missing, generation = 1L)))
        assertNull(evaluator.evaluate(commandsSample(available = missing, generation = 1L)))
        assertNotNull(evaluator.evaluate(commandsSample(available = missing, generation = 2L)))
    }

    // ------------------------------------------------------------ A-NEW-12

    private fun notificationSample(
        pausedForMs: Long = 6_000L,
        present: Boolean = false,
    ) = ServiceNotificationSample(
        atMs = 10_000L,
        pausedForMs = pausedForMs,
        notificationPresent = present,
    )

    @Test
    fun notificationGoneAfterPauseTriggers() {
        val event = ServiceNotificationAliveEvaluator().evaluate(notificationSample())
        assertEquals(
            AssumptionInvariantCode.SERVICE_NOTIFICATION_GONE_AFTER_PAUSE,
            event?.code,
        )
    }

    @Test
    fun notificationPresentAfterPauseDoesNotTrigger() {
        assertNull(
            ServiceNotificationAliveEvaluator().evaluate(notificationSample(present = true)),
        )
    }

    @Test
    fun notificationCheckBeforeThresholdDoesNotTrigger() {
        assertNull(
            ServiceNotificationAliveEvaluator().evaluate(notificationSample(pausedForMs = 2_000L)),
        )
    }

    @Test
    fun notificationReArmsAfterPlaybackResumes() {
        val evaluator = ServiceNotificationAliveEvaluator()
        assertNotNull(evaluator.evaluate(notificationSample()))
        assertNull(evaluator.evaluate(notificationSample(pausedForMs = 0L)))
        assertNotNull(evaluator.evaluate(notificationSample()))
    }

    // ------------------------------------------------------------ registry contract

    @Test
    fun everyDeclarationHasAUniqueCode() {
        assertEquals(12, AssumptionInvariantCode.ALL.size)
        assertEquals(
            AssumptionInvariantCode.ALL.size,
            AssumptionInvariantCode.ALL.toSet().size,
        )
        for (code in AssumptionInvariantCode.ALL) {
            assertTrue("code must be domain-what: $code", code.contains("-"))
        }
    }
}
