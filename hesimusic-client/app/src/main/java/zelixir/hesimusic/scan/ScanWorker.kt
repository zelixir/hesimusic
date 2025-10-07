package zelixir.hesimusic.scan

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import zelixir.hesimusic.scan.db.SongEntity
import java.io.File

/**
 * ScanWorker: orchestrates scanning using FileScanner + MetadataExtractor.
 * Supports checkpointing by writing lightweight markers under app files.
 */
class ScanWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scanId = inputData.getString("scanId") ?: return Result.failure()
        val optionsJson = inputData.getString("optionsJson") ?: "{}"

        // parse roots
        val roots = mutableListOf<File>()
        try {
            val obj = org.json.JSONObject(optionsJson)
            val arr = obj.optJSONArray("roots")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    roots.add(File(arr.getString(i)))
                }
            }
        } catch (e: Throwable) {
            try { ScanManager.reportError(scanId, "options parse failed: ${e.message}") } catch (_: Throwable) {}
        }

        val scanner = FileScanner(coroutineScope = this)
        val repository = ScanRepository(applicationContext)
        val batch = ArrayList<SongEntity>()
        val cursor = readCursorIfExists(scanId)

        try {
            scanner.scanRoots(
                roots,
                ScanOptions(roots = roots.map { it.absolutePath }),
                onFile = { file: File ->
                    val prev = ScanManager.getProgress(scanId)
                    val scanned = (prev?.scannedCount ?: 0) + 1
                    var found = prev?.foundSongs ?: 0

                    // lightweight metadata then try full extract
                    val light = MetadataExtractor.extractLight(file)
                    val meta = try {
                        if (light.durationMs > 0) MetadataExtractor.extract(file) else null
                    } catch (e: Throwable) {
                        try { ScanManager.reportError(scanId, "metadata error for ${file.absolutePath}: ${e.message}") } catch (_: Throwable) {}
                        null
                    }

                    val id = file.absolutePath

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

                    if (batch.size >= 100) {
                        repository.saveBatch(batch.toList())
                        batch.clear()
                        checkpoint(scanId, file.absolutePath, scanned)
                    }
                },
                cursor = cursor,
                onProgress = { _, _ -> }
            )

            if (batch.isNotEmpty()) {
                repository.saveBatch(batch.toList())
                batch.clear()
            }

            checkpoint(scanId, null, ScanManager.getProgress(scanId)?.scannedCount ?: 0)
            ScanManager.updateProgress(scanId, ScanProgress(scannedCount = ScanManager.getProgress(scanId)?.scannedCount ?: 0, foundSongs = ScanManager.getProgress(scanId)?.foundSongs ?: 0, currentPath = null))

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
            try { ScanManager.reportError(scanId, "scan failed: ${e.message}") } catch (_: Throwable) {}
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
            val dir = File(applicationContext.filesDir, "scan_checkpoints")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "$scanId.chk")
            val json = org.json.JSONObject()
            json.put("lastPath", lastPath)
            json.put("processedCount", processedCount)
            json.put("updatedAt", System.currentTimeMillis())
            f.writeText(json.toString())
        } catch (e: Throwable) {
            try { ScanManager.reportError(scanId, "checkpoint write failed: ${e.message}") } catch (_: Throwable) {}
        }
    }

    private fun readCursorIfExists(scanId: String): FileScanner.ScanCursor? {
        val dir = File(applicationContext.filesDir, "scan_checkpoints")
        val f = File(dir, "$scanId.cursor")
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            FileScanner.deserializeCursor(bytes)
        } catch (e: Throwable) {
            try { ScanManager.reportError(scanId, "read cursor failed: ${e.message}") } catch (_: Throwable) {}
            null
        }
    }
}