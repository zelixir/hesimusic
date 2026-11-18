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
