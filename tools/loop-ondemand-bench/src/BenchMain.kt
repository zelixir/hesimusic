package bench

import com.zjr.hesimusic.loop.analysis.Pcm16Frames
import com.zjr.hesimusic.loop.analysis.Pcm16Segment
import com.zjr.hesimusic.loop.audio.source.LoopRing
import com.zjr.hesimusic.loop.audio.source.LoopingPcmSourceCore
import com.zjr.hesimusic.loop.audio.source.LoopingPcmTimeline
import com.zjr.hesimusic.loop.audio.source.PcmBlock
import com.zjr.hesimusic.loop.audio.source.PcmBlockCache
import com.zjr.hesimusic.loop.audio.source.PcmRangeProvider
import com.zjr.hesimusic.loop.audio.source.PcmTrackFormat
import com.zjr.hesimusic.loop.audio.source.RingBandReader
import java.lang.management.ManagementFactory
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val SR = 44100
private const val CH = 2
private const val BPF = CH * 2

// 00027 现场窗口（Shiny Up）：A=23.301s B=261.457s，L=238.156s
private const val A_US = 23_301_000L
private const val B_US = 261_457_000L
private const val DECODE_PREROLL_US = 250_000L
private const val TAP_HEAP_BYTES = 402_653_184L

private fun mb(bytes: Long): String =
    if (bytes >= 1024L * 1024L) String.format("%.2fMiB", bytes / 1048576.0)
    else String.format("%.1fKB", bytes / 1024.0)

private fun allocatedBytes(): Long {
    val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    return bean.getThreadAllocatedBytes(Thread.currentThread().id)
}

private fun usedHeapAfterGc(): Long {
    val rt = Runtime.getRuntime()
    repeat(4) {
        System.gc()
        Thread.sleep(80)
    }
    return rt.totalMemory() - rt.freeMemory()
}

private fun timelineFor(aUs: Long = A_US, bUs: Long = B_US): LoopingPcmTimeline =
    LoopingPcmTimeline(
        songId = 1L,
        loopStartUs = aUs,
        loopEndUs = bUs,
        format = PcmTrackFormat(SR, CH),
    )

/** 合成解码后端：帧数与 PcmDecoder 同口径（ceil(us*sr)，多给 4 帧余量避免边界误判）。 */
private class SimProvider(
    private val sr: Int = SR,
    private val ch: Int = CH,
    private val latencyMs: (() -> Double)? = null,
) : PcmRangeProvider {
    var calls = 0
    var latSum = 0.0
    var latMax = 0.0

    override fun decode(startUs: Long, endUs: Long): Pcm16Segment {
        calls += 1
        if (latencyMs != null) {
            val lat = latencyMs()
            latSum += lat
            latMax = max(latMax, lat)
        }
        val startFrame = Pcm16Frames.frameAtOrAfterUs(startUs, sr)
        val endFrame = Pcm16Frames.frameAtOrAfterUs(endUs, sr)
        val frames = (endFrame - startFrame + 4L).coerceAtLeast(1L)
        return Pcm16Segment(ByteArray((frames * ch * 2).toInt()), sr, ch, frames)
    }
}

// ---------------------------------------------------------------- memory

private fun taskMemory() {
    val heapMax = Runtime.getRuntime().maxMemory()
    val ringBudget = heapMax / 6L
    val cacheBudget = PcmBlockCache.maxBytesForHeap(heapMax)
    println("## memory")
    println(
        "jvm.maxHeap=" + mb(heapMax) +
            "  app.ringBudget(heap/6)=" + mb(ringBudget) +
            "  app.blockCacheBudget=" + mb(cacheBudget),
    )

    val windows = listOf(10_000L, 30_000L, 60_000L, 120_000L, 150_000L, 238_156L, 300_000L, 380_000L)
    println("L(s)\tframes\tringData\tringData+coverage\tcache2s\tcache3s\tcache5s\tcache6s\tcapacityBlocks\treDecodesPerLoop")
    for (lenUs in windows) {
        val tl = timelineFor(0L, lenUs * 1000L)
        val frames = tl.loopFrames
        val dataBytes = frames * BPF
        val coverageBytes = ((frames + 63L) / 64L) * 8L
        val blockBytes = SR.toLong() * BPF
        val capacity = (cacheBudget / blockBytes).toInt()
        val n = tl.blockCount
        val redecodes = max(0, n - capacity)
        println(
            String.format(
                "%.1f\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%d\t%d",
                lenUs / 1000.0, frames,
                mb(dataBytes), mb(dataBytes + coverageBytes),
                mb(2 * blockBytes), mb(3 * blockBytes), mb(5 * blockBytes), mb(6 * blockBytes),
                capacity, redecodes,
            ),
        )
    }

    // 实测：环带真实堆占用（LoopRing = data + 覆盖位图）
    val tl = timelineFor()
    val ringSamples = ArrayList<Long>()
    repeat(3) {
        val before = usedHeapAfterGc()
        val ring = LoopRing(SR, CH, tl.loopFrames)
        ring.writeFramesAt(0L, ByteArray(4096))
        val after = usedHeapAfterGc()
        ringSamples += (after - before)
        ring.close()
    }
    println(
        "measured.LoopRing(L=238.156s)=" + mb(ringSamples.minOrNull()!!) +
            " (data=" + mb(tl.loopFrames * BPF) +
            ", coverage=" + mb(((tl.loopFrames + 63) / 64) * 8) + ")",
    )

    // 实测：块缓存 w 块真实堆占用
    for (w in intArrayOf(2, 3, 5, 6)) {
        val cache = PcmBlockCache(tl.cacheKey(), SR, BPF, cacheBudget)
        val before = usedHeapAfterGc()
        for (i in 0 until w) {
            cache.put(PcmBlock(i, tl.blockStartFrame(i), SR, ByteArray(SR * BPF)))
        }
        val after = usedHeapAfterGc()
        println(
            "measured.PcmBlockCache(w=" + w + ")=" + mb(after - before) +
                " cachedBytes=" + mb(cache.cachedBytes()),
        )
        cache.clear()
    }

    val blockBytes = SR.toLong() * BPF
    println("capacitySec(cacheBudget)=" + String.format("%.1f", cacheBudget / blockBytes.toDouble()))
    println("ringBudgetSec(heap/6)=" + String.format("%.1f", ringBudget / blockBytes.toDouble()))
}

// ---------------------------------------------------------------- allocation churn

private fun decodeOneBlockAllocationProfile(blockFrames: Int = SR): Long {
    // 与 LoopingPcmSourceCore.decodeBlock 完全同分配模式：
    // 1) provider 返回 (preroll+1s) 的 Pcm16Segment；2) dropFrames 拷贝；3) takeFrames；4) PcmBlock
    val needed = Pcm16Frames.frameAtOrAfterUs(DECODE_PREROLL_US, SR) + blockFrames
    val seg = Pcm16Segment(ByteArray((needed * BPF).toInt()), SR, CH, needed)
    val trimmed = seg.dropFrames((needed - blockFrames).toLong()).takeFrames(blockFrames.toLong())
    return trimmed.data.size.toLong()
}

private fun taskAlloc() {
    println("## alloc (churn)")
    val tl = timelineFor(0L, 30_000_000L)
    val chunkBytes = 65536
    val chunkFrames = chunkBytes / BPF
    val readCount = (60 * SR) / chunkFrames

    // ---- 1) 生产 read 路径：core.read(64KB) 每次 copyOfRange
    val provider = SimProvider()
    val core = LoopingPcmSourceCore(tl, provider, prefetchBlocks = 0)
    for (b in 0 until tl.blockCount) {
        core.cache.put(PcmBlock(b, tl.blockStartFrame(b), SR, ByteArray(SR * BPF)))
    }
    val a0 = allocatedBytes()
    var reads = 0
    repeat(readCount) {
        val r = core.read(chunkBytes)
        if (r == null) error("read returned null in pre-filled cache")
        reads++
    }
    val readAlloc = allocatedBytes() - a0
    val readsPerSec = SR.toDouble() / chunkFrames
    println(
        "path=block.read.current reads=" + reads + " allocPerRead=" + (readAlloc / reads) + "B" +
            " allocPerAudioSecond=" + mb((readAlloc / 60.0).toLong()) +
            " readsPerSecond=" + String.format("%.2f", readsPerSec),
    )

    // ---- 2) 池化 read：同一个 ByteArray 复用（设计目标）
    val buf = ByteArray(chunkBytes)
    var frame = tl.aFrame
    val a1 = allocatedBytes()
    repeat(readCount) {
        val blockIdx = tl.blockIndexForFrame(frame)
        val block = core.cache.get(blockIdx) ?: error("missing block")
        val off = (frame - block.startFrame).toInt()
        val n = min(chunkFrames, block.frames - off)
        System.arraycopy(block.data, off * BPF, buf, 0, n * BPF)
        frame = tl.normalizeFrame(frame + n)
    }
    val pooledAlloc = allocatedBytes() - a1
    println("path=block.read.pooled     allocTotal=" + pooledAlloc + "B (per audio second=" + mb((pooledAlloc / 60).toLong()) + ")")

    // ---- 3) 块安装分配（每块）
    val a2 = allocatedBytes()
    var sink = 0L
    repeat(20) { sink += decodeOneBlockAllocationProfile() }
    val perBlock = (allocatedBytes() - a2) / 20
    println(
        "path=block.install.current allocPerBlock=" + perBlock + "B (" + mb(perBlock) + ")" +
            " allocPerAudioSecond=" + mb(perBlock),
    )

    // ---- 4) 块安装池化：块 ByteArray 复用（解码输出仍新分配）
    val pool = ArrayDeque<ByteArray>()
    val a3 = allocatedBytes()
    repeat(20) {
        val needed = Pcm16Frames.frameAtOrAfterUs(DECODE_PREROLL_US, SR) + SR
        val seg = Pcm16Segment(ByteArray((needed * BPF).toInt()), SR, CH, needed)
        val out = pool.removeFirstOrNull() ?: ByteArray(SR * BPF)
        System.arraycopy(seg.data, ((needed.toInt() - SR) * BPF), out, 0, SR * BPF)
        pool.addLast(out)
    }
    val pooledBlockAlloc = (allocatedBytes() - a3) / 20
    println(
        "path=block.install.pooled  allocPerBlock=" + pooledBlockAlloc + "B" +
            " allocPerAudioSecond=" + mb(pooledBlockAlloc),
    )

    // ---- 5) 环带 read 分配（RingBandReader.copyFrames 每次新数组）
    val ring = LoopRing(SR, CH, tl.loopFrames)
    var off = 0L
    while (off < tl.loopFrames) {
        val n = min(65536L / BPF, tl.loopFrames - off).toInt()
        ring.writeFramesAt(off, ByteArray(n * BPF))
        off += n
    }
    val reader = RingBandReader(ring, 0L)
    println(
        "ring.fill coveredFrames=" + ring.coveredFrames + "/" + tl.loopFrames +
            " complete=" + ring.isFullyCovered +
            " contiguous@" + reader.offsetFrames + "=" + ring.contiguousFilledFrames(reader.offsetFrames, chunkFrames.toLong()),
    )
    val a4 = allocatedBytes()
    var ringFrames = 0L
    var shortReads = 0
    repeat(readCount) {
        val r = reader.read(chunkFrames)!!
        ringFrames += r.frames
        if (r.frames < chunkFrames) shortReads++
    }
    val ringAlloc = allocatedBytes() - a4
    println("ring.read frames=$ringFrames shortReads=$shortReads of $readCount")
    println(
        "path=ring.read.current   allocPerRead=" + (ringAlloc / readCount) + "B" +
            " allocPerAudioSecond=" + mb((ringAlloc / 60.0).toLong()),
    )
    ring.close()

    // ---- 合成：整条路径的分配速率（每播放 1 秒音频）
    println(
        "sum.current(block)=" + mb((readAlloc / 60) + perBlock) +
            "  sum.pooled(block)=" + mb((pooledAlloc / 60) + pooledBlockAlloc) +
            "  sink=" + sink,
    )
    println("observed(00027 fallback)=+0.15MB/s heap")
}

// ---------------------------------------------------------------- prefetch / underrun sim

private data class SimResult(
    val windowBlocks: Int,
    val model: String,
    val gapEvents: Int,
    val gapMs: Double,
    val seamGapEvents: Int,
    val minQueueMs: Double,
    val decodes: Int,
    val avgLatMs: Double,
    val maxLatMs: Double,
    val wraps: Int,
    val inlineDecodes: Int = 0,
    val inlineStallMs: Double = 0.0,
)

/**
 * 两级供给模型（与生产同构）：
 * - 解码 worker = LoopPcmPrefetcher：readyBlockCount < w 且 needsDecode 时连续解码；
 * - 加载线程 = LoopingPcmExtractor：队列最多领先 5s，遇到未就绪块时**内联解码**（阻塞加载线程）；
 * - 消费者 = AudioSink：按 1x 消费，队列空 = 可听欠载（outputFrames 缺口）。
 */
private fun runPipeline(
    w: Int,
    minutes: Double,
    seed: Long,
    modelLabel: String,
    latencyMs: (Random) -> Double,
    cacheBytes: Long = PcmBlockCache.maxBytesForHeap(TAP_HEAP_BYTES),
    aheadSec: Double = 5.0,
    lenUs: Long = B_US - A_US,
    fixedGate: Boolean = false,
): SimResult {
    val tl = timelineFor(0L, lenUs)
    val latRng = Random(seed)
    val provider = SimProvider(latencyMs = { latencyMs(latRng) })
    val core = LoopingPcmSourceCore(tl, provider, maxCacheBytes = cacheBytes, prefetchBlocks = w)
    val rate = SR.toDouble()
    val chunkFrames = 16384
    val quantum = 2.0
    val endT = minutes * 60_000.0

    var t = 0.0
    var delivered = 0L
    var decodes = 0
    var gapMs = 0.0
    var gapEvents = 0
    var seamGapEvents = 0
    var starved = false
    var minQueueMs = Double.MAX_VALUE
    var workerBusyUntil = -1.0
    var workerPending = false
    var wraps = 0
    var inlineDecodes = 0
    var inlineStallMs = 0.0
    var lastWrapT = -1e9
    var prevFrame = core.currentFrame()

    fun sampleLat(): Double {
        val lat = latencyMs(latRng)
        decodes += 1
        return lat
    }

    // 起播门槛：extractor 要求 2 块就绪，期间加载线程内联解码（阻塞）
    var startupGuard = 0
    while (core.readyBlockCount() < 2 && startupGuard < 8) {
        t += sampleLat()
        core.pumpDecode()
        startupGuard += 1
    }
    var playStartT = -1.0

    while (t < endT) {
        t += quantum

        if (workerPending && t >= workerBusyUntil) {
            core.pumpDecode()
            workerPending = false
        }
        val gate = if (fixedGate) core.needsDecode() else (core.readyBlockCount() < w && core.needsDecode())
        if (!workerPending && gate) {
            workerBusyUntil = t + sampleLat()
            workerPending = true
        }

        if (playStartT < 0.0 && delivered > 0L) playStartT = t
        val demand = if (playStartT < 0.0) 0.0 else (t - playStartT) / 1000.0 * rate
        val deliveredD = delivered.toDouble()
        val consumed = min(demand, deliveredD)
        val queueFrames = deliveredD - consumed
        minQueueMs = min(minQueueMs, queueFrames / rate * 1000.0)
        if (deliveredD < demand) {
            if (!starved) {
                gapEvents += 1
                if (t - lastWrapT <= 250.0) seamGapEvents += 1
                starved = true
            }
            gapMs += quantum
        } else {
            starved = false
        }

        if (queueFrames < aheadSec * rate) {
            val r = core.read(chunkFrames * BPF)
            if (r != null) {
                delivered += r.frames
            } else {
                val lat = sampleLat()
                t += lat
                inlineStallMs += lat
                inlineDecodes += 1
                core.pumpDecode()
            }
        }

        val frame = core.currentFrame()
        if (frame < prevFrame) {
            wraps += 1
            lastWrapT = t
        }
        prevFrame = frame
    }

    val avgLat = if (provider.calls > 0) provider.latSum / provider.calls else 0.0
    return SimResult(
        windowBlocks = w,
        model = modelLabel,
        gapEvents = gapEvents,
        gapMs = gapMs,
        seamGapEvents = seamGapEvents,
        minQueueMs = if (minQueueMs == Double.MAX_VALUE) 0.0 else minQueueMs,
        decodes = decodes,
        avgLatMs = avgLat,
        maxLatMs = provider.latMax,
        wraps = wraps,
        inlineDecodes = inlineDecodes,
        inlineStallMs = inlineStallMs,
    )
}

private fun taskPrefetch(seed: Int) {
    for ((aheadLabel, aheadSec) in listOf("ahead=5s(prod)" to 5.0, "ahead=1s(tight)" to 1.0)) {
        println(
            "## prefetch " + aheadLabel + " (两级供给仿真, 虚拟时钟, 10min/场景, L=238.156s, cache=" +
                mb(PcmBlockCache.maxBytesForHeap(TAP_HEAP_BYTES)) + ")",
        )
        prefetchSweep(seed, aheadSec)
    }
}

private fun prefetchSweep(seed: Int, aheadSec: Double) {
    val models = listOf<Pair<String, (Random) -> Double>>(
        "steady 20-40ms" to { r: Random -> 20.0 + r.nextInt(0, 21).toDouble() },
        "tail p99 800ms" to { r: Random ->
            if (r.nextInt(1000) < 10) 300.0 + r.nextInt(0, 501) else 20.0 + r.nextInt(0, 21)
        },
        "hiccup 2% 300ms" to { r: Random ->
            if (r.nextInt(1000) < 20) 120.0 + r.nextInt(0, 181) else 20.0 + r.nextInt(0, 21)
        },
        "throttled 150-350ms" to { r: Random -> 150.0 + r.nextInt(0, 201).toDouble() },
        "suspend 0.5% 1500ms" to { r: Random ->
            if (r.nextInt(1000) < 5) 1200.0 + r.nextInt(0, 601) else 20.0 + r.nextInt(0, 21)
        },
    )
    println("w\tmodel\tgapEvents/10min\tgapMs/10min\tseamGaps\tminQueueMs\tdecodes\tavgLatMs\tmaxLatMs\twraps")
    for (w in intArrayOf(1, 2, 3, 5, 6)) {
        for ((label, lat) in models) {
            val r = runPipeline(w, 10.0, seed.toLong() + w * 31L + label.hashCode(), label, lat, aheadSec = aheadSec)
            println(
                String.format(
                    "%d\t%s\t%d\t%.0f\t%d\t%.0f\t%d\t%.1f\t%.0f\t%d",
                    r.windowBlocks, r.model, r.gapEvents, r.gapMs, r.seamGapEvents,
                    r.minQueueMs, r.decodes, r.avgLatMs, r.maxLatMs, r.wraps,
                ),
            )
        }
    }
    println("ring(reference): 稳态零解码 => gapEvents=0 by construction (fill 阶段除外)")
}

// ---------------------------------------------------------------- heap slope

private fun gcCount(): Long {
    var n = 0L
    for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
        val c = bean.collectionCount
        if (c > 0) n += c
    }
    return n
}

private fun gcPauseMs(): Long {
    var ms = 0L
    for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
        val t = bean.collectionTime
        if (t > 0) ms += t
    }
    return ms
}

private fun usedHeapRaw(): Long {
    val rt = Runtime.getRuntime()
    return rt.totalMemory() - rt.freeMemory()
}

/**
 * heap 斜率实测（JVM，1x 墙钟 = 每 1 秒消费 1 秒音频）：
 * - current：core.read 的 copyOfRange（~172KB/s）+ 每块 Pcm16Segment/dropFrames/PcmBlock（~388KB/s）
 * - pooled ：复用 read 缓冲 + 复用块 ByteArray（只剩解码输出 ~224KB/s，生产可用常驻解码会话吃掉）
 * 采样 "usedHeap"（与 app 里 RingBandExtractor.logHeapIfDue 同口径），线性拟合斜率。
 */
private fun taskHeapSlope(seconds: Int) {
    println("## heapslope (1x wall clock, " + seconds + "s/phase, usedHeap 口径同 app logHeapIfDue)")
    val tl = timelineFor(0L, 30_000_000L)
    val core = LoopingPcmSourceCore(tl, SimProvider(), prefetchBlocks = 0)
    for (b in 0 until tl.blockCount) {
        core.cache.put(PcmBlock(b, tl.blockStartFrame(b), SR, ByteArray(SR * BPF)))
    }
    val chunkBytes = 65536
    val chunkFrames = chunkBytes / BPF
    val buf = ByteArray(chunkBytes)
    val pool = ArrayDeque<ByteArray>()
    var frame = tl.aFrame

    fun oneAudioSecondCurrent() {
        var left = SR
        while (left > 0) {
            val r = core.read(chunkBytes)
            if (r == null) {
                frame = tl.normalizeFrame(frame + chunkFrames)
                break
            }
            left -= r.frames
        }
        decodeOneBlockAllocationProfile()
    }

    fun oneAudioSecondPooled() {
        var left = SR
        while (left > 0) {
            val idx = tl.blockIndexForFrame(frame)
            val block = core.cache.get(idx) ?: break
            val off = (frame - block.startFrame).toInt()
            val n = min(chunkFrames, block.frames - off)
            System.arraycopy(block.data, off * BPF, buf, 0, n * BPF)
            frame = tl.normalizeFrame(frame + n)
            left -= n
        }
        val needed = Pcm16Frames.frameAtOrAfterUs(DECODE_PREROLL_US, SR) + SR
        val seg = Pcm16Segment(ByteArray((needed * BPF).toInt()), SR, CH, needed)
        val out = pool.removeFirstOrNull() ?: ByteArray(SR * BPF)
        System.arraycopy(seg.data, ((needed.toInt() - SR) * BPF), out, 0, SR * BPF)
        pool.addLast(out)
    }

    fun phase(label: String, pooled: Boolean) {
        for (i in 0 until 3) {
            if (pooled) oneAudioSecondPooled() else oneAudioSecondCurrent()
            Thread.sleep(1000)
        }
        val t0 = System.nanoTime()
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        val allocStart = allocatedBytes()
        val gcStart = gcCount()
        val pauseStart = gcPauseMs()
        var maxUsed = 0L
        var i = 0
        while (true) {
            val el = (System.nanoTime() - t0) / 1e9
            if (el >= seconds) break
            if (pooled) oneAudioSecondPooled() else oneAudioSecondCurrent()
            i += 1
            if (i % 5 == 0) {
                val used = usedHeapRaw()
                maxUsed = max(maxUsed, used)
                xs.add(el)
                ys.add(used / 1048576.0)
            }
            Thread.sleep(1000)
        }
        val allocRate = (allocatedBytes() - allocStart) / 1000.0 / seconds
        val slope = if (xs.size >= 2) {
            val n = xs.size
            val mx = xs.average()
            val my = ys.average()
            var num = 0.0
            var den = 0.0
            for (k in 0 until n) {
                num += (xs[k] - mx) * (ys[k] - my)
                den += (xs[k] - mx) * (xs[k] - mx)
            }
            if (den == 0.0) 0.0 else num / den
        } else {
            0.0
        }
        val liveAfterGc = usedHeapAfterGc() / 1048576.0
        println(
            "heap.phase=" + label +
                " audioSeconds=" + i +
                " slopeMiBPerSec=" + String.format("%.4f", slope) +
                " slopeMiBPerHour=" + String.format("%.0f", slope * 3600) +
                " allocKiBPerSec=" + String.format("%.1f", allocRate) +
                " gcCountDelta=" + (gcCount() - gcStart) +
                " gcPauseMsDelta=" + (gcPauseMs() - pauseStart) +
                " maxUsedMiB=" + String.format("%.1f", maxUsed / 1048576.0) +
                " liveAfterGcMiB=" + String.format("%.1f", liveAfterGc),
        )
    }

    phase("block.current", pooled = false)
    phase("block.pooled", pooled = true)
}

// ---------------------------------------------------------------- seam check

/**
 * 接缝(跨 B)行为验证：块缓存的 B→A 只是 normalizeFrame 取模，不 seek、不重建。
 * 记录跨 B 前后的帧号/块号、跨 B 当次 read 是否返回 null（供给缺口）。
 */
private fun taskSeamCheck() {
    println("## seamcheck (production LoopingPcmSourceCore)")
    val tl = timelineFor()
    val core = LoopingPcmSourceCore(tl, SimProvider(), prefetchBlocks = 6)
    println(
        "window A=" + tl.loopStartUs / 1000 + "ms B=" + tl.loopEndUs / 1000 + "ms" +
            " loopFrames=" + tl.loopFrames +
            " blocks=" + tl.blockCount +
            " lastBlockFrames=" + tl.blockFrames(tl.blockCount - 1) +
            " lastBlockMs=" + String.format("%.1f", tl.blockFrames(tl.blockCount - 1) * 1000.0 / SR),
    )
    var framesRead = 0L
    var wraps = 0
    var stalls = 0
    var lastFrame = core.currentFrame()
    val chunkBytes = 65536
    var guard = 0
    while (wraps < 3 && guard < 400000) {
        guard++
        // 模拟生产预取线程：每次读完把当前 + 6 块的缺口补齐（忽略解码延迟）
        var pumps = 0
        while (core.readyBlockCount() < 6 && core.needsDecode() && pumps < 12) {
            core.pumpDecode()
            pumps++
        }
        val blockZeroReady = core.cache.state(0) == com.zjr.hesimusic.loop.audio.source.PcmBlockCache.State.READY
        val r = core.read(chunkBytes)
        if (r == null) {
            stalls++
            core.pumpDecode()
            continue
        }
        framesRead += r.frames
        val frame = core.currentFrame()
        if (frame < lastFrame) {
            wraps++
            val prevBlock = tl.blockCount - 1
            println(
                "wrap#" + wraps +
                    " beforeFrame=" + lastFrame + " (lastBlock=" + prevBlock + ")" +
                    " afterFrame=" + frame + " (block=0 isA=" + (frame == tl.aFrame) + ")" +
                    " lastBlockMs=" + String.format("%.1f", tl.blockFrames(prevBlock) * 1000.0 / SR) +
                    " block0ReadyBeforeWrap=" + blockZeroReady +
                    " readNullAtWrap=" + (r == null),
            )
        }
        lastFrame = frame
    }
    println("framesRead=" + framesRead + " wraps=" + wraps + " readStalls=" + stalls +
        " decodeCalls=" + core.decodeCallCount() + " readyBlocks=" + core.readyBlockCount())
}

// ---------------------------------------------------------------- read cost

private fun taskReadCost() {
    println("## readcost")
    val tl = timelineFor(0L, 30_000_000L)
    val ring = LoopRing(SR, CH, tl.loopFrames)
    var off = 0L
    while (off < tl.loopFrames) {
        val n = min(65536L / BPF, tl.loopFrames - off).toInt()
        ring.writeFramesAt(off, ByteArray(n * BPF))
        off += n
    }
    val reader = RingBandReader(ring, 0L)
    val chunkFrames = 16384
    val reads = (60 * SR) / chunkFrames
    val t0 = System.nanoTime()
    var dummy = 0L
    repeat(reads) { val r = reader.read(chunkFrames); dummy += r!!.frames }
    val elMs = (System.nanoTime() - t0) / 1e6
    val audioSec = reads.toDouble() * chunkFrames / SR
    println(
        "ring.read reads=" + reads + " audioSec=" + String.format("%.0f", audioSec) +
            " wallMs=" + String.format("%.1f", elMs) +
            " cpuPerAudioSecondMs=" + String.format("%.3f", elMs / audioSec) +
            " throughput=" + String.format("%.0f", audioSec * 1000.0 / elMs) + "x realtime" +
            " dummy=" + dummy,
    )
    ring.close()
}

/**
 * 短窗口的"惰性环"实验：L * bytesPerFrame <= 块缓存预算时，
 * 按需解码在第一圈结束后不应该再有新解码（等价于环带，但内存由预算封顶、惰性填充）。
 */
private fun taskLazyRing(seed: Int) {
    println("## lazyring (steady 20-40ms, 10min/场景, cache=" +
        mb(PcmBlockCache.maxBytesForHeap(TAP_HEAP_BYTES)) + ")")
    println("L(s)	w	blocks	capacity	decodes/10min	gapEvents	minQueueMs")
    for (lenS in listOf(90L, 150L, 190L, 239L)) {
        for (w in intArrayOf(2, 3, 6)) {
            val r = runPipeline(
                w, 10.0, seed.toLong() + lenS * 7L + w,
                "steady", { r: Random -> 20.0 + r.nextInt(0, 21).toDouble() },
                lenUs = lenS * 1_000_000L,
            )
            println(
                String.format(
                    "%d	%d	%d	%d	%d	%d	%.0f",
                    lenS, w, ((lenS * SR) + SR - 1) / SR,
                    (PcmBlockCache.maxBytesForHeap(TAP_HEAP_BYTES) / (SR.toLong() * BPF)).toInt(),
                    r.decodes, r.gapEvents, r.minQueueMs,
                ),
            )
        }
    }
}

/**
 * 预取闸门实验：现网 gate = readyBlockCount < w（缓存里"任意位置"已有 w 块就停止预取），
 * 修法 gate = needsDecode（只看播放头前 w 块）。后者应在播放头进入被淘汰块之前就解好。
 */
private fun taskPrefetchGate(seed: Int) {
    println("## prefetchgate (L=238.156s, ahead=5s, 10min)  gate=prod:readyCount<w  vs  gate=fix:needsDecode")
    println("w\tgate\tgapEvents\tloaderInlineDecodes\tloaderInlineStallMs\tminQueueMs\tdecodes")
    for (w in intArrayOf(2, 3, 6)) {
        for (fixed in listOf(false, true)) {
            val r = runPipeline(
                w, 10.0, seed.toLong() + w * 131L + (if (fixed) 7 else 0),
                if (fixed) "fix" else "prod", { r: Random -> 20.0 + r.nextInt(0, 21).toDouble() },
                fixedGate = fixed,
            )
            println(
                String.format(
                    "%d	%s	%d	%d	%.0f	%.0f	%d",
                    w, r.model, r.gapEvents, r.inlineDecodes, r.inlineStallMs,
                    r.minQueueMs, r.decodes,
                ),
            )
        }
    }
}

fun main(args: Array<String>) {
    val list = args.toList()
    fun opt(name: String): String? {
        val i = list.indexOf(name)
        return if (i >= 0 && i + 1 < list.size) list[i + 1] else null
    }
    val task = opt("--task") ?: "all"
    val seed = opt("--seed")?.toInt() ?: 20261121
    when (task) {
        "memory" -> taskMemory()
        "alloc" -> taskAlloc()
        "prefetch" -> taskPrefetch(seed)
        "heapslope" -> taskHeapSlope(60)
        "lazyring" -> taskLazyRing(seed)
        "seamcheck" -> taskSeamCheck()
        "prefetchgate" -> taskPrefetchGate(seed)
        "readcost" -> taskReadCost()
        else -> {
            taskMemory()
            taskAlloc()
            taskReadCost()
            taskSeamCheck()
            taskLazyRing(seed)
            taskPrefetchGate(seed)
            taskPrefetch(seed)
            taskHeapSlope(90)
        }
    }
}