package com.zjr.hesimusic.service

import com.zjr.hesimusic.loop.LoopWindow

/**
 * 循环窗口合法性的**唯一口径**（协议校验 + 回退预检 + claim 判定共用）。
 *
 * 规则（全部满足才算可用）：
 * 1. `songId > 0`；
 * 2. `A >= 0`；
 * 3. `B > A`（半开区间 [A,B) 不能为空）；
 * 4. `B - A >= [MIN_LOOP_LENGTH_MS]`（与结果页 `LoopPageReducer.MIN_LOOP_LENGTH_MS`、
 *    预览页 `PreviewPlayer.MIN_LOOP_LENGTH_MS` 对齐的 500ms）；
 * 5. `B <= 歌曲时长 + [DURATION_SLACK_US]`（时长未知/为 0 时跳过）。
 *
 * 为什么要有最小长度：A==B 或只差 1 个样本的窗口在音频层是"零长度环回"
 * （环回周期 < 一个解码粒度，甚至 < 一个采样周期），落库后进入播放就是
 * `loop-claim-without-source` / 静音空转 / 播放头钉死。协议层必须直接 400，
 * 历史行进入播放时必须被判 `window-invalid` 且 claim=false。
 *
 * 为什么样本口径用交叉相乘：先把样本换算成 µs/ms 再比较，499.999ms 会被取整成
 * 500ms 而误放行；`(b-a)*1e6 >= MIN_US*sampleRate` 没有中间取整，499ms 一定被拒。
 */
object LoopWindowRules {

    /** 最小可循环长度（毫秒），与 UI 的两处 `MIN_LOOP_LENGTH_MS` 对齐。 */
    const val MIN_LOOP_LENGTH_MS: Long = 500L

    /** 最小可循环长度（微秒）。 */
    const val MIN_LOOP_LENGTH_US: Long = MIN_LOOP_LENGTH_MS * 1_000L

    /**
     * B 允许超出歌曲时长的容差。
     *
     * 容器时长、最后一个编码样本、毫秒取整三者之间常差几十毫秒（整曲窗口 B==duration
     * 是合法用例），超出 100ms 才认为窗口真的越界。
     */
    const val DURATION_SLACK_US: Long = 100_000L

    /** 拒绝原因：`wire` 是协议层错误码（/confirm 的 4xx body 里带它）。 */
    enum class Code(val wire: String) {
        INVALID_SONG("window-invalid-song"),
        A_NEGATIVE("window-a-negative"),
        A_NOT_BEFORE_B("window-a-not-before-b"),
        TOO_SHORT("window-too-short"),
        PAST_DURATION("window-past-duration"),
        INVALID_SAMPLE_RATE("window-invalid-sample-rate"),
    }

    /** 校验结论（纯数据，便于单测与协议层映射）。 */
    sealed interface Verdict {
        data class Valid(val window: LoopWindow) : Verdict

        data class Invalid(
            val code: Code,
            val message: String,
            val status: Int = 400,
        ) : Verdict
    }

    /**
     * 微秒口径校验（UI 的 A/B 是毫秒，DB 行是毫秒）。
     *
     * @param durationUs 整曲时长（µs）；<=0 表示未知，跳过时长上界
     */
    fun validateUs(
        songId: Long,
        startUs: Long,
        endUs: Long,
        durationUs: Long = 0L,
    ): Verdict {
        if (songId <= 0L) {
            return Verdict.Invalid(Code.INVALID_SONG, "songId=" + songId)
        }
        if (startUs < 0L) {
            return Verdict.Invalid(Code.A_NEGATIVE, "a=" + startUs + "us")
        }
        if (endUs <= startUs) {
            return Verdict.Invalid(
                Code.A_NOT_BEFORE_B,
                "b=" + endUs + "us <= a=" + startUs + "us",
            )
        }
        val widthUs = endUs - startUs
        if (widthUs < MIN_LOOP_LENGTH_US) {
            return Verdict.Invalid(
                Code.TOO_SHORT,
                "width=" + widthUs + "us < " + MIN_LOOP_LENGTH_US + "us",
            )
        }
        val window = LoopWindow(songId = songId, loopStartUs = startUs, loopEndUs = endUs)
        return durationViolation(window, durationUs) ?: Verdict.Valid(window)
    }

    /**
     * 样本口径校验（/confirm 的 aSamples/bSamples）。
     *
     * @param durationMs 整曲时长（毫秒）；<=0 表示未知，跳过时长上界
     */
    fun validateSamples(
        songId: Long,
        aSamples: Long,
        bSamples: Long,
        sampleRate: Int,
        durationMs: Long,
    ): Verdict {
        if (songId <= 0L) {
            return Verdict.Invalid(Code.INVALID_SONG, "songId=" + songId)
        }
        if (sampleRate <= 0) {
            return Verdict.Invalid(Code.INVALID_SAMPLE_RATE, "sampleRate=" + sampleRate)
        }
        if (aSamples < 0L) {
            return Verdict.Invalid(Code.A_NEGATIVE, "a=" + aSamples + " samples")
        }
        if (bSamples <= aSamples) {
            return Verdict.Invalid(
                Code.A_NOT_BEFORE_B,
                "b=" + bSamples + " <= a=" + aSamples + " samples",
            )
        }
        val widthSamples = bSamples - aSamples
        if (widthSamples * MICROS_PER_SECOND < MIN_LOOP_LENGTH_US * sampleRate) {
            val widthMs = widthSamples * 1_000.0 / sampleRate
            return Verdict.Invalid(
                Code.TOO_SHORT,
                "width=" + formatMs(widthMs) + "ms(" + widthSamples + " samples @" +
                    sampleRate + ") < " + MIN_LOOP_LENGTH_MS + "ms",
            )
        }
        val startUs = samplesToUs(aSamples, sampleRate)
        val endUs = samplesToUs(bSamples, sampleRate)
        val window = LoopWindow(songId = songId, loopStartUs = startUs, loopEndUs = endUs)
        return durationViolation(window, durationMs * 1_000L) ?: Verdict.Valid(window)
    }

    /**
     * 该窗口违反的第一条规则；null = 可用。
     *
     * @param durationUs 整曲时长（µs）；<=0 = 未知，只做窗口自身校验
     */
    fun violationOf(window: LoopWindow?, durationUs: Long = 0L): Code? {
        if (window == null) return Code.INVALID_SONG
        if (window.songId <= 0L) return Code.INVALID_SONG
        if (window.loopStartUs < 0L) return Code.A_NEGATIVE
        if (window.loopEndUs <= window.loopStartUs) return Code.A_NOT_BEFORE_B
        if (window.lengthUs < MIN_LOOP_LENGTH_US) return Code.TOO_SHORT
        if (durationUs > 0L && window.loopEndUs > durationUs + DURATION_SLACK_US) {
            return Code.PAST_DURATION
        }
        return null
    }

    /** 窗口是否可进入循环（claim / 回退预检 / 测试共用）。 */
    fun usable(window: LoopWindow?, durationUs: Long = 0L): Boolean =
        violationOf(window, durationUs) == null

    /** 拒绝原因的日志/文案（不含协议码）。 */
    fun describe(window: LoopWindow?, durationUs: Long = 0L): String {
        val code = violationOf(window, durationUs) ?: return "ok"
        return code.wire + " window=" + window +
            (if (durationUs > 0L) " durationUs=" + durationUs else "")
    }

    private fun durationViolation(window: LoopWindow, durationUs: Long): Verdict.Invalid? {
        if (durationUs <= 0L) return null
        if (window.loopEndUs <= durationUs + DURATION_SLACK_US) return null
        return Verdict.Invalid(
            Code.PAST_DURATION,
            "b=" + window.loopEndUs + "us > duration=" + durationUs + "us+" + DURATION_SLACK_US + "us",
        )
    }

    /** 样本 → 微秒（向下取整，与 [com.zjr.hesimusic.loop.LoopEligibility] 的帧对齐口径一致）。 */
    fun samplesToUs(samples: Long, sampleRate: Int): Long =
        if (samples <= 0L || sampleRate <= 0) 0L else samples * MICROS_PER_SECOND / sampleRate

    private fun formatMs(value: Double): String = String.format("%.2f", value)

    private const val MICROS_PER_SECOND: Long = 1_000_000L
}
