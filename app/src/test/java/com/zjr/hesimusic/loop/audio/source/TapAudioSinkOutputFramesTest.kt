package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import com.zjr.hesimusic.core.invariants.AudioOutputFrames
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.PcmTestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/**
 * 输出侧不变量（00020）的取证来源：TapAudioSink 把「真正交给 delegate 的帧数」
 * 记进 AudioOutputFrames，并按 controller 分桶。
 */
class TapAudioSinkOutputFramesTest {

    @After
    fun tearDown() {
        AudioOutputFrames.reset()
    }

    private fun consumingSink(): AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
    ) { _, method, args ->
        when (method.name) {
            "handleBuffer" -> {
                val buffer = args?.getOrNull(0) as? ByteBuffer
                if (buffer != null) buffer.position(buffer.limit())
                true
            }
            "supportsFormat" -> true
            "getCurrentPositionUs" -> 0L
            "isEnded" -> false
            "getFormat" -> null
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as AudioSink

    private fun configure(sink: TapAudioSink) {
        sink.configure(
            PcmTestSupport.format().buildUpon()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .build(),
            4096,
            null,
        )
    }

    @Test
    fun countsFramesHandedToTheDelegate() {
        val controller = LoopAudioController()
        val sink = TapAudioSink(consumingSink(), controller)
        configure(sink)
        for (i in 0 until 3) {
            sink.handleBuffer(PcmTestSupport.pcm16Buffer(i * 100, 100), i * 1_000L, 1)
        }
        assertEquals(300L, AudioOutputFrames.totalFrames(controller))
    }

    @Test
    fun mainAndPreviewControllersKeepSeparateCounts() {
        val mainController = LoopAudioController()
        val previewController = LoopAudioController()
        val mainSink = TapAudioSink(consumingSink(), mainController)
        val previewSink = TapAudioSink(consumingSink(), previewController)
        configure(mainSink)
        configure(previewSink)
        mainSink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 50), 0L, 1)
        previewSink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 10), 0L, 1)
        assertEquals(50L, AudioOutputFrames.totalFrames(mainController))
        assertEquals(10L, AudioOutputFrames.totalFrames(previewController))
    }

    @Test
    fun notConsumedButFullyAdvancedCountsEveryWrittenByte() {
        // A-048 的修复前会失败场景:delegate 返回 false,但写入路径把 position 推进到 limit
        // (DefaultAudioSink 的写入路径按写入量推进),统计必须按 100 帧而不是 0 帧计。
        var movedToLimit = true
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    val buffer = args?.getOrNull(0) as? ByteBuffer
                    if (buffer != null && movedToLimit) buffer.position(buffer.limit())
                    false
                }
                "supportsFormat" -> true
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
        val controller = LoopAudioController()
        val sink = TapAudioSink(delegate, controller)
        configure(sink)
        sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 100), 0L, 1)
        assertEquals("position 前进了 100 帧:统计 100", 100L, AudioOutputFrames.totalFrames(controller))

        movedToLimit = false
        sink.handleBuffer(PcmTestSupport.pcm16Buffer(100, 100), 1_000L, 1)
        assertEquals("早期返回且 position 未动:不新增统计", 100L, AudioOutputFrames.totalFrames(controller))
    }

    @Test
    fun notConsumedBufferStillCountsTheAdvancedPart() {
        var consumeFirst = true
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, args ->
            when (method.name) {
                "handleBuffer" -> {
                    val buffer = args?.getOrNull(0) as? ByteBuffer
                    if (buffer != null) {
                        if (consumeFirst) {
                            buffer.position(buffer.limit())
                        } else {
                            // 只消费一半（背压场景）。
                            buffer.position(buffer.position() + buffer.remaining() / 2)
                        }
                    }
                    consumeFirst
                }
                "supportsFormat" -> true
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
        val controller = LoopAudioController()
        val sink = TapAudioSink(delegate, controller)
        configure(sink)
        consumeFirst = false
        // 100 帧 buffer，delegate 只吃了 50 帧。
        sink.handleBuffer(PcmTestSupport.pcm16Buffer(0, 100), 0L, 1)
        assertEquals(50L, AudioOutputFrames.totalFrames(controller))
    }
}
