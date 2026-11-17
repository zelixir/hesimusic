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
    fun `format change after setPcmTap keeps same handle and writes new frames`() {
        val dir = createTempDir(prefix = "loop-tap-format-")
        val pcm = File(dir, "format.pcm")
        val controller = LoopAudioController()
        try {
            controller.setPcmTap(pcm)
            controller.onFormatConfigured(48_000, 2, 4, false)
            // 格式变化（如 AudioSink 重新 configure）不应 close/reopen 掉句柄。
            controller.onFormatConfigured(44_100, 2, 4, false)
            val buffer = ByteBuffer.allocate(3 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(3 * 2) { buffer.putShort(0) }
            buffer.flip()
            controller.tapPcm(buffer, 0, buffer.limit())
            controller.onSinkRelease()
        } finally {
            controller.setPcmTap(null)
        }
        assertEquals(44 + 3 * 4, pcm.readBytes().size)
    }

    @Test
    fun `unwritable tap path sets tapFailed`() {
        val dir = createTempDir(prefix = "loop-tap-fail-")
        val blocked = File(dir, "blocked")
        assertTrue(blocked.mkdirs())
        val controller = LoopAudioController()
        try {
            controller.setPcmTap(blocked)
            controller.onFormatConfigured(48_000, 2, 4, false)
            assertTrue(controller.tapFailedForTest)
        } finally {
            controller.setPcmTap(null)
        }
    }

    @Test
    fun `setPcmTap after configure opens file and writes duplicate view frames`() {
        val dir = createTempDir(prefix = "loop-tap-after-")
        val pcm = File(dir, "after.pcm")
        val controller = LoopAudioController()
        try {
            controller.window = LoopWindow(7L, 0L, 3_000_000L)
            controller.onFormatConfigured(48_000, 2, 4, false)
            controller.setPcmTap(pcm)

            val full = ByteBuffer.allocate(3 * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(3 * 2) { full.putShort(0x1234) }
            full.flip()
            val snapshot = full.duplicate()
            controller.tapPcm(snapshot, snapshot.position(), snapshot.remaining())
            controller.onSinkRelease()
        } finally {
            controller.setPcmTap(null)
        }

        val bytes = pcm.readBytes()
        assertEquals(44 + 3 * 4, bytes.size)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 3 * 4, header.getInt(4))
        assertEquals(3 * 4, header.getInt(40))
    }

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
