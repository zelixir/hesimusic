package com.zjr.hesimusic.loop.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 格式/边界矩阵(JVM 侧:容器事实)。
 *
 * 为什么 JVM 侧只测容器事实:
 * [PcmDecoder] 的构造需要 `Context`,解码走 `android.media.MediaExtractor/MediaCodec`;
 * 单元测试运行时 android.* 是 stub(调用即抛 "Stub!"),Robolectric 也没有
 * Vorbis/Opus/FLAC/AAC 的原生解码器。所以**真实 PCM 的逐字节确定性与 [A,B) 样本对齐
 * 不能在 JVM 上伪造**,统一由 `tools/format-matrix.ps1` 在真机上调用
 * `POST /debug/decode-range` 验证。
 *
 * 本类负责钉住"循环能不能精确切"的容器前提:
 * - 采样率 / 声道 / 容器总样本数;
 * - 编码器前导(priming / pre-skip / 编辑列表):决定解码第 0 帧与用户内容的偏差;
 * - 最小可寻址网格:决定 A/B 最细能落到哪个边界;
 * - [A,B) 帧号算术:宽窗口([A-1s,B])裁剪后必须与直接 [A,B) 帧数严格一致(0 帧偏差)。
 *
 * 样本全部是同一段确定性合成信号(3 音 + 每 250ms 一个瞬态)的 5 种真实编码,
 * 位于 `src/test/resources/loop-format/`;生成命令见该目录 README.md。
 */
class RingBandFormatMatrixTest {

    private data class Fixture(
        val fileName: String,
        val format: String,
        val sampleRate: Int,
        val channels: Int,
        val containerSamples: Long,
        val primingSamples: Long,
        val seekGridSamples: Long,
        val containerExact: Boolean,
        val durationSeconds: Double,
        val maxBytes: Long = 200L * 1024L,
    )

    /** 期望值来自容器头(ffprobe 已交叉核对),不是解码 PCM。 */
    private val fixtures = listOf(
        Fixture(
            fileName = "css-44100-flac.flac",
            format = "flac",
            sampleRate = 44_100,
            channels = 2,
            containerSamples = 141_120, // = 3.2s * 44100,STREAMINFO 精确总样本
            primingSamples = 0,
            seekGridSamples = 4_608, // FLAC 帧(本样本 blocksize 固定 4608)
            containerExact = true,
            durationSeconds = 3.2,
        ),
        Fixture(
            fileName = "css-44100-vorbis.ogg",
            format = "ogg-vorbis",
            sampleRate = 44_100,
            channels = 2,
            containerSamples = 176_400, // 末页 granule = 总样本
            primingSamples = 0,
            seekGridSamples = 0, // Ogg 只能回退到页边界
            containerExact = false,
            durationSeconds = 4.0,
        ),
        Fixture(
            fileName = "css-48000-opus.opus",
            format = "ogg-opus",
            sampleRate = 48_000,
            channels = 2,
            containerSamples = 192_312, // granule 含 pre-skip
            primingSamples = 312, // OpusHead pre-skip
            seekGridSamples = 0,
            containerExact = false,
            durationSeconds = 4.0,
        ),
        Fixture(
            fileName = "css-48000-m4a-aac-lc.m4a",
            format = "m4a-aac",
            sampleRate = 48_000,
            channels = 2,
            containerSamples = 193_024, // stts 总样本 = 188*1024 + 512
            primingSamples = 1_024, // elst media_time(AAC priming)
            seekGridSamples = 1_024, // AAC packet
            containerExact = true,
            durationSeconds = 4.0,
        ),
        Fixture(
            fileName = "css-44100-mp3-lame.mp3",
            format = "mp3-lame",
            sampleRate = 44_100,
            channels = 2,
            containerSamples = 178_560, // 155 帧 * 1152
            primingSamples = 2_160, // LAME delay 576 + padding 1584
            seekGridSamples = 1_152, // MP3 帧
            containerExact = true,
            durationSeconds = 4.0,
        ),
    )

    // ---------------------------------------------------------------- 样本本身

    @Test
    fun `全部样本短小且时长在 3 到 5 秒`() {
        fixtures.forEach { fixture ->
            val bytes = bytesOf(fixture.fileName)
            assertTrue(
                fixture.fileName + ": sample must be < 200KB, was " + bytes.size + "B",
                bytes.size in 1..fixture.maxBytes.toInt(),
            )
            val facts = AudioContainerProbe.probe(bytes, fixture.fileName)
            assertTrue(
                fixture.fileName + ": duration must be 3..5s, was " + facts.durationSeconds,
                facts.durationSeconds in 3.0..5.0,
            )
            assertEquals(fixture.fileName + " format", fixture.format, facts.format)
        }
    }

    @Test
    fun `容器事实矩阵与样本一致`() {
        val lines = ArrayList<String>()
        fixtures.forEach { fixture ->
            val facts = AudioContainerProbe.probe(bytesOf(fixture.fileName), fixture.fileName)
            val tag = "[" + fixture.fileName + "] "
            assertEquals(tag + "sampleRate", fixture.sampleRate, facts.sampleRate)
            assertEquals(tag + "channels", fixture.channels, facts.channels)
            assertEquals(tag + "containerSamples", fixture.containerSamples, facts.containerSamples)
            assertEquals(tag + "primingSamples", fixture.primingSamples, facts.primingSamples)
            assertEquals(
                tag + "presentedSamples",
                fixture.containerSamples - fixture.primingSamples,
                facts.presentedSamples,
            )
            assertEquals(tag + "seekGridSamples", fixture.seekGridSamples, facts.seekGridSamples)
            assertEquals(tag + "containerExact", fixture.containerExact, facts.containerExact)
            assertEquals(tag + "durationSeconds", fixture.durationSeconds, facts.durationSeconds, 1e-9)

            lines.add(
                "FORMAT-MATRIX format=" + facts.format +
                    " sr=" + facts.sampleRate +
                    " ch=" + facts.channels +
                    " containerSamples=" + facts.containerSamples +
                    " priming=" + facts.primingSamples +
                    " presented=" + facts.presentedSamples +
                    " durationSec=" + facts.durationSeconds +
                    " seekGrid=" + (if (facts.seekGridSamples == 0L) "page" else facts.seekGridSamples.toString()) +
                    " containerExact=" + facts.containerExact +
                    " details=" + facts.details,
            )
        }
        lines.forEach(::println)
    }

    @Test
    fun `编码器前导按格式区分`() {
        val facts = fixtures.associate { it.fileName to AudioContainerProbe.probe(bytesOf(it.fileName), it.fileName) }

        // FLAC / Vorbis 没有前导:容器第 0 帧 == 用户第 0 个样本。
        assertEquals(0L, facts.getValue("css-44100-flac.flac").primingSamples)
        assertEquals(0L, facts.getValue("css-44100-vorbis.ogg").primingSamples)

        // Opus:pre-skip 312,granule 192312 - 312 = 192000 = 4s(容器时长 4.0065s 是含 pre-skip 的口径)。
        val opus = facts.getValue("css-48000-opus.opus")
        assertEquals(312L, opus.primingSamples)
        assertEquals(192_000L, opus.presentedSamples)
        assertEquals("312", opus.details["preSkip"])
        assertEquals("192312", opus.details["granuleEnd"])
        assertEquals("4006", opus.details["granuleDurationMs"])

        // AAC:mdhd/stts 含 1024 样本 priming,elst media_time=1024;影片时长 4000ms。
        val aac = facts.getValue("css-48000-m4a-aac-lc.m4a")
        assertEquals(1_024L, aac.primingSamples)
        assertEquals(192_000L, aac.presentedSamples)
        assertEquals("mp4a", aac.details["stsdEntry"])
        assertEquals("4000", aac.details["movieDurationMs"])
        assertEquals("1024", aac.details["elstMediaTime"])
        assertEquals("193024", aac.details["sttsTotal"])
        assertEquals("2", aac.details["sttsEntryCount"])

        // MP3:LAME delay 576 + padding 1584 = 2160;155 帧 * 1152 = 178560。
        val mp3 = facts.getValue("css-44100-mp3-lame.mp3")
        assertEquals(2_160L, mp3.primingSamples)
        assertEquals(176_400L, mp3.presentedSamples)
        assertEquals("576", mp3.details["encoderDelay"])
        assertEquals("1584", mp3.details["padding"])
        assertEquals("155", mp3.details["xingFrames"])
    }

    // ---------------------------------------------------------------- [A,B) 边界契约

    @Test
    fun `宽窗口裁剪后与直接解码帧数零偏差`() {
        fixtures.forEach { fixture ->
            val durationUs = (fixture.durationSeconds * 1_000_000.0).toLong()
            windows(durationUs).forEach { (aUs, bUs) ->
                val sr = fixture.sampleRate
                val aFrame = Pcm16Frames.frameAtOrAfterUs(aUs, sr)
                val bFrame = Pcm16Frames.frameAtOrAfterUs(bUs, sr)
                val strict = Pcm16Frames.framesForUsCeil(bUs - aUs, sr)
                val natural = bFrame - aFrame
                val label = fixture.fileName + " A=" + aUs + "us B=" + bUs + "us"

                // PcmDecoder 契约:输出从 ceil(A) 帧开始,取严格帧数 ceil(B-A);
                // 自然的"PTS < B 帧数"只会比严格帧数少 0 或 1(末尾整帧)。
                assertTrue(label + " frames must be > 0", strict > 0L)
                assertTrue(
                    label + ": natural=" + natural + " strict=" + strict + " (allowed strict-1..strict)",
                    natural == strict || natural == strict - 1L,
                )

                // 宽窗口 [A-1s,B] 裁剪掉 ceil(A)-ceil(A-1s) 帧后,剩余帧数必须精确等于直接 [A,B)。
                val wideAUs = (aUs - 1_000_000L).coerceAtLeast(0L)
                val wideAFrame = Pcm16Frames.frameAtOrAfterUs(wideAUs, sr)
                val wideStrict = Pcm16Frames.framesForUsCeil(bUs - wideAUs, sr)
                val skip = aFrame - wideAFrame
                assertEquals(
                    label + ": wide trim must be frame exact (wide=" + wideStrict + " skip=" + skip + ")",
                    strict,
                    wideStrict - skip,
                )
                if (aUs >= 1_000_000L) {
                    assertEquals(
                        label + ": 1s lead must be exactly sampleRate frames",
                        sr.toLong(),
                        skip,
                    )
                }
            }
        }
    }

    @Test
    fun `贴文件尾的窗口不越过容器总样本`() {
        fixtures.forEach { fixture ->
            val presented = fixture.containerSamples - fixture.primingSamples
            val durationUs = presented * 1_000_000L / fixture.sampleRate
            val bUs = durationUs - 50_000L // B = duration - 50ms
            val aUs = bUs - 50_000L // 50ms 短窗
            val bFrame = Pcm16Frames.frameAtOrAfterUs(bUs, fixture.sampleRate)
            val aFrame = Pcm16Frames.frameAtOrAfterUs(aUs, fixture.sampleRate)
            val frames = Pcm16Frames.framesForUsCeil(bUs - aUs, fixture.sampleRate)

            assertTrue(fixture.fileName + ": tail window must have frames", frames > 0L)
            assertTrue(
                fixture.fileName + ": B frame " + bFrame + " must be < presented " + presented,
                bFrame < presented,
            )
            assertEquals(
                fixture.fileName + ": tail A/B frame delta must match strict frames",
                frames,
                bFrame - aFrame,
            )
            // 50ms 窗口在 44.1k/48k 下都是整帧(2205 / 2400),不存在半帧歧义。
            assertEquals(
                fixture.fileName + ": 50ms tail window frames",
                fixture.sampleRate * 50L / 1_000L,
                frames,
            )
        }
    }

    // ---------------------------------------------------------------- 设备侧(不伪造)

    @Test
    fun `设备侧逐字节契约骨架只在该设备上执行`() {
        val hasRealCodec = runCatching {
            val codec = Class.forName("android.media.MediaCodec")
            codec.getMethod("createDecoderByType", String::class.java).invoke(null, "audio/flac")
        }.isSuccess
        assumeTrue(
            "JVM 单测没有真实 MediaCodec;真实 PCM 对齐请跑 tools/format-matrix.ps1",
            hasRealCodec,
        )
        // 若将来出现带真实 MediaCodec 的 JVM 运行时,这里也不能偷偷构造 PcmDecoder(Context),
        // 必须走设备通道,保证"实测偏差"来自真机而不是测试替身。
        fail(
            "检测到真实 MediaCodec 运行时:请用 tools/format-matrix.ps1 " +
                "(POST /debug/decode-range)产出逐字节 sha256 与对齐偏差,不要在此伪造解码。",
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun windows(durationUs: Long): List<Pair<Long, Long>> = listOf(
        // 短窗口
        0L to 1_000_000L,
        // 区间中段
        500_000L to 2_500_000L,
        // 贴文件尾(B = duration - 50ms)
        (durationUs - 1_050_000L).coerceAtLeast(0L) to (durationUs - 50_000L),
    )

    private fun bytesOf(name: String): ByteArray {
        val stream = javaClass.classLoader?.getResourceAsStream("loop-format/" + name)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(
            File("src/test/resources/loop-format/" + name),
            File("app/src/test/resources/loop-format/" + name),
        ).firstOrNull { it.isFile }
        assertNotNull("fixture missing: " + name + " (cwd=" + File(".").absolutePath + ")", file)
        return file!!.readBytes()
    }
}