package com.zjr.hesimusic.ui.player.state

import com.zjr.hesimusic.core.statemachine.MatrixTest
import com.zjr.hesimusic.core.statemachine.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs

/**
 * 播放页「状态 × 事件」全矩阵测试 + 5 条规则的命名回归用例。
 *
 * 矩阵规模：10 个代表状态 × 19 个事件夹具 = 190 个参数化用例。
 *
 * 与 00011（分析结果页）不同，这里额外用 [MatrixTest] 做**框架级**校验：
 * 非法组合必须 Rejected 且 state 实例不变、Applied 不得落出合法状态域、
 * 每次转换后所有不变量成立、effect 类型必须在声明集合内。
 * 本文件另有一套**显式期望表**：每个单元格都必须登记（缺失即测试失败），
 * 新增 state/event 不更新矩阵会先编译失败（allowed 对 sealed 类型穷尽 when），
 * 再被期望表/框架校验拦下。
 */
@RunWith(Parameterized::class)
class PlayerPageReducerTest(
    private val case: PlayerPageMatrix.Case,
) {

    @Test
    fun matrixCaseMatchesExplicitExpectation() {
        val actual = PlayerPageReducer.reduce(case.state, case.event)
        when (val expected = case.expected) {
            is PlayerPageMatrix.Expectation.Applied -> {
                assertTrue("$case：期望 Applied，实际 $actual", actual is Transition.Applied)
                val applied = actual as Transition.Applied
                assertEquals("$case：新状态", expected.state, applied.state)
                assertEquals("$case：effect 序列", expected.effects, applied.effects)
                assertEquals("$case：allowed 必须为 true", true, PlayerPageReducer.allowed(case.state, case.event))
            }

            PlayerPageMatrix.Expectation.Rejected -> {
                assertTrue("$case：期望 Rejected，实际 $actual", actual is Transition.Rejected)
                val rejected = actual as Transition.Rejected
                assertSame("$case：拒绝必须返回同一 state 实例", case.state, rejected.state)
                assertTrue("$case：拒绝原因不能为空", rejected.reason.isNotBlank())
                assertEquals("$case：allowed 必须为 false", false, PlayerPageReducer.allowed(case.state, case.event))
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun data(): List<Array<Any>> = PlayerPageMatrix.CASES.map { arrayOf<Any>(it) }
    }
}

/**
 * 矩阵覆盖率闸门 + 全局不变量 + 「显示模式 = 实际 effect」一致性。
 */
class PlayerPageMatrixGateTest {

    @Test
    fun frameworkMatrixIsClean() {
        // 框架级断言：非法组合 Rejected 且 state 实例不变、合法组合不落出状态域、
        // 每次转换后不变量成立、effect 类型已声明。
        MatrixTest(PlayerPageStateMachine.spec).assertClean()
    }

    @Test
    fun matrixCoversEveryStateTimesEveryEventExactlyOnce() {
        val stateCount = PlayerPageStateMachine.states.size
        val eventCount = PlayerPageStateMachine.events.size
        assertEquals("状态域夹具数量", PlayerPageMatrix.STATE_KEYS.size, stateCount)
        assertEquals("事件集夹具数量", PlayerPageMatrix.EVENT_KEYS.size, eventCount)
        assertEquals("状态数 × 事件数", stateCount * eventCount, PlayerPageMatrix.CASES.size)
        assertEquals("期望表必须覆盖所有单元格", stateCount * eventCount, PlayerPageMatrix.EXPECTATIONS.size)

        val keys = PlayerPageMatrix.CASES.map { it.stateIndex to it.eventIndex }.toSet()
        assertEquals("不允许重复组合", stateCount * eventCount, keys.size)
        for (stateIndex in 0 until stateCount) {
            for (eventIndex in 0 until eventCount) {
                assertTrue(
                    "矩阵缺期望：${PlayerPageMatrix.STATE_KEYS[stateIndex]} × ${PlayerPageMatrix.EVENT_KEYS[eventIndex]}",
                    (stateIndex to eventIndex) in keys,
                )
            }
        }
    }

    @Test
    fun everyTransitionResultSatisfiesAllInvariants() {
        for (case in PlayerPageMatrix.CASES) {
            val result = PlayerPageReducer.reduce(case.state, case.event)
            val next = when (result) {
                is Transition.Applied -> result.state
                is Transition.Rejected -> result.state
            }
            PlayerPageReducer.INVARIANTS.forEachIndexed { index, invariant ->
                assertNull(
                    "$case：不变量[${index + 1}] 违反：${invariant.violation(next)}",
                    invariant.violation(next),
                )
            }
        }
    }

    /**
     * 00004 的结构性断言：状态里展示的 mode 与实际下发给播放器的
     * SetRepeat/SetShuffle effect 必须一致 —— 不允许「界面显示随机、实际单曲循环」。
     */
    @Test
    fun displayedModeAlwaysMatchesActualPlayerEffects() {
        for (case in PlayerPageMatrix.CASES) {
            val result = PlayerPageReducer.reduce(case.state, case.event)
            if (result !is Transition.Applied) continue

            val newMode = PlayerPageReducer.modeOf(result.state)
            val repeatEffects = result.effects.filterIsInstance<PlayerPageEffect.SetRepeat>()
            val shuffleEffects = result.effects.filterIsInstance<PlayerPageEffect.SetShuffle>()

            if (newMode != null) {
                if (repeatEffects.isNotEmpty()) {
                    assertEquals(
                        "$case：最后一个 SetRepeat 必须等于状态里的 repeat",
                        newMode.repeat,
                        repeatEffects.last().repeat,
                    )
                }
                if (shuffleEffects.isNotEmpty()) {
                    assertEquals(
                        "$case：最后一个 SetShuffle 必须等于状态里的 shuffle",
                        newMode.shuffle,
                        shuffleEffects.last().enabled,
                    )
                }
            }

            // 模式事件（SetRepeat / SetShuffle）被视为「用户已写、reducer 观测到」的事实；
            // 因此不要求每次模式变化都产生 effect，但任何补写 effect 的值必须等于新状态。
            // 其它路径（进入/退出无缝、切歌、归一化）由各自的命名用例断言完整 effect。
        }
    }

    @Test
    fun everyEffectProducedByMatrixIsDeclared() {
        val produced = PlayerPageMatrix.CASES
            .map { PlayerPageReducer.reduce(it.state, it.event) }
            .filterIsInstance<Transition.Applied<PlayerPageState, PlayerPageEffect>>()
            .flatMap { it.effects }
            .map { it::class }
            .toSet()
        assertTrue(
            "矩阵产生的 effect 必须都在 effectTypes 中声明：$produced",
            PlayerPageStateMachine.effectTypes.containsAll(produced),
        )
    }
}

/**
 * 规则 1–5 的命名用例（每条规则单独可读、失败时名字直接指向真实 bug）。
 */
class PlayerPageRuleTest {

    private val normalMode = PlayMode(repeat = RepeatMode.ALL, shuffle = false)

    private fun playing(
        positionMs: Long = 30_000L,
        mode: PlayMode = normalMode,
    ): PlayerPageState.Playing = PlayerPageState.Playing(positionMs, mode)

    private fun appliedTransition(
        state: PlayerPageState,
        event: PlayerPageEvent,
        nowMs: Long = 0L,
    ): Transition.Applied<PlayerPageState, PlayerPageEffect> {
        val result = PlayerPageReducer.reduce(state, event, nowMs)
        assertTrue("期望 Applied，实际 $result", result is Transition.Applied)
        @Suppress("UNCHECKED_CAST")
        return result as Transition.Applied<PlayerPageState, PlayerPageEffect>
    }

    private fun applied(
        state: PlayerPageState,
        event: PlayerPageEvent,
        nowMs: Long = 0L,
    ): Pair<PlayerPageState, List<PlayerPageEffect>> {
        val transition = appliedTransition(state, event, nowMs)
        return transition.state to transition.effects
    }

    private fun assertRejectedSame(
        state: PlayerPageState,
        event: PlayerPageEvent,
        nowMs: Long = 0L,
    ): Transition.Rejected<PlayerPageState, PlayerPageEffect> {
        val result = PlayerPageReducer.reduce(state, event, nowMs)
        assertTrue("期望 Rejected，实际 $result", result is Transition.Rejected)
        @Suppress("UNCHECKED_CAST")
        val rejected = result as Transition.Rejected<PlayerPageState, PlayerPageEffect>
        assertSame("Rejected 必须返回同一个 state 实例", state, rejected.state)
        return rejected
    }

    // ------------------------------------------------------------------ 00001：拖拽 / pendingSeek

    @Test
    fun rule00001_positionTickDuringDragNeverMovesDragHandle() {
        val dragging = applied(playing(), PlayerPageEvent.DragStart).first as PlayerPageState.Seeking
        assertEquals("按下时 dragMs = 当前播放位置", 30_000L, dragging.dragMs)
        assertNull("拖拽中 pendingSince 必须为 null", dragging.pendingSince)

        val (afterTick, tickEffects) = applied(dragging, PlayerPageEvent.PositionTick(31_000L))
        assertSame("拖拽中 PositionTick 必须返回同一个 state 实例", dragging, afterTick)
        assertEquals("拖拽中 tick 不得改变 dragMs（00001 回弹）", 30_000L, (afterTick as PlayerPageState.Seeking).dragMs)
        assertTrue("拖拽中 tick 不得产生 effect（尤其不得 seek）", tickEffects.isEmpty())

        // DragMove 才允许改本地位置
        val moved = applied(dragging, PlayerPageEvent.DragMove(12_000L)).first as PlayerPageState.Seeking
        assertEquals(12_000L, moved.dragMs)
        assertEquals("拖拽中 targetMs 与 dragMs 保持同步", moved.dragMs, moved.targetMs)

        // 即使 tick 落在 pending 目标附近，拖拽中也不得改 dragMs
        val (afterFarTick, farEffects) = applied(moved, PlayerPageEvent.PositionTick(45_500L))
        assertSame(moved, afterFarTick)
        assertTrue(farEffects.isEmpty())
    }

    @Test
    fun rule00001_pendingSeekOverlayHoldsUntilConvergence() {
        val dragging = applied(playing(), PlayerPageEvent.DragStart).first as PlayerPageState.Seeking
        val (pending, commitEffects) = applied(dragging, PlayerPageEvent.DragEnd(50_000L), nowMs = 1_000L)
        assertEquals("松手只提交一次 seek", listOf(PlayerPageEffect.SeekTo(50_000L)), commitEffects)

        val pendingState = pending as PlayerPageState.Seeking
        assertEquals(50_000L, pendingState.targetMs)
        assertEquals(50_000L, pendingState.dragMs)
        assertEquals(1_000L, pendingState.pendingSince)

        // 真实位置仍在远处：覆盖层保留目标位置，不产生任何 effect
        val (stillPending, tickEffects) = applied(pendingState, PlayerPageEvent.PositionTick(30_000L), nowMs = 1_200L)
        assertSame("pendingSeek 期间 tick 必须返回同一 state 实例", pendingState, stillPending)
        assertEquals("pendingSeek 期间显示的仍是目标位置", 50_000L, (stillPending as PlayerPageState.Seeking).dragMs)
        assertTrue(tickEffects.isEmpty())

        // 真实位置收敛到 ±1.2s 内：交出真实位置，回到 Playing
        val (converged, convergedEffects) = applied(pendingState, PlayerPageEvent.PositionTick(50_900L), nowMs = 1_400L)
        assertEquals(PlayerPageState.Playing(50_900L, normalMode), converged)
        assertTrue(convergedEffects.isEmpty())
    }

    @Test
    fun rule00001_pendingSeekConvergenceBoundaryIsExactly1200ms() {
        val pending = pendingSeekState(targetMs = 50_000L, pendingSince = 1_000L)

        // 恰好 1200ms：收敛
        assertEquals(
            PlayerPageState.Playing(51_200L, normalMode),
            applied(pending, PlayerPageEvent.PositionTick(51_200L), nowMs = 1_200L).first,
        )
        // 1201ms 且未超时：继续 overaly
        assertSame(
            pending,
            applied(pending, PlayerPageEvent.PositionTick(51_201L), nowMs = 1_200L).first,
        )
        assertSame(
            pending,
            applied(pending, PlayerPageEvent.PositionTick(48_799L), nowMs = 1_200L).first,
        )
    }

    @Test
    fun rule00001_pendingSeekTimesOutAt1500ms() {
        val pending = pendingSeekState(targetMs = 50_000L, pendingSince = 1_000L)

        // 1499ms：未超时，即使位置远离也继续覆盖
        assertSame(
            pending,
            applied(pending, PlayerPageEvent.PositionTick(30_000L), nowMs = 2_499L).first,
        )

        // 1500ms：超时，释放覆盖层，显示真实位置（不额外 seek）
        val (released, effects) = applied(pending, PlayerPageEvent.PositionTick(30_000L), nowMs = 2_500L)
        assertEquals(PlayerPageState.Playing(30_000L, normalMode), released)
        assertTrue("超时释放不得产生 seek effect", effects.isEmpty())
    }

    @Test
    fun rule00001_pendingSeekResumesPausedWhenDragStartedFromPaused() {
        val paused = PlayerPageState.Paused(30_000L, normalMode)
        val dragging = applied(paused, PlayerPageEvent.DragStart).first as PlayerPageState.Seeking
        assertEquals(ResumeTarget.PAUSED, dragging.resume)

        val pending = applied(dragging, PlayerPageEvent.DragEnd(10_000L), nowMs = 100L).first
        val (converged, _) = applied(pending, PlayerPageEvent.PositionTick(10_100L), nowMs = 200L)
        assertEquals("暂停态拖拽收敛后必须回到 Paused", PlayerPageState.Paused(10_100L, normalMode), converged)
    }

    private fun pendingSeekState(targetMs: Long, pendingSince: Long): PlayerPageState.Seeking {
        val dragging = applied(playing(), PlayerPageEvent.DragStart).first as PlayerPageState.Seeking
        return applied(dragging, PlayerPageEvent.DragEnd(targetMs), nowMs = pendingSince)
            .first as PlayerPageState.Seeking
    }

    // ------------------------------------------------------------------ 00004：无缝意图 / 模式一致性

    @Test
    fun rule00004_enterSeamlessForcesSingleLoopAndRecordsOriginals() {
        val base = PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = true))
        val (next, effects) = applied(base, PlayerPageEvent.EnterSeamless(42L))

        val mode = (next as PlayerPageState.Playing).mode
        assertEquals("进入无缝后 repeat 必须为 ONE", RepeatMode.ONE, mode.repeat)
        assertFalse("进入无缝后 shuffle 必须为 false", mode.shuffle)
        assertEquals(
            "必须记录进入前的 repeat/shuffle",
            SeamlessIntent(songId = 42L, previousRepeat = RepeatMode.ALL, previousShuffle = true),
            mode.seamless,
        )
        assertEquals(
            "实际播放器也要同步模式（先关 shuffle 再设 ONE，避免中间非法）",
            listOf(PlayerPageEffect.SetShuffle(false), PlayerPageEffect.SetRepeat(RepeatMode.ONE)),
            effects,
        )
    }

    @Test
    fun rule00004_exitSeamlessRestoresBothValues() {
        val seamless = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = true)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first

        val (next, effects) = applied(seamless, PlayerPageEvent.ExitSeamless("user"))
        val mode = (next as PlayerPageState.Playing).mode
        assertEquals(PlayMode(repeat = RepeatMode.ALL, shuffle = true), mode)
        assertEquals(
            listOf(PlayerPageEffect.SetRepeat(RepeatMode.ALL), PlayerPageEffect.SetShuffle(true)),
            effects,
        )
    }

    @Test
    fun rule00004_userShuffleChangeWhileSeamlessExitsIntent() {
        val seamless = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = true)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first

        val (next, effects) = applied(seamless, PlayerPageEvent.SetShuffle(true))
        val mode = (next as PlayerPageState.Playing).mode
        assertNull("用户改 shuffle 等同退出无缝意图", mode.seamless)
        assertTrue(mode.shuffle)
        assertEquals("退出后 repeat 恢复进入前的 ALL", RepeatMode.ALL, mode.repeat)
        assertEquals(
            "shuffle 是用户刚写的值，不得重复下发；只需补写恢复的 repeat",
            listOf(PlayerPageEffect.SetRepeat(RepeatMode.ALL)),
            effects,
        )
        assertTrue("用户改 shuffle 不得产生 SetShuffle effect", effects.none { it is PlayerPageEffect.SetShuffle })

        // 00004 实测 bug：无缝前是单曲循环时，退出后绝不允许 shuffle+ONE 自相矛盾
        val fromSingleLoop = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ONE, shuffle = false)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first
        val (exited, _) = applied(fromSingleLoop, PlayerPageEvent.SetShuffle(true))
        val exitedMode = (exited as PlayerPageState.Playing).mode
        assertNull(exitedMode.seamless)
        assertEquals("原 repeat=ONE 时必须归一到 ALL，不能留下 shuffle+ONE", RepeatMode.ALL, exitedMode.repeat)
        assertTrue(exitedMode.shuffle)
        PlayerPageReducer.INVARIANTS.forEach { assertNull(it.violation(exited)) }
    }

    @Test
    fun rule00004_userRepeatChangeWhileSeamlessKeepsShuffleOffWithoutWritingIt() {
        val seamless = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = true)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first

        val (next, effects) = applied(seamless, PlayerPageEvent.SetRepeat(RepeatMode.OFF))
        val mode = (next as PlayerPageState.Playing).mode
        assertNull(mode.seamless)
        assertEquals("用户显式选择的 repeat 生效", RepeatMode.OFF, mode.repeat)
        assertEquals("shuffle 保持无缝期间的 false，不恢复进入前的 true", false, mode.shuffle)
        assertTrue("用户改 repeat 退出：ModeRestore 两个字段都为 null，不得写任何模式", effects.isEmpty())
        assertTrue("尤其不得写 shuffle", effects.none { it is PlayerPageEffect.SetShuffle })
    }

    @Test
    fun rule00004_trackChangeWithoutIntentExitsSeamlessAndRestores() {
        val seamless = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = false)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first

        val (next, effects) = applied(seamless, PlayerPageEvent.TrackChanged(songId = 7L, hasLoopIntent = false))
        val buffering = next as PlayerPageState.Buffering
        assertEquals("切歌后退出无缝并恢复进入前模式", PlayMode(repeat = RepeatMode.ALL, shuffle = false), buffering.mode)
        assertNull(buffering.mode.seamless)
        assertEquals(listOf(PlayerPageEffect.SetRepeat(RepeatMode.ALL)), effects)

        // 切回原歌不会自动恢复
        val (back, _) = applied(buffering, PlayerPageEvent.TrackChanged(songId = 42L, hasLoopIntent = false))
        assertNull(PlayerPageReducer.modeOf(back)!!.seamless)
    }

    @Test
    fun rule00004_trackChangeWithIntentReArmsSeamlessWithFreshOriginals() {
        val existing = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = false)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first

        val (next, effects) = applied(existing, PlayerPageEvent.TrackChanged(songId = 7L, hasLoopIntent = true))
        val buffering = next as PlayerPageState.Buffering
        assertEquals(
            "重新武装必须记录「恢复后的模式」作为新的进入前原值",
            PlayMode(
                repeat = RepeatMode.ONE,
                shuffle = false,
                seamless = SeamlessIntent(songId = 7L, previousRepeat = RepeatMode.ALL, previousShuffle = false),
            ),
            buffering.mode,
        )
        assertTrue("实际模式未变时不得写播放器", effects.isEmpty())

        // 无旧意图时切到带意图的新歌：显式进入单曲循环
        val (fresh, freshEffects) = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = false)),
            PlayerPageEvent.TrackChanged(songId = 7L, hasLoopIntent = true),
        )
        val freshMode = (fresh as PlayerPageState.Buffering).mode
        assertEquals(RepeatMode.ONE, freshMode.repeat)
        assertFalse(freshMode.shuffle)
        assertEquals(
            SeamlessIntent(songId = 7L, previousRepeat = RepeatMode.ALL, previousShuffle = false),
            freshMode.seamless,
        )
        assertEquals(listOf(PlayerPageEffect.SetRepeat(RepeatMode.ONE)), freshEffects)
    }

    @Test
    fun rule00004_secondEnterSeamlessIsRejectedWithoutOverwritingOriginals() {
        val first = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = true)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first

        assertRejectedSame(first, PlayerPageEvent.EnterSeamless(42L))
        assertRejectedSame(first, PlayerPageEvent.EnterSeamless(7L))
        assertEquals(
            "重复进入不得覆盖进入前原值",
            SeamlessIntent(songId = 42L, previousRepeat = RepeatMode.ALL, previousShuffle = true),
            (first as PlayerPageState.Playing).mode.seamless,
        )
    }

    @Test
    fun rule00004_modeChangesAfterExplicitExitNeverAutoReenterSeamless() {
        var state: PlayerPageState = PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = false))
        state = applied(state, PlayerPageEvent.EnterSeamless(42L)).first
        state = applied(state, PlayerPageEvent.ExitSeamless("user")).first
        assertNull((state as PlayerPageState.Playing).mode.seamless)

        // 点回单曲循环不会自动恢复无缝
        val (repeatOne, _) = applied(state, PlayerPageEvent.SetRepeat(RepeatMode.ONE))
        assertNull((repeatOne as PlayerPageState.Playing).mode.seamless)

        // 切回原歌也不会自动恢复
        val (back, _) = applied(repeatOne, PlayerPageEvent.TrackChanged(songId = 42L, hasLoopIntent = false))
        assertNull(PlayerPageReducer.modeOf(back)!!.seamless)
    }

    @Test
    fun rule00004_modeInvariantRejectsShufflePlusSingleLoopWithoutIntent() {
        val illegal = PlayerPageState.Playing(
            0L,
            PlayMode(repeat = RepeatMode.ONE, shuffle = true, seamless = null),
        )
        val reasons = PlayerPageReducer.INVARIANTS.mapNotNull { it.violation(illegal) }
        assertTrue("必须识别 shuffle+ONE+无意图的自相矛盾模式：$reasons", reasons.any { it.contains("自相矛盾") })

        val legalSeamless = PlayerPageState.Playing(
            0L,
            PlayMode(
                repeat = RepeatMode.ONE,
                shuffle = false,
                seamless = SeamlessIntent(42L, RepeatMode.ALL, false),
            ),
        )
        PlayerPageReducer.INVARIANTS.forEach { assertNull(it.violation(legalSeamless)) }
    }

    @Test
    fun rule00004_normalizingModeNeverLeavesContradictoryCombination() {
        // 开着随机时点单曲循环：必须同时关随机
        val (repeatOne, repeatEffects) = applied(
            PlayerPageState.Paused(0L, PlayMode(repeat = RepeatMode.ALL, shuffle = true)),
            PlayerPageEvent.SetRepeat(RepeatMode.ONE),
        )
        assertEquals(PlayMode(repeat = RepeatMode.ONE, shuffle = false), (repeatOne as PlayerPageState.Paused).mode)
        assertEquals(
            "repeat 是用户刚写的值，不重复下发；只补写归一化的 shuffle=false",
            listOf(PlayerPageEffect.SetShuffle(false)),
            repeatEffects,
        )

        // 单曲循环时打开随机：必须把 repeat 归一到 ALL
        val (shuffled, shuffleEffects) = applied(
            PlayerPageState.Paused(0L, PlayMode(repeat = RepeatMode.ONE, shuffle = false)),
            PlayerPageEvent.SetShuffle(true),
        )
        assertEquals(PlayMode(repeat = RepeatMode.ALL, shuffle = true), (shuffled as PlayerPageState.Paused).mode)
        assertEquals(
            "shuffle 是用户刚写的值，不重复下发；只补写归一化的 repeat=ALL",
            listOf(PlayerPageEffect.SetRepeat(RepeatMode.ALL)),
            shuffleEffects,
        )
    }

    @Test
    fun rule00004_closingShuffleNeverForcesRepeat() {
        // 非无缝：关闭 shuffle 只保留 repeat，不触发任何不变量强制
        val (closed, effects) = applied(
            PlayerPageState.Paused(0L, PlayMode(repeat = RepeatMode.OFF, shuffle = true)),
            PlayerPageEvent.SetShuffle(false),
        )
        assertEquals(PlayMode(repeat = RepeatMode.OFF, shuffle = false), (closed as PlayerPageState.Paused).mode)
        assertTrue("关闭 shuffle 不需要任何模式补写", effects.isEmpty())

        // 无缝态：shuffle 本来就是 false，关闭是恒等 no-op，不得清掉无缝意图
        val seamless = applied(
            PlayerPageState.Playing(30_000L, PlayMode(repeat = RepeatMode.ALL, shuffle = false)),
            PlayerPageEvent.EnterSeamless(42L),
        ).first
        assertSame(
            "无缝期间关闭已为 false 的 shuffle 必须是恒等 no-op",
            seamless,
            applied(seamless, PlayerPageEvent.SetShuffle(false)).first,
        )
        assertEquals(
            SeamlessIntent(songId = 42L, previousRepeat = RepeatMode.ALL, previousShuffle = false),
            PlayerPageReducer.modeOf(seamless)!!.seamless,
        )
    }

    @Test
    fun rule00004_globalInvariantShuffleImpliesNotSingleLoop() {
        for (state in PlayerPageStateMachine.states) {
            val mode = PlayerPageReducer.modeOf(state) ?: continue
            if (mode.shuffle) {
                assertTrue("$state：shuffle=true 时 repeat 不得为 ONE", mode.repeat != RepeatMode.ONE)
            }
        }
        val illegal = PlayerPageState.Paused(0L, PlayMode(repeat = RepeatMode.ONE, shuffle = true))
        assertTrue(
            "全局不变量必须能独立识别 shuffle+ONE（即使没有无缝意图）",
            PlayerPageReducer.INVARIANTS.mapNotNull { it.violation(illegal) }
                .any { it.contains("shuffle=true") },
        )
    }

    // ------------------------------------------------------------------ 规则 5：Ended / Error 闸门

    @Test
    fun rule00005_endedAndErrorRejectEverythingExceptPlayAndTrackChanged() {
        val states = listOf(
            PlayerPageState.Ended(PlayMode(repeat = RepeatMode.ALL, shuffle = false)),
            PlayerPageState.Error("decoder boom", PlayMode(repeat = RepeatMode.ONE, shuffle = false)),
        )
        for (state in states) {
            for (event in PlayerPageStateMachine.events) {
                if (event == PlayerPageEvent.Play || event is PlayerPageEvent.TrackChanged) continue
                val result = PlayerPageReducer.reduce(state, event)
                assertTrue("$state × $event 必须 Rejected，实际 $result", result is Transition.Rejected)
                assertSame(state, (result as Transition.Rejected).state)
                assertEquals(false, PlayerPageReducer.allowed(state, event))
            }
        }
    }

    @Test
    fun rule00005_playFromEndedOrErrorRestartsAtZero() {
        val states = listOf(
            PlayerPageState.Ended(PlayMode(repeat = RepeatMode.ALL, shuffle = false)),
            PlayerPageState.Error("decoder boom", PlayMode(repeat = RepeatMode.ONE, shuffle = false)),
        )
        for (state in states) {
            val (next, effects) = applied(state, PlayerPageEvent.Play)
            assertEquals(PlayerPageState.Playing(0L, PlayerPageReducer.modeOf(state)!!), next)
            assertEquals(listOf(PlayerPageEffect.PlayerPlay), effects)
        }
    }
}

// ====================================================================== 矩阵定义

/**
 * 10 个状态 × 19 个事件夹具 = 190 个单元格。
 *
 * 每个单元格都必须在 [EXPECTATIONS] 显式登记；期望表由 [expectedFor] 中对
 * sealed 事件的穷尽 when 生成，新增事件类型会编译失败。
 */
object PlayerPageMatrix {

    const val DRAG_MOVE_MS = 20_000L
    const val DRAG_END_MS = 50_000L
    const val SEEK_MS = 40_000L
    const val TICK_MS = 31_000L
    const val CONVERGE_TICK_MS = 45_500L
    const val NEW_SONG_ID = PlayerPageStateMachine.NEXT_SONG_ID

    val STATE_KEYS: List<String> = listOf(
        "Idle",
        "Playing(normal)",
        "Playing(seamless)",
        "Paused(shuffle)",
        "Paused(seamless)",
        "Seeking(dragging)",
        "Seeking(pending)",
        "Buffering",
        "Ended",
        "Error",
    )

    val EVENT_KEYS: List<String> = listOf(
        "Play",
        "Pause",
        "Toggle",
        "DragStart",
        "DragMove($DRAG_MOVE_MS)",
        "DragEnd($DRAG_END_MS)",
        "Seek($SEEK_MS)",
        "SetRepeat(ALL)",
        "SetRepeat(ONE)",
        "SetShuffle(true)",
        "SetShuffle(false)",
        "TrackChanged($NEW_SONG_ID,false)",
        "TrackChanged($NEW_SONG_ID,true)",
        "PositionTick($TICK_MS)",
        "PositionTick($CONVERGE_TICK_MS)",
        "PlaybackError(decoder boom)",
        "EnterSeamless($NEW_SONG_ID)",
        "ExitSeamless(user)",
        "PlaybackEnded",
    )

    val ROWS: List<Pair<String, PlayerPageState>> = STATE_KEYS.zip(PlayerPageStateMachine.states)
    val COLUMNS: List<Pair<String, PlayerPageEvent>> = EVENT_KEYS.zip(PlayerPageStateMachine.events)

    data class Case(
        val stateIndex: Int,
        val eventIndex: Int,
        val stateKey: String,
        val eventKey: String,
        val state: PlayerPageState,
        val event: PlayerPageEvent,
        val expected: Expectation,
    ) {
        override fun toString(): String = "$stateKey × $eventKey"
    }

    sealed interface Expectation {
        data class Applied(
            val state: PlayerPageState,
            val effects: List<PlayerPageEffect>,
        ) : Expectation

        data object Rejected : Expectation
    }

    val EXPECTATIONS: Map<Pair<Int, Int>, Expectation> = buildMap {
        ROWS.forEachIndexed { stateIndex, (_, state) ->
            COLUMNS.forEachIndexed { eventIndex, (_, event) ->
                put(stateIndex to eventIndex, expectedFor(state, event))
            }
        }
    }

    val CASES: List<Case> = ROWS.flatMapIndexed { stateIndex, (stateKey, state) ->
        COLUMNS.mapIndexed { eventIndex, (eventKey, event) ->
            Case(
                stateIndex = stateIndex,
                eventIndex = eventIndex,
                stateKey = stateKey,
                eventKey = eventKey,
                state = state,
                event = event,
                expected = EXPECTATIONS.getValue(stateIndex to eventIndex),
            )
        }
    }

    // ------------------------------------------------------------------ 期望构造

    private fun applied(
        state: PlayerPageState,
        effects: List<PlayerPageEffect> = emptyList(),
    ): Expectation = Expectation.Applied(state, effects)

    private fun clamp(positionMs: Long): Long = positionMs.coerceAtLeast(0L)

    private fun expectedFor(state: PlayerPageState, event: PlayerPageEvent): Expectation = when (state) {
        is PlayerPageState.Idle -> idleExpected(event)
        is PlayerPageState.Playing -> playingExpected(state, event)
        is PlayerPageState.Paused -> pausedExpected(state, event)
        is PlayerPageState.Seeking -> seekingExpected(state, event)
        is PlayerPageState.Buffering -> bufferingExpected(state, event)
        is PlayerPageState.Ended -> endedExpected(state, event)
        is PlayerPageState.Error -> endedExpected(state, event)
    }

    private fun idleExpected(event: PlayerPageEvent): Expectation = when (event) {
        is PlayerPageEvent.TrackChanged -> trackChangedExpected(PlayerPageState.Idle, event)
        else -> Expectation.Rejected
    }

    private fun playingExpected(
        state: PlayerPageState.Playing,
        event: PlayerPageEvent,
    ): Expectation = when (event) {
        PlayerPageEvent.Play -> applied(state)
        PlayerPageEvent.Pause -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )
        PlayerPageEvent.Toggle -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )

        PlayerPageEvent.DragStart -> applied(
            PlayerPageState.Seeking(
                dragMs = state.positionMs,
                targetMs = state.positionMs,
                pendingSince = null,
                mode = state.mode,
                resume = ResumeTarget.PLAYING,
            ),
        )

        is PlayerPageEvent.DragMove -> Expectation.Rejected
        is PlayerPageEvent.DragEnd -> Expectation.Rejected

        is PlayerPageEvent.Seek -> {
            val target = clamp(event.positionMs)
            applied(
                state.copy(positionMs = target),
                listOf(PlayerPageEffect.SeekTo(target)),
            )
        }

        is PlayerPageEvent.SetRepeat ->
            setRepeatExpected(state, state.mode, event.repeat) { state.copy(mode = it) }

        is PlayerPageEvent.SetShuffle ->
            setShuffleExpected(state, state.mode, event.enabled) { state.copy(mode = it) }

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChangedExpected(state, event) else Expectation.Rejected

        is PlayerPageEvent.PositionTick -> applied(state.copy(positionMs = clamp(event.positionMs)))

        is PlayerPageEvent.PlaybackError -> applied(
            PlayerPageState.Error(event.message, state.mode),
            listOf(PlayerPageEffect.ShowToast(event.message)),
        )

        is PlayerPageEvent.EnterSeamless ->
            if (state.mode.seamless == null && event.songId > 0L) {
                enterSeamlessExpected(state.mode, event.songId) { state.copy(mode = it) }
            } else {
                Expectation.Rejected
            }

        is PlayerPageEvent.ExitSeamless ->
            if (state.mode.seamless != null) {
                exitSeamlessExpected(state.mode) { state.copy(mode = it) }
            } else {
                Expectation.Rejected
            }

        PlayerPageEvent.PlaybackEnded -> applied(PlayerPageState.Ended(state.mode))
    }

    private fun pausedExpected(
        state: PlayerPageState.Paused,
        event: PlayerPageEvent,
    ): Expectation = when (event) {
        PlayerPageEvent.Play -> applied(
            PlayerPageState.Playing(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPlay),
        )
        PlayerPageEvent.Pause -> applied(state)
        PlayerPageEvent.Toggle -> applied(
            PlayerPageState.Playing(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPlay),
        )

        PlayerPageEvent.DragStart -> applied(
            PlayerPageState.Seeking(
                dragMs = state.positionMs,
                targetMs = state.positionMs,
                pendingSince = null,
                mode = state.mode,
                resume = ResumeTarget.PAUSED,
            ),
        )

        is PlayerPageEvent.DragMove -> Expectation.Rejected
        is PlayerPageEvent.DragEnd -> Expectation.Rejected

        is PlayerPageEvent.Seek -> {
            val target = clamp(event.positionMs)
            applied(
                state.copy(positionMs = target),
                listOf(PlayerPageEffect.SeekTo(target)),
            )
        }

        is PlayerPageEvent.SetRepeat ->
            setRepeatExpected(state, state.mode, event.repeat) { state.copy(mode = it) }

        is PlayerPageEvent.SetShuffle ->
            setShuffleExpected(state, state.mode, event.enabled) { state.copy(mode = it) }

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChangedExpected(state, event) else Expectation.Rejected

        is PlayerPageEvent.PositionTick -> applied(state.copy(positionMs = clamp(event.positionMs)))

        is PlayerPageEvent.PlaybackError -> applied(
            PlayerPageState.Error(event.message, state.mode),
            listOf(PlayerPageEffect.ShowToast(event.message)),
        )

        is PlayerPageEvent.EnterSeamless ->
            if (state.mode.seamless == null && event.songId > 0L) {
                enterSeamlessExpected(state.mode, event.songId) { state.copy(mode = it) }
            } else {
                Expectation.Rejected
            }

        is PlayerPageEvent.ExitSeamless ->
            if (state.mode.seamless != null) {
                exitSeamlessExpected(state.mode) { state.copy(mode = it) }
            } else {
                Expectation.Rejected
            }

        PlayerPageEvent.PlaybackEnded -> Expectation.Rejected
    }

    private fun seekingExpected(
        state: PlayerPageState.Seeking,
        event: PlayerPageEvent,
    ): Expectation = when (event) {
        is PlayerPageEvent.DragMove ->
            if (state.pendingSince == null) {
                val position = clamp(event.positionMs)
                applied(state.copy(dragMs = position, targetMs = position))
            } else {
                Expectation.Rejected
            }

        is PlayerPageEvent.DragEnd ->
            if (state.pendingSince == null) {
                val target = clamp(event.targetMs)
                applied(
                    state.copy(dragMs = target, targetMs = target, pendingSince = 0L),
                    listOf(PlayerPageEffect.SeekTo(target)),
                )
            } else {
                Expectation.Rejected
            }

        is PlayerPageEvent.PositionTick -> seekingTickExpected(state, event.positionMs, nowMs = 0L)

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChangedExpected(state, event) else Expectation.Rejected

        is PlayerPageEvent.PlaybackError -> applied(
            PlayerPageState.Error(event.message, state.mode),
            listOf(PlayerPageEffect.ShowToast(event.message)),
        )

        else -> Expectation.Rejected
    }

    private fun seekingTickExpected(
        state: PlayerPageState.Seeking,
        positionMs: Long,
        nowMs: Long,
    ): Expectation {
        val pendingSince = state.pendingSince ?: return applied(state)
        val position = clamp(positionMs)
        val converged = abs(position - state.targetMs) <= PlayerPageReducer.SEEK_CONVERGENCE_MS
        val timedOut = nowMs >= pendingSince &&
            nowMs - pendingSince >= PlayerPageReducer.PENDING_SEEK_TIMEOUT_MS
        if (!converged && !timedOut) return applied(state)

        val next = when (state.resume) {
            ResumeTarget.PLAYING -> PlayerPageState.Playing(position, state.mode)
            ResumeTarget.PAUSED -> PlayerPageState.Paused(position, state.mode)
        }
        return applied(next)
    }

    private fun bufferingExpected(
        state: PlayerPageState.Buffering,
        event: PlayerPageEvent,
    ): Expectation = when (event) {
        PlayerPageEvent.Play -> applied(state)
        PlayerPageEvent.Pause -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )
        PlayerPageEvent.Toggle -> applied(
            PlayerPageState.Paused(state.positionMs, state.mode),
            listOf(PlayerPageEffect.PlayerPause),
        )

        PlayerPageEvent.DragStart,
        is PlayerPageEvent.DragMove,
        is PlayerPageEvent.DragEnd,
        is PlayerPageEvent.Seek,
        PlayerPageEvent.PlaybackEnded -> Expectation.Rejected

        is PlayerPageEvent.PositionTick ->
            if (event.positionMs > 0L) {
                applied(PlayerPageState.Playing(clamp(event.positionMs), state.mode))
            } else {
                applied(state)
            }

        is PlayerPageEvent.PlaybackError -> applied(
            PlayerPageState.Error(event.message, state.mode),
            listOf(PlayerPageEffect.ShowToast(event.message)),
        )

        is PlayerPageEvent.SetRepeat ->
            setRepeatExpected(state, state.mode, event.repeat) { state.copy(mode = it) }

        is PlayerPageEvent.SetShuffle ->
            setShuffleExpected(state, state.mode, event.enabled) { state.copy(mode = it) }

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChangedExpected(state, event) else Expectation.Rejected

        is PlayerPageEvent.EnterSeamless ->
            if (state.mode.seamless == null && event.songId > 0L) {
                enterSeamlessExpected(state.mode, event.songId) { state.copy(mode = it) }
            } else {
                Expectation.Rejected
            }

        is PlayerPageEvent.ExitSeamless ->
            if (state.mode.seamless != null) {
                exitSeamlessExpected(state.mode) { state.copy(mode = it) }
            } else {
                Expectation.Rejected
            }
    }

    private fun endedExpected(
        state: PlayerPageState,
        event: PlayerPageEvent,
    ): Expectation = when (event) {
        PlayerPageEvent.Play -> applied(
            PlayerPageState.Playing(0L, PlayerPageReducer.modeOf(state)!!),
            listOf(PlayerPageEffect.PlayerPlay),
        )

        is PlayerPageEvent.TrackChanged ->
            if (event.songId > 0L) trackChangedExpected(state, event) else Expectation.Rejected

        else -> Expectation.Rejected
    }

    // ------------------------------------------------------------------ 模式 / 切歌期望

    private fun setRepeatExpected(
        state: PlayerPageState,
        mode: PlayMode,
        repeat: RepeatMode,
        withMode: (PlayMode) -> PlayerPageState,
    ): Expectation {
        val seamless = mode.seamless
        if (seamless != null) {
            if (repeat == RepeatMode.ONE) return applied(state)
            // 00004 裁决：repeat 保留用户新值且不重复写；shuffle 保持 false，不恢复 prev。
            val target = PlayMode(repeat = repeat, shuffle = false, seamless = null)
            return applied(withMode(target))
        }

        val shuffle = if (repeat == RepeatMode.ONE && mode.shuffle) false else mode.shuffle
        val target = mode.copy(repeat = repeat, shuffle = shuffle)
        if (target == mode) return applied(state)
        val effects = if (shuffle != mode.shuffle) {
            listOf(PlayerPageEffect.SetShuffle(shuffle))
        } else {
            emptyList()
        }
        return applied(withMode(target), effects)
    }

    private fun setShuffleExpected(
        state: PlayerPageState,
        mode: PlayMode,
        enabled: Boolean,
        withMode: (PlayMode) -> PlayerPageState,
    ): Expectation {
        val seamless = mode.seamless
        if (seamless != null) {
            if (!enabled) return applied(state)
            val restored = PlayMode(seamless.previousRepeat, seamless.previousShuffle, null)
            val repeat = if (restored.repeat == RepeatMode.ONE) RepeatMode.ALL else restored.repeat
            val target = PlayMode(repeat = repeat, shuffle = true, seamless = null)
            val effects = if (repeat != mode.repeat) {
                listOf(PlayerPageEffect.SetRepeat(repeat))
            } else {
                emptyList()
            }
            return applied(withMode(target), effects)
        }

        if (mode.shuffle == enabled) return applied(state)
        val repeat = if (enabled && mode.repeat == RepeatMode.ONE) RepeatMode.ALL else mode.repeat
        val target = mode.copy(repeat = repeat, shuffle = enabled)
        val effects = if (repeat != mode.repeat) {
            listOf(PlayerPageEffect.SetRepeat(repeat))
        } else {
            emptyList()
        }
        return applied(withMode(target), effects)
    }

    private fun enterSeamlessExpected(
        mode: PlayMode,
        songId: Long,
        withMode: (PlayMode) -> PlayerPageState,
    ): Expectation {
        val target = PlayMode(
            repeat = RepeatMode.ONE,
            shuffle = false,
            seamless = SeamlessIntent(songId, mode.repeat, mode.shuffle),
        )
        return applied(withMode(target), modeDiff(mode, target))
    }

    private fun exitSeamlessExpected(
        mode: PlayMode,
        withMode: (PlayMode) -> PlayerPageState,
    ): Expectation {
        val intent = mode.seamless ?: return applied(withMode(mode))
        val target = PlayMode(intent.previousRepeat, intent.previousShuffle, null)
        return applied(withMode(target), modeDiff(mode, target))
    }

    private fun trackChangedExpected(
        state: PlayerPageState,
        event: PlayerPageEvent.TrackChanged,
    ): Expectation {
        val base = PlayerPageReducer.modeOf(state) ?: PlayMode.DEFAULT
        val restored = base.seamless?.let {
            PlayMode(it.previousRepeat, it.previousShuffle, null)
        } ?: base
        val target = if (event.hasLoopIntent) {
            PlayMode(
                repeat = RepeatMode.ONE,
                shuffle = false,
                seamless = SeamlessIntent(event.songId, restored.repeat, restored.shuffle),
            )
        } else {
            restored
        }

        val effects = modeDiff(base, target).toMutableList()
        val next: PlayerPageState = when (state) {
            is PlayerPageState.Idle,
            is PlayerPageState.Ended,
            is PlayerPageState.Error -> {
                effects += PlayerPageEffect.PlayerPlay
                PlayerPageState.Playing(0L, target)
            }

            is PlayerPageState.Playing,
            is PlayerPageState.Buffering -> PlayerPageState.Buffering(0L, target)

            is PlayerPageState.Paused -> PlayerPageState.Paused(0L, target)

            is PlayerPageState.Seeking -> when (state.resume) {
                ResumeTarget.PLAYING -> PlayerPageState.Buffering(0L, target)
                ResumeTarget.PAUSED -> PlayerPageState.Paused(0L, target)
            }
        }
        return applied(next, effects)
    }

    private fun modeDiff(old: PlayMode, new: PlayMode): List<PlayerPageEffect> {
        val repeat = if (old.repeat != new.repeat) PlayerPageEffect.SetRepeat(new.repeat) else null
        val shuffle = if (old.shuffle != new.shuffle) PlayerPageEffect.SetShuffle(new.shuffle) else null
        if (repeat != null && shuffle != null) {
            return if (new.repeat == RepeatMode.ONE) {
                listOf(shuffle, repeat)
            } else {
                listOf(repeat, shuffle)
            }
        }
        return listOfNotNull(repeat, shuffle)
    }
}
