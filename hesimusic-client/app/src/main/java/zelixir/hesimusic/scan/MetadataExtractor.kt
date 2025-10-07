package zelixir.hesimusic.scan

import android.media.MediaMetadataRetriever
import org.jaudiotagger.audio.AudioFileIO
import org.mozilla.universalchardet.UniversalDetector
import java.io.File

data class LightMetadata(val sizeBytes: Long, val durationMs: Long, val format: String?)

data class MetadataResult(
    val durationMs: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val bitrate: Int?,
    val sampleRate: Int?,
    val channels: Int?,
    val format: String?,
    val sizeBytes: Long,
    val success: Boolean
)

object MetadataExtractor {
    fun extractLight(file: File): LightMetadata {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            return LightMetadata(sizeBytes = file.length(), durationMs = dur, format = mime)
        } catch (t: Throwable) {
            try { ScanManager.reportError(null, "read light metadata failed for ${file.absolutePath}: ${t.message}") } catch (_: Throwable) {}
            return LightMetadata(sizeBytes = file.length(), durationMs = 0L, format = null)
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    fun extract(file: File): MetadataResult {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()

            // attempt deeper tag read with jaudiotagger for better coverage
            var jaTitle: String? = null
            var jaArtist: String? = null
            var jaAlbum: String? = null
            try {
                val af = AudioFileIO.read(file)
                val tag = af.tag
                if (tag != null) {
                    jaTitle = tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE)
                    jaArtist = tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST)
                    jaAlbum = tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM)
                }
            } catch (e: Throwable) {
                try { ScanManager.reportError(null, "jaudiotagger read failed for ${file.absolutePath}: ${e.message}") } catch (_: Throwable) {}
            }

            val finalTitle = title ?: jaTitle ?: file.nameWithoutExtension
            val finalArtist = artist ?: jaArtist
            val finalAlbum = album ?: jaAlbum

            return MetadataResult(
                durationMs = dur,
                title = finalTitle,
                artist = finalArtist,
                album = finalAlbum,
                bitrate = bitrate,
                sampleRate = null,
                channels = null,
                format = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                sizeBytes = file.length(),
                success = true
            )
        } catch (t: Throwable) {
            try { ScanManager.reportError(null, "extract metadata failed for ${file.absolutePath}: ${t.message}") } catch (_: Throwable) {}
            return MetadataResult(0L, null, null, null, null, null, null, null, file.length(), false)
        } finally {
            try { mmr.release() } catch (e: Throwable) { try { ScanManager.reportError(null, "mmr.release failed: ${e.message}") } catch (_: Throwable) {} }
        }
    }

    fun decodeTagText(raw: ByteArray): String {
        val detector = UniversalDetector(null)
        detector.handleData(raw, 0, raw.size)
        detector.dataEnd()
        val encoding = detector.detectedCharset ?: "UTF-8"
        return try {
            String(raw, charset(encoding))
        } catch (t: Throwable) {
            String(raw, Charsets.UTF_8)
        }
    }
}
