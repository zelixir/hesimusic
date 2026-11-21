package com.zjr.hesimusic.service

import android.util.Log
import com.zjr.hesimusic.loop.LoopWindow
import com.zjr.hesimusic.loop.audio.source.PcmTrackFormat

/**
 * SOURCE 构建失败的收口：**先预检、失败就回退 M3′、并且只回退一次**。
 *
 * 为什么需要：循环 PCM 数据源要探测音轨格式（[PcmFileFormatProbe] 这类实现）并为整个窗口
 * 申请 PCM 环（约 L×192KB/s）。格式不支持、文件读不到、窗口非法、或环超出预算时，构造期
 * 会抛异常——如果不管，ExoPlayer 的 MediaSourceFactory 会把异常抛回主线程，表现为播放报错/
 * 卡死。这里把「能不能走 SOURCE」变成一个纯函数判定，失败时：
 *
 * 1. 协调器**不替换**当前 item（队列与普通文件源保持原样）；
 * 2. 会话层不按 T 投影，位置/时长继续是媒体时间（透传）；
 * 3. 打一条带原因的 Log.w，并把该 (songId, A, B) 登记为不可用，避免每次 refresh 反复重试/刷日志；
 * 4. 用户改了 A/B 或换歌后自动重新判定（旧登记按窗口区分，不跨窗口生效）。
 *
 * 「回退 M3′」的可达边界：主播放器的 AudioSink 在 ExoPlayer 构建时就固定了（SOURCE 构建时没有
 * LoopAudioSink），所以**同实例内**只能退到「不循环的普通播放 + 媒体时间不投影」；真正的 M3′
 * 需要引擎开关回到 M3′ 后重建播放器（MediaModule 按开关选择 renderers），日志里会给出这条提示。
 */
object LoopSourceFallback {

    private const val TAG = "LoopPerf"

    /**
     * 环预算分母：窗口 PCM 字节数 <= heap / 6 才建环（与 RingBandSource.BUDGET_DIVISOR_MAIN 同口径）。
     *
     * 00027：原为 heap/10，Shiny Up（238s，42.0MB）超出 40.27MB 被判 RING_TOO_LARGE，
     * 主播放器回退成普通整曲播放但 UI 仍折返进度条 -> "显示 A、声音在尾奏"。
     * 主播放器 SOURCE 路线没有整曲预填/解码的额外大对象，与预览统一到 heap/6。
     */
    const val RING_BUDGET_DIVISOR: Long = 6L

    /** 判定/回退原因（日志里用 [logName]，避免被日志过滤干扰）。 */
    enum class Reason(val logName: String) {
        WINDOW_INVALID("window-invalid"),
        FILE_MISSING("file-missing"),
        FORMAT_UNSUPPORTED("format-unsupported"),
        RING_TOO_LARGE("ring-too-large"),
        BUILD_FAILED("build-failed"),
    }

    /** 预检结论。 */
    sealed interface Decision {
        /** 可以走 SOURCE；[ringBytes] 是整窗 PCM 字节数（日志/预算诊断用）。 */
        data class UseSource(val ringBytes: Long) : Decision

        /** 回退 M3′：本次不替换 item、不投影。 */
        data class UseM3Prime(val reason: Reason, val detail: String) : Decision
    }

    /** 整窗 PCM 字节数（L 秒 × 采样率 × 声道 × 2B）；无法判定时 0。 */
    fun ringBytes(window: LoopWindow?, format: PcmTrackFormat?): Long {
        if (window == null || !window.isValid() || format == null) return 0L
        val frames = window.lengthUs * format.sampleRate / 1_000_000L
        if (frames <= 0L) return 0L
        return frames * format.bytesPerFrame
    }

    /**
     * 进入 SOURCE 前的纯预检。
     *
     * @param window 目标窗口（null/非法 → WINDOW_INVALID）
     * @param songId 歌 id（无效或 [filePath] 为空 → FILE_MISSING）
     * @param filePath 媒体文件路径（空 → FILE_MISSING）
     * @param probe 音轨格式探测；null 或抛异常 → FORMAT_UNSUPPORTED
     * @param heapBytes 堆上限（默认进程 maxMemory；单测可注入固定值）
     * @param durationUs 整曲时长（µs）；<=0 = 未知。用于拒绝 B 越界的历史行
     */
    fun evaluate(
        window: LoopWindow?,
        songId: Long?,
        filePath: String?,
        probe: (() -> PcmTrackFormat)?,
        heapBytes: Long = Runtime.getRuntime().maxMemory(),
        durationUs: Long = 0L,
    ): Decision {
        // 窗口自身合法性：A>=0、B>A、B-A>=500ms、B<=时长（[LoopWindowRules] 唯一口径）。
        // 历史坏行（A==B / 只差 1 sample / 越界）在这里被判 window-invalid，绝不建源。
        if (!LoopWindowRules.usable(window, durationUs)) {
            return Decision.UseM3Prime(
                Reason.WINDOW_INVALID,
                LoopWindowRules.describe(window, durationUs),
            )
        }
        if (songId == null || songId <= 0L || filePath.isNullOrBlank()) {
            return Decision.UseM3Prime(
                Reason.FILE_MISSING,
                "songId=" + songId + " path=" + filePath,
            )
        }
        if (probe == null) {
            return Decision.UseM3Prime(Reason.FORMAT_UNSUPPORTED, "no probe path=" + filePath)
        }
        val format = try {
            probe()
        } catch (t: Throwable) {
            return Decision.UseM3Prime(
                Reason.FORMAT_UNSUPPORTED,
                "probe failed path=" + filePath + " err=" + t,
            )
        }
        val bytes = ringBytes(window, format)
        if (bytes <= 0L) {
            return Decision.UseM3Prime(
                Reason.WINDOW_INVALID,
                "ringBytes=0 window=" + window + " format=" + format,
            )
        }
        val budget = if (heapBytes > 0L) heapBytes / RING_BUDGET_DIVISOR else 0L
        if (budget > 0L && bytes > budget) {
            return Decision.UseM3Prime(
                Reason.RING_TOO_LARGE,
                "ringBytes=" + bytes + " budget=" + budget + " window=" + window,
            )
        }
        return Decision.UseSource(bytes)
    }

    // ---------------------------------------------------------------- 失败登记

    private data class Failure(val reason: Reason, val startUs: Long, val endUs: Long)

    private val failures = HashMap<Long, Failure>()
    private var latest: Failure? = null

    /** 登记「这个窗口的 SOURCE 不可用」；同一窗口只影响自己，换窗口自动重新判定。 */
    @Synchronized
    fun reportUnavailable(songId: Long, window: LoopWindow?, reason: Reason, detail: String = "") {
        // 00027/P0-3：WINDOW_INVALID 也是回退原因之一。原实现把非法窗口直接丢掉，
        // 导致 reason/compact claim 无记录、每次 refresh 反复 evaluate 刷日志。
        // 这里按 (songId, start, end) 原样登记；是否合法由调用方/claim 再判。
        if (songId <= 0L || window == null || window.songId != songId) return
        val failure = Failure(reason, window.loopStartUs, window.loopEndUs)
        failures[songId] = failure
        latest = failure
        LoopClaimStore.invalidate()
        logW(
            "source unavailable -> fallback M3' reason=" + reason.logName +
                " songId=" + songId + " window=" + window +
                (if (detail.isBlank()) "" else " detail=" + detail) +
                " (this player keeps plain playback; rebuild with the M3' engine to loop again)",
        )
    }

    /** 运行期构建异常（工厂 catch 后调用）：登记 + 打日志，返回 BUILD_FAILED。 */
    @Synchronized
    fun reportBuildFailure(songId: Long, window: LoopWindow?, throwable: Throwable?): Reason {
        reportUnavailable(
            songId = songId,
            window = window,
            reason = Reason.BUILD_FAILED,
            detail = if (throwable == null) "" else throwable.toString(),
        )
        return Reason.BUILD_FAILED
    }

    /**
     * 该窗口被登记的回退原因；没有登记 / 窗口已变化 → null。
     *
     * 与 [isUnavailable] 同口径：非法窗口同样可以命中（WINDOW_INVALID 需要被看见）。
     */
    @Synchronized
    fun reasonFor(songId: Long?, window: LoopWindow?): Reason? {
        if (songId == null || songId <= 0L || window == null) return null
        val failure = failures[songId] ?: return null
        return failure.reason.takeIf {
            failure.startUs == window.loopStartUs && failure.endUs == window.loopEndUs
        }
    }

    /** 该窗口是否已被判定为 SOURCE 不可用（窗口变化后为 false，可重试）。 */
    @Synchronized
    fun isUnavailable(songId: Long?, window: LoopWindow?): Boolean =
        reasonFor(songId, window) != null

    /** 换窗口/换歌时清掉登记（允许下一次重试）。 */
    @Synchronized
    fun clear(songId: Long) {
        failures.remove(songId)
        if (latest != null && !failures.containsKey(songId)) latest = failures.values.lastOrNull()
        LoopClaimStore.invalidate()
    }

    @Synchronized
    fun reset() {
        failures.clear()
        latest = null
        LoopClaimStore.invalidate()
    }

    /** 最近一次回退原因；About/日志用。 */
    @Synchronized
    fun latestReason(): Reason? = latest?.reason

    @Synchronized
    fun failureCount(): Int = failures.size

    @Synchronized
    fun reasonOf(songId: Long): Reason? = failures[songId]?.reason

    /** android.util.Log 在纯 JVM 单测里是 stub：打点失败绝不影响判定。 */
    private fun logW(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
            // JVM 单测无 android.util.Log 实现。
        }
    }
}
