package com.zjr.hesimusic.loop.analysis

import android.app.ActivityManager
import android.content.res.AssetManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机逐样本一致对照:同一文件单线程解码 vs 并行解码(2/3/4 段)的 mono PCM 必须完全相同。
 *
 * 加固点(上版一次跑 5 格式 × 3 段数 = 15 次解码,崩了无法定位):
 * - **每种格式一个 @Test**:进程崩溃时 instrumented 输出能指认是哪个格式/段数;
 * - 每次解码前后 Log.i + println(println 进 instrumentation 输出,进程崩了也能看到);
 * - 段数 2 -> 3 -> 4;每次之间 System.gc()+runFinalization()+150ms,让 MediaCodec 服务回收实例;
 * - 解码器内部每个段/每次解码都在 finally 中 stop/release,测试不持有 codec;
 * - 低内存设备/堆过小用 Assume 跳过,避免正常 CI 红。
 *
 * 运行(需要连接设备,可选只跑某一种格式):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.zjr.hesimusic.loop.analysis.PcmDecoderParallelEqualityTest
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.zjr.hesimusic.loop.analysis.PcmDecoderParallelEqualityTest#mp3_128k
 *
 * 手工验证(不必跑 instrumented,推荐):
 *   curl -s -X POST http://127.0.0.1:8765/debug/pcm-compare \
 *     -d '{"path":"/sdcard/Music/xxx.mp3","segments":4}'
 */
@RunWith(AndroidJUnit4::class)
class PcmDecoderParallelEqualityTest {

    private lateinit var decoder: PcmDecoder
    private lateinit var testAssets: AssetManager
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        val am = ctx.getSystemService(ActivityManager::class.java)
        Assume.assumeFalse("low-RAM device: skip parallel decode stress", am?.isLowRamDevice == true)
        Assume.assumeTrue(
            "heap class < 256MB: skip whole-track compare",
            (am?.memoryClass ?: 512) >= 256,
        )
        decoder = PcmDecoder(ctx)
        decoder.clearParallelCaches()
        testAssets = instr.context.assets
        cacheDir = ctx.cacheDir
        println("PCM_TEST SETUP processors=" + Runtime.getRuntime().availableProcessors() +
            " memoryClass=" + (am?.memoryClass ?: -1) + " lowRam=" + (am?.isLowRamDevice == true))
        ParallelDecodeHooks.disableParallel = false
        ParallelDecodeHooks.segmentCountOverride = null
    }

    @After
    fun tearDown() {
        ParallelDecodeHooks.disableParallel = false
        ParallelDecodeHooks.segmentCountOverride = null
        decoder.clearParallelCaches()
        System.gc()
        System.runFinalization()
        println("PCM_TEST TEARDOWN")
    }

    @Test
    fun mp3_128k() = compareAsset("loop-mp3-128.mp3")

    @Test
    fun mp3_320k() = compareAsset("loop-mp3-320.mp3")

    @Test
    fun mp3_48k() = compareAsset("loop-mp3-48k.mp3")

    @Test
    fun flac() = compareAsset("loop-flac.flac")

    @Test
    fun vorbis() = compareAsset("loop-vorbis.ogg")

    private fun compareAsset(name: String) = runBlocking {
        val file = File(cacheDir, "pcm-parallel-test-" + name)
        testAssets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        println("PCM_TEST ASSET " + name + " bytes=" + file.length())
        try {
            ParallelDecodeHooks.disableParallel = true
            ParallelDecodeHooks.segmentCountOverride = null
            val serial = decodeLogged("serial", file, name, 1)

            for (segments in intArrayOf(2, 3, 4)) {
                // 只清失败/黑名单(保留探针偏移):上一段失败不影响本次,且不重复建探针解码器
                decoder.clearParallelFailures()
                ParallelDecodeHooks.disableParallel = false
                ParallelDecodeHooks.segmentCountOverride = segments
                val par = decodeLogged("parallel", file, name, segments)
                if (!decoder.lastDecodeUsedParallel) {
                    Log.w(
                        TAG,
                        "pcm-test FALLBACK asset=" + name + " segments=" + segments +
                            " (probe/seam rejected; product would stay serial)",
                    )
                    println("PCM_TEST FALLBACK asset=" + name + " segments=" + segments)
                } else {
                    comparePcm(serial, par, name, segments)
                }
                // MediaCodec 服务侧回收是异步的:给一点时间再创建下一批实例。
                System.gc()
                System.runFinalization()
                Thread.sleep(150L)
            }
        } finally {
            file.delete()
            ParallelDecodeHooks.disableParallel = false
            ParallelDecodeHooks.segmentCountOverride = null
        }
    }

    private suspend fun decodeLogged(
        kind: String,
        file: File,
        name: String,
        segments: Int,
    ): MonoPcm {
        val line = "PCM_TEST START kind=" + kind + " asset=" + name + " segments=" + segments +
            " usedParallelBefore=" + decoder.lastDecodeUsedParallel
        Log.i(TAG, line)
        println(line)
        val started = System.currentTimeMillis()
        val pcm = decoder.decodeMono(file.absolutePath)
        val ms = System.currentTimeMillis() - started
        val done = "PCM_TEST DONE kind=" + kind + " asset=" + name + " segments=" + segments +
            " usedParallel=" + decoder.lastDecodeUsedParallel +
            " actualSegments=" + decoder.lastDecodeSegmentCount +
            " frames=" + pcm.frames + " sr=" + pcm.sampleRate + " ms=" + ms
        Log.i(TAG, done)
        println(done)
        return pcm
    }

    private fun comparePcm(serial: MonoPcm, par: MonoPcm, name: String, segments: Int) {
        var reason = ""
        var firstDiff = -1
        if (serial.sampleRate != par.sampleRate) {
            reason = "sampleRate " + serial.sampleRate + " != " + par.sampleRate
        } else if (serial.frames != par.frames) {
            reason = "frames " + serial.frames + " != " + par.frames
        } else if (serial.data.size != par.data.size) {
            reason = "size " + serial.data.size + " != " + par.data.size
        } else {
            for (i in serial.data.indices) {
                if (serial.data[i] != par.data[i]) {
                    firstDiff = i
                    break
                }
            }
            if (firstDiff >= 0) reason = "sample " + firstDiff + ": " +
                serial.data[firstDiff] + " != " + par.data[firstDiff]
        }
        val identical = reason.isEmpty()
        val line = "PCM_TEST COMPARE asset=" + name + " segments=" + segments +
            " identical=" + identical + " firstDiff=" + firstDiff +
            " samples=" + serial.data.size + " reason=" + reason
        Log.i(TAG, line)
        println(line)
        if (!identical) {
            fail("parallel PCM mismatch asset=" + name + " segments=" + segments + ": " + reason)
        }
    }

    private companion object {
        const val TAG = "LoopPerf"
    }
}
