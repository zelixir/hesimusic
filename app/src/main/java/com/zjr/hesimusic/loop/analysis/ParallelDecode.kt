package com.zjr.hesimusic.loop.analysis

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * 分析解码的并行分片规划与拼接(v7 §decode-parallel)。
 *
 * 设计约束(与单线程解码**逐样本一致**):
 * 1. 整曲按全局样本号切成 N 段(段数 <= [SegmentPlanner.MAX_SEGMENTS],按 CPU 核数);
 * 2. 每段由独立 MediaExtractor + MediaCodec 解码,复用 PcmDecoder 的“seek 到起点前 1s +
 *    按 PTS 追赶 + 丢弃早于起点帧 + 段尾严格帧数”对齐逻辑;
 * 3. 拼接前做**接缝校验**:左段多解 [SegmentPlan.overlapFrames] 帧,与右段开头逐样本比对;
 *    任一接缝不一致 -> 整体放弃并行(返回 [ParallelDecodeResult.Rejected]),调用方回退单线程;
 * 4. 进度全局单调、回调串行化;取消时结构化并发会中断所有段(coroutineScope)。
 *
 * 本文件不依赖 Android 类,可 JVM 单测(见 ParallelDecodeTest)。
 */
internal data class DecodeSegment(
    val index: Int,
    /** 段起点(全局样本号,源采样率)。 */
    val startFrame: Long,
    /** 精确帧数;负数 = 解到 EOS(仅最后一段)。 */
    val frameCount: Long,
) {
    val toEos: Boolean get() = frameCount < 0L
}

internal class SegmentPlan(
    val sampleRate: Int,
    val channels: Int,
    val segments: List<DecodeSegment>,
    /** 由媒体时长推算的总帧数(仅用于切段与进度分母)。 */
    val totalFramesEstimate: Long,
    /** 接缝校验的额外帧数。 */
    val overlapFrames: Long,
) {
    val segmentCount: Int get() = segments.size

    /** 段起点的媒体时间(us);与 PcmDecoder 的帧号-时间口径一致(floor)。 */
    fun startUs(segment: DecodeSegment): Long =
        if (sampleRate <= 0) 0L else segment.startFrame * 1_000_000L / sampleRate

    /** 进度分母:普通段 = 精确帧数,尾段 = 估算剩余帧数(至少 1)。 */
    fun expectedFrames(segment: DecodeSegment): Long =
        if (segment.toEos) maxOf(1L, totalFramesEstimate - segment.startFrame)
        else maxOf(1L, segment.frameCount)
}

internal object SegmentPlanner {

    /** MediaCodec 实例上限与收益上限:最多 4 段。 */
    const val MAX_SEGMENTS = 4

    /** 单段最短 15s:太短的曲子并行收益抵不过多解码器开销。 */
    const val MIN_SEGMENT_US = 15_000_000L

    /** 单段上限留余量(预填区间上限 300s / PCM 64MB 约束)。 */
    private const val MAX_SEGMENT_US = 280_000_000L

    /** 按 CPU 核数与曲目时长选段数:>=4 核用 4 段,否则 2 段;受时长与区间上限约束。 */
    fun segmentCount(cores: Int, durationUs: Long): Int {
        if (durationUs <= 0L) return 1
        val byCores = if (cores >= 4) 4 else 2
        val byDuration = (durationUs / MIN_SEGMENT_US).toInt().coerceAtLeast(1)
        val byRange = ((durationUs + MAX_SEGMENT_US - 1L) / MAX_SEGMENT_US).toInt().coerceAtLeast(1)
        val n = maxOf(byCores, byRange).coerceAtMost(byDuration)
        return n.coerceIn(1, MAX_SEGMENTS)
    }

    /**
     * 切段:边界取 floor 的全局帧号;除最后一段外帧数精确,最后一段解到 EOS。
     * [totalFrames] 只是估算(用于边界/进度),不影响拼接总长(尾段以 EOS 为准)。
     */
    fun plan(
        totalFrames: Long,
        sampleRate: Int,
        channels: Int,
        segmentCount: Int,
        overlapFrames: Long,
    ): SegmentPlan {
        val n = segmentCount.coerceIn(1, MAX_SEGMENTS)
        val total = totalFrames.coerceAtLeast(0L)
        val segments = ArrayList<DecodeSegment>(n)
        for (i in 0 until n) {
            val start = total * i / n
            if (i == n - 1) {
                segments.add(DecodeSegment(i, start, -1L))
            } else {
                val end = total * (i + 1) / n
                segments.add(DecodeSegment(i, start, end - start))
            }
        }
        return SegmentPlan(sampleRate, channels, segments, total, overlapFrames)
    }
}

/** 一段解码结果(mono,已按源声道平均下混,幅度与 16bit PCM 同尺度)。 */
internal class DecodedSegment(
    val segment: DecodeSegment,
    /** 本段主体帧(普通段 = frameCount;尾段 = EOS 剩余帧)。 */
    val mono: FloatArray,
    /** 主体之后多解的帧,用于接缝校验(尾段为空)。 */
    val overlap: FloatArray,
)

/** 单段解码器抽象(生产实现 = MediaCodec;测试实现 = 纯函数)。 */
internal fun interface MonoSegmentDecoder {
    /**
     * 解码 [segment] 的主体 + 额外 overlap 帧。
     * [onFrames] 报告本段已产出的帧数(用于进度)。
     */
    suspend fun decode(segment: DecodeSegment, onFrames: (Long) -> Unit): DecodedSegment
}

internal sealed interface ParallelDecodeResult {
    class Success(val mono: FloatArray) : ParallelDecodeResult

    /** 不可并行/接缝不一致;调用方回退单线程。 */
    class Rejected(val reason: String) : ParallelDecodeResult
}

internal object ParallelMonoDecode {

    /**
     * 并行解码按 [plan] 的所有段并拼接为整曲 mono。
     *
     * - 各段在同一 [coroutineScope] 中 launch:取消会中断所有段;
     * - 进度回调串行化(锁内调用),全局单调,结束必报 1.0;
     * - 任一段抛 [CancellationException] 原样上抛;其他异常由调用方捕获并回退。
     */
    suspend fun decode(
        plan: SegmentPlan,
        decoder: MonoSegmentDecoder,
        onProgress: ((Float) -> Unit)?,
    ): ParallelDecodeResult = coroutineScope {
        if (plan.segmentCount <= 1) return@coroutineScope ParallelDecodeResult.Rejected("段数不足")
        if (plan.overlapFrames <= 0L) return@coroutineScope ParallelDecodeResult.Rejected("缺少接缝校验帧")

        val parts = arrayOfNulls<DecodedSegment>(plan.segmentCount)
        val progressLock = Any()
        var lastFraction = -1f
        var lastReportMs = 0L
        fun report(fraction: Float) {
            val cb = onProgress ?: return
            synchronized(progressLock) {
                val f = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
                if (f <= lastFraction) return
                val now = System.currentTimeMillis()
                if (f < 1f && now - lastReportMs < 50L) return
                lastFraction = f
                lastReportMs = now
                cb(f)
            }
        }

        val jobs = plan.segments.map { segment ->
            launch(Dispatchers.Default) {
                coroutineContext.ensureActive()
                val expected = plan.expectedFrames(segment)
                val part = decoder.decode(segment) { done ->
                    val frac = (done.toDouble() / expected.toDouble()).coerceIn(0.0, 1.0).toFloat()
                    report((segment.index + frac) / plan.segmentCount)
                }
                parts[segment.index] = part
            }
        }
        jobs.forEach { it.join() }

        if (parts.any { it == null }) return@coroutineScope ParallelDecodeResult.Rejected("段结果缺失")
        val ordered = parts.map { it!! }

        // 接缝校验:左段多解的重叠区必须与右段主体开头逐样本一致。
        val overlap = plan.overlapFrames.toInt()
        for (k in 1 until ordered.size) {
            val left = ordered[k - 1]
            val right = ordered[k]
            val n = minOf(overlap, left.overlap.size, right.mono.size)
            if (n < minOf(overlap, right.mono.size)) {
                return@coroutineScope ParallelDecodeResult.Rejected("接缝 " + k + " 重叠帧不足")
            }
            for (i in 0 until n) {
                if (left.overlap[i] != right.mono[i]) {
                    return@coroutineScope ParallelDecodeResult.Rejected(
                        "接缝 " + k + " 不一致(sample " + i + ": " + left.overlap[i] +
                            " != " + right.mono[i] + ")",
                    )
                }
            }
        }

        var total = 0L
        for (part in ordered) {
            total += part.mono.size.toLong()
            if (total > Int.MAX_VALUE) return@coroutineScope ParallelDecodeResult.Rejected("解码结果过大")
        }
        if (total <= 0L) return@coroutineScope ParallelDecodeResult.Rejected("解码结果为空")
        val out = FloatArray(total.toInt())
        var offset = 0
        for (part in ordered) {
            part.mono.copyInto(out, offset)
            offset += part.mono.size
        }
        report(1f)
        ParallelDecodeResult.Success(out)
    }
}
