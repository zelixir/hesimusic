package com.zjr.hesimusic.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import com.zjr.hesimusic.loop.LoopIntent
import com.zjr.hesimusic.loop.LoopModePreference
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.LoopExitNotice
import com.zjr.hesimusic.service.LoopExitReason
import com.zjr.hesimusic.ui.common.MusicViewModel
import com.zjr.hesimusic.utils.TimeFormatter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * D05：显式退出无缝循环的**纯策略 + 执行计划**。
 *
 * 设计约束：
 * - 退出后位置保持（媒体时间口径）由协调器负责：UI 只清意图，窗口变 null 后
 *   LoopPlaybackCoordinator 会把 SOURCE item 按 display 位置还原（见 restoreSourceItem）；
 * - repeat/shuffle 的“恢复”由 UI 按进入前的值**顺序**写回；写序列保证任何中间态都不非法
 *   （shuffle=true 且 repeat=ONE）；
 * - 不碰 LoopIntent/LoopClaim 的实现，只调用它们的公开 API。
 *
 * 本文件的可测部分全部是无 Android 的纯函数；[LocalLoopSeamlessExitGateway] 只做“调用 + 写”。
 */

internal object LoopSeamlessExitPolicy {

    /** 三态 repeat 按钮在普通播放下的下一态；无缝态下它变成“退出并保留普通单曲循环”。 */
    enum class RepeatTap {
        TO_SINGLE_LOOP,
        TO_LIST_LOOP,
        TO_SHUFFLE,

        /** XOR：无缝态点“单曲循环”= 退出无缝 + 普通单曲循环（不是切到 ALL）。 */
        EXIT_KEEP_PLAIN_SINGLE,
    }

    fun repeatTap(
        shuffleEnabled: Boolean,
        repeatMode: Int,
        seamlessActive: Boolean,
    ): RepeatTap {
        if (seamlessActive) return RepeatTap.EXIT_KEEP_PLAIN_SINGLE
        return when {
            shuffleEnabled -> RepeatTap.TO_SINGLE_LOOP
            repeatMode == Player.REPEAT_MODE_ONE -> RepeatTap.TO_LIST_LOOP
            else -> RepeatTap.TO_SHUFFLE
        }
    }

    /** 一次模式写入。 */
    sealed interface ModeWrite {
        data class Repeat(val mode: Int) : ModeWrite
        data class Shuffle(val enabled: Boolean) : ModeWrite
    }

    /**
     * 退出时要写的模式序列。
     *
     * @param keepPlainSingleLoop true = 用户点的是“单曲循环”（XOR），退出后保持普通单曲循环。
     */
    fun exitWrites(
        armed: LoopIntent.Armed,
        keepPlainSingleLoop: Boolean,
    ): List<ModeWrite> {
        if (keepPlainSingleLoop) {
            // 先把 shuffle 关掉，再设 ONE；任何一步都不会出现 shuffle=true + repeat=ONE。
            return listOf(ModeWrite.Shuffle(false), ModeWrite.Repeat(Player.REPEAT_MODE_ONE))
        }
        val restored = LoopModePreference.restored(armed)
        val repeat = restored.repeatMode
        // 历史脏数据（prev repeat=ONE 且 prev shuffle=true）按全局不变量归一成 shuffle=false。
        val shuffle = if (repeat == Player.REPEAT_MODE_ONE) false else restored.shuffleEnabled
        return if (repeat == Player.REPEAT_MODE_ONE) {
            listOf(ModeWrite.Shuffle(false), ModeWrite.Repeat(repeat))
        } else {
            listOf(ModeWrite.Repeat(repeat), ModeWrite.Shuffle(shuffle))
        }
    }

    /** 退出入口是否出现：claim 生效，或（回退态下）显式意图仍指向当前歌。 */
    fun shouldShowExitEntry(
        currentSongId: Long?,
        armedSongId: Long?,
        claimActive: Boolean,
    ): Boolean {
        if (claimActive) return true
        return currentSongId != null && armedSongId == currentSongId
    }

    /** 播放页状态行的区间文案（页面可见；D10 的通知正文刻意不带区间）。 */
    fun loopRangeLabel(window: LoopWindow?): String? {
        if (window == null) return null
        return TimeFormatter.formatTime(window.loopStartUs / 1_000L) +
            "–" +
            TimeFormatter.formatTime(window.loopEndUs / 1_000L)
    }
}

/**
 * 一次退出动作的结果。
 *
 * 退出提示不再由 UI 本地生成：统一发布 [LoopExitNotice]（reason=USER_EXIT），
 * 由 [LoopClaimStore.consumeExitNotice] → ServiceLoopExitNoticeFeed → LoopNoticeCenter
 * 走同一条通道，避免 service 与 UI 两套提示。
 */
internal data class LoopExitOutcome(
    val songId: Long,
    val keepPlainSingleLoop: Boolean,
    val writes: List<LoopSeamlessExitPolicy.ModeWrite>,
    /** 退出前的生效窗口（回退态可能为 null）。 */
    val window: LoopWindow?,
    /** 退出前播放器的 repeat/shuffle（无缝态通常是 ONE/false）。 */
    val previousRepeatMode: Int,
    val previousShuffleEnabled: Boolean,
) {
    fun toExitNotice(nowMs: Long): LoopExitNotice {
        val nextRepeat = writes.filterIsInstance<LoopSeamlessExitPolicy.ModeWrite.Repeat>()
            .lastOrNull()?.mode ?: previousRepeatMode
        val nextShuffle = writes.filterIsInstance<LoopSeamlessExitPolicy.ModeWrite.Shuffle>()
            .lastOrNull()?.enabled ?: previousShuffleEnabled
        return LoopExitNotice(
            songId = songId,
            reason = LoopExitReason.USER_EXIT,
            window = window,
            previousRepeatMode = previousRepeatMode,
            nextRepeatMode = nextRepeat,
            previousShuffleEnabled = previousShuffleEnabled,
            nextShuffleEnabled = nextShuffle,
            notifiedAtMs = nowMs,
        )
    }
}

/**
 * 退出执行网关。
 *
 * 目前是 UI 本地实现（清意图 + 按序列写模式）；semantics-state 的 service 侧退出计划
 * 如果将来需要接管，只替换这个接口的实现，页面不用改。
 */
internal interface LoopSeamlessExitGateway {
    fun exit(
        viewModel: MusicViewModel,
        loopIntent: LoopIntent,
        keepPlainSingleLoop: Boolean,
    ): LoopExitOutcome?
}

internal object LocalLoopSeamlessExitGateway : LoopSeamlessExitGateway {

    override fun exit(
        viewModel: MusicViewModel,
        loopIntent: LoopIntent,
        keepPlainSingleLoop: Boolean,
    ): LoopExitOutcome? {
        val armed = loopIntent.armed.value ?: return null
        val writes = LoopSeamlessExitPolicy.exitWrites(armed, keepPlainSingleLoop)
        // 退出前的可见事实：窗口（用于日志/提示）、播放器当前模式。
        val window = LoopClaimStore.claimFor(armed.songId).window
        val previousRepeat = viewModel.uiState.value.repeatMode
        val previousShuffle = viewModel.uiState.value.shuffleModeEnabled
        // 先清意图：协调器随后的 refresh 会把 window 置 null，并按展示位置还原 SOURCE item。
        loopIntent.clear()
        for (write in writes) {
            when (write) {
                is LoopSeamlessExitPolicy.ModeWrite.Repeat -> viewModel.setRepeatMode(write.mode)
                is LoopSeamlessExitPolicy.ModeWrite.Shuffle -> viewModel.setShuffleModeEnabled(write.enabled)
            }
        }
        return LoopExitOutcome(
            songId = armed.songId,
            keepPlainSingleLoop = keepPlainSingleLoop,
            writes = writes,
            window = window,
            previousRepeatMode = previousRepeat,
            previousShuffleEnabled = previousShuffle,
        )
    }
}

/**
 * 页面统一入口：执行退出 + 把“已退出”结果送进同一条提示通道。
 *
 * @return false = 当前没有可退出的显式意图（按钮理论上不该出现）。
 */
internal fun exitSeamless(
    viewModel: MusicViewModel,
    loopIntent: LoopIntent,
    keepPlainSingleLoop: Boolean,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    val outcome = LocalLoopSeamlessExitGateway.exit(viewModel, loopIntent, keepPlainSingleLoop)
        ?: return false
    // D05：统一由 service 侧发布 USER_EXIT；UI 的 LoopNoticeHost 会一次性消费并提示。
    // 不再在这里 offer 本地 EXITED notice，避免两套提示。
    LoopClaimStore.publishExit(outcome.toExitNotice(nowMs))
    return true
}

// ---------------------------------------------------------------------------
// Hilt：从 UI 取 LoopIntent（不依赖 MusicViewModel / MainActivity 的改动）
// ---------------------------------------------------------------------------

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LoopUiEntryPoint {
    fun loopIntent(): LoopIntent
}

@Composable
internal fun rememberLoopIntent(): LoopIntent {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, LoopUiEntryPoint::class.java).loopIntent()
    }
}
