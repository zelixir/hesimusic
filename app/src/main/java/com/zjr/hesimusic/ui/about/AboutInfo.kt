package com.zjr.hesimusic.ui.about

import java.util.Locale

/**
 * 关于页「当前版本」信息区的纯数据与文案映射。
 *
 * 这里**不 import 任何 Compose / Android 类型**:格式化逻辑全部可在纯 JVM 单测里验证
 * (见 `app/src/test/.../ui/about/AboutInfoFormatTest.kt`)。数据来源:
 * - 版本/渠道/构建时间:`BuildConfig.VERSION_NAME` / `VERSION_CODE` / `FLAVOR` / `BUILD_TIME`;
 * - 引擎/循环路径/回退原因:运行时快照 [LoopEngineSnapshot](由
 *   `com.zjr.hesimusic.loop.audio.source.LoopEngineInfo` 提供,见 `AboutEngineInfo.kt`)。
 */
data class AboutInfo(
    val versionName: String,
    val versionCode: Long,
    val flavor: String,
    val buildTime: String,
    val engine: String = "",
    val loopPath: String = "",
    val bandBytes: Long = 0L,
    val fallbackReason: String? = null,
    /** `LoopEngineInfo.display(context)` 的原始单行(`ENGINE=... BAND_BYTES=...`),截图/反馈用。 */
    val engineRaw: String = "",
)

/** 信息区的一行;[label] 不含冒号,[value] 是已经本地化好的展示文案。 */
data class AboutInfoRow(val label: String, val value: String)

/**
 * 关于页信息区的文案格式化(纯函数,不依赖 Compose)。
 *
 * 口径:
 * - 版本:`versionName (versionCode)`,versionName 为空时用「未知」兜底;
 * - 渠道:`prod` / `demo`,原样小写;
 * - 引擎:`source`(环带)→「环带 (SOURCE)」,`m3`(M3′ 老引擎)→「老引擎 (M3′)」,
 *   `none`/普通 ExoPlayer→「无循环 (普通播放)」;
 * - 循环路径:运行时真实值 `source` / `m3` / `none`;
 * - 回退原因:只在真的回退时出现(`null`/空/`none` 一律不显示该行)。
 */
object AboutInfoText {

    const val LABEL_VERSION = "版本"
    const val LABEL_CHANNEL = "构建渠道"
    const val LABEL_BUILD_TIME = "构建时间"
    const val LABEL_ENGINE = "循环引擎"
    const val LABEL_LOOP_PATH = "循环路径"
    const val LABEL_BAND_BYTES = "环带大小"
    const val LABEL_FALLBACK = "回退原因"
    const val LABEL_ENGINE_RAW = "引擎原始值"

    /** 无法判定时的统一兜底文案。 */
    const val UNKNOWN = "未知"

    /**
     * 「标签:」列的显示宽度(按终端/等宽列的显示宽度算,CJK 记 2 列)。
     * 复制文本用它对齐全段信息;「构建渠道:」（9 列）后补 1 空格即到 10 列。
     */
    const val LABEL_COLUMNS = 10

    /** `2.3.0 (1234)`;版本名缺失/空白时退化为 `未知 (1234)`。 */
    fun formatVersionLine(versionName: String?, versionCode: Long): String {
        val name = versionName?.trim().orEmpty().ifEmpty { UNKNOWN }
        return name + " (" + versionCode + ")"
    }

    /** 构建渠道:`prod` / `demo`(去空白 + 小写;缺失时「未知」)。 */
    fun formatChannel(flavor: String?): String {
        val normalized = flavor?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized.ifEmpty { UNKNOWN }
    }

    /**
     * 循环引擎的用户可读文案。
     *
     * 兼容 [com.zjr.hesimusic.loop.audio.source.LoopSourceMode] 的两种枚举名
     * (`SOURCE` / `M3_PRIME`),也兼容 `LoopEngineInfo.engine` 直接给已本地化文案
     * (如「环带」)的情况:未命中任何已知值时原样展示,不吞信息。
     */
    fun formatEngine(engine: String?, loopPath: String?): String {
        val normalizedEngine = engine?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val normalizedPath = loopPath?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            isSourceEngine(normalizedEngine) || isSourceEngine(normalizedPath) -> ENGINE_SOURCE
            isM3Engine(normalizedEngine) || isM3Engine(normalizedPath) -> ENGINE_M3
            isNoLoopEngine(normalizedEngine) || isNoLoopEngine(normalizedPath) -> ENGINE_NONE
            !engine.isNullOrBlank() -> engine.trim()
            normalizedPath.isNotEmpty() -> normalizedPath
            else -> UNKNOWN
        }
    }

    /** 循环路径的原始运行时值:`source` / `m3` / `none`(规范化小写,缺失时「未知」)。 */
    fun formatLoopPath(loopPath: String?): String {
        val normalized = loopPath?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (normalized) {
            "" -> UNKNOWN
            "source", "m3", "none" -> normalized
            "m3_prime", "m3′", "m3prime" -> "m3"
            else -> normalized
        }
    }

    /** 回退原因;没有真正回退时返回 null(该行不显示)。 */
    fun formatFallbackReason(reason: String?): String? {
        val trimmed = reason?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val lowered = trimmed.lowercase(Locale.ROOT)
        if (lowered == "none" || lowered == "null" || lowered == "n/a" || lowered == "-") return null
        if (trimmed == "—" || trimmed == UNKNOWN) return null
        return trimmed
    }

    /** 环带 PCM 字节数 → 可读体积;`<= 0` 返回 null(该行不显示)。 */
    fun formatBandBytes(bytes: Long): String? {
        if (bytes <= 0L) return null
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        if (mib >= 1.0) return String.format(Locale.ROOT, "%.2f MB", mib)
        val kib = bytes / 1024.0
        if (kib >= 1.0) return String.format(Locale.ROOT, "%.1f KB", kib)
        return "$bytes B"
    }

    /** 信息区要展示的全部行(顺序即页面/复制文本顺序)。 */
    fun rows(info: AboutInfo): List<AboutInfoRow> {
        val base = listOf(
            AboutInfoRow(LABEL_VERSION, formatVersionLine(info.versionName, info.versionCode)),
            AboutInfoRow(LABEL_CHANNEL, formatChannel(info.flavor)),
            AboutInfoRow(LABEL_BUILD_TIME, info.buildTime.trim().ifEmpty { UNKNOWN }),
            AboutInfoRow(LABEL_ENGINE, formatEngine(info.engine, info.loopPath)),
            AboutInfoRow(LABEL_LOOP_PATH, formatLoopPath(info.loopPath)),
        )
        val extra = buildList {
            formatBandBytes(info.bandBytes)?.let { add(AboutInfoRow(LABEL_BAND_BYTES, it)) }
            formatFallbackReason(info.fallbackReason)?.let { add(AboutInfoRow(LABEL_FALLBACK, it)) }
            info.engineRaw.trim().takeIf { it.isNotEmpty() }
                ?.let { add(AboutInfoRow(LABEL_ENGINE_RAW, it)) }
        }
        return base + extra
    }

    /** 长按复制用的整段文本:标签按 [LABEL_COLUMNS] 列对齐,一行一项。 */
    fun formatForClipboard(info: AboutInfo): String = rows(info).joinToString("\n") { row ->
        padDisplayWidth(row.label + ":", LABEL_COLUMNS) + " " + row.value
    }

    // ---------------------------------------------------------------- 内部口径

    private const val ENGINE_SOURCE = "环带 (SOURCE)"
    private const val ENGINE_M3 = "老引擎 (M3′)"
    private const val ENGINE_NONE = "无循环 (普通播放)"

    private fun isSourceEngine(value: String): Boolean = value in setOf(
        "source", "source_ring", "ring", "ring_band", "band", "环带", "环带 (source)", "source_pcm",
    )

    private fun isM3Engine(value: String): Boolean = value in setOf(
        "m3", "m3_prime", "m3prime", "m3′", "m3prime_legacy", "legacy", "老引擎",
    )

    private fun isNoLoopEngine(value: String): Boolean = value in setOf(
        "none", "off", "exo", "exo_player", "no_loop", "offline",
    )

    /** 显示宽度:CJK 全角字符按 2 列计,其余按 1 列计。 */
    fun displayWidth(text: String): Int = text.fold(0) { acc, ch -> acc + if (isWide(ch)) 2 else 1 }

    /** 右侧补空格到指定显示宽度(已超出则原样返回)。 */
    fun padDisplayWidth(text: String, columns: Int): String {
        val pad = columns - displayWidth(text)
        return if (pad > 0) text + " ".repeat(pad) else text
    }

    private fun isWide(ch: Char): Boolean = when (ch.code) {
        in 0x1100..0x115F, // Hangul Jamo
        in 0x2E80..0x303E, // CJK Radicals / Kangxi / CJK Symbols
        in 0x3041..0x33FF, // Kana / CJK Compatibility
        in 0x3400..0x4DBF, // CJK Ext A
        in 0x4E00..0x9FFF, // CJK Unified
        in 0xA000..0xA4CF, // Yi
        in 0xAC00..0xD7A3, // Hangul Syllables
        in 0xF900..0xFAFF, // CJK Compatibility Ideographs
        in 0xFE30..0xFE4F, // CJK Compatibility Forms
        in 0xFF00..0xFF60, // Fullwidth Forms
        in 0xFFE0..0xFFE6, // Fullwidth Signs
        -> true
        else -> false
    }
}
