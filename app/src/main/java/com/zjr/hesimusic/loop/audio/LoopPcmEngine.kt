package com.zjr.hesimusic.loop.audio

import com.zjr.hesimusic.loop.LoopWindow
import java.nio.ByteBuffer

/**
 * 无缝循环的 PCM 状态机（纯 Kotlin，无 Android / Media3 依赖，可在 JVM 单测）。
 *
 * 状态：
 *   PASS_THROUGH --(window 有效 & 格式已知)--> CAPTURING --(输入越过 B 且 ring 完整)--> REPLAYING
 *        ^                                          |                                      |
 *        +-------------- window 变更 / release <----+--------------------------------------+
 *
 * 关键口径：
 * - 输入 PTS 一律是**媒体时间**（调用方已减掉 outputStreamOffsetUs）。
 * - 环回窗口是半开区间 [A, B)；A/B 落在 buffer 中间时按帧整数取整（ceiling）。
 * - ring 长度严格等于 ceil(B*sr/1e6) - ceil(A*sr/1e6) 帧；重放按帧推进，PTS 由帧号
 *   反算，因此跨轮零漂移。
 * - 所有时间/帧换算都用 Long 整数算术，不做浮点累积。
 *
 * 线程模型：所有公开方法都在内部锁下执行。正常调用方是音频线程；window 变更可能来自
 * 服务层线程，因此 setWindow/configure 也必须可重入安全。
 */
class LoopPcmEngine {

    enum class State {
        PASS_THROUGH,
        CAPTURING,
        REPLAYING,
    }

    /** 音频输出格式（PCM）。 */
    data class Format(
        val sampleRate: Int,
        val channelCount: Int,
        val bytesPerFrame: Int,
        val pcmIsFloat: Boolean,
    ) {
        fun isValid(): Boolean = sampleRate > 0 && bytesPerFrame > 0 && channelCount > 0
    }

    /**
     * [seedRing] 的结果状态。
     *
     * - [PENDING]：引擎还没有输出格式（sink 未 configure），调用方（controller）应暂存，
     *   待 onFormatConfigured 后重试；
     * - [REJECTED_*]：调用方应丢弃这份预填 PCM（日志说明原因，不重试）。
     */
    enum class SeedStatus {
        INSTALLED,
        PENDING,
        REJECTED_WINDOW,
        REJECTED_FORMAT,
        REJECTED_SIZE,
        REJECTED_BUSY,
        REJECTED_RELEASED,
    }

    /** [seedRing] 的返回值；[frames] = 实际装进 ring 的帧数，[detail] 供日志/单测断言。 */
    data class SeedResult(
        val status: SeedStatus,
        val frames: Long = 0L,
        val detail: String = "",
    ) {
        val isInstalled: Boolean get() = status == SeedStatus.INSTALLED
    }

    /** 当前窗口对应的帧坐标（A/B 均已换算为绝对帧号）。 */
    data class Region(
        val window: LoopWindow,
        val format: Format,
        val aFrame: Long,
        val bFrame: Long,
    ) {
        val loopFrames: Long get() = bFrame - aFrame

        /**
         * 一轮的媒体时长（µs）。由帧数反算而不是直接用 B-A，
         * 这样 A/B 不落在帧边界时，重放 PTS 与实际输出帧数严格一致（零漂移）。
         */
        val loopDurationUs: Long
            get() = if (format.sampleRate > 0) {
                loopFrames * US_PER_SECOND / format.sampleRate
            } else {
                0L
            }
    }

    /**
     * 单个输入 buffer 的处理计划。
     *
     * [forwardOffset]/[forwardLength] 是**绝对** buffer 偏移（相对 ByteBuffer 0 位置），
     * 调用方只能把这段交给 delegate；[discard] 为 true 时整段输入应被消费但不转发。
     * [enterReplayWhenDrained] 表示这段输入全部被 delegate 吃掉后应尝试切到重放。
     * [entryCursorFrames]/[entryRound] 是进入重放的初始环内游标/轮次：
     * 仅当 ring 已完整且输入 PTS >= B 时按 PTS 折返相位计算，否则为 0/1。
     */
    data class BufferPlan(
        val forwardOffset: Int,
        val forwardLength: Int,
        val forwardFrames: Int,
        val discard: Boolean,
        val enterReplayWhenDrained: Boolean,
        val degraded: Boolean = false,
        val entryCursorFrames: Long = 0L,
        val entryRound: Long = 1L,
    ) {
        val forwardEndOffset: Int get() = forwardOffset + forwardLength
    }

    /** 一次重放喂送。ringOffsetBytes/byteLength 描述 ring 中的一段连续 PCM。 */
    data class ReplayChunk(
        val ringOffsetBytes: Int,
        val byteLength: Int,
        val frames: Int,
        val ptsUs: Long,
        val round: Long,
        val frameOffsetInRound: Long,
    )

    @Volatile
    var state: State = State.PASS_THROUGH
        private set

    @Volatile
    var ringFrames: Long = 0L
        private set

    @Volatile
    var capturedFrames: Long = 0L
        private set

    @Volatile
    var isRingComplete: Boolean = false
        private set

    /** 环回次数（一轮播完、光标折返时 +1）。 */
    @Volatile
    var seamCount: Long = 0L
        private set

    /** 降级次数（ring 超限 / 分配失败 / 不完整 / 重放异常）。 */
    @Volatile
    var degradeCount: Long = 0L
        private set

    /** 已重放输出的累计帧数（debug/E2E 用）。 */
    @Volatile
    var totalReplayFrames: Long = 0L
        private set

    /**
     * 当前 ring 是否由 seed（解码预填）安装，而不是由播放捕获填满。
     *
     * 预填窗口内不再用播放捕获覆盖 ring：预填 PCM 来自分析侧解码器，与播放链解码输出
     * 可能有极微差异；若允许捕获覆盖，不同轮次（预填轮 vs 后续轮）的环内容就不再逐位相同。
     */
    @Volatile
    var isRingPrefilled: Boolean = false
        private set

    private var format: Format? = null
    private var window: LoopWindow? = null
    private var region: Region? = null
    private var regionDirty = true
    private var unavailableWindow: LoopWindow? = null
    private var released = false

    private var ring: ByteArray? = null
    private var coverage = Coverage()

    private var cursorFrames = 0L
    private var round = 1L
    private var pendingChunk: ReplayChunk? = null
    private var pendingConsumedBytes = 0
    private var pendingAdvancedFrames = 0L
    private var incompleteDegraded = false
    private var replayUnavailable = false

    /** flush/discontinuity 后的首个输入是否可用于“区间内部 seek”相位定位。 */
    private var pendingPostFlushPhase = false

    /** 侧车 seam.iter：每次进入重放从 1 开始，折返递增。 */
    private var seamIteration = 0L

    /** 是否由 controller 要求“音频线程不做大分配”（生产路径）。 */
    private var preallocationRequired = false
    private var preallocated: Preallocated? = null

    /** 音频线程上实际发生过几次 ring 分配（用于单测断言预分配生效）。 */
    internal var audioThreadAllocationCount: Long = 0L
        private set

    /** 降级事件回调（由 controller 注入；engine 保证同一事件只回调一次）。 */
    internal var onDegrade: ((targetRawUs: Long) -> Unit)? = null
    private var degradeCallbackArmed = true

    /** 接缝事件回调：iter=轮次，outReplayFrames=已完成重放帧，ptsUs=新媒体域轮起点。 */
    internal var onSeam: ((iter: Long, outReplayFrames: Long, ptsUs: Long) -> Unit)? = null

    // ------------------------------------------------------------------ 配置

    /** 记录输出格式。格式变化时 ring 失效并等待下一次输入重建。 */
    @Synchronized
    fun configure(newFormat: Format) {
        if (!newFormat.isValid()) {
            format = null
            invalidateRegionLocked()
            return
        }
        if (format == newFormat) {
            // 同格式的 configure 不重建 ring（flush/reset 保留 ring 的语义依赖于此），
            // 但必须清掉上一个 sink 生命周期留下的瞬态降级/重放状态。
            released = false
            replayUnavailable = false
            incompleteDegraded = false
            pendingChunk = null
            pendingConsumedBytes = 0
            pendingAdvancedFrames = 0L
            cursorFrames = 0L
            round = 1L
            degradeCallbackArmed = true
            state = if (region != null) State.CAPTURING else State.PASS_THROUGH
            return
        }
        format = newFormat
        released = false
        invalidateRegionLocked()
    }

    /** 服务层窗口变化：重建 ring（不在这里分配，第一次输入时按需分配）。 */
    @Synchronized
    fun setWindow(newWindow: LoopWindow?) {
        if (window == newWindow) return
        window = newWindow
        invalidateRegionLocked()
    }

    /**
     * 由 controller 开启“预分配模式”：window/format 写入时后台分配 ring，
     * 音频线程只做容量校验；ring 未就绪时 onBuffer 不分配、保持 pending。
     */
    @Synchronized
    fun requirePreallocation() {
        preallocationRequired = true
    }

    /** 当前 window/format 是否已经有一块可用的 ring（预分配或已在捕获）。 */
    @Synchronized
    fun hasRingFor(window: LoopWindow, format: Format): Boolean {
        val r = region
        return r != null && ring != null && r.window == window && r.format == format
    }

    /**
     * 后台线程完成分配后安装 ring。window/format 必须仍是当前值，字节数必须匹配
     * planRing 预算；已存在同窗口 ring 时保留现有捕获进度。
     */
    @Synchronized
    internal fun offerPreallocatedRing(
        window: LoopWindow,
        format: Format,
        bytes: ByteArray,
    ): Boolean {
        if (released) return false
        if (this.window != window || this.format != format) return false
        val plan = planRing(window, format) ?: return false
        if (bytes.size.toLong() != plan.byteSize) return false
        if (hasRingForLocked(window, format)) return true
        preallocated = Preallocated(window, format, plan.loopFrames, bytes)
        // 后台线程直接把 ring/region 安装好，音频线程首次回调只剩容量校验。
        regionDirty = false
        region = Region(window, format, plan.aFrame, plan.bFrame)
        ring = bytes
        coverage = Coverage()
        ringFrames = plan.loopFrames
        capturedFrames = 0L
        isRingComplete = false
        isRingPrefilled = false
        incompleteDegraded = false
        cursorFrames = 0L
        round = 1L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        degradeCallbackArmed = true
        state = if (replayUnavailable) State.PASS_THROUGH else State.CAPTURING
        return true
    }

    /**
     * 解码侧预填 ring（后台线程调用）：把 [window] 对应的 PCM16（交错、小端、[channels] 声道、
     * [sampleRate] Hz）直接装进 ring，覆盖率立即 100%，并标记 prefilled。
     *
     * 帧数口径：
     * - 要求至少覆盖 ceil((B−A)*sampleRate/1e6) 帧（与 [PcmDecoder] 的产出严格对应）；
     * - ring 实际长度是 ceil(B*sr/1e6) − ceil(A*sr/1e6) 帧，可能比上一步少 1 帧
     *   （A/B 都不落在帧边界时），多出来的尾帧丢弃；
     * - 不足帧直接拒绝（不静默补零），由调用方走兜底捕获路径。
     *
     * 只有 window 与当前窗口完全一致、且 sampleRate/channels 与 sink 输出格式一致时才安装；
     * 预填不会改 [degradeCount]。安装后该窗口内 [captureLocked] 不再覆盖 ring。
     */
    @Synchronized
    fun seedRing(
        window: LoopWindow,
        pcm16: ByteArray,
        sampleRate: Int,
        channels: Int,
    ): SeedResult {
        if (!window.isValid()) {
            return SeedResult(SeedStatus.REJECTED_WINDOW, detail = "invalid window=$window")
        }
        if (sampleRate <= 0 || channels <= 0) {
            return SeedResult(
                SeedStatus.REJECTED_FORMAT,
                detail = "invalid sampleRate=$sampleRate channels=$channels",
            )
        }
        if (pcm16.isEmpty()) {
            return SeedResult(SeedStatus.REJECTED_SIZE, detail = "empty pcm16")
        }
        if (released) {
            return SeedResult(SeedStatus.REJECTED_RELEASED, detail = "sink released")
        }
        val fmt = format ?: return SeedResult(SeedStatus.PENDING, detail = "format unknown")
        if (this.window != window) {
            return SeedResult(
                SeedStatus.REJECTED_WINDOW,
                detail = "window mismatch current=" + this.window,
            )
        }
        if (fmt.sampleRate != sampleRate || fmt.channelCount != channels) {
            return SeedResult(
                SeedStatus.REJECTED_FORMAT,
                detail = "format mismatch sr=$sampleRate ch=$channels engine=" +
                    fmt.sampleRate + "/" + fmt.channelCount,
            )
        }
        if (fmt.pcmIsFloat || fmt.bytesPerFrame != channels * 2) {
            // 预填数据是 16bit 交错 PCM；float/24bit/32bit 输出无法直接装。
            return SeedResult(
                SeedStatus.REJECTED_FORMAT,
                detail = "output not pcm16 bpf=" + fmt.bytesPerFrame +
                    " float=" + fmt.pcmIsFloat,
            )
        }
        if (state == State.REPLAYING) {
            // 正在重放时替换 ring 内容会让本轮与下一轮听到不同样本，拒绝。
            return SeedResult(SeedStatus.REJECTED_BUSY, detail = "replaying")
        }
        val plan = planRing(window, fmt) ?: return SeedResult(
            SeedStatus.REJECTED_WINDOW,
            detail = "planRing unavailable (too long/too large) window=$window",
        )
        val requiredFrames = usLengthToFrameCeil(window.lengthUs, sampleRate)
        val srcBytesPerFrame = channels * 2
        val pcmFrames = pcm16.size.toLong() / srcBytesPerFrame
        if (pcmFrames < requiredFrames || pcmFrames < plan.loopFrames) {
            return SeedResult(
                SeedStatus.REJECTED_SIZE,
                frames = pcmFrames,
                detail = "insufficient pcm frames=$pcmFrames required=$requiredFrames " +
                    "loop=" + plan.loopFrames,
            )
        }
        val byteSize = (plan.loopFrames * fmt.bytesPerFrame).toInt()
        if (byteSize <= 0) {
            return SeedResult(SeedStatus.REJECTED_WINDOW, detail = "zero byteSize")
        }
        val copyBytes = (plan.loopFrames * srcBytesPerFrame).toInt()

        // 同 window/format 已有 ring 时原地覆盖（分配发生在后台线程，音频线程不参与）。
        var target = ring
        if (target == null || target.size != byteSize ||
            region?.window != window || region?.format != fmt
        ) {
            target = ByteArray(byteSize)
            region = Region(window, fmt, plan.aFrame, plan.bFrame)
            regionDirty = false
            ring = target
        }
        System.arraycopy(pcm16, 0, target, 0, copyBytes)

        coverage = Coverage().apply { add(0L, plan.loopFrames, plan.loopFrames) }
        ringFrames = plan.loopFrames
        capturedFrames = plan.loopFrames
        isRingComplete = true
        isRingPrefilled = true
        incompleteDegraded = false
        replayUnavailable = false
        degradeCallbackArmed = true
        unavailableWindow = null
        cursorFrames = 0L
        round = 1L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        state = State.CAPTURING
        return SeedResult(SeedStatus.INSTALLED, frames = plan.loopFrames)
    }

    /**
     * 预分配是否仍未就绪。sink 在首个 handleBuffer 里据此施加背压，避免音频线程
     * 自己分配大 ring，也避免丢数据。
     */
    @Synchronized
    fun isPreallocationPending(): Boolean {
        if (!preallocationRequired || released || replayUnavailable) return false
        if (region != null && ring != null) return false
        val w = window ?: return false
        val f = format ?: return false
        val plan = planRing(w, f) ?: return false
        val pre = preallocated
        return pre == null ||
            pre.window != w ||
            pre.format != f ||
            pre.loopFrames != plan.loopFrames ||
            pre.bytes.size.toLong() != plan.byteSize
    }

    /** 预分配等待超时/失败后的兜底：放弃预分配并降级为直通。 */
    @Synchronized
    fun abandonPreallocation() {
        if (region != null) return
        if (!isPreallocationPending()) return
        replayUnavailable = true
        degradeCount += 1L
        state = State.PASS_THROUGH
    }

    /** AudioSink.handleDiscontinuity 通知：下一次输入可按 PTS 重新定位重放相位。 */
    @Synchronized
    fun onInputDiscontinuity() {
        if (!released) pendingPostFlushPhase = true
    }

    /** flush/discontinuity 后是否还在等待“下一段输入”来决定重放入口相位。 */
    @Synchronized
    fun isPostFlushPhasePending(): Boolean = pendingPostFlushPhase

    /**
     * REPLAYING 中收到 discontinuity 后的第一段输入：ring 完整时按 PTS 立即重定位，
     * 不需要依赖 flush。返回 true 表示已重定位；false 时若 state 变为 CAPTURING，
     * 调用方应把该输入按普通前奏路径处理。
     */
    @Synchronized
    fun tryRephaseFromInput(frameTimeUs: Long): Boolean {
        if (!pendingPostFlushPhase) return false
        val r = ensureRegionLocked() ?: return false
        val fmt = format ?: return false
        val startFrameAbs = frameFromUsRounded(frameTimeUs, fmt.sampleRate)
        if (!isRingComplete) {
            pendingPostFlushPhase = false
            return false
        }
        if (startFrameAbs >= r.aFrame && startFrameAbs < r.bFrame) {
            pendingPostFlushPhase = false
            state = State.REPLAYING
            cursorFrames = startFrameAbs - r.aFrame
            round = 0L
            pendingChunk = null
            pendingConsumedBytes = 0
            pendingAdvancedFrames = 0L
            seamIteration = 0L
            if (cursorFrames == 0L) notifySeamLocked()
            return true
        }
        if (startFrameAbs >= r.bFrame) {
            pendingPostFlushPhase = false
            state = State.REPLAYING
            cursorFrames = entryCursorFramesLocked(startFrameAbs, r)
            round = entryRoundLocked(startFrameAbs, r)
            pendingChunk = null
            pendingConsumedBytes = 0
            pendingAdvancedFrames = 0L
            seamIteration = 0L
            if (cursorFrames == 0L) notifySeamLocked()
            return true
        }
        // PTS < A：seek 到前奏，退出重放，让调用方按普通输入处理。
        pendingPostFlushPhase = false
        state = State.CAPTURING
        cursorFrames = 0L
        round = 1L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        seamIteration = 0L
        return false
    }

    /** 由服务层/调试显式废弃 ring（窗口变更等）。 */
    @Synchronized
    fun invalidateRing() {
        invalidateRegionLocked()
    }

    /** 当前配置是否处于可环回状态（window 合法、格式有效、ring 可分配）。 */
    @Synchronized
    fun isLoopConfigured(): Boolean {
        if (released || replayUnavailable) return false
        return ensureRegionLocked() != null
    }

    /** 当前引擎侧媒体位置（µs）：重放期 = B+(k-1)L+环内偏移。 */
    @Synchronized
    fun currentPositionUs(): Long {
        val r = region ?: return -1L
        if (state != State.REPLAYING) return r.window.loopEndUs
        val fmt = format ?: return -1L
        // cursorFrames 已经包含“本 chunk 内已确认消费”的帧；
        // pendingAdvancedFrames 是它的累计量，不能再加一次。
        val effectiveCursor = cursorFrames
        return replayPtsLocked(effectiveCursor, round, r, fmt)
    }

    // ------------------------------------------------------------------ 输入

    /**
     * 计算一个输入 buffer 的转发范围，并**立即**把落在 [A,B) 内的帧写进 ring
     * （输入 PCM 是源数据，提前捕获不影响最终输出内容；调用方必须每个 buffer 只调一次）。
     */
    @Synchronized
    fun onBuffer(frameTimeUs: Long, frameCount: Int, src: ByteBuffer): BufferPlan {
        val srcPos = src.position()
        val srcRemaining = src.limit() - srcPos
        if (released) return passThroughPlan(srcPos, frameCount, srcRemaining)

        val fmt = format
        if (fmt == null || !fmt.isValid()) {
            return passThroughPlan(srcPos, frameCount, srcRemaining)
        }
        val r = ensureRegionLocked()
        if (r == null || replayUnavailable) {
            return passThroughPlan(srcPos, frameCount, srcRemaining)
        }
        if (state == State.REPLAYING) {
            return BufferPlan(
                forwardOffset = srcPos,
                forwardLength = 0,
                forwardFrames = 0,
                discard = true,
                enterReplayWhenDrained = false,
            )
        }

        val bpf = fmt.bytesPerFrame
        val availableFrames = minOf(
            if (frameCount > 0) frameCount.toLong() else 0L,
            (srcRemaining / bpf).toLong(),
        )
        if (availableFrames <= 0L) {
            return BufferPlan(srcPos, 0, 0, discard = true, enterReplayWhenDrained = false)
        }

        val startFrameAbs = frameFromUsRounded(frameTimeUs, fmt.sampleRate)
        val endFrameAbs = startFrameAbs + availableFrames

        // flush/discontinuity 后：ring 完整且下一段输入直接落在 [A,B) 内部时，
        // 立即按输入 PTS 的相位进入重放（不捕获、不改 ring）。典型场景:播放中 seek。
        if (pendingPostFlushPhase) {
            if (isRingComplete && startFrameAbs >= r.aFrame && startFrameAbs < r.bFrame) {
                pendingPostFlushPhase = false
                state = State.REPLAYING
                cursorFrames = startFrameAbs - r.aFrame
                // round=0 表示“从 B 前的一个不完整轮次开始”：
                // PTS = B + (0-1)L + cursor = A + cursor = 输入 PTS，保证连续。
                round = 0L
                pendingChunk = null
                pendingConsumedBytes = 0
                pendingAdvancedFrames = 0L
                // 只有从整轮起点(A)进入才记 seam；区间中部 seek 的首个部分是
                // 不完整轮次，不该被 A2 当作正常接缝。
                if (cursorFrames == 0L) notifySeamLocked()
                return BufferPlan(
                    forwardOffset = srcPos,
                    forwardLength = 0,
                    forwardFrames = 0,
                    discard = true,
                    enterReplayWhenDrained = false,
                    entryCursorFrames = cursorFrames,
                    entryRound = 0L,
                )
            }
            // 前奏/≥B/ring 不完整等:本次不适用相位入口，正常走原有路径。
            pendingPostFlushPhase = false
        }

        // 先把 [A,B) 内的帧捕获到 ring（不改变 src 的 position）。
        val capStart = maxOf(startFrameAbs, r.aFrame)
        val capEnd = minOf(endFrameAbs, r.bFrame)
        if (capEnd > capStart) {
            captureLocked(src, srcPos, startFrameAbs, capStart, capEnd, r, fmt)
        }

        // 转发范围裁到 B（半开区间，B 样本不转发）。
        val forwardEndAbs = minOf(endFrameAbs, r.bFrame)
        val forwardFrames = (forwardEndAbs - startFrameAbs).coerceIn(0L, availableFrames)
        // 注意用 >=：clip 源的最后一个 buffer 可能恰好结束在 B 上（此时没有尾部可裁），
        // 也必须在这一段消费完后立刻进入重放。
        val crossedB = endFrameAbs >= r.bFrame || startFrameAbs >= r.bFrame
        val entryCursor = if (crossedB) entryCursorFramesLocked(startFrameAbs, r) else 0L
        val entryRound = if (crossedB) entryRoundLocked(startFrameAbs, r) else 1L

        if (forwardFrames <= 0L) {
            return BufferPlan(
                forwardOffset = srcPos,
                forwardLength = 0,
                forwardFrames = 0,
                discard = true,
                enterReplayWhenDrained = crossedB,
                entryCursorFrames = entryCursor,
                entryRound = entryRound,
            )
        }

        return BufferPlan(
            forwardOffset = srcPos,
            forwardLength = (forwardFrames * bpf).toInt(),
            forwardFrames = forwardFrames.toInt(),
            discard = false,
            enterReplayWhenDrained = crossedB,
            entryCursorFrames = entryCursor,
            entryRound = entryRound,
        )
    }

    /**
     * 调用方确认 plan 对应的输入已被 delegate 全部消费后调用（部分消费时不要调用）。
     * 越过 B 且 ring 完整时切到 REPLAYING；ring 不完整则记一次降级并停在 CAPTURING。
     */
    @Synchronized
    fun onInputDrained(plan: BufferPlan) {
        if (!plan.enterReplayWhenDrained) return
        if (state == State.REPLAYING) return
        val r = ensureRegionLocked() ?: return
        if (!isRingComplete) {
            if (!incompleteDegraded) {
                incompleteDegraded = true
                degradeCount += 1L
                notifyDegradeLocked()
            }
            // 不完整时不能让 sink 永久停在“循环态”空转：降级为直通，
            // 由服务层（seek 回 A 补环 / 清窗口）决定后续。
            replayUnavailable = true
            state = State.PASS_THROUGH
            return
        }
        state = State.REPLAYING
        cursorFrames = if (r.loopFrames > 0L) {
            plan.entryCursorFrames.coerceIn(0L, r.loopFrames - 1L)
        } else {
            0L
        }
        round = plan.entryRound.coerceAtLeast(1L)
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        if (r.loopFrames <= 0L) state = State.PASS_THROUGH
        // 从 B 之后 seek 进入时首轮可能是不完整的（cursor != 0），不记 seam。
        if (cursorFrames == 0L) notifySeamLocked()
    }

    /**
     * 源端 EOS：如果 ring 已完整但最后一个 buffer 恰好停在 B 之前一帧（没有触发
     * crossedB），在这里兜底切到重放。ring 不完整则降级为直通。
     *
     * @return true = 已处于/已进入 REPLAYING
     */
    @Synchronized
    fun onInputEnded(): Boolean {
        if (released) return false
        if (state == State.REPLAYING) return true
        // seek/flush 后还没看到新输入：不能先按 0 进入重放，否则会把 seek 落点吞成 A。
        if (pendingPostFlushPhase) return false
        if (replayUnavailable) return false
        ensureRegionLocked() ?: return false
        if (!isRingComplete) {
            if (!incompleteDegraded) {
                incompleteDegraded = true
                degradeCount += 1L
                notifyDegradeLocked()
            }
            replayUnavailable = true
            state = State.PASS_THROUGH
            return false
        }
        state = State.REPLAYING
        cursorFrames = 0L
        round = 1L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        notifySeamLocked()
        return true
    }

    // ------------------------------------------------------------------ 重放

    /**
     * 取下一段重放数据；输出上限 [maxBytes] 字节。上一段尚未被 onReplayChunkConsumed
     * 完全确认时返回 null（调用方必须保留同一个 ByteBuffer 重试）。
     */
    @Synchronized
    fun pumpChunk(maxBytes: Int): ReplayChunk? {
        if (state != State.REPLAYING) return null
        if (pendingChunk != null) return null
        val fmt = format ?: return null
        val r = region ?: return null
        val bpf = fmt.bytesPerFrame
        if (bpf <= 0 || maxBytes <= 0) return null
        if (r.loopFrames <= 0L) return null

        if (cursorFrames >= r.loopFrames) {
            cursorFrames = 0L
            round += 1L
            seamCount += 1L
            notifySeamLocked()
        }
        val maxFrames = maxOf(1, maxBytes / bpf).toLong()
        val frames = minOf(maxFrames, r.loopFrames - cursorFrames).toInt()
        if (frames <= 0) return null
        val byteLength = frames * bpf
        val chunk = ReplayChunk(
            ringOffsetBytes = (cursorFrames * bpf).toInt(),
            byteLength = byteLength,
            frames = frames,
            ptsUs = replayPtsLocked(cursorFrames, round, r, fmt),
            round = round,
            frameOffsetInRound = cursorFrames,
        )
        pendingChunk = chunk
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        return chunk
    }

    /**
     * delegate 实际消费了 [consumedBytes]（可能小于 chunk.byteLength，调用方用同一个
     * ByteBuffer 重试剩余部分）。只有整段确认后才推进到下一个 chunk。
     */
    @Synchronized
    fun onReplayChunkConsumed(consumedBytes: Int) {
        val chunk = pendingChunk ?: return
        if (consumedBytes <= 0) return
        val remainingBytes = chunk.byteLength - pendingConsumedBytes
        val accepted = minOf(consumedBytes, remainingBytes)
        if (accepted <= 0) return
        pendingConsumedBytes += accepted

        val fmt = format ?: return
        val targetFrames = (pendingConsumedBytes / fmt.bytesPerFrame).toLong()
        val frameStep = targetFrames - pendingAdvancedFrames
        if (frameStep > 0L) {
            advanceReplayCursorLocked(frameStep)
            pendingAdvancedFrames = targetFrames
        }
        if (pendingConsumedBytes >= chunk.byteLength) {
            pendingChunk = null
            pendingConsumedBytes = 0
            pendingAdvancedFrames = 0L
        }
    }

    /** 把 chunk 的 PCM 拷贝到调用方的 direct LE ByteBuffer。 */
    @Synchronized
    fun copyChunk(chunk: ReplayChunk, dst: ByteBuffer) {
        val bytes = ring ?: return
        if (chunk.byteLength <= 0) return
        if (chunk.ringOffsetBytes < 0) return
        if (chunk.ringOffsetBytes + chunk.byteLength > bytes.size) return
        if (dst.remaining() < chunk.byteLength) return
        dst.put(bytes, chunk.ringOffsetBytes, chunk.byteLength)
    }

    // ------------------------------------------------------------------ 生命周期

    /** flush：退出重放、清 pending；ring/覆盖进度保留（窗口不变时下次仍可用）。 */
    @Synchronized
    fun onFlush() {
        // flush 通常由 seek 触发：允许重新捕获补环、允许再次降级通知。
        replayUnavailable = false
        incompleteDegraded = false
        degradeCallbackArmed = true
        pendingPostFlushPhase = true
        seamIteration = 0L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        cursorFrames = 0L
        round = 1L
        state = when {
            released -> State.PASS_THROUGH
            region != null -> State.CAPTURING
            else -> State.PASS_THROUGH
        }
    }

    /** reset：与 flush 相同，保留 ring。 */
    @Synchronized
    fun onReset() {
        onFlush()
    }

    /** release：停止一切重放，之后 onBuffer 一律直通。 */
    @Synchronized
    fun onRelease() {
        released = true
        pendingPostFlushPhase = false
        seamIteration = 0L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        cursorFrames = 0L
        round = 1L
        state = State.PASS_THROUGH
    }

    /** 重放写 delegate 抛异常时调用：降级并退出重放。 */
    @Synchronized
    fun onReplayFailure() {
        if (state == State.REPLAYING) {
            degradeCount += 1L
            notifyDegradeLocked()
        }
        replayUnavailable = true
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        cursorFrames = 0L
        round = 1L
        state = State.PASS_THROUGH
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 降级事件只回调一次（同一事件/同一 epoch）。flush 会重新 arm，允许补环失败后再次通知。
     * 注意：回调在 engine 锁内、音频线程上执行；controller 必须把它 dispatch 到主线程。
     */
    private fun notifyDegradeLocked() {
        if (!degradeCallbackArmed) return
        degradeCallbackArmed = false
        val target = degradeTargetUsLocked()
        onDegrade?.invoke(target)
    }

    /** 建议 seek 目标：当前轮起点 - 250ms，下限 0。 */
    private fun degradeTargetUsLocked(): Long {
        val r = region
        val roundStart = if (r != null) {
            r.window.loopStartUs +
                (round - 1L).coerceAtLeast(0L) * r.loopDurationUs
        } else {
            window?.loopStartUs ?: 0L
        }
        return (roundStart - DEGRADE_SEEK_BACK_US).coerceAtLeast(0L)
    }

    private fun passThroughPlan(srcPos: Int, frameCount: Int, remainingBytes: Int): BufferPlan {
        val frames = if (frameCount > 0) frameCount else {
            val bpf = format?.bytesPerFrame ?: 0
            if (bpf > 0) remainingBytes / bpf else 0
        }
        return BufferPlan(
            forwardOffset = srcPos,
            forwardLength = remainingBytes,
            forwardFrames = frames,
            discard = false,
            enterReplayWhenDrained = false,
        )
    }

    /** 计算/重建 region 与 ring。返回 null 表示当前不可环回（window 空/非法/超限/预分配未就绪）。 */
    private fun ensureRegionLocked(): Region? {
        if (!regionDirty) return region
        val fmt = format
        val w = window
        if (released || fmt == null || !fmt.isValid() || w == null || !w.isValid()) {
            regionDirty = false
            region = null
            ring = null
            ringFrames = 0L
            capturedFrames = 0L
            isRingComplete = false
            state = State.PASS_THROUGH
            return null
        }

        val plan = planRing(w, fmt)
        if (plan == null) {
            regionDirty = false
            region = null
            ring = null
            ringFrames = 0L
            capturedFrames = 0L
            isRingComplete = false
            state = State.PASS_THROUGH
            if (unavailableWindow != w) {
                unavailableWindow = w
                degradeCount += 1L
            }
            return null
        }

        val pre = preallocated
        val reusable = if (pre != null &&
            pre.window == w &&
            pre.format == fmt &&
            pre.loopFrames == plan.loopFrames &&
            pre.bytes.size.toLong() == plan.byteSize
        ) {
            pre.bytes
        } else {
            null
        }

        val newRing = if (reusable != null) {
            reusable
        } else {
            if (preallocationRequired) {
                // 预分配还没完成/失败：音频线程绝不做大分配，保持 pending。
                // 保持 regionDirty=true，下一次 ensure 时重新检查。
                region = null
                ring = null
                ringFrames = plan.loopFrames
                capturedFrames = 0L
                isRingComplete = false
                state = State.PASS_THROUGH
                return null
            }
            audioThreadAllocationCount += 1L
            try {
                ByteArray(plan.byteSize.toInt())
            } catch (t: Throwable) {
                regionDirty = false
                region = null
                ring = null
                ringFrames = plan.loopFrames
                capturedFrames = 0L
                isRingComplete = false
                state = State.PASS_THROUGH
                if (unavailableWindow != w) {
                    unavailableWindow = w
                    degradeCount += 1L
                }
                return null
            }
        }

        regionDirty = false
        region = Region(w, fmt, plan.aFrame, plan.bFrame)
        ring = newRing
        coverage = Coverage()
        ringFrames = plan.loopFrames
        capturedFrames = 0L
        isRingComplete = false
        isRingPrefilled = false
        incompleteDegraded = false
        replayUnavailable = false
        degradeCallbackArmed = true
        cursorFrames = 0L
        round = 1L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        state = State.CAPTURING
        return region
    }

    private fun hasRingForLocked(window: LoopWindow, format: Format): Boolean {
        val r = region
        return r != null && ring != null && r.window == window && r.format == format
    }

    private fun invalidateRegionLocked() {
        region = null
        regionDirty = true
        ring = null
        coverage = Coverage()
        ringFrames = 0L
        capturedFrames = 0L
        isRingComplete = false
        isRingPrefilled = false
        incompleteDegraded = false
        replayUnavailable = false
        preallocated = null
        degradeCallbackArmed = true
        pendingPostFlushPhase = false
        seamIteration = 0L
        cursorFrames = 0L
        round = 1L
        pendingChunk = null
        pendingConsumedBytes = 0
        pendingAdvancedFrames = 0L
        state = State.PASS_THROUGH
        unavailableWindow = null
        seamCount = 0L
        totalReplayFrames = 0L
    }

    private fun captureLocked(
        src: ByteBuffer,
        srcPos: Int,
        startFrameAbs: Long,
        capStart: Long,
        capEnd: Long,
        r: Region,
        fmt: Format,
    ) {
        // 解码预填的窗口不再用播放捕获覆盖 ring：保证每一轮的环内容逐位相同
        // （预填 PCM 与播放链解码输出可能有极微差异）。
        if (isRingPrefilled) return
        val bytes = ring ?: return
        val bpf = fmt.bytesPerFrame
        val srcByteStart = srcPos + ((capStart - startFrameAbs) * bpf).toInt()
        val frameCount = (capEnd - capStart).toInt()
        val byteLength = frameCount * bpf
        val destOffset = ((capStart - r.aFrame) * bpf).toInt()
        if (srcByteStart < 0 || byteLength <= 0) return
        if (srcByteStart + byteLength > src.limit()) return
        if (destOffset < 0 || destOffset + byteLength > bytes.size) return

        val dup = src.duplicate()
        dup.position(srcByteStart)
        dup.limit(srcByteStart + byteLength)
        dup.get(bytes, destOffset, byteLength)

        coverage.add(
            start = capStart - r.aFrame,
            end = capEnd - r.aFrame,
            total = r.loopFrames,
        )
        capturedFrames = coverage.coveredFrames
        isRingComplete = coverage.coversFull(r.loopFrames)
    }

    private fun advanceReplayCursorLocked(frames: Long) {
        val r = region ?: return
        var left = frames
        while (left > 0L) {
            val room = r.loopFrames - cursorFrames
            if (room <= 0L) {
                cursorFrames = 0L
                round += 1L
                seamCount += 1L
                notifySeamLocked()
                continue
            }
            val step = minOf(left, room)
            cursorFrames += step
            left -= step
            totalReplayFrames += step
            if (cursorFrames >= r.loopFrames) {
                cursorFrames = 0L
                round += 1L
                seamCount += 1L
                notifySeamLocked()
            }
        }
    }

    /**
     * 进入重放时的初始游标：仅当 ring 完整且输入 PTS 已到/越过 B 时，按 PTS 在
     * [A,B) 内的折返相位定位；常规跨越 B 的输入（PTS < B）维持 0。
     */
    private fun entryCursorFramesLocked(startFrameAbs: Long, r: Region): Long {
        if (!isRingComplete || startFrameAbs < r.bFrame) return 0L
        return Math.floorMod(startFrameAbs - r.aFrame, r.loopFrames)
    }

    /** 进入重放时的轮次：保证首个 chunk 的 PTS = 输入 PTS（媒体域连续）。 */
    private fun entryRoundLocked(startFrameAbs: Long, r: Region): Long {
        if (!isRingComplete || startFrameAbs < r.bFrame) return 1L
        return (startFrameAbs - r.bFrame) / r.loopFrames + 1L
    }

    private fun notifySeamLocked() {
        val r = region ?: return
        val fmt = format ?: return
        seamIteration += 1L
        val cursor = if (state == State.REPLAYING) cursorFrames else 0L
        onSeam?.invoke(seamIteration, totalReplayFrames, replayPtsLocked(cursor, round, r, fmt))
    }

    private fun replayPtsLocked(
        frameOffset: Long,
        round: Long,
        r: Region,
        fmt: Format,
    ): Long {
        val roundBaseUs = r.window.loopEndUs + (round - 1L) * r.loopDurationUs
        val offsetUs = frameOffset * US_PER_SECOND / fmt.sampleRate
        return roundBaseUs + offsetUs
    }

    /** 后台预分配安装的 ring。 */
    private class Preallocated(
        val window: LoopWindow,
        val format: Format,
        val loopFrames: Long,
        val bytes: ByteArray,
    )

    /** 覆盖区间集合：支持 seek 进区间中部后回补前半段。 */
    private class Coverage {
        private val intervals = ArrayList<LongArray>()

        var coveredFrames: Long = 0L
            private set

        fun add(start: Long, end: Long, total: Long) {
            var ns = start.coerceIn(0L, total)
            var ne = end.coerceIn(0L, total)
            if (ne <= ns) return
            val merged = ArrayList<LongArray>(intervals.size + 1)
            for (iv in intervals) {
                if (iv[1] < ns || iv[0] > ne) {
                    merged.add(iv)
                } else {
                    ns = minOf(ns, iv[0])
                    ne = maxOf(ne, iv[1])
                }
            }
            merged.add(longArrayOf(ns, ne))
            merged.sortBy { it[0] }
            intervals.clear()
            intervals.addAll(merged)
            var covered = 0L
            for (iv in intervals) covered += iv[1] - iv[0]
            coveredFrames = covered
        }

        fun coversFull(total: Long): Boolean =
            intervals.size == 1 && intervals[0][0] <= 0L && intervals[0][1] >= total
    }

    companion object {
        /** 单窗口时长上限：300s。 */
        const val MAX_LOOP_DURATION_US: Long = 300_000_000L

        /** ring 字节上限：64MB。 */
        const val MAX_RING_BYTES: Long = 64L * 1024L * 1024L

        /** 每次重放喂送约 10ms（与探针一致）。 */
        const val REPLAY_CHUNK_MS: Int = 10

        /** 降级时建议 seek 回“该轮起点 - 250ms”（探针验证过的预卷量）。 */
        const val DEGRADE_SEEK_BACK_US: Long = 250_000L

        /** ring 容量预算：由 controller 在后台按同一份计划预分配。 */
        data class RingPlan(
            val aFrame: Long,
            val bFrame: Long,
            val loopFrames: Long,
            val byteSize: Long,
        )

        /**
         * 纯计算 ring 计划；null = window/format 非法、长度 <= 0、超 300s 或超 64MB。
         * controller 后台预分配与 engine 音频线程容量校验共用此函数。
         */
        fun planRing(window: LoopWindow, format: Format): RingPlan? {
            if (!window.isValid() || !format.isValid()) return null
            val aFrame = usToFrameCeil(window.loopStartUs, format.sampleRate)
            val bFrame = usToFrameCeil(window.loopEndUs, format.sampleRate)
            val loopFrames = bFrame - aFrame
            if (loopFrames <= 0L) return null
            if (window.lengthUs > MAX_LOOP_DURATION_US) return null
            if (loopFrames > MAX_RING_BYTES / format.bytesPerFrame) return null
            return RingPlan(
                aFrame = aFrame,
                bFrame = bFrame,
                loopFrames = loopFrames,
                byteSize = loopFrames * format.bytesPerFrame,
            )
        }

        private const val US_PER_SECOND: Long = 1_000_000L

        /** 区间长度(µs) -> 帧数 = ceil(durationUs * sr / 1e6)，用于预填长度校验。 */
        internal fun usLengthToFrameCeil(durationUs: Long, sampleRate: Int): Long {
            if (durationUs <= 0L || sampleRate <= 0) return 0L
            return (durationUs * sampleRate + US_PER_SECOND - 1L) / US_PER_SECOND
        }

        /** 绝对帧号 = ceil(us * sr / 1e6)，用于半开区间起点/终点。 */
        internal fun usToFrameCeil(us: Long, sampleRate: Int): Long {
            if (us <= 0L) return 0L
            val whole = us / US_PER_SECOND
            val rem = us % US_PER_SECOND
            return whole * sampleRate + (rem * sampleRate + US_PER_SECOND - 1L) / US_PER_SECOND
        }

        /** 输入 PTS -> 绝对帧号（四舍五入，吸收 decoder 的 µs 量化误差）。 */
        internal fun frameFromUsRounded(us: Long, sampleRate: Int): Long {
            if (us == Long.MIN_VALUE) return Long.MIN_VALUE
            val negative = us < 0L
            val abs = if (negative) -us else us
            val whole = abs / US_PER_SECOND
            val rem = abs % US_PER_SECOND
            val frame = whole * sampleRate + (rem * sampleRate + 500_000L) / US_PER_SECOND
            return if (negative) -frame else frame
        }
    }
}
