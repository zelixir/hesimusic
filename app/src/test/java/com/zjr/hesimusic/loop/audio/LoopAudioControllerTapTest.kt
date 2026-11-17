package com.zjr.hesimusic.loop.audio

import com.zjr.hesimusic.loop.LoopWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** SOURCE tap：PCM 字节数按帧对齐，seam 事件的 outFrames 与 PCM 帧号一致。 */
class LoopAudioControllerTapTest {

    @Test
    fun `tap writes frame aligned pcm and seam event uses output frame index`() {
        val dir = createTempDir(prefix = "loop-tap-")
        val pcm = File(dir, "source.pcm")
        val events = File(dir, "source.pcm.jsonl")
        val controller = LoopAudioController()
        try {
            controller.window = LoopWindow(
                songId = 7L,
                loopStartUs = 20_000_000L,
                loopEndUs = 35_000_000L,
            )
            controller.setPcmTap(pcm)
            controller.onFormatConfigured(
                sampleRate = 48_000,
                channels = 2,
                bytesPerFrame = 4,
                pcmIsFloat = false,
            )

            val frames = 1_000
            val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames * 2) { buffer.putShort(0) }
            buffer.flip()
            controller.tapPcm(buffer, 0, buffer.limit())
            controller.tapSeam(frames.toLong())
            controller.onSinkRelease()
        } finally {
            // onSinkRelease 已关闭 tap；这里只兜底清空引用。
            controller.setPcmTap(null)
        }

        val bytes = pcm.readBytes()
        // WAV header 44 + 1000 stereo PCM16 frames * 4 bytes.
        assertEquals(44 + 1_000 * 4, bytes.size)
        val text = events.readText()
        assertTrue("run event must carry loop window", text.contains("\"loopStartUs\":"))
        assertTrue("run event must carry channels", text.contains("\"channels\":2"))
        assertTrue("seam event must exist", text.contains("\"seam\""))
        assertTrue(
            "seam outFrames must match PCM frame count: " + text,
            text.contains("\"outFrames\":1000"),
        )
    }
}
