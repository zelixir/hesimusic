package com.zjr.spike

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 顺序写入 PCM 的 WAV 容器：数据区从 44 字节开始，结束时回填 RIFF/fmt/data 头。
 * 线程安全（playback 线程 + sink 模式的重放线程都会写）。
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
    private val audioFormat: Int, // 1 = PCM int, 3 = IEEE float
) {
    private val raf = RandomAccessFile(file, "rw")
    private val lock = Any()
    private var dataBytes = 0L

    @Volatile
    private var closed = false

    init {
        synchronized(lock) {
            raf.setLength(0)
            raf.write(ByteArray(HEADER_BYTES)) // 占位，最后回填
            raf.seek(HEADER_BYTES.toLong())
        }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        synchronized(lock) {
            if (closed) return
            raf.write(data, offset, length)
            dataBytes += length
        }
    }

    fun bytesWritten(): Long = synchronized(lock) { dataBytes }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val blockAlign = channels * bitsPerSample / 8
            val byteRate = sampleRate * blockAlign
            val dataLen = dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataLen)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM fmt chunk size
            header.putShort(audioFormat.toShort())
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataLen)
            raf.seek(0)
            raf.write(header.array())
            raf.close()
        }
    }

    companion object {
        private const val HEADER_BYTES = 44
    }
}
