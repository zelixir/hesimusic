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

    private val cache = android.util.LruCache<Char, Char>(1000)

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
        cache.get(c)?.let { return it }

        val initial = computeInitial(c)
        cache.put(c, initial)
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

    fun getInitial(text: String?): Char {
        if (text.isNullOrEmpty()) return '#'
        return getInitial(text[0])
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
