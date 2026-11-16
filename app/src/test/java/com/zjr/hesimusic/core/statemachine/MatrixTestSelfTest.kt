package com.zjr.hesimusic.core.statemachine

import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 框架自测：用一个小的会话状态机验证 [MatrixTest] 本身。
 *
 * 覆盖「合法矩阵通过」以及四类被报出来的问题：
 * - 非法组合却返回 Applied；
 * - Rejected 替换了 state 实例；
 * - Applied 落出合法状态域（矩阵遗漏）；
 * - 不变量违反 / effect 类型未声明 / reducer 抛异常。
 */
class MatrixTestSelfTest {

    // ------------------------------------------------------------------ 样例状态机

    private sealed interface SessionState : SmState {
        data object LoggedOut : SessionState
        data class Waiting(val attempts: Int = 0) : SessionState
        data class Active(val token: String) : SessionState
        data object Locked : SessionState
    }

    private sealed interface SessionEvent : SmEvent {
        data object Login : SessionEvent
        data object Ok : SessionEvent
        data class Fail(val message: String) : SessionEvent
        data object Logout : SessionEvent
        data class Kick(val reason: String) : SessionEvent
    }

    private sealed interface SessionEffect : SmEffect {
        data object RequestLogin : SessionEffect
        data class Notify(val text: String) : SessionEffect
        data object ClearCache : SessionEffect
    }

    private val loggedOut = SessionState.LoggedOut
    private val waiting = SessionState.Waiting(0)
    private val active = SessionState.Active("token")
    private val locked = SessionState.Locked

    private val allStates = listOf(loggedOut, waiting, active, locked)
    private val allEvents = listOf(
        SessionEvent.Login,
        SessionEvent.Ok,
        SessionEvent.Fail("bad credentials"),
        SessionEvent.Logout,
        SessionEvent.Kick("kicked"),
    )
    private val goodInvariants: List<Invariant<SessionState>> = listOf(
        Invariant { state ->
            if (state is SessionState.Active && state.token.isBlank()) "Active.token 不能为空" else null
        },
        Invariant { state ->
            if (state is SessionState.Waiting && state.attempts < 0) "Waiting.attempts 不能为负" else null
        },
    )
    private val goodEffectTypes: Set<KClass<out SessionEffect>> = setOf(
        SessionEffect.RequestLogin::class,
        SessionEffect.Notify::class,
        SessionEffect.ClearCache::class,
    )

    private fun goodAllowed(state: SessionState, event: SessionEvent): Boolean = when {
        state == loggedOut && event == SessionEvent.Login -> true
        state == waiting && event == SessionEvent.Ok -> true
        state is SessionState.Waiting && event is SessionEvent.Fail -> true
        state == active && event == SessionEvent.Logout -> true
        else -> false
    }

    private fun goodReduce(
        state: SessionState,
        event: SessionEvent,
    ): Transition<SessionState, SessionEffect> = when {
        state == loggedOut && event == SessionEvent.Login ->
            Transition.Applied(waiting, listOf(SessionEffect.RequestLogin))

        state == waiting && event == SessionEvent.Ok ->
            Transition.Applied(active, listOf(SessionEffect.Notify("welcome")))

        state is SessionState.Waiting && event is SessionEvent.Fail ->
            Transition.Applied(loggedOut, listOf(SessionEffect.Notify(event.message)))

        state == active && event == SessionEvent.Logout ->
            Transition.Applied(loggedOut, listOf(SessionEffect.ClearCache))

        else -> Transition.Rejected(state, "不允许的组合")
    }

    private fun spec(
        states: List<SessionState> = allStates,
        events: List<SessionEvent> = allEvents,
        reduce: (SessionState, SessionEvent) -> Transition<SessionState, SessionEffect> = ::goodReduce,
        invariants: List<Invariant<SessionState>> = goodInvariants,
        allowed: (SessionState, SessionEvent) -> Boolean = ::goodAllowed,
        effectTypes: Set<KClass<out SessionEffect>> = goodEffectTypes,
    ): StateMachineSpec<SessionState, SessionEvent, SessionEffect> = StateMachineSpec(
        name = "session",
        states = states,
        events = events,
        reduce = reduce,
        invariants = invariants,
        allowed = allowed,
        effectTypes = effectTypes,
    )

    // ------------------------------------------------------------------ 通过路径

    @Test
    fun cleanSpecPassesAndEnumeratesEveryCell() {
        val report = MatrixTest(spec()).run()
        assertTrue(report.render(), report.isClean)
        assertEquals("状态数 × 事件数", 4 * 5, report.cells.size)
        assertFalse(report.render().contains("失败详情"))

        // assertClean 在干净矩阵上不得抛异常。
        MatrixTest(spec()).assertClean()
    }

    // ------------------------------------------------------------------ 失败路径

    @Test
    fun appliedForUndeclaredCombinationIsReported() {
        val buggy = spec(
            reduce = { state, event ->
                if (state == loggedOut && event == SessionEvent.Ok) {
                    Transition.Applied(active, listOf(SessionEffect.Notify("nope")))
                } else {
                    goodReduce(state, event)
                }
            },
        )

        val report = MatrixTest(buggy).run()
        val failure = report.failures.firstOrNull {
            it.stateKey == loggedOut.toString() && it.eventKey == SessionEvent.Ok.toString()
        }
        assertNotNull("必须报告 LoggedOut × Ok 的非法转换", failure)
        assertTrue(failure!!.message, failure.message.contains("allowed=false"))

        val text = report.render()
        assertTrue("矩阵片段必须打印实际 Transition：\n$text", text.contains("LoggedOut × Ok → Applied"))
        assertTrue("失败单元格必须标记为 !：\n$text", text.contains("!"))
    }

    @Test
    fun rejectedThatReplacesStateInstanceIsReported() {
        val otherInstance = SessionState.Active("other")
        val buggy = spec(
            reduce = { state, event ->
                if (state == active && event == SessionEvent.Login) {
                    Transition.Rejected(otherInstance, "nope")
                } else {
                    goodReduce(state, event)
                }
            },
        )

        val report = MatrixTest(buggy).run()
        val failure = report.failures.firstOrNull {
            it.stateKey == active.toString() && it.eventKey == SessionEvent.Login.toString()
        }
        assertNotNull("必须报告 state 实例被替换", failure)
        assertTrue(failure!!.message, failure.message.contains("同一个 state 实例"))
    }

    @Test
    fun matrixOmittedStateIsReportedAsOutOfDomain() {
        // 合法状态域漏掉了 Locked，但 reducer 会走到它 —— 等价于「新增状态却忘了更新矩阵」。
        val buggy = spec(
            states = listOf(loggedOut, waiting, active),
            reduce = { state, event ->
                if (state == waiting && event == SessionEvent.Ok) {
                    Transition.Applied(locked, listOf(SessionEffect.Notify("welcome")))
                } else {
                    goodReduce(state, event)
                }
            },
        )

        val report = MatrixTest(buggy).run()
        val failure = report.failures.firstOrNull {
            it.stateKey == waiting.toString() && it.eventKey == SessionEvent.Ok.toString()
        }
        assertNotNull("必须报告落出合法状态域", failure)
        assertTrue(failure!!.message, failure.message.contains("落出合法状态域"))
        assertTrue(report.render().contains("Locked"))
    }

    @Test
    fun invariantViolationIsReported() {
        val buggy = spec(
            reduce = { state, event ->
                if (state == waiting && event == SessionEvent.Ok) {
                    Transition.Applied(SessionState.Active(""), listOf(SessionEffect.Notify("welcome")))
                } else {
                    goodReduce(state, event)
                }
            },
        )

        val report = MatrixTest(buggy).run()
        val failure = report.failures.firstOrNull {
            it.stateKey == waiting.toString() && it.eventKey == SessionEvent.Ok.toString()
        }
        assertNotNull("必须报告不变量违反", failure)
        assertTrue(failure!!.message, failure.message.contains("不变量违反"))
        assertTrue(failure.message, failure.message.contains("Active.token 不能为空"))
    }

    @Test
    fun undeclaredEffectTypeIsReported() {
        val buggy = spec(
            effectTypes = setOf(SessionEffect.RequestLogin::class, SessionEffect.Notify::class),
        )

        val report = MatrixTest(buggy).run()
        val failure = report.failures.firstOrNull {
            it.stateKey == active.toString() && it.eventKey == SessionEvent.Logout.toString()
        }
        assertNotNull("必须报告未声明的 effect 类型", failure)
        assertTrue(failure!!.message, failure.message.contains("effect 类型未在 effectTypes 中声明"))
        assertTrue(failure.message, failure.message.contains("ClearCache"))
    }

    @Test
    fun emptyEffectTypeDeclarationIsReported() {
        val report = MatrixTest(spec(effectTypes = emptySet())).run()
        assertTrue(
            report.render(),
            report.failures.any { it.message.contains("未声明 effect 类型集合") },
        )
    }

    @Test
    fun throwingReducerIsReportedAsMatrixFailureInsteadOfEscaping() {
        val buggy = spec(
            reduce = { state, event ->
                if (state == active && event == SessionEvent.Logout) {
                    error("boom")
                } else {
                    goodReduce(state, event)
                }
            },
        )

        val report = MatrixTest(buggy).run()
        val failure = report.failures.firstOrNull {
            it.stateKey == active.toString() && it.eventKey == SessionEvent.Logout.toString()
        }
        assertNotNull("必须把 reducer 异常报告成矩阵失败", failure)
        assertTrue(failure!!.message, failure.message.contains("reduce 抛异常"))
        assertTrue(failure.message, failure.message.contains("IllegalStateException"))
        assertTrue(report.render().contains("<reducer 未返回 Transition>"))
    }

    @Test
    fun declaredIllegalStateIsReported() {
        val badWaiting = SessionState.Waiting(-3)
        val buggy = spec(states = listOf(loggedOut, waiting, active, badWaiting))

        val report = MatrixTest(buggy).run()
        assertTrue(
            report.render(),
            report.failures.any {
                it.stateKey == badWaiting.toString() && it.message.contains("Waiting.attempts 不能为负")
            },
        )
    }

    @Test
    fun assertCleanThrowsAssertionErrorWithMatrixFragment() {
        val buggy = spec(
            reduce = { state, event ->
                if (state == loggedOut && event == SessionEvent.Ok) {
                    Transition.Applied(active, emptyList())
                } else {
                    goodReduce(state, event)
                }
            },
        )

        try {
            MatrixTest(buggy).assertClean()
            fail("非法的矩阵必须让 assertClean 抛 AssertionError")
        } catch (expected: AssertionError) {
            val text = expected.message.orEmpty()
            assertTrue(text, text.contains("矩阵（行 sNN / 列 eNN"))
            assertTrue(text, text.contains("×"))
            assertTrue(text, text.contains("→"))
            assertTrue(text, text.contains("失败详情"))
        }
    }
}
