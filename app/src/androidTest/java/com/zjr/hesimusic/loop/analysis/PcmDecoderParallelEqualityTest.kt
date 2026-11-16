package com.zjr.hesimusic.loop.analysis

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机逐样本一致对照:同一文件单线程解码 vs 并行解码(2/3/4 段)的 mono PCM 必须完全相同。
 *
 * 运行(需要连接设备):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.zjr.hesimusic.loop.analysis.PcmDecoderParallelEqualityTest
 *
 * 结果看 logcat 的 "LoopPerf: parallel-test ..." 行:
 * - usedParallel=true 表示确实走了并行路径,随后逐样本对比;
 * - FALLBACK 表示该格式/解码器被运行时接缝校验拒绝并回退单线程(设计内的安全回退)。
 *
 * 覆盖格式:MP3 128k/320k/48k、FLAC、Vorbis(12s/7s 片段,asset 内)。
 */
@RunWith(AndroidJUnit4::class)
class PcmDecoderParallelEqualityTest {

    private val assets = listOf(
        "loop-mp3-128.mp3",
        "loop-mp3-320.mp3",
        "loop-mp3-48k.mp3",
        "loop-flac.flac",
        "loop-vorbis.ogg",
    )

    @After
    fun resetHooks() {
        ParallelDecodeHooks.segmentCountOverride = null
        ParallelDecodeHooks.disableParallel = false
    }

    @Test
    fun parallelDecodeIsSampleIdenticalToSerial() = runBlocking {
        val instr = InstrumentationRegistry.getInstrumentation()
        val testAssets = instr.context.assets
        val cacheDir = instr.targetContext.cacheDir
        val decoder = PcmDecoder(instr.targetContext)
        val failures = ArrayList<String>()
        val fallbacks = ArrayList<String>()
        var parallelRuns = 0

        for (name in assets) {
            val file = File(cacheDir, "pcm-parallel-test-" + name)
            testAssets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }

            ParallelDecodeHooks.disableParallel = true
            ParallelDecodeHooks.segmentCountOverride = null
            val serialStarted = System.currentTimeMillis()
            val serial = decoder.decodeMono(file.absolutePath)
            val serialMs = System.currentTimeMillis() - serialStarted
            assertFalse("serial must not use parallel", decoder.lastDecodeUsedParallel)
            Log.d(
                "LoopPerf",
                "parallel-test " + name + " serial frames=" + serial.frames +
                    " sr=" + serial.sampleRate + " ms=" + serialMs,
            )

            for (n in listOf(2, 3, 4)) {
                ParallelDecodeHooks.segmentCountOverride = n
                val started = System.currentTimeMillis()
                val par = decoder.decodeMono(file.absolutePath)
                val elapsed = System.currentTimeMillis() - started
                val used = decoder.lastDecodeUsedParallel
                val segs = decoder.lastDecodeSegmentCount
                Log.d(
                    "LoopPerf",
                    "parallel-test " + name + " n=" + n + " usedParallel=" + used +
                        " segments=" + segs + " frames=" + par.frames +
                        " sr=" + par.sampleRate + " ms=" + elapsed,
                )
                if (!used) {
                    fallbacks.add(name + " n=" + n)
                    continue
                }
                parallelRuns++
                if (serial.frames != par.frames) {
                    failures.add(name + " n=" + n + " frame count " + serial.frames + " != " + par.frames)
                    continue
                }
                if (serial.sampleRate != par.sampleRate) {
                    failures.add(name + " n=" + n + " sample rate differs")
                    continue
                }
                if (serial.data.size != par.data.size) {
                    failures.add(name + " n=" + n + " array size " + serial.data.size + " != " + par.data.size)
                    continue
                }
                var firstDiff = -1
                for (i in serial.data.indices) {
                    if (serial.data[i] != par.data[i]) {
                        firstDiff = i
                        break
                    }
                }
                if (firstDiff >= 0) {
                    failures.add(
                        name + " n=" + n + " first differing sample " + firstDiff +
                            ": " + serial.data[firstDiff] + " != " + par.data[firstDiff],
                    )
                } else {
                    Log.d(
                        "LoopPerf",
                        "parallel-test " + name + " n=" + n + " PCM IDENTICAL (" +
                            serial.data.size + " samples)",
                    )
                }
            }
            ParallelDecodeHooks.disableParallel = false
            ParallelDecodeHooks.segmentCountOverride = null
            file.delete()
        }

        for (f in fallbacks) Log.w("LoopPerf", "parallel-test FALLBACK " + f)
        Log.d(
            "LoopPerf",
            "parallel-test done: parallelRuns=" + parallelRuns + " fallbacks=" + fallbacks.size +
                " failures=" + failures.size,
        )
        assertTrue(
            "parallel decode never engaged (all formats fell back); see logcat",
            parallelRuns > 0,
        )
        if (failures.isNotEmpty()) {
            fail(failures.joinToString(separator = "\n"))
        }
    }
}
