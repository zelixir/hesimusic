package com.zjr.spike

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * 取证落盘：latest.jsonl（事件流，单锁顺序写）+ latest.wav（PCM，详见 CONTRACT §3）。
 *
 * 事件时间 t = 相对“播放开始”（player.play() 时刻）的毫秒；play 之前的采样统一记 0。
 * 额外带 seq 单调序号，便于分析侧在 t 相同（或事件早于 play）时判定真实顺序。
 */
class SpikeRecorder(dir: File) {

    val jsonlFile = File(dir, "latest.jsonl")
    val wavFile = File(dir, "latest.wav")

    private val logLock = Any()
    private val writer: OutputStreamWriter
    private var seq = 0L

    @Volatile
    private var playStartElapsedMs = -1L

    @Volatile
    var stopped = false
        private set

    @Volatile
    var totalFrames = 0L
        private set

    @Volatile
    var wavWrites = 0L
        private set

    private var wav: WavWriter? = null
    private val wavLock = Any()

    init {
        dir.mkdirs()
        jsonlFile.delete()
        wavFile.delete()
        writer = OutputStreamWriter(FileOutputStream(jsonlFile, false), Charsets.UTF_8)
    }

    fun markPlayStart() {
        playStartElapsedMs = SystemClock.elapsedRealtime()
        event("play")
    }

    fun markStop() {
        stopped = true
    }

    fun nowMs(): Long {
        val start = playStartElapsedMs
        if (start < 0) return 0L
        return (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
    }

    fun event(ev: String, fields: Map<String, Any?> = emptyMap()) {
        val obj = JSONObject()
        synchronized(logLock) {
            obj.put("t", nowMs())
            obj.put("seq", ++seq)
            obj.put("ev", ev)
            for ((k, v) in fields) {
                when (v) {
                    null -> obj.put(k, JSONObject.NULL)
                    is Long, is Int, is Boolean, is Double, is Float -> obj.put(k, v)
                    else -> obj.put(k, v.toString())
                }
            }
            try {
                writer.write(obj.toString())
                writer.write("\n")
                writer.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "event write failed: " + t.stackTraceToString())
            }
        }
    }

    fun createWav(sampleRate: Int, channels: Int, bitsPerSample: Int, audioFormat: Int) {
        if (!SpikeRun.tapEnabled) return
        synchronized(wavLock) {
            if (wav != null) return
            wav = WavWriter(wavFile, sampleRate, channels, bitsPerSample, audioFormat)
        }
    }

    fun writePcm(data: ByteArray, bytesPerFrame: Int) {
        synchronized(wavLock) {
            wav?.write(data, 0, data.size)
        }
        wavWrites += data.size
        if (bytesPerFrame > 0) totalFrames += (data.size / bytesPerFrame).toLong()
    }

    fun finishWav() {
        synchronized(wavLock) {
            wav?.close()
            wav = null
        }
    }

    fun closeIfNeeded() {
        synchronized(logLock) {
            try {
                writer.flush()
                writer.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val TAG = "Spike"
    }
}
