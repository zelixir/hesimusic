package com.zjr.hesimusic.loop.analysis

/**
 * [LoopCandidateKt] 列表 ↔ JSON 的手写编解码(issue 00016:候选列表落库)。
 *
 * 为什么手写:项目无 kotlinx.serialization / org.json 在纯 JVM 单测里可用
 * (android.jar 是 mockable,任何 org.json 调用都会 "not mocked"),因此用最小
 * 手写实现保证**纯 JVM 可往返测试**,不引入新依赖。
 *
 * 格式(稳定、与字段一一对应,顺序即引擎给出的 score 降序):
 * ```json
 * [{"startSamples":123,"endSamples":456,"score":0.9,"noteDistance":0.1,"loudnessDifference":0.2}]
 * ```
 *
 * 契约:
 * - [encode]:非有限 double(NaN/±Inf,理论上不会出现)编码为 `0.0`,保证输出永远是合法 JSON;
 * - [decode]:null/空白/结构损坏/根不是数组 → **null**(调用方退回老数据单候选行为);
 *   合法数组(含 `[]`)→ 列表,顺序保持;未知字段、字段顺序、空白、数字写成字符串均容忍;
 * - 逐条校验窗口(0 <= startSamples < endSamples):非法条目丢弃而不是污染 A/B。
 */
object LoopCandidatesJson {

    private const val FIELD_START = "startSamples"
    private const val FIELD_END = "endSamples"
    private const val FIELD_SCORE = "score"
    private const val FIELD_NOTE = "noteDistance"
    private const val FIELD_LOUDNESS = "loudnessDifference"

    /** 候选列表 → JSON 字符串;空列表得到 `[]`。 */
    fun encode(candidates: List<LoopCandidateKt>): String {
        val sb = StringBuilder(candidates.size * 96 + 2)
        sb.append('[')
        candidates.forEachIndexed { index, candidate ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append('"').append(FIELD_START).append("\":").append(candidate.startSamples)
            sb.append(',').append('"').append(FIELD_END).append("\":").append(candidate.endSamples)
            sb.append(',').append('"').append(FIELD_SCORE).append("\":").append(formatDouble(candidate.score))
            sb.append(',').append('"').append(FIELD_NOTE).append("\":").append(formatDouble(candidate.noteDistance))
            sb.append(',').append('"').append(FIELD_LOUDNESS).append("\":")
                .append(formatDouble(candidate.loudnessDifference))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * JSON 字符串 → 候选列表。
     *
     * @return null = 没有可用数据(null/空白/损坏),调用方应退回单候选;
     *         非 null(可能为空列表)= 数据可解析。
     */
    fun decode(raw: String?): List<LoopCandidateKt>? {
        if (raw.isNullOrBlank()) return null
        return try {
            JsonReader(raw).parseArrayOfCandidates()
        } catch (e: Exception) {
            null
        }
    }

    private fun formatDouble(value: Double): String =
        if (value.isFinite()) value.toString() else "0.0"

    /** 极简 JSON 读取器:只服务本文件的固定 schema,但容忍未知字段/空白/字符串数字。 */
    private class JsonReader(private val text: String) {

        private var pos = 0

        fun parseArrayOfCandidates(): List<LoopCandidateKt> {
            skipWs()
            expect('[')
            skipWs()
            val result = ArrayList<LoopCandidateKt>()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                skipWs()
                parseCandidate()?.let { result += it }
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWs()
                    }

                    ']' -> {
                        pos++
                        return result
                    }

                    else -> fail()
                }
            }
        }

        /** 单个候选;窗口非法(负数/反向/零长度)返回 null(调用方丢弃该条)。 */
        private fun parseCandidate(): LoopCandidateKt? {
            skipWs()
            expect('{')
            var start = 0L
            var end = 0L
            var score = 0.0
            var noteDistance = 0.0
            var loudnessDifference = 0.0
            skipWs()
            if (peek() == '}') {
                pos++
                return null
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                expect(':')
                skipWs()
                when (key) {
                    FIELD_START -> start = readLong()
                    FIELD_END -> end = readLong()
                    FIELD_SCORE -> score = readDouble()
                    FIELD_NOTE -> noteDistance = readDouble()
                    FIELD_LOUDNESS -> loudnessDifference = readDouble()
                    else -> skipValue()
                }
                skipWs()
                when (peek()) {
                    ',' -> {
                        pos++
                        skipWs()
                    }

                    '}' -> {
                        pos++
                        if (start < 0L || end <= start) return null
                        return LoopCandidateKt(
                            startSamples = start,
                            endSamples = end,
                            score = score,
                            noteDistance = noteDistance,
                            loudnessDifference = loudnessDifference,
                        )
                    }

                    else -> fail()
                }
            }
        }

        private fun readLong(): Long {
            skipWs()
            if (peek() == '"') {
                val raw = readString()
                return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: fail()
            }
            if (startsWithLiteral("null")) return 0L
            val raw = readNumberToken()
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: fail()
        }

        private fun readDouble(): Double {
            skipWs()
            if (peek() == '"') {
                return readString().toDoubleOrNull() ?: fail()
            }
            if (startsWithLiteral("null")) return 0.0
            return readNumberToken().toDoubleOrNull() ?: fail()
        }

        private fun readNumberToken(): String {
            val start = pos
            while (pos < text.length && text[pos] in NUMBER_CHARS) pos++
            if (pos == start) fail()
            return text.substring(start, pos)
        }

        private fun startsWithLiteral(literal: String): Boolean {
            if (!text.startsWith(literal, pos)) return false
            pos += literal.length
            return true
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < text.length) {
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail()
                        when (val escaped = text[pos++]) {
                            '"', '\\', '/' -> sb.append(escaped)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail()
                                val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: fail()
                                pos += 4
                                sb.append(code.toChar())
                            }

                            else -> fail()
                        }
                    }

                    else -> sb.append(c)
                }
            }
            fail()
        }

        private fun skipValue() {
            skipWs()
            when (val c = peek()) {
                '"' -> readString()
                '{' -> {
                    pos++
                    skipWs()
                    if (peek() == '}') {
                        pos++
                        return
                    }
                    while (true) {
                        skipWs()
                        readString()
                        skipWs()
                        expect(':')
                        skipValue()
                        skipWs()
                        when (peek()) {
                            ',' -> {
                                pos++
                                skipWs()
                            }

                            '}' -> {
                                pos++
                                return
                            }

                            else -> fail()
                        }
                    }
                }

                '[' -> {
                    pos++
                    skipWs()
                    if (peek() == ']') {
                        pos++
                        return
                    }
                    while (true) {
                        skipValue()
                        skipWs()
                        when (peek()) {
                            ',' -> {
                                pos++
                                skipWs()
                            }

                            ']' -> {
                                pos++
                                return
                            }

                            else -> fail()
                        }
                    }
                }

                't' -> if (startsWithLiteral("true")) return else fail()
                'f' -> if (startsWithLiteral("false")) return else fail()
                'n' -> if (startsWithLiteral("null")) return else fail()
                else -> {
                    if (c == null || c !in NUMBER_CHARS) fail()
                    readNumberToken()
                }
            }
        }

        private fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = if (pos < text.length) text[pos] else null

        private fun expect(c: Char) {
            if (peek() != c) fail()
            pos++
        }

        private fun fail(): Nothing = throw IllegalArgumentException("invalid candidates json at " + pos)

        private companion object {
            const val NUMBER_CHARS = "-+.eE0123456789"
        }
    }

}
