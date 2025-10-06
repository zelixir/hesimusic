package zelixir.hesimusic.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SongEntry(val id: String, val path: String, val size: Long, val lastModified: Long)

data class DuplicateCheckResult(val isDuplicate: Boolean, val duplicateOf: String?)

object Deduplicator {
    // quick index keyed by quickKey -> SongEntry.id
    private val quickIndex = ConcurrentHashMap<String, String>()

    private fun quickKey(entry: SongEntry): String = "${entry.path}:${entry.size}:${entry.lastModified}"

    fun indexExisting(entry: SongEntry) {
        quickIndex[quickKey(entry)] = entry.id
    }

    fun isDuplicate(candidate: SongEntry): DuplicateCheckResult {
        val key = quickKey(candidate)
        val existing = quickIndex[key]
        return if (existing != null) DuplicateCheckResult(true, existing) else DuplicateCheckResult(false, null)
    }

    /**
     * Compute a simple SHA-1 fingerprint asynchronously. This is an optional, potentially expensive
     * operation and should be run in background.
     */
    fun computeFingerprintAsync(file: File) = GlobalScope.async(Dispatchers.IO) {
        try {
            val md = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { ins ->
                val buf = ByteArray(8192)
                var read: Int
                while (ins.read(buf).also { read = it } > 0) {
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString(separator = "") { String.format("%02x", it) }
        } catch (t: Throwable) {
            null
        }
    }
}
