package com.zjr.hesimusic.loop.audio.source

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * prelude→loop 首次交接的自校准（issue 00029 方案 A）。
 *
 * 固定取 prelude 尾部 [windowFrames]（默认 50ms）作为参考窗口，在 ±maxLag 帧内搜索
 * 环带前缀的最佳对齐。候选 lag 的实际重叠 = windowFrames - |lag|，NCC 只在重叠区间算。
 *
 * 护栏（任一不满足 -> applied=false，调用方保持现状）：
 * - 重叠 >= minOverlapFrames（默认 20ms）；
 * - 峰值 >= minPeak（0.95）；
 * - |lag| <= maxLagFrames；
 * - 次大峰（排除最大峰 ±3 帧邻域）必须比最大峰低至少 0.02，否则 ambiguous-peak。
 */
object RingBandSeamCalibrator {

    data class Result(
        val applied: Boolean,
        val lagFrames: Int,
        val peak: Double,
        val windowFrames: Int,
        val reason: String?,
        val mode: String? = null,
    )

    const val DEFAULT_MAX_LAG_FRAMES: Int = 1_000
    const val DEFAULT_MIN_PEAK: Double = 0.95
    const val DEFAULT_MIN_OVERLAP_FRAMES: Int = 882
    const val DEFAULT_MIN_PEAK_MARGIN: Double = 0.02

    fun calibrate(
        preludeTail: ByteArray,
        preludeTailFrames: Int,
        ringPrefix: ByteArray,
        ringPrefixFrames: Int,
        bytesPerFrame: Int,
        maxLagFrames: Int = DEFAULT_MAX_LAG_FRAMES,
        minPeak: Double = DEFAULT_MIN_PEAK,
        minOverlapFrames: Int = DEFAULT_MIN_OVERLAP_FRAMES,
        minPeakMargin: Double = DEFAULT_MIN_PEAK_MARGIN,
    ): Result {
        if (bytesPerFrame <= 0 || preludeTailFrames <= 0 || ringPrefixFrames <= 0) {
            return Result(false, 0, 0.0, 0, "no-data")
        }
        val tailFrames = minOf(preludeTailFrames, preludeTail.size / bytesPerFrame)
        val ringFrames = minOf(ringPrefixFrames, ringPrefix.size / bytesPerFrame)
        if (tailFrames <= 0 || ringFrames <= 0) {
            return Result(false, 0, 0.0, 0, "no-data")
        }
        val tail = mono(preludeTail, tailFrames, bytesPerFrame)
        val ring = mono(ringPrefix, ringFrames, bytesPerFrame)
        val window = minOf(tailFrames, ringFrames)
        val maxLag = minOf(maxLagFrames, window - minOverlapFrames)
        if (maxLag < 0) {
            return Result(false, 0, 0.0, 0, "overlap-too-small")
        }
        val peaks = DoubleArray(2 * maxLag + 1) { -2.0 }
        for (lag in -maxLag..maxLag) {
            val overlap = window - abs(lag)
            if (overlap < minOverlapFrames) continue
            val aOff = if (lag >= 0) lag else 0
            val bOff = if (lag >= 0) 0 else -lag
            if (aOff + overlap > ring.size || bOff + overlap > tail.size) continue
            peaks[lag + maxLag] = ncc(ring, aOff, tail, bOff, overlap)
        }
        var bestIdx = -1
        var bestPeak = -2.0
        for (i in peaks.indices) {
            if (peaks[i] > bestPeak) {
                bestPeak = peaks[i]
                bestIdx = i
            }
        }
        if (bestIdx < 0 || bestPeak < -1.0) {
            return Result(false, 0, 0.0, 0, "overlap-too-small")
        }
        val bestLag = bestIdx - maxLag
        val bestWindow = window - abs(bestLag)
        var secondPeak = -2.0
        for (i in peaks.indices) {
            if (abs((i - maxLag) - bestLag) <= 3) continue
            if (peaks[i] > secondPeak) secondPeak = peaks[i]
        }
        if (bestPeak < minPeak || secondPeak > bestPeak - minPeakMargin) {
            // 全窗口法在 4.8ms 级偏移上只有一小段真实重叠（其余是不同内容），峰值低或歧义。
            // 回退到“前缀重叠”法：ring[0..lag] 对 tail 末尾 lag 帧；窗口=lag，峰值门槛 0.995。
            val prefix = calibratePrefix(tail, ring, maxLagFrames, minPeakMargin)
            if (prefix != null) return prefix
        }
        if (bestPeak < minPeak) {
            return Result(false, bestLag, bestPeak.coerceAtLeast(0.0), bestWindow, "low-peak")
        }
        if (secondPeak > bestPeak - minPeakMargin) {
            return Result(
                false, bestLag, bestPeak, bestWindow, "ambiguous-peak",
            )
        }
        if (abs(bestLag) > maxLagFrames) {
            return Result(false, bestLag, bestPeak, bestWindow, "lag-too-large")
        }
        return Result(true, bestLag, bestPeak, bestWindow, null)
    }

    /**
     * 跨 A 参考段校准（主路径）：reference 覆盖 [A-refFrames/2, A+refFrames/2)，
     * ring 覆盖 [A+s, A+s+ringFrames)。搜索 s，二者在真实重叠区做 NCC。
     * 返回的 [Result.lagFrames] 是 s；调用方用 -s 作为环带读取起点偏移。
     */
    fun calibrateCrossA(
        reference: ByteArray,
        referenceFrames: Int,
        ringPrefix: ByteArray,
        ringPrefixFrames: Int,
        bytesPerFrame: Int,
        maxLagFrames: Int = DEFAULT_MAX_LAG_FRAMES,
        minPeak: Double = DEFAULT_MIN_PEAK,
        minOverlapFrames: Int = DEFAULT_MIN_OVERLAP_FRAMES,
        minPeakMargin: Double = DEFAULT_MIN_PEAK_MARGIN,
    ): Result {
        if (bytesPerFrame <= 0 || referenceFrames <= 0 || ringPrefixFrames <= 0) {
            return Result(false, 0, 0.0, 0, "no-data", "reference")
        }
        val refFrames = minOf(referenceFrames, reference.size / bytesPerFrame)
        val ringFrames = minOf(ringPrefixFrames, ringPrefix.size / bytesPerFrame)
        if (refFrames <= 0 || ringFrames <= 0) {
            return Result(false, 0, 0.0, 0, "no-data", "reference")
        }
        val ref = mono(reference, refFrames, bytesPerFrame)
        val ring = mono(ringPrefix, ringFrames, bytesPerFrame)
        val refStart = -(refFrames / 2)
        val refEnd = refStart + refFrames
        val maxLag = minOf(maxLagFrames, maxOf(abs(refStart), abs(refEnd)) + ringFrames)
        val peaks = DoubleArray(2 * maxLag + 1) { -2.0 }
        var bestWindow = 0
        for (s in -maxLag..maxLag) {
            val overlapStart = maxOf(refStart, s)
            val overlapEnd = minOf(refEnd, s + ringFrames)
            val overlap = overlapEnd - overlapStart
            if (overlap < minOverlapFrames) continue
            val refOff = overlapStart - refStart
            val ringOff = overlapStart - s
            if (refOff + overlap > ref.size || ringOff + overlap > ring.size) continue
            peaks[s + maxLag] = ncc(ref, refOff, ring, ringOff, overlap)
        }
        var bestIdx = -1
        var bestPeak = -2.0
        for (i in peaks.indices) {
            if (peaks[i] > bestPeak) {
                bestPeak = peaks[i]
                bestIdx = i
            }
        }
        if (bestIdx < 0 || bestPeak < -1.0) {
            return Result(false, 0, 0.0, 0, "overlap-too-small", "reference")
        }
        val bestLag = bestIdx - maxLag
        bestWindow = run {
            val overlapStart = maxOf(refStart, bestLag)
            val overlapEnd = minOf(refEnd, bestLag + ringFrames)
            (overlapEnd - overlapStart).coerceAtLeast(0)
        }
        var secondPeak = -2.0
        for (i in peaks.indices) {
            if (abs((i - maxLag) - bestLag) <= 3) continue
            if (peaks[i] > secondPeak) secondPeak = peaks[i]
        }
        if (bestPeak < minPeak) {
            return Result(false, bestLag, bestPeak.coerceAtLeast(0.0), bestWindow, "low-peak", "reference")
        }
        if (secondPeak > bestPeak - minPeakMargin) {
            return Result(false, bestLag, bestPeak, bestWindow, "ambiguous-peak", "reference")
        }
        if (abs(bestLag) > maxLagFrames) {
            return Result(false, bestLag, bestPeak, bestWindow, "lag-too-large", "reference")
        }
        return Result(true, bestLag, bestPeak, bestWindow, null, "reference")
    }

    /** 前缀重叠兜底：ring[0..lag) 与 prelude tail 末尾 lag 帧重合。 */
    fun calibratePrefixOnly(
        preludeTail: ByteArray,
        preludeTailFrames: Int,
        ringPrefix: ByteArray,
        ringPrefixFrames: Int,
        bytesPerFrame: Int,
        maxLagFrames: Int = DEFAULT_MAX_LAG_FRAMES,
        minPeakMargin: Double = DEFAULT_MIN_PEAK_MARGIN,
    ): Result {
        if (bytesPerFrame <= 0 || preludeTailFrames <= 0 || ringPrefixFrames <= 0) {
            return Result(false, 0, 0.0, 0, "no-data", "prefix")
        }
        val tailFrames = minOf(preludeTailFrames, preludeTail.size / bytesPerFrame)
        val ringFrames = minOf(ringPrefixFrames, ringPrefix.size / bytesPerFrame)
        val tail = mono(preludeTail, tailFrames, bytesPerFrame)
        val ring = mono(ringPrefix, ringFrames, bytesPerFrame)
        return calibratePrefix(tail, ring, maxLagFrames, minPeakMargin)
            ?: Result(false, 0, 0.0, 0, "prefix-no-match", "prefix")
    }

    private fun calibratePrefix(
        tail: FloatArray,
        ring: FloatArray,
        maxLagFrames: Int,
        minPeakMargin: Double,
    ): Result? {
        // 前缀法只用于小偏移回退；窗口至少 ~3.6ms，峰值 0.995，避免噪声误配。
        val maxLag = minOf(maxLagFrames, tail.size, ring.size, 600)
        val minWindow = minOf(160, maxLag)
        if (maxLag < minWindow) return null
        val peaks = DoubleArray(maxLag + 1) { -2.0 }
        for (lag in minWindow..maxLag) {
            peaks[lag] = ncc(ring, 0, tail, tail.size - lag, lag)
        }
        var bestLag = 0
        var bestPeak = -2.0
        for (lag in 1..maxLag) {
            if (peaks[lag] > bestPeak) {
                bestPeak = peaks[lag]
                bestLag = lag
            }
        }
        if (bestPeak < 0.995) return null
        var secondPeak = -2.0
        for (lag in 1..maxLag) {
            if (abs(lag - bestLag) <= 3) continue
            if (peaks[lag] > secondPeak) secondPeak = peaks[lag]
        }
        if (secondPeak > bestPeak - minPeakMargin) {
            return Result(false, bestLag, bestPeak, bestLag, "ambiguous-peak", "prefix")
        }
        return Result(true, bestLag, bestPeak, bestLag, null, "prefix")
    }

    private fun mono(data: ByteArray, frames: Int, bytesPerFrame: Int): FloatArray {
        val channels = (bytesPerFrame / 2).coerceAtLeast(1)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0.0f
            for (c in 0 until channels) {
                val idx = (i * channels + c) * 2
                if (idx + 1 >= data.size) break
                val lo = data[idx].toInt() and 0xff
                val hi = data[idx + 1].toInt()
                acc += ((hi shl 8) or lo).toShort() / 32768f
            }
            out[i] = acc / channels
        }
        return out
    }

    private fun ncc(a: FloatArray, aOff: Int, b: FloatArray, bOff: Int, n: Int): Double {
        var sa = 0.0
        var sb = 0.0
        for (i in 0 until n) {
            sa += a[aOff + i]
            sb += b[bOff + i]
        }
        val ma = sa / n
        val mb = sb / n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[aOff + i] - ma
            val y = b[bOff + i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        return num / (sqrt(da * db) + 1e-12)
    }
}
