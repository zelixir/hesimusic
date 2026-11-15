package com.zjr.hesimusic.loop.audio

import com.zjr.hesimusic.loop.LoopWindow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务层 / sink 之间的唯一接口。
 *
 * 公开 API 刻意保持很小：服务层只写 [window] / [fullDurationUs]，UI 与服务层只读
 * 位置与 debug 计数；音频线程通过 internal 方法与 [engine] 交互。
 */
@Singleton
class LoopAudioController @Inject constructor() {

    internal val engine = LoopPcmEngine()

    /**
     * 无缝窗口；null = 纯直通。仅服务层写。
     *
     * 写入时同步到引擎（会重建 ring）；sink 在每个音频回调里也会再同步一次，
     * 避免服务层在 configure 之前写窗口时丢失。
     */
    @Volatile
    var window: LoopWindow? = null
        set(value) {
            field = value
            engine.setWindow(value)
        }

    /** 当前歌整曲时长（µs），会话层 getDuration 投影用。 */
    @Volatile
    var fullDurationUs: Long = 0L

    /** 引擎侧媒体位置（µs）：重放期 = B+(k-1)L+环内偏移，由 sink 每次 getCurrentPositionUs 更新。 */
    @Volatile
    private var lastPositionUs: Long = 0L

    val positionUs: Long
        get() = lastPositionUs

    val isReplaying: Boolean
        get() = engine.state == LoopPcmEngine.State.REPLAYING

    val seamCount: Long
        get() = engine.seamCount

    val degradeCount: Long
        get() = engine.degradeCount

    val isRingComplete: Boolean
        get() = engine.isRingComplete

    // ------------------------------------------------------------------ PCM tap

    private val tapLock = Any()
    private var tapFile: File? = null
    private var tapOut: RandomAccessFile? = null
    private var tapFormat: TapFormat? = null
    private var tapDataBytes: Long = 0L
    private var tapFailed = false

    private data class TapFormat(
        val sampleRate: Int,
        val channels: Int,
        val bytesPerFrame: Int,
        val pcmIsFloat: Boolean,
    )

    /**
     * debug 取证开关：把**实际输出**到 delegate 的 PCM 落成 WAV（44 字节头 + raw PCM）。
     * null 关闭。文件在 configure 时创建，release 时补写 WAV 头长度。
     */
    fun setPcmTap(file: File?) {
        synchronized(tapLock) {
            if (tapFile == file) return
            closeTapLocked()
            tapFile = file
            tapFailed = false
            if (file != null && tapFormat != null) {
                openTapLocked()
            }
        }
    }

    // ------------------------------------------------------------ sink 回调入口

    internal fun onFormatConfigured(
        sampleRate: Int,
        channels: Int,
        bytesPerFrame: Int,
        pcmIsFloat: Boolean,
    ) {
        engine.configure(
            LoopPcmEngine.Format(
                sampleRate = sampleRate,
                channelCount = channels,
                bytesPerFrame = bytesPerFrame,
                pcmIsFloat = pcmIsFloat,
            )
        )
        engine.setWindow(window)
        synchronized(tapLock) {
            val newFormat = TapFormat(
                sampleRate = sampleRate,
                channels = channels,
                bytesPerFrame = bytesPerFrame,
                pcmIsFloat = pcmIsFloat,
            )
            val changed = tapFormat != newFormat
            tapFormat = newFormat
            if (tapFile != null && (changed || tapOut == null)) {
                closeTapLocked()
                tapFailed = false
                openTapLocked()
            }
        }
    }

    internal fun updatePositionUs(mediaUs: Long) {
        lastPositionUs = mediaUs
    }

    internal fun onSinkFlush() {
        engine.onFlush()
    }

    internal fun onSinkReset() {
        engine.onReset()
    }

    internal fun onSinkRelease() {
        engine.onRelease()
        synchronized(tapLock) {
            closeTapLocked()
        }
    }

    /** 把 delegate 实际消费的一段 PCM 写入 tap（offset/length 相对 buffer 绝对位置）。 */
    internal fun tapPcm(buffer: ByteBuffer, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(tapLock) {
            val out = tapOut ?: return
            if (tapFailed) return
            if (offset < 0 || length <= 0) return
            if (offset + length > buffer.limit()) return
            val data = ByteArray(length)
            val dup = buffer.duplicate()
            dup.position(offset)
            dup.limit(offset + length)
            dup.get(data)
            try {
                out.write(data)
                tapDataBytes += length.toLong()
            } catch (e: IOException) {
                tapFailed = true
                closeTapLocked()
            }
        }
    }

    // ------------------------------------------------------------------ WAV 写盘

    private fun openTapLocked() {
        val file = tapFile ?: return
        val fmt = tapFormat ?: return
        if (tapFailed) return
        try {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            raf.setLength(0L)
            // 先写 44 字节占位头（data size 0），release 时回填。
            raf.write(buildWavHeader(fmt, 0L))
            raf.seek(44L)
            tapOut = raf
            tapDataBytes = 0L
        } catch (e: IOException) {
            tapFailed = true
            tapOut = null
        }
    }

    private fun closeTapLocked() {
        val out = tapOut ?: return
        tapOut = null
        val fmt = tapFormat
        try {
            if (fmt != null) {
                out.seek(0L)
                out.write(buildWavHeader(fmt, tapDataBytes))
            }
        } catch (e: IOException) {
            tapFailed = true
        } finally {
            try {
                out.close()
            } catch (e: IOException) {
                // ignore
            }
            tapDataBytes = 0L
        }
    }

    private fun buildWavHeader(fmt: TapFormat, dataBytes: Long): ByteArray {
        val header = ByteArray(44)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = fmt.channels.coerceAtLeast(1)
        val bpf = fmt.bytesPerFrame.coerceAtLeast(1)
        val bitsPerSample = (bpf * 8 / channels).coerceIn(8, 32)
        val dataSize32 = dataBytes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(36 + dataSize32)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort((if (fmt.pcmIsFloat) 3 else 1).toShort())
        bb.putShort(channels.toShort())
        bb.putInt(fmt.sampleRate)
        bb.putInt(fmt.sampleRate * bpf)
        bb.putShort(bpf.toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize32)
        return header
    }
}
