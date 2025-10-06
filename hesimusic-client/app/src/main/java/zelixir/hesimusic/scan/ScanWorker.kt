package zelixir.hesimusic.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import zelixir.hesimusic.scan.db.SongEntity
import java.io.File

/**
 * ScanWorker: orchestrates scanning using FileScanner + MetadataExtractor (to be implemented).
 * It supports checkpointing by serializing the FileScanner cursor into a file under app files.
 */
class ScanWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scanId = inputData.getString("scanId") ?: return Result.failure()
        val optionsJson = inputData.getString("optionsJson") ?: "{}"

        // simple options parse (roots only)
        val roots = mutableListOf<File>()
        try {
            val obj = org.json.JSONObject(optionsJson)
            val arr = obj.optJSONArray("roots")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    roots.add(File(arr.getString(i)))
                }
            }
        } catch (_: Throwable) {
        }

        val scanner = FileScanner(coroutineScope = this)

        val repository = ScanRepository(applicationContext)

        val batch = ArrayList<SongEntity>()

        // load cursor if exists
        val cursor = readCursorIfExists(scanId)

        try {
            scanner.scanRoots(roots, ScanOptions(roots = roots.map { it.absolutePath }), onFile = { file ->
                val prev = ScanManager.getProgress(scanId)
                val scanned = (prev?.scannedCount ?: 0) + 1
                var found = prev?.foundSongs ?: 0

                // lightweight metadata then try full extract
                val light = MetadataExtractor.extractLight(file)
                val meta = if (light.durationMs > 0) MetadataExtractor.extract(file) else null

                val id = if (file.extension.equals("flac", true) && file.name.endsWith(".cue", true)) file.absolutePath else file.absolutePath

                val song = SongEntity(
                    id = id,
                    path = file.absolutePath,
                    cue_blob = null,
                    title = meta?.title,
                    artist = meta?.artist,
                    album = meta?.album,
                    duration_ms = meta?.durationMs ?: light.durationMs,
                    size_bytes = file.length(),
                    format = meta?.format ?: light.format,
                    bitrate = meta?.bitrate,
                    sample_rate = meta?.sampleRate,
                    channels = meta?.channels,
                    tags_json = null,
                    fingerprint = null,
                    last_scanned_at = System.currentTimeMillis()
                )

                batch.add(song)
                if (meta?.success == true) found++

                ScanManager.updateProgress(scanId, ScanProgress(scannedCount = scanned, foundSongs = found, currentPath = file.absolutePath))

                // write batch every 100
                if (batch.size >= 100) {
                    // persist
                    repository.saveBatch(batch.toList())
                    batch.clear()
                    // checkpoint (write last processed path)
                    checkpoint(scanId, file.absolutePath, scanned)
                }
            }, cursor = cursor, onProgress = { _, _ ->
                // heartbeat
            })

            // flush remaining batch
            if (batch.isNotEmpty()) {
                repository.saveBatch(batch.toList())
                batch.clear()
            }

            // final checkpoint
            checkpoint(scanId, null, ScanManager.getProgress(scanId)?.scannedCount ?: 0)
            ScanManager.updateProgress(scanId, ScanProgress(scannedCount = ScanManager.getProgress(scanId)?.scannedCount ?: 0, foundSongs = ScanManager.getProgress(scanId)?.foundSongs ?: 0, currentPath = null))
            // mark completed
            // persist status
            // write a small completed marker
            val dir = File(applicationContext.filesDir, "scan_states")
            val f = File(dir, "$scanId.json")
            if (f.exists()) {
                val js = org.json.JSONObject(f.readText())
                js.put("status", ScanStatus.COMPLETED.name)
                js.put("completedAt", System.currentTimeMillis())
                f.writeText(js.toString())
            }
            return Result.success()
        } catch (e: Exception) {
            // on failure, persist failure status and cursor
            val progress = ScanManager.getProgress(scanId)
            checkpoint(scanId, progress?.currentPath, progress?.scannedCount ?: 0)
            val dir = File(applicationContext.filesDir, "scan_states")
            val f = File(dir, "$scanId.json")
            val js = if (f.exists()) org.json.JSONObject(f.readText()) else org.json.JSONObject()
            js.put("status", ScanStatus.FAILED.name)
            js.put("errorMessage", e.message ?: "")
            js.put("failedAt", System.currentTimeMillis())
            f.writeText(js.toString())
            return Result.failure()
        }
    }

    private fun checkpoint(scanId: String, lastPath: String?, processedCount: Int) {
        try {
            // attempt to serialize an empty cursor (FileScanner provides static serializeCursor when available)
            // since internal cursor is local to scanRoots, we write a lightweight marker that a checkpoint happened
            val dir = File(applicationContext.filesDir, "scan_checkpoints")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "$scanId.chk")
            val json = org.json.JSONObject()
            json.put("lastPath", lastPath)
            json.put("processedCount", processedCount)
            json.put("updatedAt", System.currentTimeMillis())
            f.writeText(json.toString())
        } catch (_: Throwable) {
        }
    }

    private fun readCursorIfExists(scanId: String): FileScanner.ScanCursor? {
        val dir = File(applicationContext.filesDir, "scan_checkpoints")
        val f = File(dir, "$scanId.cursor")
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            FileScanner.deserializeCursor(bytes)
        } catch (_: Throwable) {
            null
        }
    }
}