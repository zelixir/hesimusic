package zelixir.hesimusic.scan

import org.mozilla.universalchardet.UniversalDetector
import java.io.File

data class CueTrackEntry(
    val trackNumber: Int,
    val title: String?,
    val performer: String?,
    val indexes: Map<Int, Long>, // index number -> ms
    val fileReference: String?
)

data class CueParseResult(val tracks: List<CueTrackEntry>, val errors: List<String>)

object CueParser {
    fun parse(cueFile: File): CueParseResult {
        if (!cueFile.exists()) return CueParseResult(emptyList(), listOf("file not found"))

        val raw = cueFile.readBytes()
        val charsetName = detectCharset(raw)
        val text = try { String(raw, charset(charsetName)) } catch (_: Throwable) { String(raw, Charsets.UTF_8) }

        val lines = text.lines()
        val errors = mutableListOf<String>()

        var currentFile: String? = null
        var currentPerformer: String? = null
        var currentTitle: String? = null
        val tracks = mutableListOf<CueTrackEntry>()
        var currentTrackNumber = -1
        var currentIndexes = mutableMapOf<Int, Long>()

        fun flushTrack() {
            if (currentTrackNumber >= 0) {
                tracks.add(CueTrackEntry(currentTrackNumber, currentTitle, currentPerformer, currentIndexes.toMap(), currentFile))
            }
            currentTrackNumber = -1
            currentTitle = null
            currentIndexes = mutableMapOf()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("REM")) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            val key = parts[0].uppercase()
            val rest = parts.getOrNull(1)?.trim() ?: ""

            when (key) {
                "FILE" -> {
                    // FILE "filename" WAVE
                    val m = Regex("\"(.*)\"").find(rest)
                    currentFile = m?.groups?.get(1)?.value ?: rest.split(Regex("\\s+"))[0]
                }
                "PERFORMER" -> {
                    currentPerformer = stripQuotes(rest)
                }
                "TITLE" -> {
                    currentTitle = stripQuotes(rest)
                }
                "TRACK" -> {
                    // flush previous
                    flushTrack()
                    val tok = rest.split(Regex("\\s+"))
                    currentTrackNumber = tok.getOrNull(0)?.toIntOrNull() ?: -1
                }
                "INDEX" -> {
                    val tok = rest.split(Regex("\\s+"))
                    val idx = tok.getOrNull(0)?.toIntOrNull() ?: 1
                    val time = tok.getOrNull(1) ?: "00:00:00"
                    val ms = cueTimeToMs(time)
                    currentIndexes[idx] = ms
                }
                else -> {
                    // unknown, ignore
                }
            }
        }

        // flush last
        if (currentTrackNumber >= 0) flushTrack()

        return CueParseResult(tracks = tracks, errors = errors)
    }

    private fun stripQuotes(s: String): String {
        return s.trim().trim('"', '\'')
    }

    private fun cueTimeToMs(t: String): Long {
        // format mm:ss:ff (frames) where frames are usually 75 per second
        val parts = t.split(':')
        return try {
            val mm = parts.getOrNull(0)?.toLongOrNull() ?: 0L
            val ss = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val ff = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val ms = mm * 60_000 + ss * 1000 + (ff * 1000L / 75L)
            ms
        } catch (e: Throwable) {
            try { ScanManager.reportError(null, "cue time parse failed: ${e.message}") } catch (_: Throwable) {}
            0L
        }
    }

    private fun detectCharset(bytes: ByteArray): String {
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        return detector.detectedCharset ?: "UTF-8"
    }
}
