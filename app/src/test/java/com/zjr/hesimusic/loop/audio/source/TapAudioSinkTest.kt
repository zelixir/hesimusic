package com.zjr.hesimusic.loop.audio.source

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import com.zjr.hesimusic.loop.audio.LoopAudioController
import com.zjr.hesimusic.loop.audio.PcmTestSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

class TapAudioSinkTest {

    private var consumed = true
    private var calls = 0

    private fun fakeSink(): AudioSink = Proxy.newProxyInstance(
        AudioSink::class.java.classLoader,
        arrayOf(AudioSink::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "handleBuffer" -> {
                calls += 1
                consumed
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

    @After
    fun tearDown() {
        LoopSourcePauseGate.reset()
    }

    @Test
    fun `handleBuffer writes all frames even when delegate reports not consumed`() {
        val file = File.createTempFile("tap-sink-", ".wav")
        val controller = LoopAudioController()
        val sink = TapAudioSink(fakeSink(), controller)
        try {
            controller.setPcmTap(file)
            sink.configure(
                PcmTestSupport.format().buildUpon()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .build(),
                4096,
                null,
            )
            for (i in 0 until 100) {
                consumed = i % 10 >= 3 // 30 次 consumed=false
                sink.handleBuffer(PcmTestSupport.pcm16Buffer(i, 1), i * 1_000L, 1)
            }
            controller.setPcmTap(null)
        } finally {
            controller.setPcmTap(null)
        }
        assertEquals("100 frames * 2 bytes + 44 header", 244, file.readBytes().size)
        assertEquals(100, calls)
    }

    /**
     * issue 00020 回归：sink 绝不能在 handleBuffer 里自己启动播放。
     *
     * 旧实现有一个“诊断兜底”：首个 handleBuffer 之后强制 `play()`，并在 pause() 里把标志复位。
     * 结果 UI 进「无缝分析」页暂停主播放时，AudioTrack 被重新 start，之后 ExoPlayer 每秒喂进来的
     * 那一小段 PCM 就变成“~96ms 出声 + ~0.9s 静音”的循环（用户听到的 0.x 秒播放/停顿）。
     */
    @Test
    fun `handleBuffer never starts or restarts the delegate sink`() {
        val file = File.createTempFile("tap-sink-play-", ".wav")
        var playCalls = 0
        var pauseCalls = 0
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "play" -> {
                    playCalls += 1
                    null
                }

                "pause" -> {
                    pauseCalls += 1
                    null
                }

                "handleBuffer" -> true
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
        val controller = LoopAudioController()
        val sink = TapAudioSink(delegate, controller)
        try {
            controller.setPcmTap(file)
            sink.configure(
                PcmTestSupport.format().buildUpon()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .build(),
                4096,
                null,
            )
            for (i in 0 until 5) {
                sink.handleBuffer(PcmTestSupport.pcm16Buffer(i, 1), i * 1_000L, 1)
            }
            assertEquals("handleBuffer 不得启动播放", 0, playCalls)

            sink.pause()
            assertEquals("pause 应原样转发给 delegate", 1, pauseCalls)

            for (i in 5 until 10) {
                sink.handleBuffer(PcmTestSupport.pcm16Buffer(i, 1), i * 1_000L, 1)
            }
            assertEquals("暂停后到达的 buffer 不得重启 AudioTrack", 0, playCalls)
        } finally {
            controller.setPcmTap(null)
        }
    }

    @Test
    fun `play is forwarded exactly once`() {
        var playCalls = 0
        val delegate = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "play" -> {
                    playCalls += 1
                    null
                }

                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
        val sink = TapAudioSink(delegate, LoopAudioController())
        sink.play()
        assertEquals(1, playCalls)
    }

    @Test
    fun `same buffer passed twice is written once`() {
        val file = File.createTempFile("tap-sink-dup-", ".wav")
        val controller = LoopAudioController()
        val sink = TapAudioSink(fakeSink(), controller)
        try {
            controller.setPcmTap(file)
            sink.configure(
                PcmTestSupport.format().buildUpon()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .build(),
                4096,
                null,
            )
            val buffer = PcmTestSupport.pcm16Buffer(0, 1)
            sink.handleBuffer(buffer, 0L, 1)
            sink.handleBuffer(buffer, 0L, 1)
            controller.setPcmTap(null)
        } finally {
            controller.setPcmTap(null)
        }
        assertEquals("one 2-byte frame + 44 header", 46, file.readBytes().size)
    }
}
