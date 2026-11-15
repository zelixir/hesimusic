package com.zjr.spike

/**
 * 一次探针运行的全部参数（来自 intent extras，字段语义见 spike/CONTRACT.md §2）。
 */
data class SpikeConfig(
    val mode: String,
    val path: String,
    val aMs: Int,
    val bMs: Int,
    val seconds: Int,
    val loops: Int,
    val tap: Boolean,
    val seekToMs: Int,
    val seekDelayMs: Int,
    val tag: String,
) {
    val aUs: Long get() = aMs * 1000L
    val bUs: Long get() = bMs * 1000L

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "mode" to mode,
        "path" to path,
        "aMs" to aMs,
        "bMs" to bMs,
        "seconds" to seconds,
        "loops" to loops,
        "tap" to tap,
        "seekToMs" to seekToMs,
        "seekDelayMs" to seekDelayMs,
        "tag" to tag,
    )
}
