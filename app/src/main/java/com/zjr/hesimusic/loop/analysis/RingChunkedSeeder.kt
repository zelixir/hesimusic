package com.zjr.hesimusic.loop.analysis

import com.zjr.hesimusic.loop.LoopWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * 按时间顺序**分片**解码并逐片追加进环缓冲（连续前缀覆盖模型）。
 *
 * 设计目标（issue 00010）：不要等整个 [A,B) 解完才可播——第一片（默认 2s）落地后
 * 音频层就可以开始环回；后续片在后台按时间顺序继续追加，对播放完全透明。
 *
 * 分片口径（与音频层 `seedRingRange(window, startFrame, …)` 对齐）：
 * - `startFrame` 是**环内帧号**（0 = 窗口起点 A 对应的帧），片与片严格连续：
 *   第一片 `[0, N)`、第二片 `[N, 2N)`……；
 * - 环帧数 = `ceil(B*sr/1e6) − ceil(A*sr/1e6)`，区间换算的“严格帧数”可能多 1 帧，
 *   由本类按环长度裁剪（[Pcm16Segment.takeFrames]），绝不越界；
 * - 第二片起用**帧对齐的时间边界**解码（`floor(frame*1e6/sr)`），使每片首帧恰好等于
 *   期望的环内帧号：解码器内部按 `ceil(startUs*sr/1e6)` 对齐，两边口径一致，
 *   不会因 A/片边界不在帧上而产生缝隙或重复；
 * - [startFrame] 允许从播放捕获已经覆盖的前缀之后继续（音频层的 `coveredFrames`），
 *   已覆盖的帧不重复解码/写入。
 *
 * 取消/失败语义：
 * - 协程取消（窗口变化/退出）→ 立即抛 [CancellationException]，不再解码/追加；
 * - 单片解码失败或追加被拒 → 停止，返回 [Result.stopReason]；已追加的前缀保持可用。
 */
class RingChunkedSeeder(
    private val chunkMs: Int = DEFAULT_CHUNK_MS,
) {

    /**
     * 一次分片预填的结果。
     *
     * @param appendedFrames 已连续覆盖到第几帧（= 结束位置，含调用前已有的 [startFrame]）
     * @param chunks 本次成功追加的片数
     * @param complete 是否已覆盖整环（= 结束位置达到环帧数）
     * @param stopReason 提前停止原因（解码失败/追加被拒/数据不足）；null = 正常跑完
     */
    data class Result(
        val appendedFrames: Long,
        val chunks: Int,
        val complete: Boolean,
        val stopReason: String? = null,
    )

    /**
     * 逐片解码并追加，直到覆盖整环 / 取消 / 失败。
     *
     * @param window 目标窗口（半开区间 [A,B)）
     * @param startFrame 已连续覆盖的前缀帧数（音频层 `coveredFrames`；默认 0）
     * @param decode (startUs, endUs) -> 该段的精确 PCM16（生产 = [PcmDecoder.decodePcm16Range]）
     * @param seed (startFrame, segment) -> 是否成功追加；false = 音频层拒绝 → 停止追加
     */
    suspend fun run(
        window: LoopWindow,
        startFrame: Long = 0L,
        decode: suspend (startUs: Long, endUs: Long) -> Pcm16Segment,
        seed: suspend (startFrame: Long, segment: Pcm16Segment) -> Boolean,
    ): Result {
        if (!window.isValid()) return Result(startFrame, 0, false, "invalid window")
        val aUs = window.loopStartUs
        val bUs = window.loopEndUs
        val chunkUs = chunkMs.coerceAtLeast(1) * 1_000L

        var next = startFrame.coerceAtLeast(0L)
        var chunks = 0
        var first = true
        var sampleRate = 0
        var aFrame = 0L
        var totalFrames = 0L
        var chunkFrames = 0L

        while (true) {
            coroutineContext.ensureActive()
            if (!first && next >= totalFrames) return Result(next, chunks, true)

            val chunkStart: Long
            val segment: Pcm16Segment
            if (first) {
                // 第一片从 A 起（时间边界）；拿到 sampleRate 后才能换算环帧数。
                val endUs = minOf(aUs + chunkUs, bUs)
                segment = try {
                    decode(aUs, endUs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result(next, chunks, false, "decode: " + (e.message ?: "error"))
                }
                if (segment.frames <= 0L) return Result(next, chunks, false, "empty pcm")
                if (segment.sampleRate <= 0 || segment.channels <= 0) {
                    return Result(next, chunks, false, "invalid format")
                }
                sampleRate = segment.sampleRate
                aFrame = Pcm16Frames.frameAtOrAfterUs(aUs, sampleRate)
                val bFrame = Pcm16Frames.frameAtOrAfterUs(bUs, sampleRate)
                totalFrames = bFrame - aFrame
                if (totalFrames <= 0L) return Result(next, chunks, false, "empty window")
                chunkFrames = Pcm16Frames
                    .framesForUsCeil(chunkUs, sampleRate)
                    .coerceAtLeast(1L)
                chunkStart = 0L
                first = false
            } else {
                if (next >= totalFrames) return Result(next, chunks, true)
                chunkStart = next
                // 帧对齐的媒体时间边界：保证本片首帧 = 环内帧 `next`。
                val endFrame = minOf(next + chunkFrames, totalFrames)
                val startUs = frameUs(aFrame + next, sampleRate)
                val endUs = frameUs(aFrame + endFrame, sampleRate)
                segment = try {
                    decode(startUs, endUs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Result(next, chunks, false, "decode: " + (e.message ?: "error"))
                }
                if (segment.frames <= 0L) return Result(next, chunks, false, "empty pcm")
            }

            // 本片覆盖环帧 [chunkStart, chunkStart + segment.frames)。
            val coveredEnd = chunkStart + segment.frames
            if (coveredEnd <= next) {
                // 整片都已被播放捕获覆盖：跳到片尾继续（首片可能落在 startFrame 之前）。
                next = maxOf(next, coveredEnd)
                continue
            }
            val skip = (next - chunkStart).coerceAtLeast(0L)
            val want = minOf(segment.frames - skip, totalFrames - next)
            if (want <= 0L) {
                next = maxOf(next, coveredEnd)
                continue
            }
            val piece = segment.dropFrames(skip).takeFrames(want)
            if (!seed(next, piece)) {
                return Result(next, chunks, false, "seed rejected at frame " + next)
            }
            next += piece.frames
            chunks += 1
        }
    }

    /** 环内帧号 -> 媒体时间(µs)：floor(frame*1e6/sr)，与解码器 ceil(us*sr/1e6) 互逆。 */
    private fun frameUs(frame: Long, sampleRate: Int): Long =
        if (frame <= 0L) 0L else frame * 1_000_000L / sampleRate

    companion object {
        /** 默认片长：2s（第一片落地即可起播，其余后台补）。 */
        const val DEFAULT_CHUNK_MS: Int = 2_000
    }
}
