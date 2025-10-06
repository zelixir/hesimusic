package zelixir.hesimusic.scan

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ScanManager
 *
 * Responsible for starting/stopping scan WorkManager jobs and keeping lightweight in-memory
 * status/progress cache. Persists an initial scan_state JSON file so other components (Worker)
 * can pick up basic configuration before Room/ScanRepository is available.
 */
object ScanManager {
    private lateinit var appContext: Context

    private val statusMap = ConcurrentHashMap<String, ScanStatus>()
    private val progressMap = ConcurrentHashMap<String, ScanProgress>()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Start a scan. Returns a scanId immediately after enqueuing a WorkManager job.
     * The ScanWorker will receive input Data with keys: scanId, optionsJson
     */
    fun startScan(options: ScanOptions): String {
        checkInitialized()

        val scanId = UUID.randomUUID().toString()
        statusMap[scanId] = ScanStatus.PENDING

        // Build options JSON
        val optionsJson = JSONObject().apply {
            put("roots", JSONArray(options.roots))
            put("excludedPaths", JSONArray(options.excludedPaths))
            put("minDurationMs", options.minDurationMs)
        }.toString()

        val input = Data.Builder()
            .putString("scanId", scanId)
            .putString("optionsJson", optionsJson)
            .build()

        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .addTag(scanId)
            .setInputData(input)
            .build()

        WorkManager.getInstance(appContext).enqueue(request)

        // Mark running and persist initial scan state for recovery
        statusMap[scanId] = ScanStatus.RUNNING
        persistInitialScanState(scanId, options)

        return scanId
    }

    /**
     * Stop a running scan. Cancels all WorkManager jobs with the scanId tag and updates status.
     */
    fun stopScan(scanId: String): Boolean {
        if (!this::appContext.isInitialized) return false
        statusMap[scanId] = ScanStatus.CANCELLED
        WorkManager.getInstance(appContext).cancelAllWorkByTag(scanId)
        persistScanStateStatus(scanId, ScanStatus.CANCELLED)
        return true
    }

    fun getStatus(scanId: String): ScanStatus {
        return statusMap[scanId] ?: ScanStatus.UNKNOWN
    }

    fun getProgress(scanId: String): ScanProgress? {
        return progressMap[scanId] ?: readPersistedProgress(scanId)
    }

    internal fun updateProgress(scanId: String, progress: ScanProgress) {
        progressMap[scanId] = progress
        writePersistedProgress(scanId, progress)
    }

    private fun checkInitialized() {
        if (!this::appContext.isInitialized) throw IllegalStateException("ScanManager not initialized. Call ScanManager.init(context) first.")
    }

    // Persist initial scan_state to a file so Worker can access it before DB is available.
    private fun persistInitialScanState(scanId: String, options: ScanOptions) {
        val dir = File(appContext.filesDir, "scan_states")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "$scanId.json")
        val json = JSONObject().apply {
            put("scanId", scanId)
            put("status", ScanStatus.RUNNING.name)
            put("options", JSONObject().apply {
                put("roots", JSONArray(options.roots))
                put("excludedPaths", JSONArray(options.excludedPaths))
                put("minDurationMs", options.minDurationMs)
            })
            put("createdAt", System.currentTimeMillis())
        }
        f.writeText(json.toString())
    }

    private fun persistScanStateStatus(scanId: String, status: ScanStatus) {
        val dir = File(appContext.filesDir, "scan_states")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "$scanId.json")
        val json = if (f.exists()) {
            JSONObject(f.readText()).apply { put("status", status.name); put("updatedAt", System.currentTimeMillis()) }
        } else {
            JSONObject().apply { put("scanId", scanId); put("status", status.name); put("updatedAt", System.currentTimeMillis()) }
        }
        f.writeText(json.toString())
    }

    private fun writePersistedProgress(scanId: String, progress: ScanProgress) {
        val dir = File(appContext.filesDir, "scan_progress")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "$scanId.json")
        val json = JSONObject().apply {
            put("scannedCount", progress.scannedCount)
            put("foundSongs", progress.foundSongs)
            put("currentPath", progress.currentPath)
            put("lastUpdated", progress.lastUpdated)
        }
        f.writeText(json.toString())
    }

    private fun readPersistedProgress(scanId: String): ScanProgress? {
        val f = File(appContext.filesDir, "scan_progress/$scanId.json")
        if (!f.exists()) return null
        return try {
            val obj = JSONObject(f.readText())
            ScanProgress(
                scannedCount = obj.optInt("scannedCount", 0),
                foundSongs = obj.optInt("foundSongs", 0),
                currentPath = obj.optString("currentPath", null),
                lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
            )
        } catch (t: Throwable) {
            null
        }
    }
}

// --- Supporting data classes ---
data class ScanOptions(
    val roots: List<String>,
    val excludedPaths: List<String> = emptyList(),
    val minDurationMs: Long = 0L
)

enum class ScanStatus { PENDING, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED, UNKNOWN }

data class ScanProgress(
    val scannedCount: Int = 0,
    val foundSongs: Int = 0,
    val currentPath: String? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
