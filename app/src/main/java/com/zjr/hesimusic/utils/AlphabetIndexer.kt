package com.zjr.hesimusic.utils

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination

object AlphabetIndexer {

    // Configure pinyin4j output format: Uppercase, No Tone
    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.UPPERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
    }

    private const val CACHE_SIZE = 1000
    private val cacheLock = Any()
    private val cache = linkedMapOf<Char, Char>()
    
    // Pattern to match track numbers at the start of a string: \d+\.\s*
    private val trackNumberPattern = Regex("""^\d+\.\s*""")

    private fun isChinese(c: Char): Boolean {
        return (c.code in 0x4E00..0x9FA5) || c.code == 0x3007
    }

    private fun toPinyin(c: Char): String {
        return try {
            val pinyins = PinyinHelper.toHanyuPinyinStringArray(c, pinyinFormat)
            if (!pinyins.isNullOrEmpty()) {
                pinyins[0]
            } else {
                ""
            }
        } catch (e: BadHanyuPinyinOutputFormatCombination) {
            ""
        }
    }

    fun getInitial(c: Char): Char {
        synchronized(cacheLock) {
            cache[c]?.let { return it }
        }
        val initial = computeInitial(c)
        synchronized(cacheLock) {
            if (cache.size >= CACHE_SIZE) {
                cache.entries.firstOrNull()?.key?.let(cache::remove)
            }
            cache[c] = initial
        }
        return initial
    }

    private fun computeInitial(c: Char): Char {
        // 1. English / Latin
        if (c in 'a'..'z') return c.uppercaseChar()
        if (c in 'A'..'Z') return c

        // 2. Japanese Kana (Hiragana & Katakana)
        val kanaInitial = getKanaInitial(c)
        if (kanaInitial != null) return kanaInitial

        // 3. Chinese / Kanji (Hanzi)
        if (isChinese(c)) {
            val pinyin = toPinyin(c)
            if (pinyin.isNotEmpty()) {
                return pinyin[0]
            }
        }

        // 4. Fallback
        return '#'
    }

    /**
     * Strip track number prefix from text if present.
     * Track number pattern: \d+\.\s* (e.g., "01. ", "2. ", "123. ", "01.Song")
     */
    fun stripTrackNumber(text: String): String {
        return trackNumberPattern.replace(text, "")
    }

    private const val SORT_KEY_CACHE_SIZE = 4096
    private val sortKeyCacheLock = Any()
    private val sortKeyCache = linkedMapOf<Char, String>()

    /**
     * 歌曲二级排序键：去掉曲目号前缀后逐字符映射——汉字转全拼（取第一个读音），
     * 日文假名取罗马音（Hepburn，如 さ→sa、し→shi），其余字符原样小写。
     * 供同首字母分组内的拼音/单词排序使用。
     */
    fun sortKey(text: String): String {
        val cleaned = stripTrackNumber(text).trim()
        if (cleaned.isEmpty()) return ""
        val sb = StringBuilder(cleaned.length)
        for (c in cleaned) {
            sb.append(charSortKey(c))
        }
        return sb.toString()
    }

    private fun charSortKey(c: Char): String {
        synchronized(sortKeyCacheLock) {
            sortKeyCache[c]?.let { return it }
        }
        val key = computeCharSortKey(c)
        synchronized(sortKeyCacheLock) {
            if (sortKeyCache.size >= SORT_KEY_CACHE_SIZE) {
                sortKeyCache.entries.firstOrNull()?.key?.let(sortKeyCache::remove)
            }
            sortKeyCache[c] = key
        }
        return key
    }

    private fun computeCharSortKey(c: Char): String {
        if (c in 'a'..'z') return c.toString()
        if (c in 'A'..'Z') return c.lowercaseChar().toString()
        if (isChinese(c)) return toPinyin(c).lowercase().ifEmpty { c.lowercaseChar().toString() }
        val kanaRomaji = getKanaRomaji(c)
        if (kanaRomaji != null) return kanaRomaji
        val kanaInitial = getKanaInitial(c)
        if (kanaInitial != null) return kanaInitial.lowercaseChar().toString()
        return c.lowercaseChar().toString()
    }

    /**
     * Hepburn romaji for kana (hiragana & katakana), used in secondary sort keys:
     * さ→sa, し→shi, つ→tsu, ん→n, ヴ→vu …. Small kana (ゃ・っ・ぁ…) map to their
     * full syllable so keys stay in gojūon order (e.g. しゃ→shiya). Returns null
     * for non-kana characters.
     */
    private fun getKanaRomaji(c: Char): String? {
        return when (c) {
            // A row (incl. small ぁぃぅぇぉ)
            '\u3041', '\u3042', '\u30a1', '\u30a2' -> "a"
            '\u3043', '\u3044', '\u30a3', '\u30a4' -> "i"
            '\u3045', '\u3046', '\u30a5', '\u30a6' -> "u"
            '\u3047', '\u3048', '\u30a7', '\u30a8' -> "e"
            '\u3049', '\u304a', '\u30a9', '\u30aa' -> "o"

            // K row (incl. small ヵヶ)
            '\u304b', '\u30ab', '\u30f5' -> "ka"
            '\u304d', '\u30ad' -> "ki"
            '\u304f', '\u30af' -> "ku"
            '\u3051', '\u30b1', '\u30f6' -> "ke"
            '\u3053', '\u30b3' -> "ko"

            // S row (し = shi)
            '\u3055', '\u30b5' -> "sa"
            '\u3057', '\u30b7' -> "shi"
            '\u3059', '\u30b9' -> "su"
            '\u305b', '\u30bb' -> "se"
            '\u305d', '\u30bd' -> "so"

            // T row (ち = chi, つ/っ = tsu)
            '\u305f', '\u30bf' -> "ta"
            '\u3061', '\u30c1' -> "chi"
            '\u3063', '\u30c3', '\u3064', '\u30c4' -> "tsu"
            '\u3066', '\u30c6' -> "te"
            '\u3068', '\u30c8' -> "to"

            // N row
            '\u306a', '\u30ca' -> "na"
            '\u306b', '\u30cb' -> "ni"
            '\u306c', '\u30cc' -> "nu"
            '\u306d', '\u30cd' -> "ne"
            '\u306e', '\u30ce' -> "no"

            // H row (ふ = fu)
            '\u306f', '\u30cf' -> "ha"
            '\u3072', '\u30d2' -> "hi"
            '\u3075', '\u30d5' -> "fu"
            '\u3078', '\u30d8' -> "he"
            '\u307b', '\u30db' -> "ho"

            // M row
            '\u307e', '\u30de' -> "ma"
            '\u307f', '\u30df' -> "mi"
            '\u3080', '\u30e0' -> "mu"
            '\u3081', '\u30e1' -> "me"
            '\u3082', '\u30e2' -> "mo"

            // Y row (incl. small ゃゅょ)
            '\u3083', '\u3084', '\u30e3', '\u30e4' -> "ya"
            '\u3085', '\u3086', '\u30e5', '\u30e6' -> "yu"
            '\u3087', '\u3088', '\u30e7', '\u30e8' -> "yo"

            // R row
            '\u3089', '\u30e9' -> "ra"
            '\u308a', '\u30ea' -> "ri"
            '\u308b', '\u30eb' -> "ru"
            '\u308c', '\u30ec' -> "re"
            '\u308d', '\u30ed' -> "ro"

            // W row (incl. small ゎヮ, archaic ゐゑヰヱ)
            '\u308e', '\u308f', '\u30ee', '\u30ef' -> "wa"
            '\u3090', '\u30f0' -> "wi"
            '\u3091', '\u30f1' -> "we"
            '\u3092', '\u30f2' -> "wo"
            '\u3093', '\u30f3' -> "n"

            // G row
            '\u304c', '\u30ac' -> "ga"
            '\u304e', '\u30ae' -> "gi"
            '\u3050', '\u30b0' -> "gu"
            '\u3052', '\u30b2' -> "ge"
            '\u3054', '\u30b4' -> "go"

            // Z row (じ/ぢ = ji, ず/づ = zu)
            '\u3056', '\u30b6' -> "za"
            '\u3058', '\u30b8', '\u3062', '\u30c2' -> "ji"
            '\u305a', '\u30ba', '\u3065', '\u30c5' -> "zu"
            '\u305c', '\u30bc' -> "ze"
            '\u305e', '\u30be' -> "zo"

            // D row
            '\u3060', '\u30c0' -> "da"
            '\u3067', '\u30c7' -> "de"
            '\u3069', '\u30c9' -> "do"

            // B row
            '\u3070', '\u30d0' -> "ba"
            '\u3073', '\u30d3' -> "bi"
            '\u3076', '\u30d6' -> "bu"
            '\u3079', '\u30d9' -> "be"
            '\u307c', '\u30dc' -> "bo"

            // P row
            '\u3071', '\u30d1' -> "pa"
            '\u3074', '\u30d4' -> "pi"
            '\u3077', '\u30d7' -> "pu"
            '\u307a', '\u30da' -> "pe"
            '\u307d', '\u30dd' -> "po"

            // V (katakana only)
            '\u30f4' -> "vu"
            '\u30f7' -> "va"
            '\u30f8' -> "vi"
            '\u30f9' -> "ve"
            '\u30fa' -> "vo"

            else -> null
        }
    }

    fun getInitial(text: String?): Char {
        if (text.isNullOrEmpty()) return '#'
        val hasTrackNumberPrefix = trackNumberPattern.containsMatchIn(text)
        val cleanedText = stripTrackNumber(text)
        if (cleanedText.isEmpty()) return '#'
        val leadingChar = if (hasTrackNumberPrefix) {
            // Skip only continuation numbering after the track number; a symbol-led
            // title (e.g. "01. @Special") still groups under '#'.
            cleanedText.firstOrNull { !it.isDigit() && !it.isWhitespace() }
        } else {
            cleanedText.firstOrNull()
        } ?: return '#'
        return getInitial(leadingChar)
    }

    private fun getKanaInitial(c: Char): Char? {
        // Hiragana: 3040-309F
        // Katakana: 30A0-30FF
        val code = c.code
        if (code !in 0x3040..0x30FF) return null

        return when (c) {
            // A row: あ-お, ア-オ
            in '\u3041'..'\u304a', in '\u30a1'..'\u30aa' -> 'A'
            
            // Ka row: か, き, く, け, こ (and Katakana)
            '\u304b', '\u304d', '\u304f', '\u3051', '\u3053',
            '\u30ab', '\u30ad', '\u30af', '\u30b1', '\u30b3',
            '\u3095', '\u30f5' -> 'K'
            
            // Ga row: が, ぎ, ぐ, げ, ご (and Katakana)
            '\u304c', '\u304e', '\u3050', '\u3052', '\u3054',
            '\u30ac', '\u30ae', '\u30b0', '\u30b2', '\u30b4' -> 'G'
            
            // Sa row: さ, し, す, せ, そ (and Katakana)
            '\u3055', '\u3057', '\u3059', '\u305b', '\u305d',
            '\u30b5', '\u30b7', '\u30b9', '\u30bb', '\u30bd' -> 'S'
            
            // Za row: ざ, ず, ぜ, ぞ (and Katakana) - Ji moved to J
            '\u3056', '\u305a', '\u305c', '\u305e',
            '\u30b6', '\u30ba', '\u30bc', '\u30be',
            '\u3065', '\u30c5' -> 'Z' // Includes Zu (Du)
            
            // J: じ, ジ, ぢ, ヂ
            '\u3058', '\u30b8', '\u3062', '\u30c2' -> 'J'
            
            // Ta row: た, つ, て, と (and Katakana) - Chi moved to C
            '\u305f', '\u3063', '\u3064', '\u3066', '\u3068',
            '\u30bf', '\u30c3', '\u30c4', '\u30c6', '\u30c8' -> 'T'
            
            // C: ち, チ
            '\u3061', '\u30c1' -> 'C'
            
            // Da row: だ, で, ど (and Katakana) - Ji moved to J, Zu moved to Z
            '\u3060', '\u3067', '\u3069',
            '\u30c0', '\u30c7', '\u30c9' -> 'D'
            
            // Na row: な-の, ナ-ノ
            in '\u306a'..'\u306e', in '\u30ca'..'\u30ce' -> 'N'
            
            // Ha row: は, ひ, へ, ほ (and Katakana) - Fu moved to F
            '\u306f', '\u3072', '\u3078', '\u307b',
            '\u30cf', '\u30d2', '\u30d8', '\u30db' -> 'H'
            
            // F: ふ, フ
            '\u3075', '\u30d5' -> 'F'
            
            // Ba row: ば, び, ぶ, べ, ぼ (and Katakana)
            '\u3070', '\u3073', '\u3076', '\u3079', '\u307c',
            '\u30d0', '\u30d3', '\u30d6', '\u30d9', '\u30dc' -> 'B'
            
            // Pa row: ぱ, ぴ, ぷ, ぺ, ぽ (and Katakana)
            '\u3071', '\u3074', '\u3077', '\u307a', '\u307d',
            '\u30d1', '\u30d4', '\u30d7', '\u30d9', '\u30dd' -> 'P'
            
            // Ma row: ま-も, マ-モ
            in '\u307e'..'\u3082', in '\u30de'..'\u30e2' -> 'M'
            
            // Ya row: や-よ, ヤ-ヨ
            in '\u3083'..'\u3088', in '\u30e3'..'\u30e8' -> 'Y'
            
            // Ra row: ら-ろ, ラ-ロ
            in '\u3089'..'\u308d', in '\u30e9'..'\u30ed' -> 'R'
            
            // Wa row: わ-ん, ワ-ン
            in '\u308e'..'\u3093', in '\u30ee'..'\u30f3' -> 'W'
            
            // Vu (V)
            '\u30f4' -> 'V'
            
            else -> null
        }
    }
}
