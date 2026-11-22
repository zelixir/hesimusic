package com.zjr.hesimusic.service

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次「播放因为读不到音频文件而失败」的可观察事件（issue 00030）。
 *
 * 为什么不是第二条 Toast：Toast 没有按钮、无法跳转，而且部分系统会抑制后台应用的
 * Toast。存储权限问题必须由前台 UI 接管成带「去授权」按钮的对话框。
 *
 * 这个类型只描述**发生了什么**，不判断「要不要弹」：前台/权限/去重由
 * [PlaybackErrorRouter] 与 UI 侧的 StoragePermissionPromptPolicy 决定。
 */
data class PlaybackBlock(
    /** 当前播放项对应的 songId；解析不出时为 null（去重键里记成 -1）。 */
    val songId: Long?,
    /** [PlaybackException.errorCode]；与 songId 一起构成去重键。 */
    val errorCode: Int,
    /** 当前媒体项标题，只用于日志/兜底文案（对话框正文是固定文案）。 */
    val title: String?,
    /** 发布时刻（wall clock；单测直接注入，不读系统时钟）。 */
    val publishedAtMs: Long,
) {
    /** 去重键：同一首歌的同一种错误视为同一事件。 */
    val dedupKey: String
        get() = "${songId ?: -1L}:$errorCode"
}

/**
 * 进程内“待处理的播放阻塞事件”发布点（单例 + StateFlow）。
 *
 * 语义约定（UI 侧据此实现，见 ui/common/StoragePermissionPrompt.kt）：
 * - **只保留一个**待处理事件（用户一次只能看一个对话框）；
 * - [publish] 对「同一个去重键」做两层去重：
 *   1. 已有一条**未被消费**的同类事件 → 丢弃（对话框已经/即将展示）；
 *   2. 同类事件距上次发布不足 [DEDUP_WINDOW_MS] → 丢弃（ExoPlayer 一次失败常连发多条）；
 * - [consume] 是**一次消费**：取走并清空，旋转/重进页面不会再弹；
 * - 消费之后再发生同一错误（例如从设置页返回仍未授权、用户再点播放）会重新发布，
 *   所以提示不会被永久静音；
 * - 应用在后台时 UI 不消费，事件留在 store 里，回前台再弹。
 */
object PlaybackBlockStore {

    /** 同一去重键的最小复弹间隔；短于此窗口的连发视为同一次失败。 */
    const val DEDUP_WINDOW_MS = 3000L

    private val block = MutableStateFlow<PlaybackBlock?>(null)

    /** 待处理事件；消费后变回 null。 */
    val blockFlow: StateFlow<PlaybackBlock?> = block.asStateFlow()

    private var lastPublishedKey: String? = null
    private var lastPublishedAtMs = 0L

    /**
     * 发布一次阻塞事件；被去重时返回 false（调用方不需要做任何提示）。
     *
     * 线程安全：Service 的错误回调不保证在主线程，这里同步整段状态更新。
     */
    fun publish(block: PlaybackBlock): Boolean = synchronized(this) {
        val pending = this.block.value
        if (pending != null && pending.dedupKey == block.dedupKey) return false
        val elapsed = block.publishedAtMs - lastPublishedAtMs
        if (lastPublishedKey == block.dedupKey && elapsed in 0 until DEDUP_WINDOW_MS) return false
        this.block.value = block
        lastPublishedKey = block.dedupKey
        lastPublishedAtMs = block.publishedAtMs
        true
    }

    /** 当前待处理事件（不消费）。 */
    fun peek(): PlaybackBlock? = block.value

    /**
     * 取走并清空**同一条**事件；已被消费/已被更新的事件返回 null。
     *
     * 用整条事件比较（含 publishedAtMs）而不是只比 key：避免把刚到达的新事件误清掉。
     */
    fun consume(block: PlaybackBlock): PlaybackBlock? = synchronized(this) {
        val current = this.block.value ?: return null
        if (current != block) return null
        this.block.value = null
        current
    }

    /** 清空待处理事件与去重记忆（Service 销毁/单测隔离）。 */
    fun clear() = synchronized(this) {
        block.value = null
        lastPublishedKey = null
        lastPublishedAtMs = 0L
    }

    /** 仅供单测隔离。 */
    fun resetForTest() = clear()
}

/**
 * 播放错误 → 是否属于「读不到音频文件」的判据（issue 00030）。
 *
 * 判据（与 issue 描述一致）：
 * - [PlaybackException.ERROR_CODE_IO_NO_PERMISSION]；
 * - [PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND]；
 * - cause 链里出现 EACCES / Permission denied（底层 ErrnoException 可能被 media3 多层包装）。
 */
object StorageAccessErrors {

    /** 单测可直接用 errorCode + 普通异常验证，不依赖 PlaybackException 实例。 */
    fun isStorageAccessError(errorCode: Int, cause: Throwable?): Boolean {
        if (errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        ) {
            return true
        }
        var current = cause
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val message = current.message
            if (message != null && (message.contains("EACCES") || message.contains("Permission denied"))) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    fun isStorageAccessError(error: PlaybackException): Boolean =
        isStorageAccessError(error.errorCode, error.cause)

    /** 存储类错误 → 一条待处理事件；其它错误返回 null（仍然只走原有 Toast）。 */
    fun blockFor(
        errorCode: Int,
        cause: Throwable?,
        songId: Long?,
        title: String?,
        nowMs: Long,
    ): PlaybackBlock? = if (isStorageAccessError(errorCode, cause)) {
        PlaybackBlock(songId = songId, errorCode = errorCode, title = title, publishedAtMs = nowMs)
    } else {
        null
    }

    fun blockFor(
        error: PlaybackException,
        songId: Long?,
        title: String?,
        nowMs: Long,
    ): PlaybackBlock? = blockFor(error.errorCode, error.cause, songId, title, nowMs)

    /** 防御性上限：任何 cause 链都不会深到需要遍历更久。 */
    private const val MAX_CAUSE_DEPTH = 16
}

/**
 * 一次播放错误的分发计划。
 *
 * - [publish]：要发布给 UI 的阻塞事件（非存储错误为 null）；
 * - [toast]：是否还要弹原有的 Toast。前台且已发布时由 UI 对话框接管（toast=false），
 *   后台/无法发布时保留 Toast 兜底。
 */
data class PlaybackErrorPlan(
    val publish: PlaybackBlock?,
    val toast: Boolean,
)

object PlaybackErrorRouter {

    fun plan(block: PlaybackBlock?, appInForeground: Boolean): PlaybackErrorPlan = when {
        block == null -> PlaybackErrorPlan(publish = null, toast = true)
        appInForeground -> PlaybackErrorPlan(publish = block, toast = false)
        else -> PlaybackErrorPlan(publish = block, toast = true)
    }
}
