package com.zjr.hesimusic.core.statemachine

import kotlin.reflect.KClass

/**
 * 显式状态机框架（纯 Kotlin，零 Android 依赖）。
 *
 * 契约见 docs/state-machine-architecture.md。核心思想：
 * - [StateMachineSpec.states] 是**合法状态域**（代表实例）；
 * - [StateMachineSpec.events] 是**事件全集**；
 * - [StateMachineSpec.allowed] 显式声明「哪些事件在该状态下允许」；
 * - [StateMachineSpec.invariants] 声明所有合法状态必须满足的性质；
 * - [StateMachineSpec.reduce] 是纯函数：合法事件 -> [Transition.Applied]，
 *   未声明的组合 -> [Transition.Rejected] 且**返回同一个 state 实例**。
 */

/** 状态标记。 */
interface SmState

/** 事件标记。 */
interface SmEvent

/** 副作用标记。模型只声明，不执行。 */
interface SmEffect

/**
 * 一次状态转换的结果。
 *
 * [Applied] 用于声明允许的组合，[Rejected] 用于非法/过期/重复事件。
 * [Rejected] 必须原样返回入参 state（同一实例），便于矩阵测试用引用相等断言
 * 「非法操作没有产生非法状态」。
 */
sealed interface Transition<out S : SmState, out F : SmEffect> {
    data class Applied<out S : SmState, out F : SmEffect>(
        val state: S,
        val effects: List<F>,
    ) : Transition<S, F>

    data class Rejected<out S : SmState, out F : SmEffect>(
        val state: S,
        val reason: String,
    ) : Transition<S, F>
}

/** 状态不变量：返回违规原因；null 表示通过。 */
fun interface Invariant<S : SmState> {
    fun violation(state: S): String?
}

/**
 * 一个状态机的完整声明。
 *
 * @param name 机器名（矩阵报告里展示）
 * @param states 合法状态域的代表实例；新增状态必须加到这里，否则矩阵覆盖不到
 * @param events 事件全集；新增事件必须加到这里，否则矩阵覆盖不到
 * @param reduce 纯 reducer；未声明的组合必须返回 [Transition.Rejected] 且 state 实例不变
 * @param invariants 所有合法状态（含转换结果）都必须满足的性质
 * @param allowed 显式声明「该状态 × 该事件」是否允许；
 *   实现建议用对 sealed 类型的**穷尽 when**（不写 else），这样新增 state/event 会编译失败，
 *   从而强制同步矩阵
 * @param effectTypes 本机器允许声明的 effect 具体类型；[MatrixTest] 会逐个校验，
 *   留空视为契约不完整（矩阵测试失败）
 */
class StateMachineSpec<S : SmState, E : SmEvent, F : SmEffect>(
    val name: String,
    val states: List<S>,
    val events: List<E>,
    val reduce: (S, E) -> Transition<S, F>,
    val invariants: List<Invariant<S>>,
    val allowed: (S, E) -> Boolean,
    val effectTypes: Set<KClass<out F>> = emptySet(),
)
