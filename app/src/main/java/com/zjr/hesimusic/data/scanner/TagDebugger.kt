package com.zjr.hesimusic.data.scanner

import android.util.Log
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.nio.charset.Charset
import javax.inject.Inject

class TagDebugger @Inject constructor(
    private val tagLibHelper: TagLibHelper
) {

    data class DebugResult(
        val filePath: String,
        val fields: List<FieldDebugInfo>,
        val error: String? = null
    )

    data class FieldDebugInfo(
        val fieldName: String,
        val originalValue: String,
        val rawBytesHex: String,
        val detectedCharset: String?,
        val decodedCandidates: Map<String, String>
    )

    fun debug(filePath: String): DebugResult {
        Log.d("TagDebugger", "Starting debug for: $filePath")
        val file = File(filePath)
        if (!file.exists()) {
            return DebugResult(filePath, emptyList(), "File not found")
        }

        try {
            val metadata = tagLibHelper.extractMetadata(filePath)
            
            if (metadata == null) {
                return DebugResult(filePath, emptyList(), "No tag found or file unreadable")
            }

            val fieldsToCheck = listOf("TITLE", "ARTIST", "ALBUM")
            val debugInfos = fieldsToCheck.mapNotNull { key ->
                val value = metadata[key]
                if (value.isNullOrEmpty()) null
                else analyzeField(key, value)
            }

            // Log results
            debugInfos.forEach { info ->
                Log.d("TagDebugger", "Field: ${info.fieldName}")
                Log.d("TagDebugger", "  Original: ${info.originalValue}")
                Log.d("TagDebugger", "  Hex: ${info.rawBytesHex}")
                Log.d("TagDebugger", "  Detected: ${info.detectedCharset}")
                info.decodedCandidates.forEach { (charset, decoded) ->
                    Log.d("TagDebugger", "  $charset: $decoded")
                }
            }

            return DebugResult(filePath, debugInfos)

        } catch (e: Exception) {
            Log.e("TagDebugger", "Error debugging file", e)
            return DebugResult(filePath, emptyList(), e.stackTraceToString())
        }
    }

    private fun analyzeField(fieldName: String, originalValue: String): FieldDebugInfo {
        // Attempt to recover raw bytes assuming ISO-8859-1 misinterpretation
        // This is the most common cause of mojibake for legacy MP3s
        val rawBytes = originalValue.toByteArray(Charsets.ISO_8859_1)
        
        val detector = UniversalDetector(null)
        detector.handleData(rawBytes, 0, rawBytes.size)
        detector.dataEnd()
        val detectedCharset = detector.detectedCharset
        detector.reset()

        val candidates = mutableMapOf<String, String>()
        // Common charsets in CJK regions + UTF-8
        val charsets = listOf("GBK", "GB2312", "Big5", "UTF-8", "ISO-8859-1", "EUC-KR", "Shift_JIS", "Windows-1252")
        
        charsets.forEach { charsetName ->
            try {
                if (Charset.isSupported(charsetName)) {
                    candidates[charsetName] = String(rawBytes, Charset.forName(charsetName))
                }
            } catch (e: Exception) {
                candidates[charsetName] = "Error: ${e.message}"
            }
        }

        return FieldDebugInfo(
            fieldName = fieldName,
            originalValue = originalValue,
            rawBytesHex = rawBytes.joinToString(" ") { "%02X".format(it) },
            detectedCharset = detectedCharset,
            decodedCandidates = candidates
        )
    }
}
