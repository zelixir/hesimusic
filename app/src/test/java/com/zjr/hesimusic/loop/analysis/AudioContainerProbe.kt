package com.zjr.hesimusic.loop.analysis

/**
 * 测试专用:最小音频容器头解析(只读字节,不依赖 Android / media3)。
 *
 * 产出的是**容器层事实**而非解码数据:
 * - 采样率 / 声道 / 容器声明的总样本数;
 * - 编码器前导(priming / pre-skip / 编辑列表前导),它决定"从容器第 0 帧解出的 PCM
 *   与用户听到的第 0 个样本差多少";
 * - 容器可寻址的最小样本网格(FLAC 帧 / AAC packet / MP3 帧 / Ogg 页);
 * - 容器是否自带精确总样本与索引(决定"能否精确取 [A,B)")。
 *
 * **真实 PCM 解码与逐样本对齐必须在真机 MediaCodec 上测**:
 * JVM 单测里 android.media 是 stub,本类刻意不解码、不伪造 PCM。
 * 设备侧口径见 `tools/format-matrix.ps1` 与 `/debug/decode-range`。
 */
internal object AudioContainerProbe {

    data class Facts(
        val format: String,
        val container: String,
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        /** 容器时间轴声明的总样本数(含编码器前导/padding)。 */
        val containerSamples: Long,
        /** 编码器前导样本数(解码后不属于用户内容的开头部分)。 */
        val primingSamples: Long,
        /** 容器内可寻址的最小样本网格;0 = 页级/可变(A 只能回退到页边界)。 */
        val seekGridSamples: Long,
        /** 容器是否提供精确总样本+索引(决定能否在帧边界精确取 [A,B))。 */
        val containerExact: Boolean,
        val details: Map<String, String>,
    ) {
        val presentedSamples: Long get() = containerSamples - primingSamples
        val durationSeconds: Double get() = presentedSamples.toDouble() / sampleRate
    }

    fun probe(bytes: ByteArray, fileName: String): Facts {
        require(bytes.size >= 12) { "file too short: $fileName" }
        return when {
            ascii(bytes, 0, 4) == "OggS" -> probeOgg(bytes)
            ascii(bytes, 0, 4) == "fLaC" -> probeFlac(bytes)
            ascii(bytes, 0, 3) == "ID3" || (u8(bytes, 0) == 0xFF && (u8(bytes, 1) and 0xE0) == 0xE0) ->
                probeMp3(bytes)
            ascii(bytes, 4, 4) == "ftyp" -> probeMp4(bytes)
            else -> error("unsupported container: $fileName")
        }
    }

    // ---------------------------------------------------------------- Ogg (Vorbis / Opus)

    private fun probeOgg(b: ByteArray): Facts {
        var off = 0
        var pages = 0
        var firstGranule = -1L
        var lastGranule = 0L
        var sawEos = false
        var expectedSeq = -1
        var idPacket: ByteArray? = null
        while (off + 27 <= b.size) {
            require(ascii(b, off, 4) == "OggS") { "not an Ogg page at " + off }
            val type = u8(b, off + 5)
            val granule = u64le(b, off + 6)
            val seq = u32le(b, off + 18).toInt()
            val nseg = u8(b, off + 26)
            var payloadLen = 0
            for (i in 0 until nseg) payloadLen += u8(b, off + 27 + i)
            val payloadOff = off + 27 + nseg
            require(payloadOff + payloadLen <= b.size) { "truncated Ogg page at " + off }
            if (pages == 0) {
                firstGranule = granule
                idPacket = b.copyOfRange(payloadOff, payloadOff + payloadLen)
            }
            if (expectedSeq >= 0) {
                require(seq == expectedSeq) { "Ogg page sequence gap: " + seq + " != " + expectedSeq }
            }
            expectedSeq = seq + 1
            lastGranule = granule
            if ((type and 0x04) != 0) sawEos = true
            pages++
            off = payloadOff + payloadLen
        }
        val id = idPacket ?: error("no Ogg page")
        require(pages > 0) { "no Ogg page" }
        require(sawEos) { "Ogg stream has no EOS page" }
        require(firstGranule == 0L) { "Ogg first page granule must be 0, was " + firstGranule }
        require(lastGranule > 0L) { "Ogg last granule missing" }

        if (ascii(id, 0, 8) == "OpusHead") {
            val channels = u8(id, 9)
            val preSkip = u16le(id, 10)
            val inputRate = u32le(id, 12).toInt()
            return Facts(
                format = "ogg-opus",
                container = "ogg",
                codec = "opus",
                sampleRate = 48_000, // Ogg Opus 的 granule 固定按 48k 计
                channels = channels,
                containerSamples = lastGranule,
                primingSamples = preSkip.toLong(),
                seekGridSamples = 0L,
                containerExact = false,
                details = linkedMapOf(
                    "pageCount" to pages.toString(),
                    "granuleEnd" to lastGranule.toString(),
                    "preSkip" to preSkip.toString(),
                    "inputSampleRate" to inputRate.toString(),
                    "granuleDurationMs" to (lastGranule * 1000L / 48_000L).toString(),
                ),
            )
        }

        require(u8(id, 0) == 1 && ascii(id, 1, 6) == "vorbis") { "unknown Ogg codec" }
        val channels = u8(id, 11)
        val sampleRate = u32le(id, 12).toInt()
        val blockRaw = u8(id, 28)
        val blockSmall = 1 shl (blockRaw and 0x0F)
        val blockLarge = 1 shl (blockRaw shr 4)
        return Facts(
            format = "ogg-vorbis",
            container = "ogg",
            codec = "vorbis",
            sampleRate = sampleRate,
            channels = channels,
            containerSamples = lastGranule,
            primingSamples = 0L,
            seekGridSamples = 0L,
            containerExact = false,
            details = linkedMapOf(
                "pageCount" to pages.toString(),
                "granuleEnd" to lastGranule.toString(),
                "blockSmall" to blockSmall.toString(),
                "blockLarge" to blockLarge.toString(),
            ),
        )
    }

    // ---------------------------------------------------------------- FLAC

    private fun probeFlac(b: ByteArray): Facts {
        require(ascii(b, 0, 4) == "fLaC") { "not a FLAC stream" }
        var off = 4
        var streamInfo: ByteArray? = null
        var seekTablePoints = 0
        while (off + 4 <= b.size) {
            val type = u8(b, off) and 0x7F
            val last = (u8(b, off) and 0x80) != 0
            val len = u24be(b, off + 1)
            require(off + 4 + len <= b.size) { "truncated FLAC metadata block" }
            if (type == 0) streamInfo = b.copyOfRange(off + 4, off + 4 + len)
            if (type == 3) seekTablePoints = len / 18
            off += 4 + len
            if (last) break
        }
        val si = streamInfo ?: error("FLAC missing STREAMINFO")
        require(si.size >= 18) { "FLAC STREAMINFO too short" }
        val sampleRate = (u8(si, 10) shl 12) or (u8(si, 11) shl 4) or (u8(si, 12) shr 4)
        val channels = ((u8(si, 12) shr 1) and 0x07) + 1
        val bitsPerSample = (((u8(si, 12) and 0x01) shl 4) or (u8(si, 13) shr 4)) + 1
        val totalSamples = ((u8(si, 13) and 0x0F).toLong() shl 32) or u32be(si, 14)
        val minBlock = u16be(si, 0)
        val maxBlock = u16be(si, 2)
        require(totalSamples > 0L) { "FLAC total samples missing" }
        return Facts(
            format = "flac",
            container = "flac",
            codec = "flac",
            sampleRate = sampleRate,
            channels = channels,
            containerSamples = totalSamples,
            primingSamples = 0L,
            seekGridSamples = maxBlock.toLong(),
            containerExact = true,
            details = linkedMapOf(
                "bitsPerSample" to bitsPerSample.toString(),
                "minBlock" to minBlock.toString(),
                "maxBlock" to maxBlock.toString(),
                "seekTablePoints" to seekTablePoints.toString(),
                "firstFrameOffset" to off.toString(),
            ),
        )
    }

    // ---------------------------------------------------------------- MP4 / M4A (AAC-LC)

    private data class Box(val type: String, val start: Int, val dataStart: Int, val end: Int)

    private val containerBoxes = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex",
    )

    private fun boxes(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var off = start
        while (off + 8 <= end) {
            var size = u32be(b, off)
            val type = ascii(b, off + 4, 4)
            var dataStart = off + 8
            if (size == 1L) {
                if (off + 16 > end) break
                size = u64be(b, off + 8)
                dataStart = off + 16
            } else if (size == 0L) {
                size = (end - off).toLong()
            }
            if (size < 8L || off + size > end) break
            out.add(Box(type, off, dataStart, off + size.toInt()))
            off += size.toInt()
        }
        return out
    }

    private fun collectBoxes(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        fun walk(s: Int, e: Int, depth: Int) {
            if (depth > 8) return
            for (box in boxes(b, s, e)) {
                out.add(box)
                if (box.type in containerBoxes) walk(box.dataStart, box.end, depth + 1)
            }
        }
        walk(start, end, 0)
        return out
    }

    private data class TimeHeader(val version: Int, val timescale: Long, val duration: Long)

    private fun timeHeader(b: ByteArray, box: Box): TimeHeader {
        val version = u8(b, box.dataStart)
        val o = box.dataStart + 4
        return if (version == 1) {
            TimeHeader(1, u32be(b, o + 16), u64be(b, o + 20))
        } else {
            TimeHeader(0, u32be(b, o + 8), u32be(b, o + 12))
        }
    }

    private fun probeMp4(b: ByteArray): Facts {
        val all = collectBoxes(b, 0, b.size)
        fun first(type: String): Box? = all.firstOrNull { it.type == type }
        val mvhd = first("mvhd") ?: error("MP4 missing mvhd")
        val mdhd = first("mdhd") ?: error("MP4 missing mdhd")
        val stts = first("stts") ?: error("MP4 missing stts")
        val elst = first("elst")
        val stsd = first("stsd") ?: error("MP4 missing stsd")
        val ftyp = first("ftyp")

        val movie = timeHeader(b, mvhd)
        val media = timeHeader(b, mdhd)
        require(media.timescale > 0L) { "MP4 media timescale missing" }

        val sttsEntryCount = u32be(b, stts.dataStart + 4).toInt()
        // stts 的 count 是"packet 数",delta 是每个 packet 的样本数;
        // 容器总样本 = Σ(count * delta),不是 Σcount。
        var sttsTotal = 0L
        var sttsPackets = 0L
        val deltas = LinkedHashMap<Long, Int>()
        var o = stts.dataStart + 8
        for (i in 0 until sttsEntryCount) {
            val count = u32be(b, o)
            val delta = u32be(b, o + 4)
            sttsTotal += count * delta
            sttsPackets += count
            deltas[delta] = (deltas[delta] ?: 0) + 1
            o += 8
        }
        val dominantDelta = deltas.entries.maxByOrNull { it.value }?.key ?: 0L

        var elstMediaTime = 0L
        var elstSegmentDuration = 0L
        if (elst != null) {
            val version = u8(b, elst.dataStart)
            val count = u32be(b, elst.dataStart + 4).toInt()
            var e = elst.dataStart + 8
            for (i in 0 until count) {
                if (version == 1) {
                    val seg = u64be(b, e)
                    val mt = i64be(b, e + 8)
                    if (i == 0) {
                        elstSegmentDuration = seg
                        elstMediaTime = mt
                    }
                    e += 20
                } else {
                    val seg = u32be(b, e)
                    val mt = u32be(b, e + 4)
                    if (i == 0) {
                        elstSegmentDuration = seg
                        elstMediaTime = mt
                    }
                    e += 12
                }
            }
        }

        val entryStart = stsd.dataStart + 8
        val entryFormat = ascii(b, entryStart + 4, 4)
        val channels = u16be(b, entryStart + 24)
        val sampleRate = (u32be(b, entryStart + 32) ushr 16).toInt()

        return Facts(
            format = "m4a-aac",
            container = "mp4",
            codec = "aac-lc",
            sampleRate = media.timescale.toInt(),
            channels = channels,
            containerSamples = sttsTotal,
            primingSamples = elstMediaTime,
            seekGridSamples = dominantDelta,
            containerExact = true,
            details = linkedMapOf(
                "brand" to (if (ftyp != null) ascii(b, ftyp.dataStart, 4) else "?"),
                "stsdEntry" to entryFormat,
                "stsdSampleRate" to sampleRate.toString(),
                "movieTimescale" to movie.timescale.toString(),
                "movieDuration" to movie.duration.toString(),
                "movieDurationMs" to (movie.duration * 1000L / movie.timescale).toString(),
                "mediaTimescale" to media.timescale.toString(),
                "mediaDuration" to media.duration.toString(),
                "sttsEntryCount" to sttsEntryCount.toString(),
                "sttsTotal" to sttsTotal.toString(),
                "sttsPackets" to sttsPackets.toString(),
                "sttsDeltas" to deltas.entries.joinToString(",") { it.key.toString() + "x" + it.value },
                "elstMediaTime" to elstMediaTime.toString(),
                "elstSegmentDuration" to elstSegmentDuration.toString(),
            ),
        )
    }

    // ---------------------------------------------------------------- MP3 (LAME / Xing)

    private fun probeMp3(b: ByteArray): Facts {
        require(b.size > 4) { "MP3 too short" }
        var off = 0
        if (ascii(b, 0, 3) == "ID3") {
            val size = syncSafe(b, 6)
            val footer = (u8(b, 5) and 0x10) != 0
            off = 10 + size + if (footer) 10 else 0
        }
        require(off + 4 < b.size) { "MP3 missing audio frame" }
        require(u8(b, off) == 0xFF && (u8(b, off + 1) and 0xE0) == 0xE0) {
            "MP3 frame sync missing at " + off
        }
        val b1 = u8(b, off + 1)
        val b2 = u8(b, off + 2)
        val b3 = u8(b, off + 3)
        val versionBits = (b1 ushr 3) and 0x03
        val layerBits = (b1 ushr 1) and 0x03
        require(versionBits == 3) { "only MPEG1 fixtures are supported, versionBits=" + versionBits }
        require(layerBits == 1) { "only Layer III fixtures are supported, layerBits=" + layerBits }
        val bitrateIndex = b2 ushr 4
        val sampleRateIndex = (b2 ushr 2) and 0x03
        val padding = (b2 ushr 1) and 0x01
        val channelMode = b3 ushr 6
        val sampleRate = intArrayOf(44_100, 48_000, 32_000)[sampleRateIndex]
        val kbps = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)[bitrateIndex]
        require(kbps > 0) { "MP3 free-format bitrate unsupported" }
        val frameLength = 144 * kbps * 1000 / sampleRate + padding
        val sideInfo = if (channelMode == 3) 17 else 32
        val xingOff = off + 4 + sideInfo
        val xingTag = ascii(b, xingOff, 4)
        require(xingTag == "Xing" || xingTag == "Info") { "MP3 missing Xing/Info header: " + xingTag }
        val flags = u32be(b, xingOff + 4).toInt()
        var o = xingOff + 8
        var frameCount = 0L
        var streamBytes = 0L
        if ((flags and 1) != 0) {
            frameCount = u32be(b, o)
            o += 4
        }
        if ((flags and 2) != 0) {
            streamBytes = u32be(b, o)
            o += 4
        }
        if ((flags and 4) != 0) o += 100
        if ((flags and 8) != 0) o += 4
        require(frameCount > 0L) { "MP3 Xing frame count missing" }

        // LAME 标签固定位于 Xing 头之后 120 字节;第 21..23 字节是 delay/padding(各 12bit)。
        val lameOff = xingOff + 120
        val vendor = ascii(b, lameOff, 9)
        val packed = u24be(b, lameOff + 21)
        val encoderDelay = (packed ushr 12) and 0x0FFF
        val paddingSamples = packed and 0x0FFF
        val containerSamples = frameCount * 1152L
        val priming = (encoderDelay + paddingSamples).toLong()
        require(priming < containerSamples) { "MP3 priming >= container samples" }
        return Facts(
            format = "mp3-lame",
            container = "mp3",
            codec = "mp3",
            sampleRate = sampleRate,
            channels = if (channelMode == 3) 1 else 2,
            containerSamples = containerSamples,
            primingSamples = priming,
            seekGridSamples = 1152L,
            containerExact = true,
            details = linkedMapOf(
                "id3End" to off.toString(),
                "bitrateKbps" to kbps.toString(),
                "frameLength" to frameLength.toString(),
                "xingTag" to xingTag,
                "xingFrames" to frameCount.toString(),
                "xingBytes" to streamBytes.toString(),
                "lameVendor" to vendor.trim(),
                "encoderDelay" to encoderDelay.toString(),
                "padding" to paddingSamples.toString(),
            ),
        )
    }

    // ---------------------------------------------------------------- byte helpers

    private fun u8(b: ByteArray, o: Int): Int {
        require(o in b.indices) { "read past end: " + o + "/" + b.size }
        return b[o].toInt() and 0xFF
    }

    private fun u16be(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)

    private fun u16le(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)

    private fun u24be(b: ByteArray, o: Int): Int =
        (u8(b, o) shl 16) or (u8(b, o + 1) shl 8) or u8(b, o + 2)

    private fun u32be(b: ByteArray, o: Int): Long =
        (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or
            (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()

    private fun u32le(b: ByteArray, o: Int): Long =
        u8(b, o).toLong() or (u8(b, o + 1).toLong() shl 8) or
            (u8(b, o + 2).toLong() shl 16) or (u8(b, o + 3).toLong() shl 24)

    private fun i64be(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or u8(b, o + i).toLong()
        return v
    }

    private fun u64be(b: ByteArray, o: Int): Long = i64be(b, o)

    private fun u64le(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or u8(b, o + i).toLong()
        return v
    }

    private fun syncSafe(b: ByteArray, o: Int): Int =
        ((u8(b, o) and 0x7F) shl 21) or ((u8(b, o + 1) and 0x7F) shl 14) or
            ((u8(b, o + 2) and 0x7F) shl 7) or (u8(b, o + 3) and 0x7F)

    private fun ascii(b: ByteArray, o: Int, len: Int): String {
        require(o >= 0 && o + len <= b.size) { "ascii read past end: " + o + "+" + len + "/" + b.size }
        return String(b, o, len, Charsets.US_ASCII)
    }
}