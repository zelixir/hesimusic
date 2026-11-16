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
 * 对齐模型(不再要求 sample 0 相等):
 * 1. 第 0 段不 seek、从文件头连续解码 = 单线程前缀(真值链的起点);
 * 2. 其余段 seek 到“计划起点 - 1s”,并**多保留 [MARGIN_FRAMES] 帧前导**(计划起点之前),
 *    因此编解码器的 seek/priming/时间戳截断造成的整体偏移(MP3 常见几十~几千帧)
 *    都落在前导里,不会被切成错位样本;
 * 3. 每个接缝用左段重叠区(真值)与右段原始数据做**归一化互相关搜索**
 *    ([SegmentAligner],中心 = 前导长度 ±[SegmentAligner.SEARCH_RADIUS]),
 *    取相关 >= [SegmentAligner.MIN_CORRELATION] 的最佳偏移,按该偏移裁掉前导;
 * 4. 裁完后用左段重叠区覆盖右段主体开头,保证拼接处样本连续;
 * 5. 任一步失败 -> 整体回退单线程,不产出错误 PCM。
 *
 * 偏移逐接缝测量(而不是按 mime 全局复用),因此能容忍 PTS 截断误差随播放位置
 * 累积导致的偏移漂移;探针只用于“该格式是否值得尝试并行”的能力判定。
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

    /** 第 0 段之外每段多保留的前导帧(覆盖 seek/priming/时间戳截断偏移)。 */
    const val MARGIN_FRAMES = 2048

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

/**
 * 一段解码结果:[raw] 从“计划起点 - [frontMargin]”开始,连续到段尾(EOS/精确帧数)。
 */
internal class DecodedSegment(
    val segment: DecodeSegment,
    val frontMargin: Int,
    val raw: FloatArray,
)

/** 单段解码器抽象(生产实现 = MediaCodec;测试实现 = 纯函数)。 */
internal fun interface MonoSegmentDecoder {
    /**
     * 解码 [segment] 的主体(含前导 + overlap 帧)。
     * [onFrames] 报告本段已产出的帧数(用于进度)。
     */
    suspend fun decode(segment: DecodeSegment, onFrames: (Long) -> Unit): DecodedSegment
}

internal sealed interface ParallelDecodeResult {
    class Success(
        val mono: FloatArray,
        val decodeMs: Long,
        val seamMs: Long,
        val stitchMs: Long,
    ) : ParallelDecodeResult

    /** 不可并行/接缝不一致;调用方回退单线程。 */
    class Rejected(
        val reason: String,
        val decodeMs: Long,
        val seamMs: Long,
    ) : ParallelDecodeResult
}

/**
 * 段间样本偏移测量(纯函数,可 JVM 单测)。
 *
 * 语义:[bestShift] 返回 shift,使 a[i] ≈ b[i + shift](i 为窗口内样本)。
 */
internal object SegmentAligner {

    /** 相关系数阈值:满足即认为"同一段波形,只差偏移"。 */
    const val MIN_CORRELATION = 0.995

    /** 偏移搜索半径(样本)。 */
    const val SEARCH_RADIUS = 2048

    /** 相关窗口(样本)。 */
    const val WINDOW = 1024

    /** 窗口太短时相关没有统计意义。 */
    private const val MIN_WINDOW = 64

    class Match(val shift: Int, val correlation: Double)

    /** 窗口内归一化互相关(Pearson)。返回 [-1,1];任一窗口常数/全零返回 0。 */
    fun correlation(
        a: FloatArray,
        aOffset: Int,
        b: FloatArray,
        bOffset: Int,
        n: Int,
    ): Double {
        if (n <= 1 || aOffset < 0 || bOffset < 0 ||
            aOffset + n > a.size || bOffset + n > b.size
        ) {
            return 0.0
        }
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        for (i in 0 until n) {
            val x = a[aOffset + i].toDouble()
            val y = b[bOffset + i].toDouble()
            sa += x
            sb += y
            saa += x * x
            sbb += y * y
            sab += x * y
        }
        val va = saa - sa * sa / n
        val vb = sbb - sb * sb / n
        if (va <= 1e-12 || vb <= 1e-12) return 0.0
        return (sab - sa * sb / n) / kotlin.math.sqrt(va * vb)
    }

    /**
     * 在 [center-radius, center+radius] 内搜索最佳偏移(center 优先,其次按
     * |shift-center| 从小到大),使 a[i] ≈ b[i + shift]。
     */
    fun bestShift(
        a: FloatArray,
        b: FloatArray,
        radius: Int = SEARCH_RADIUS,
        window: Int = WINDOW,
        center: Int = 0,
    ): Match? {
        if (a.isEmpty() || b.isEmpty() || window <= 1) return null
        var best: Match? = null
        fun tryShift(shift: Int) {
            val aOff = maxOf(0, -shift)
            val bOff = maxOf(0, shift)
            val n = minOf(window, a.size - aOff, b.size - bOff)
            if (n < minOf(MIN_WINDOW, window)) return
            val corr = correlation(a, aOff, b, bOff, n)
            if (best == null || corr > best!!.correlation + 1e-12) {
                best = Match(shift, corr)
            }
        }
        tryShift(center)
        for (d in 1..radius) {
            tryShift(center - d)
            tryShift(center + d)
        }
        return best
    }
}

internal object ParallelMonoDecode {

    /**
     * 并行解码按 [plan] 的所有段并拼接为整曲 mono。
     *
     * - 各段在同一 [coroutineScope] 中 launch:取消会中断所有段;
     * - 进度回调串行化(锁内调用),全局单调,结束必报 1.0;
     * - 接缝用互相关逐段对齐(容忍偏移漂移);
     * - 任一段抛 [CancellationException] 原样上抛;其他异常由调用方捕获并回退。
     */
    suspend fun decode(
        plan: SegmentPlan,
        decoder: MonoSegmentDecoder,
        onProgress: ((Float) -> Unit)?,
    ): ParallelDecodeResult = coroutineScope {
        if (plan.segmentCount <= 1) return@coroutineScope ParallelDecodeResult.Rejected("段数不足", 0, 0)
        if (plan.overlapFrames <= 0L) {
            return@coroutineScope ParallelDecodeResult.Rejected("缺少接缝校验帧", 0, 0)
        }

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

        val decodeStarted = System.currentTimeMillis()
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
        val decodeMs = System.currentTimeMillis() - decodeStarted

        if (parts.any { it == null }) {
            return@coroutineScope ParallelDecodeResult.Rejected("段结果缺失", decodeMs, 0)
        }
        val ordered = parts.map { it!! }

        val seamStarted = System.currentTimeMillis()
        val overlap = plan.overlapFrames.toInt()
        val mains = arrayOfNulls<FloatArray>(plan.segmentCount)
        val overlaps = arrayOfNulls<FloatArray>(plan.segmentCount)

        for (k in 0 until ordered.size) {
            val part = ordered[k]
            val raw = part.raw
            if (k == 0) {
                if (part.frontMargin != 0) {
                    return@coroutineScope ParallelDecodeResult.Rejected(
                        "第 0 段不应有前导", decodeMs, System.currentTimeMillis() - seamStarted,
                    )
                }
                val mainLen = if (part.segment.toEos) raw.size else part.segment.frameCount.toInt()
                if (mainLen <= 0 || mainLen > raw.size) {
                    return@coroutineScope ParallelDecodeResult.Rejected(
                        "第 0 段长度非法", decodeMs, System.currentTimeMillis() - seamStarted,
                    )
                }
                mains[k] = raw.copyOfRange(0, mainLen)
                overlaps[k] = if (part.segment.toEos) FloatArray(0) else {
                    val end = minOf(raw.size, mainLen + overlap)
                    raw.copyOfRange(mainLen, end)
                }
                continue
            }

            val leftOverlap = overlaps[k - 1]
            if (leftOverlap == null || leftOverlap.isEmpty()) {
                return@coroutineScope ParallelDecodeResult.Rejected(
                    "接缝 " + k + " 缺少左段重叠", decodeMs, System.currentTimeMillis() - seamStarted,
                )
            }
            // 右段 raw 从“计划起点 - frontMargin”开始;真实起点在 frontMargin 附近。
            val best = SegmentAligner.bestShift(
                a = leftOverlap,
                b = raw,
                radius = SegmentAligner.SEARCH_RADIUS,
                window = SegmentAligner.WINDOW,
                center = part.frontMargin,
            )
            if (best == null || best.correlation < SegmentAligner.MIN_CORRELATION) {
                return@coroutineScope ParallelDecodeResult.Rejected(
                    "接缝 " + k + " 相关不足(corr=" + (best?.correlation ?: 0.0) +
                        ", shift=" + (best?.shift ?: Int.MIN_VALUE) +
                        ", margin=" + part.frontMargin + ")",
                    decodeMs,
                    System.currentTimeMillis() - seamStarted,
                )
            }
            val d = best.shift
            val mainLen = if (part.segment.toEos) raw.size - d else part.segment.frameCount.toInt()
            if (d < 0 || mainLen <= 0 || d + mainLen > raw.size) {
                return@coroutineScope ParallelDecodeResult.Rejected(
                    "接缝 " + k + " 裁剪越界(d=" + d + ", main=" + mainLen + ")",
                    decodeMs,
                    System.currentTimeMillis() - seamStarted,
                )
            }
            val main = raw.copyOfRange(d, d + mainLen)
            // 用左段重叠区覆盖主体开头:即使相关只有 0.995,拼接处也连续。
            val copyN = minOf(minOf(overlap, leftOverlap.size), main.size)
            leftOverlap.copyInto(main, 0, 0, copyN)
            mains[k] = main
            overlaps[k] = if (part.segment.toEos) FloatArray(0) else {
                val end = minOf(raw.size, d + mainLen + overlap)
                raw.copyOfRange(d + mainLen, end)
            }
        }
        val seamMs = System.currentTimeMillis() - seamStarted

        val stitchStarted = System.currentTimeMillis()
        var total = 0L
        for (main in mains) {
            total += (main?.size ?: 0).toLong()
            if (total > Int.MAX_VALUE) {
                return@coroutineScope ParallelDecodeResult.Rejected("解码结果过大", decodeMs, seamMs)
            }
        }
        if (total <= 0L) {
            return@coroutineScope ParallelDecodeResult.Rejected("解码结果为空", decodeMs, seamMs)
        }
        val out = FloatArray(total.toInt())
        var offset = 0
        for (main in mains) {
            val m = main ?: continue
            m.copyInto(out, offset)
            offset += m.size
        }
        report(1f)
        ParallelDecodeResult.Success(out, decodeMs, seamMs, System.currentTimeMillis() - stitchStarted)
    }
}
