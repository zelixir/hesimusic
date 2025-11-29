package com.zjr.hesimusic.data.scanner

import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset
import javax.inject.Inject

data class CueTrack(
    val trackNumber: Int,
    val title: String,
    val performer: String,
    val album: String,
    val index01: String, // mm:ss:ff
    val fileName: String,
    val startMs: Long
)

class CueParser @Inject constructor() {

    fun parse(cueFile: File): List<CueTrack> {
        val encoding = detectEncoding(cueFile)
        val lines = try {
            val rawLines = cueFile.readLines(Charset.forName(encoding))
            // Handle UTF-8 BOM
            if (rawLines.isNotEmpty() && rawLines[0].startsWith("\uFEFF")) {
                val mutableLines = rawLines.toMutableList()
                mutableLines[0] = rawLines[0].substring(1)
                mutableLines
            } else {
                rawLines
            }
        } catch (e: Exception) {
            return emptyList()
        }
        
        val tracks = mutableListOf<CueTrack>()
        var currentFile = ""
        var currentTrackNumber = 0
        var currentTitle = ""
        var currentPerformer = ""
        var currentIndex01 = ""
        
        var globalPerformer = ""
        var globalTitle = "" // Album title

        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("PERFORMER") -> {
                    val p = extractValue(trimmed)
                    if (currentTrackNumber == 0) globalPerformer = p else currentPerformer = p
                }
                trimmed.startsWith("TITLE") -> {
                    val t = extractValue(trimmed)
                    if (currentTrackNumber == 0) globalTitle = t else currentTitle = t
                }
                trimmed.startsWith("FILE") -> {
                    currentFile = extractValue(trimmed)
                }
                trimmed.startsWith("TRACK") -> {
                    // Save previous track if exists
                    if (currentTrackNumber > 0) {
                        tracks.add(CueTrack(
                            currentTrackNumber, 
                            currentTitle, 
                            currentPerformer.ifEmpty { globalPerformer }, 
                            globalTitle,
                            currentIndex01, 
                            currentFile,
                            parseTime(currentIndex01)
                        ))
                    }
                    // Reset track specific vars
                    val parts = trimmed.split(" ")
                    if (parts.size >= 2) {
                        currentTrackNumber = parts[1].toIntOrNull() ?: 0
                    }
                    currentTitle = ""
                    currentPerformer = ""
                    currentIndex01 = ""
                }
                trimmed.startsWith("INDEX 01") -> {
                    val parts = trimmed.split(" ")
                    if (parts.size >= 3) {
                        currentIndex01 = parts[2]
                    }
                }
            }
        }
        // Add last track
        if (currentTrackNumber > 0) {
             tracks.add(CueTrack(
                 currentTrackNumber, 
                 currentTitle, 
                 currentPerformer.ifEmpty { globalPerformer }, 
                 globalTitle,
                 currentIndex01, 
                 currentFile,
                 parseTime(currentIndex01)
             ))
        }
        
        return tracks
    }

    private fun extractValue(line: String): String {
        val firstQuote = line.indexOf('"')
        val lastQuote = line.lastIndexOf('"')
        if (firstQuote != -1 && lastQuote != -1 && lastQuote > firstQuote) {
            return line.substring(firstQuote + 1, lastQuote)
        }
        // Fallback if no quotes
        val parts = line.split(" ", limit = 2)
        return if (parts.size > 1) parts[1] else ""
    }

    private fun detectEncoding(file: File): String {
        val buf = ByteArray(4096)
        val fis = FileInputStream(file)
        val detector = UniversalDetector(null)
        
        var nread: Int
        while (fis.read(buf).also { nread = it } > 0 && !detector.isDone) {
            detector.handleData(buf, 0, nread)
        }
        detector.dataEnd()
        val encoding = detector.detectedCharset
        detector.reset()
        fis.close()
        
        return encoding ?: "UTF-8"
    }

    private fun parseTime(timeStr: String): Long {
        val parts = timeStr.split(":")
        if (parts.size != 3) return 0
        val min = parts[0].toLongOrNull() ?: 0
        val sec = parts[1].toLongOrNull() ?: 0
        val frames = parts[2].toLongOrNull() ?: 0
        return (min * 60 * 1000) + (sec * 1000) + ((frames * 1000) / 75)
    }
}
