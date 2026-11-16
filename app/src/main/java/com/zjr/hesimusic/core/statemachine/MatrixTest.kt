package com.zjr.hesimusic.core.statemachine

/**
 * JUnit 友好的「状态 × 事件」矩阵跑测器。
 *
 * 对 [StateMachineSpec.states] × [StateMachineSpec.events] 的每一个组合执行并校验：
 * 1. 未声明的组合必须返回 [Transition.Rejected]，且**返回同一个 state 实例**；
 * 2. 声明的组合必须返回 [Transition.Applied]，且落点仍在合法状态域内；
 * 3. 每次转换后所有 [Invariant] 必须通过（包括 Rejected 返回的 state）；
 * 4. 每个 effect 的具体类型必须在 [StateMachineSpec.effectTypes] 内；
 * 5. 若 reducer / allowed 抛异常，也作为矩阵失败报告，而不是让 JUnit 只看到堆栈。
 *
 * 失败时 [MatrixReport.render] 会打印「state × event → 实际 Transition」的矩阵片段。
 * 用法：
 * ```
 * @Test fun matrix() {
 *     MatrixTest(spec).assertClean()
 * }
 * ```
 */
class MatrixTest<S : SmState, E : SmEvent, F : SmEffect>(
    private val spec: StateMachineSpec<S, E, F>,
) {

    /** 只跑矩阵、返回每个单元格的结果（不抛异常）。 */
    fun run(): MatrixReport<S, E, F> {
        val domainFailures = mutableListOf<MatrixFailure<S, E, F>>()
        val rows = spec.states
        val columns = spec.events

        if (rows.isEmpty()) {
            domainFailures += specFailure("合法状态域为空：states 至少需要一个代表实例")
        }
        if (columns.isEmpty()) {
            domainFailures += specFailure("事件集为空：events 至少需要一个事件")
        }
        val duplicateRows = rows.groupBy { it.toString() }.filterValues { it.size > 1 }.keys
        if (duplicateRows.isNotEmpty()) {
            domainFailures += specFailure("state 标签重复，矩阵无法区分：$duplicateRows")
        }
        val duplicateColumns = columns.groupBy { it.toString() }.filterValues { it.size > 1 }.keys
        if (duplicateColumns.isNotEmpty()) {
            domainFailures += specFailure("event 标签重复，矩阵无法区分：$duplicateColumns")
        }
        if (spec.effectTypes.isEmpty()) {
            domainFailures += specFailure(
                "未声明 effect 类型集合（StateMachineSpec.effectTypes 为空）：无法执行「effect 类型必须在声明的集合内」校验",
            )
        }

        // 声明出来的状态实例本身也必须满足不变量，否则「合法状态域」就不合法。
        rows.forEach { state ->
            invariantProblems(state).forEach { problem ->
                domainFailures += MatrixFailure(
                    rowIndex = null,
                    columnIndex = null,
                    stateKey = state.toString(),
                    eventKey = "-",
                    transition = null,
                    message = problem,
                )
            }
        }

        val cells = ArrayList<MatrixCell<S, E, F>>(rows.size * columns.size)
        rows.forEachIndexed { rowIndex, state ->
            columns.forEachIndexed { columnIndex, event ->
                val stateKey = state.toString()
                val eventKey = event.toString()
                val problems = mutableListOf<String>()

                val allowed = try {
                    spec.allowed(state, event)
                } catch (t: Throwable) {
                    problems += "allowed 抛异常：${t.describe()}"
                    false
                }

                val transition = try {
                    spec.reduce(state, event)
                } catch (t: Throwable) {
                    problems += "reduce 抛异常：${t.describe()}"
                    null
                }

                if (transition != null) {
                    when (transition) {
                        is Transition.Applied -> {
                            if (!allowed) {
                                problems += "allowed=false，但 reducer 返回了 Applied：未声明的组合必须 Rejected 且保持 state 实例不变"
                            }
                            if (spec.states.none { it::class == transition.state::class }) {
                                problems += "Applied 落出合法状态域：${transition.state}（类型 ${transition.state::class.simpleName}）不在 states 中（矩阵遗漏了一个状态？）"
                            }
                            problems += invariantProblems(transition.state)
                            if (spec.effectTypes.isNotEmpty()) {
                                transition.effects.forEach { effect ->
                                    if (spec.effectTypes.none { it.java.isInstance(effect) }) {
                                        problems += "effect 类型未在 effectTypes 中声明：${effect.javaClass.simpleName}"
                                    }
                                }
                            }
                        }

                        is Transition.Rejected -> {
                            if (allowed) {
                                problems += "allowed=true，但 reducer 返回了 Rejected(${transition.reason})：声明允许的组合必须产生 Applied"
                            }
                            if (transition.state !== state) {
                                problems += "Rejected 必须返回同一个 state 实例：非法事件不得替换状态"
                            }
                            problems += invariantProblems(transition.state)
                        }
                    }
                }

                cells += MatrixCell(
                    rowIndex = rowIndex,
                    columnIndex = columnIndex,
                    stateKey = stateKey,
                    eventKey = eventKey,
                    state = state,
                    event = event,
                    expectedAllowed = allowed,
                    transition = transition,
                    problems = problems,
                )
            }
        }

        val cellFailures: List<MatrixFailure<S, E, F>> = cells.flatMap { cell ->
            cell.problems.map { problem ->
                MatrixFailure<S, E, F>(
                    rowIndex = cell.rowIndex,
                    columnIndex = cell.columnIndex,
                    stateKey = cell.stateKey,
                    eventKey = cell.eventKey,
                    transition = cell.transition,
                    message = problem,
                )
            }
        }

        return MatrixReport(
            spec = spec,
            rows = rows.map { it.toString() },
            columns = columns.map { it.toString() },
            cells = cells,
            failures = domainFailures + cellFailures,
        )
    }

    /**
     * 断言矩阵干净；失败时抛 [AssertionError]，消息就是矩阵片段（JUnit 会原样展示）。
     */
    fun assertClean(): MatrixReport<S, E, F> {
        val report = run()
        if (!report.isClean) {
            throw AssertionError(report.render())
        }
        return report
    }

    private fun invariantProblems(state: S): List<String> {
        val problems = mutableListOf<String>()
        spec.invariants.forEachIndexed { index, invariant ->
            val reason = try {
                invariant.violation(state)
            } catch (t: Throwable) {
                "invariant[${index + 1}] 抛异常：${t.describe()}"
            }
            if (reason != null) {
                problems += "不变量违反[${index + 1}]：$reason"
            }
        }
        return problems
    }

    private fun specFailure(message: String): MatrixFailure<S, E, F> = MatrixFailure(
        rowIndex = null,
        columnIndex = null,
        stateKey = "-",
        eventKey = "-",
        transition = null,
        message = message,
    )
}

/** 单个矩阵单元格。 */
data class MatrixCell<S : SmState, E : SmEvent, F : SmEffect>(
    val rowIndex: Int,
    val columnIndex: Int,
    val stateKey: String,
    val eventKey: String,
    val state: S,
    val event: E,
    val expectedAllowed: Boolean,
    val transition: Transition<S, F>?,
    val problems: List<String>,
) {
    val isFailure: Boolean get() = problems.isNotEmpty()
}

/** 一条矩阵失败记录。 */
data class MatrixFailure<S : SmState, E : SmEvent, F : SmEffect>(
    val rowIndex: Int?,
    val columnIndex: Int?,
    val stateKey: String,
    val eventKey: String,
    val transition: Transition<S, F>?,
    val message: String,
)

/**
 * 矩阵运行报告。保留完整单元格，便于测试按需二次断言；[render] 输出可读矩阵片段。
 */
class MatrixReport<S : SmState, E : SmEvent, F : SmEffect>(
    private val spec: StateMachineSpec<S, E, F>,
    val rows: List<String>,
    val columns: List<String>,
    val cells: List<MatrixCell<S, E, F>>,
    val failures: List<MatrixFailure<S, E, F>>,
) {
    val isClean: Boolean get() = failures.isEmpty()

    fun cell(rowIndex: Int, columnIndex: Int): MatrixCell<S, E, F> =
        cells.first { it.rowIndex == rowIndex && it.columnIndex == columnIndex }

    fun render(): String {
        val sb = StringBuilder()
        val allowedCount = cells.count { it.expectedAllowed }
        val rejectedCount = cells.size - allowedCount
        sb.append("[${spec.name}] ${rows.size} states × ${columns.size} events = ${cells.size} cells")
        sb.append("（声明允许 $allowedCount / 声明拒绝 $rejectedCount），失败 ${failures.size} 处")
        sb.append('\n')

        if (failures.isEmpty()) {
            return sb.toString()
        }

        sb.append("图例：. = 声明允许且通过；R = 声明拒绝且通过；! = 失败\n")
        sb.append("\n矩阵（行 sNN / 列 eNN，完整标签见下方图例）：\n")
        sb.append("      ")
        columns.indices.forEach { sb.append("e").append(indexLabel(it)).append(' ') }
        sb.append('\n')
        rows.indices.forEach { rowIndex ->
            sb.append("s").append(indexLabel(rowIndex)).append("   ")
            columns.indices.forEach { columnIndex ->
                val cell = cell(rowIndex, columnIndex)
                val mark = when {
                    cell.isFailure -> "!"
                    cell.expectedAllowed -> "."
                    else -> "R"
                }
                sb.append(mark.padEnd(3))
            }
            sb.append('\n')
        }

        sb.append("\n图例：\n")
        rows.indices.forEach { rowIndex ->
            sb.append("  s").append(indexLabel(rowIndex)).append(" = ").append(rows[rowIndex]).append('\n')
        }
        columns.indices.forEach { columnIndex ->
            sb.append("  e").append(indexLabel(columnIndex)).append(" = ").append(columns[columnIndex]).append('\n')
        }

        sb.append("\n失败详情（state × event → 实际 Transition）：\n")
        failures.take(MAX_DETAILS).forEachIndexed { index, failure ->
            sb.append('\n').append('#').append(index + 1).append(' ')
            sb.append(failure.stateKey).append(" × ").append(failure.eventKey).append(" → ")
            sb.append(failure.transition?.toString() ?: "<reducer 未返回 Transition>")
            sb.append('\n')
            sb.append("  声明：").append(
                when {
                    failure.rowIndex == null || failure.columnIndex == null -> "状态机契约"
                    cells.first { it.rowIndex == failure.rowIndex && it.columnIndex == failure.columnIndex }.expectedAllowed ->
                        "允许（应返回 Applied）"
                    else -> "拒绝（应返回 Rejected 且 state 实例不变）"
                },
            ).append('\n')
            sb.append("  问题：").append(failure.message).append('\n')

            val rowIndex = failure.rowIndex
            val columnIndex = failure.columnIndex
            if (rowIndex != null && columnIndex != null) {
                sb.append("  状态行 s").append(indexLabel(rowIndex)).append("：")
                columns.indices.forEach { ci ->
                    sb.append('e').append(indexLabel(ci)).append('=')
                        .append(marker(cell(rowIndex, ci))).append(' ')
                }
                sb.append('\n')
                sb.append("  事件列 e").append(indexLabel(columnIndex)).append("：")
                rows.indices.forEach { ri ->
                    sb.append('s').append(indexLabel(ri)).append('=')
                        .append(marker(cell(ri, columnIndex))).append(' ')
                }
                sb.append('\n')
            }
        }
        if (failures.size > MAX_DETAILS) {
            sb.append("\n… 其余 ${failures.size - MAX_DETAILS} 条失败已省略（表格中的 ! 仍然标出全部位置）\n")
        }
        return sb.toString()
    }

    private fun marker(cell: MatrixCell<S, E, F>): String = when {
        cell.isFailure -> "!"
        cell.expectedAllowed -> "."
        else -> "R"
    }

    private fun indexLabel(index: Int): String = (index + 1).toString().padStart(2, '0')

    private companion object {
        const val MAX_DETAILS = 40
    }
}

private fun Throwable.describe(): String =
    "${this::class.simpleName}: ${message ?: "(no message)"}"
