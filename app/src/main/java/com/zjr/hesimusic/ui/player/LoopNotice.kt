package com.zjr.hesimusic.ui.player

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.service.LoopClaim
import com.zjr.hesimusic.service.LoopClaimStore
import com.zjr.hesimusic.service.LoopExitNotice
import com.zjr.hesimusic.service.LoopExitReason
import com.zjr.hesimusic.service.LoopSourceFallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * D05/D06/D13 共用的**用户可见提示通道**（纯逻辑 + 一个 Compose host）。
 *
 * 为什么要有它：回退（D06）、demote/删除/恢复失败（D13）、用户主动退出（D05）如果各弹各的
 * Toast/横幅，用户会看到三种样式、三方去重。这里把三种来源收敛成同一个 [LoopNotice]，
 * 由各页面的 Scaffold 用同一个 Snackbar host 呈现。
 *
 * 去重口径：
 * - 同一 [LoopNotice.id] 只入队一次；
 * - 真正展示过（Snackbar 返回/超时）才写入持久化的“已提示”集合，所以**进程重启/重进页面
 *   不会复弹**；如果页面在弹之前被销毁，下次进入仍会补上（不丢信息）。
 *
 * 不持久化“当前是否在循环”：那仍然只读 [LoopClaim]（00027 单一事实源）。
 */

internal enum class LoopNoticeKind {
    /** D06：源建不成（窗口/格式/文件/预算/构建失败），按普通播放但还是 armed。 */
    FALLBACK,

    /** D05：用户主动退出无缝。 */
    EXITED,

    /** D13：分析结果被取消确认/删除，或重启恢复失败导致的退场。 */
    DEMOTED,
}

internal data class LoopNotice(
    /** 去重键；同一 id 不会提示第二次（持久化）。 */
    val id: String,
    /** null = 不绑定当前歌（保留给全局提示）。 */
    val songId: Long?,
    val kind: LoopNoticeKind,
    val message: String,
    /** 可选动作文案；D06 的“退出无缝”在 D05 接入后启用。 */
    val actionLabel: String? = null,
)

// ---------------------------------------------------------------------------
// D06：回退原因 -> 文案（带原因码，方便用户截图反馈）
// ---------------------------------------------------------------------------

internal fun fallbackReasonLabel(reason: LoopSourceFallback.Reason): String = when (reason) {
    LoopSourceFallback.Reason.WINDOW_INVALID -> "循环窗口无效"
    LoopSourceFallback.Reason.FILE_MISSING -> "找不到音频文件"
    LoopSourceFallback.Reason.FORMAT_UNSUPPORTED -> "音频格式不支持无缝循环"
    LoopSourceFallback.Reason.RING_TOO_LARGE -> "该循环段过大"
    LoopSourceFallback.Reason.BUILD_FAILED -> "循环环带构建失败"
}

/** 一行说明；末尾括号里的原因码就是 /state.loop.claimReason。 */
internal fun fallbackNoticeMessage(reason: LoopSourceFallback.Reason): String =
    fallbackReasonLabel(reason) + "，已按普通播放（" + reason.logName + "）"

internal fun fallbackNoticeId(
    songId: Long?,
    window: LoopWindow?,
    reason: LoopSourceFallback.Reason,
): String {
    val startUs = window?.loopStartUs ?: -1L
    val endUs = window?.loopEndUs ?: -1L
    return "fallback:" + (songId ?: -1L) + ":" + startUs + ":" + endUs + ":" + reason.logName
}

/**
 * 当前 claim 是否处于“用户要无缝、但源没生效”的回退态。
 *
 * claim 未生效 + [LoopClaim.fallbackReason] 非空 = 回退；claim 生效时即使 window 还在也
 * 不得提示（否则会把正常循环说成失败）。window 为空也可能是 WINDOW_INVALID，同样要提示。
 */
internal fun fallbackNotice(songId: Long?, claim: LoopClaim): LoopNotice? {
    val reason = claim.fallbackReason ?: return null
    if (claim.claimActive) return null
    return LoopNotice(
        id = fallbackNoticeId(songId, claim.window, reason),
        songId = songId,
        kind = LoopNoticeKind.FALLBACK,
        message = fallbackNoticeMessage(reason),
        // 同一条通道里给一个动作：退出无缝（D05 的 gateway）。
        actionLabel = "退出无缝",
    )
}

// ---------------------------------------------------------------------------
// D13：service 的一次性退场通知 -> 同一提示模型
// ---------------------------------------------------------------------------

internal fun modeLabel(repeatMode: Int, shuffleEnabled: Boolean): String {
    if (shuffleEnabled) return "随机播放"
    return when (repeatMode) {
        Player.REPEAT_MODE_ONE -> "单曲循环"
        Player.REPEAT_MODE_ALL -> "列表循环"
        else -> "顺序播放"
    }
}

internal fun exitNoticeMessage(notice: LoopExitNotice): String {
    val mode = modeLabel(notice.nextRepeatMode, notice.nextShuffleEnabled)
    val why = when (notice.reason) {
        LoopExitReason.CONFIRMATION_REVOKED -> "分析结果已取消，已退出无缝循环"
        LoopExitReason.RESULT_DELETED -> "分析结果已删除，已退出无缝循环"
        LoopExitReason.RESTORE_UNAVAILABLE -> "上次的无缝循环未能恢复，已按普通播放"
        // 契约里还会补 USER_EXIT；新增枚举值不应让 UI 编译失败。
        else -> "已退出无缝循环"
    }
    return why + "（" + notice.reason.wire + "，当前：" + mode + "）"
}

internal fun LoopExitNotice.toLoopNotice(): LoopNotice = LoopNotice(
    id = "exit:" + songId + ":" + reason.wire + ":" + notifiedAtMs,
    songId = songId,
    kind = LoopNoticeKind.DEMOTED,
    message = exitNoticeMessage(this),
)

/**
 * 拉取式消费 service 的退场通知（[LoopClaimStore.consumeExitNotice]）。
 *
 * 只有当前页面对应歌的 notice 才消费；别的歌的 notice 留着，等对应页面出现时再取，
 * 避免“在 B 歌的页面把 A 歌的提示吃掉”。
 */
internal object ServiceLoopExitNoticeFeed {

    fun consumeFor(songId: Long?): LoopNotice? {
        if (songId == null) return null
        val pending = LoopClaimStore.exitNoticeFlow.value ?: return null
        if (pending.songId != songId) return null
        val consumed = LoopClaimStore.consumeExitNotice() ?: return null
        return consumed.toLoopNotice()
    }
}

// ---------------------------------------------------------------------------
// “已提示”持久化（64 键上限 + 90 天 TTL；真的展示过才写）
// ---------------------------------------------------------------------------

internal interface LoopNoticeSeenStore {
    fun isSeen(id: String, nowMs: Long): Boolean
    fun mark(id: String, nowMs: Long)
}

/**
 * 纯内存的“已提示”日志：id -> shownAt，按插入顺序淘汰，超过 TTL 视为没提示过。
 *
 * 单独抽出来是为了在没有 Android 的 JVM 单测里穷举 TTL/上限/序列化口径。
 */
internal class LoopNoticeSeenLog(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = TTL_MS,
) {
    private val shown = LinkedHashMap<String, Long>()

    fun isSeen(id: String, nowMs: Long): Boolean {
        val at = shown[id] ?: return false
        if (nowMs - at > ttlMs || nowMs < at) {
            shown.remove(id)
            return false
        }
        return true
    }

    fun mark(id: String, nowMs: Long) {
        shown.remove(id)
        shown[id] = nowMs
        while (shown.size > maxEntries) {
            val eldest = shown.keys.first()
            shown.remove(eldest)
        }
    }

    fun size(): Int = shown.size

    /** 序列化：每行 id + TAB + shownAt；id 由本文件生成，不含 tab/换行。 */
    fun encode(): String = shown.entries.joinToString("\n") { it.key + "\t" + it.value }

    fun load(encoded: String, nowMs: Long) {
        shown.clear()
        if (encoded.isBlank()) return
        for (line in encoded.split('\n')) {
            val tab = line.lastIndexOf('\t')
            if (tab <= 0) continue
            val id = line.substring(0, tab)
            val at = line.substring(tab + 1).toLongOrNull() ?: continue
            if (nowMs - at > ttlMs || nowMs < at) continue
            shown.remove(id)
            shown[id] = at
        }
        while (shown.size > maxEntries) {
            shown.remove(shown.keys.first())
        }
    }

    fun clear() = shown.clear()

    companion object {
        /** 与需求一致：最多 64 键。 */
        const val MAX_ENTRIES: Int = 64

        /** 90 天（需求下限 30 天；给“几个月后回来看”留余量）。 */
        const val TTL_MS: Long = 90L * 24L * 60L * 60L * 1000L
    }
}

internal class SharedPreferencesLoopNoticeSeenStore(
    context: Context,
    private val now: () -> Long = { System.currentTimeMillis() },
) : LoopNoticeSeenStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val log = LoopNoticeSeenLog()

    init {
        log.load(prefs.getString(KEY_SEEN, "").orEmpty(), now())
    }

    override fun isSeen(id: String, nowMs: Long): Boolean = synchronized(log) {
        log.isSeen(id, nowMs)
    }

    override fun mark(id: String, nowMs: Long) {
        synchronized(log) {
            log.mark(id, nowMs)
            prefs.edit().putString(KEY_SEEN, log.encode()).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "loop_notices"
        const val KEY_SEEN = "seen_v1"
    }
}

// ---------------------------------------------------------------------------
// 进程级队列 + Compose host
// ---------------------------------------------------------------------------

/**
 * 提示队列（进程级、单实例）。
 *
 * - [offer] 只入队；同一 id 已在队列里就不再入队；
 * - [markShown]/[dismiss] 在 Snackbar 真正结束后调用，写入持久化并出队；
 * - 页面按 songId 过滤显示，别的歌的提示留在队列里等对应页面。
 */
internal class LoopNoticeCenter(
    private val seen: LoopNoticeSeenStore,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxQueue: Int = 8,
) {
    private val _notices = MutableStateFlow<List<LoopNotice>>(emptyList())
    val notices: StateFlow<List<LoopNotice>> = _notices.asStateFlow()

    fun offer(notice: LoopNotice) {
        if (seen.isSeen(notice.id, now())) return
        _notices.update { current ->
            when {
                current.any { it.id == notice.id } -> current
                else -> (current + notice).takeLast(maxQueue)
            }
        }
    }

    fun pendingFor(songId: Long?): LoopNotice? =
        _notices.value.firstOrNull { it.songId == null || it.songId == songId }

    fun markShown(id: String) {
        seen.mark(id, now())
        dismiss(id)
    }

    fun dismiss(id: String) {
        _notices.update { current -> current.filterNot { it.id == id } }
    }

    fun resetForTest() {
        _notices.value = emptyList()
    }
}

/** 进程内单例；首次从页面取用时用真实 SharedPreferences 初始化。 */
internal object AppLoopNoticeCenter {

    @Volatile
    private var center: LoopNoticeCenter? = null

    fun get(context: Context): LoopNoticeCenter =
        center ?: synchronized(this) {
            center ?: LoopNoticeCenter(
                SharedPreferencesLoopNoticeSeenStore(context.applicationContext),
            ).also { center = it }
        }

    /** 仅供单测隔离。 */
    fun resetForTest() {
        center = null
    }
}

/**
 * 唯一提示样式：Material3 Snackbar（Long，可带一个动作）。
 *
 * 先展示、后 markShown：页面在弹之前被销毁时提示不会丢；真正展示过才持久化去重。
 * 队列变化（mark 出队）发生在 showSnackbar 返回之后，不会取消正在展示的 Snackbar。
 */
@Composable
internal fun LoopNoticeHost(
    songId: Long?,
    snackbarHostState: SnackbarHostState,
    onAction: (LoopNotice) -> Unit = {},
) {
    val context = LocalContext.current
    val center = remember(context) { AppLoopNoticeCenter.get(context) }
    val notices by center.notices.collectAsState()
    val revision by LoopClaimStore.revisionFlow.collectAsState()

    // service 侧的 D13 退场通知：每次 claim 变化/进入页面拉一次（一次性消费）。
    LaunchedEffect(songId, revision) {
        if (songId == null) return@LaunchedEffect
        ServiceLoopExitNoticeFeed.consumeFor(songId)?.let { center.offer(it) }
    }

    val next = notices.firstOrNull { it.songId == null || it.songId == songId }
    LaunchedEffect(next?.id) {
        val notice = next ?: return@LaunchedEffect
        if (songId == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = notice.message,
            actionLabel = notice.actionLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Long,
        )
        center.markShown(notice.id)
        if (result == SnackbarResult.ActionPerformed) onAction(notice)
    }
}
