package com.zjr.hesimusic.loop.audio

/**
 * 00017 接缝拼接缓冲原型（纯逻辑，不接 Android / 默认路径）。
 *
 * S = [B-Δ,B) ‖ [A,A+Δ)，长度 2Δ；X 实时捕获，Y 精确解码。
 * 状态：IDLE -> CAPTURING_SEAM_PREFIX -> PLAYING_SEAM -> RESUMING_SOURCE。
 *
 * 该原型只负责“缓冲 + 切换计划 + 统计”，不直接调用 AudioSink；
 * 生产接线时由 sink 在 normal/seam 路由，并在 [onSourceReady] 后从 resumeFrame 继续。
 */
class LoopSeamSpliceBuffer(
    val sampleRate: Int,
    val bytesPerFrame: Int,
    val windowStartFrame: Long,
    val windowEndFrame: Long,
    val deltaFrames: Long,
    private val decodeRange: (startFrame: Long, endFrame: Long) -> ByteArray,
) {

    enum class State {
        IDLE,
        CAPTURING_SEAM_PREFIX,
        PLAYING_SEAM,
        RESUMING_SOURCE,
    }

    init {
        require(sampleRate > 0)
        require(bytesPerFrame > 0)
        require(windowEndFrame > windowStartFrame)
        require(deltaFrames in 1L..(windowEndFrame - windowStartFrame) / 2L)
    }

    private var seam: ByteArray = ByteArray((2L * deltaFrames * bytesPerFrame).toInt())

    var state: State = State.IDLE
        private set

    var seamBufferBytes: Long = seam.size.toLong()
        private set

    var seamSwitchCount: Long = 0L
        private set

    var seamDeferCount: Long = 0L
        private set

    var maxSwitchGapFrames: Long = 0L
        private set

    var seamVerifyFailCount: Long = 0L
        private set

    var lastOutputEndFrame: Long = Long.MIN_VALUE
        private set

    private var capturedX = false
    private var preparedY = false
    private var verified = false
    private var sourceReady = false
    private var deferredTiles = 0L

    private val loopFrames: Long get() = windowEndFrame - windowStartFrame
    private val seamBytesPerHalf: Int get() = (deltaFrames * bytesPerFrame).toInt()

    fun beginCapture(): Boolean {
        if (seam.isEmpty()) return false
        state = State.CAPTURING_SEAM_PREFIX
        capturedX = false
        preparedY = false
        verified = false
        sourceReady = false
        deferredTiles = 0L
        return true
    }

    /** 预解码 X=[B-Δ,B)（无需 warm-up 首轮捕获）。 */
    fun prepareSeamPrefix(): Boolean {
        if (seam.isEmpty()) return false
        val pcm = decodeRange(windowEndFrame - deltaFrames, windowEndFrame)
        val need = seamBytesPerHalf
        if (pcm.size < need) return false
        System.arraycopy(pcm, 0, seam, 0, need)
        capturedX = true
        return true
    }

    /**
     * warm-up 正常输出时调用：把 [startFrame,startFrame+frames) 中落在
     * [B-Δ,B) 的实际 PCM 复制进 X。
     */
    fun captureSeamPrefix(pcm16: ByteArray, startFrame: Long, frames: Int): Boolean {
        if (state != State.CAPTURING_SEAM_PREFIX) return false
        if (startFrame < 0L || frames <= 0) return false
        val xStart = windowEndFrame - deltaFrames
        val copyStart = maxOf(startFrame, xStart)
        val copyEnd = minOf(startFrame + frames, windowEndFrame)
        if (copyEnd <= copyStart) return false
        val srcOffset = ((copyStart - startFrame) * bytesPerFrame).toInt()
        val dstOffset = ((copyStart - xStart) * bytesPerFrame).toInt()
        val length = ((copyEnd - copyStart) * bytesPerFrame).toInt()
        if (srcOffset + length > pcm16.size || dstOffset + length > seamBytesPerHalf) return false
        System.arraycopy(pcm16, srcOffset, seam, dstOffset, length)
        if (copyStart == xStart && copyEnd == windowEndFrame) capturedX = true
        return true
    }

    /** 精确解码 Y=[A,A+Δ) 并安装到 S 后半段。 */
    fun prepareSeamSuffix(): Boolean {
        if (seam.isEmpty()) return false
        val pcm = decodeRange(windowStartFrame, windowStartFrame + deltaFrames)
        val need = seamBytesPerHalf
        if (pcm.size < need) return false
        System.arraycopy(pcm, 0, seam, seamBytesPerHalf, need)
        preparedY = true
        return true
    }

    /**
     * 切换点在线校验：只比较前 N 个样本，|live-X| 超阈值则本轮不切。
     * 比较整段 Δ 也可以，但首样本/前 16 样本足以发现对齐错误，且开销恒定。
     */
    fun verifyLivePrefix(livePcm: ByteArray, liveFrames: Int, maxAbsDiff: Int = 64): Boolean {
        if (!capturedX || livePcm.size < bytesPerFrame || liveFrames <= 0) {
            verified = false
            seamVerifyFailCount += 1L
            return false
        }
        val count = minOf(liveFrames.toLong(), deltaFrames, 16L).toInt()
        var maxDiff = 0
        for (i in 0 until count) {
            val live = readShort(livePcm, i * bytesPerFrame)
            val expect = readShort(seam, i * bytesPerFrame)
            maxDiff = maxOf(maxDiff, kotlin.math.abs(live - expect))
        }
        if (maxDiff > maxAbsDiff) {
            verified = false
            seamVerifyFailCount += 1L
            return false
        }
        verified = true
        return true
    }

    private fun readShort(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    /** 进入 B-Δ：输出 X+Y。返回 false 表示 X/Y 未就绪或校验失败（继续源播放）。 */
    fun beginSeam(): Boolean {
        if (!capturedX || !preparedY || !verified) return false
        state = State.PLAYING_SEAM
        sourceReady = false
        deferredTiles = 0L
        seamSwitchCount += 1L
        return true
    }

    /** S 前半段 X=[B-Δ,B)。 */
    fun seamPrefixBytes(): ByteArray = seam.copyOfRange(0, seamBytesPerHalf)

    /** S 后半段 Y=[A,A+Δ)（defer 后会被覆盖为下一个 Δ）。 */
    fun seamSuffixBytes(): ByteArray = seam.copyOfRange(seamBytesPerHalf, 2 * seamBytesPerHalf)

    /** S 播完：进入 RESUMING_SOURCE。 */
    fun finishSeam(): Long {
        state = State.RESUMING_SOURCE
        return resumeFrame()
    }

    fun markSourceReady(): Long {
        sourceReady = true
        state = State.IDLE
        return resumeFrame()
    }

    /**
     * 源在 A+Δ 未就绪：复用 Y 区解码下一个 Δ 并返回该段 PCM。
     * 内存保持 2Δ，不重新分配。
     */
    fun deferSeam(): ByteArray? {
        if (state != State.RESUMING_SOURCE) return null
        val start = resumeFrame()
        val end = start + deltaFrames
        val pcm = decodeRange(start, end)
        val need = seamBytesPerHalf
        if (pcm.size < need) return null
        System.arraycopy(pcm, 0, seam, seamBytesPerHalf, need)
        deferredTiles += 1L
        seamDeferCount += 1L
        preparedY = true
        return seamSuffixBytes()
    }

    fun resumeFrame(): Long = windowStartFrame + deltaFrames + deferredTiles * deltaFrames

    /** 记录一段实际输出，用于统计切换点样本缺口。 */
    fun noteOutput(startFrame: Long, frames: Long) {
        if (frames <= 0L) return
        val end = startFrame + frames
        if (lastOutputEndFrame != Long.MIN_VALUE) {
            val gap = when {
                lastOutputEndFrame == windowEndFrame && startFrame == windowStartFrame -> 0L
                else -> kotlin.math.abs(startFrame - lastOutputEndFrame)
            }
            if (gap > maxSwitchGapFrames) maxSwitchGapFrames = gap
        }
        lastOutputEndFrame = end
    }

    fun cancel() {
        seam = ByteArray(0)
        seamBufferBytes = 0L
        capturedX = false
        preparedY = false
        verified = false
        sourceReady = false
        deferredTiles = 0L
        state = State.IDLE
        lastOutputEndFrame = Long.MIN_VALUE
    }
}

/** 00017 开关；默认关闭，生产默认走 M3′。 */
enum class SeamBufferMode {
    OFF,
    ON,
}
