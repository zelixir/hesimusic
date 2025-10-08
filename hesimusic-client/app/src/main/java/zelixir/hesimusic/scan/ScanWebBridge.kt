package zelixir.hesimusic.scan

import android.content.Context
import android.webkit.JavascriptInterface
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import zelixir.hesimusic.scan.db.ScanDatabase
import zelixir.hesimusic.scan.db.ScanSettingsEntity

class ScanWebBridge(private val context: Context) {

    init {
        ScanManager.init(context)
    }

    @JavascriptInterface
    fun startScanFromJs(optionsJson: String): String {
        val obj = try { JSONObject(optionsJson) } catch (e: Throwable) { ScanManager.reportError(null, "解析扫描参数失败: ${e.message}"); JSONObject() }
        
        // Extract the 'options' object if present (frontend sends { options: {...} })
        val optionsObj = if (obj.has("options")) obj.getJSONObject("options") else obj
        
        val roots = mutableListOf<String>()
        // Try 'folders' first (new format), then 'paths' (legacy format)
        val arr = optionsObj.optJSONArray("folders") ?: optionsObj.optJSONArray("paths")
        if (arr != null) {
            for (i in 0 until arr.length()) roots.add(arr.getString(i))
        }
        val excluded = mutableListOf<String>()
        val ex = optionsObj.optJSONArray("excluded")
        if (ex != null) for (i in 0 until ex.length()) excluded.add(ex.getString(i))
        val minDuration = optionsObj.optLong("minDurationMs", 0L)

        val opts = ScanOptions(roots = roots, excludedPaths = excluded, minDurationMs = minDuration)
        val scanId = ScanManager.startScan(opts)
        return JSONObject().put("scanId", scanId).toString()
    }

    @JavascriptInterface
    fun stopScanFromJs(scanId: String): String {
        val ok = ScanManager.stopScan(scanId)
        return JSONObject().put("success", ok).toString()
    }

    @JavascriptInterface
    fun getScanState(scanId: String): String {
        try {
            val dir = java.io.File(context.filesDir, "scan_states")
            val f = java.io.File(dir, "$scanId.json")
            if (!f.exists()) return JSONObject().put("error", "not_found").toString()
            return f.readText()
        } catch (e: Throwable) {
            try { ScanManager.reportError(scanId, "getScanState failed: ${e.message}") } catch (_: Throwable) {}
            return JSONObject().put("error", "read_failed").put("message", e.message).toString()
        }
    }

    @JavascriptInterface
    fun setScanSettings(settingsJson: String): String {
        try {
            val obj = JSONObject(settingsJson)
            val settings = obj.optJSONObject("settings") ?: JSONObject()
            
            // Extract folder and exclude arrays
            val foldersArr = settings.optJSONArray("folders") ?: JSONArray()
            val excludesArr = settings.optJSONArray("excludes") ?: JSONArray()
            
            // Convert to JSON strings
            val foldersJson = foldersArr.toString()
            val excludesJson = excludesArr.toString()
            
            val skipShort = settings.optBoolean("skipShort", true)
            val skipAmrMid = settings.optBoolean("skipAmrMid", true)
            val skipHidden = settings.optBoolean("skipHidden", true)
            
            val entity = ScanSettingsEntity(
                id = 1,
                folders_json = foldersJson,
                excludes_json = excludesJson,
                skip_short = skipShort,
                skip_amr_mid = skipAmrMid,
                skip_hidden = skipHidden,
                last_updated_at = System.currentTimeMillis()
            )
            
            runBlocking {
                ScanDatabase.getInstance(context).songDao().insertScanSettings(entity)
            }
            
            return JSONObject().put("success", true).toString()
        } catch (e: Throwable) {
            ScanManager.reportError(null, "setScanSettings failed: ${e.message}")
            return JSONObject().put("error", e.message).toString()
        }
    }

    @JavascriptInterface
    fun getScanSettings(argsJson: String): String {
        try {
            val entity = runBlocking {
                ScanDatabase.getInstance(context).songDao().getScanSettings()
            }
            
            if (entity == null) {
                return JSONObject().put("settings", JSONObject.NULL).toString()
            }
            
            val foldersArr = try { JSONArray(entity.folders_json ?: "[]") } catch (_: Throwable) { JSONArray() }
            val excludesArr = try { JSONArray(entity.excludes_json ?: "[]") } catch (_: Throwable) { JSONArray() }
            
            val settings = JSONObject().apply {
                put("folders", foldersArr)
                put("excludes", excludesArr)
                put("skipShort", entity.skip_short)
                put("skipAmrMid", entity.skip_amr_mid)
                put("skipHidden", entity.skip_hidden)
            }
            
            return JSONObject().put("settings", settings).toString()
        } catch (e: Throwable) {
            ScanManager.reportError(null, "getScanSettings failed: ${e.message}")
            return JSONObject().put("error", e.message).toString()
        }
    }

    // Helper function to get display name from URI using DocumentFile
    @JavascriptInterface
    fun getDisplayNameFromUri(uriString: String): String {
        try {
            val uri = android.net.Uri.parse(uriString)
            val docFile = DocumentFile.fromTreeUri(context, uri)
            return docFile?.name ?: uri.lastPathSegment ?: uriString
        } catch (e: Throwable) {
            return uriString
        }
    }

    // Bridge method to allow native to call into web page via evaluateJavascript (not used here)
    fun onProgressUpdate(scanId: String, progress: ScanProgress) {
        // no-op: WebView client should receive messages from Activity hosting the WebView
        // referenced to avoid unused parameter warnings
        @Suppress("UNUSED_VARIABLE")
        val _ref = Pair(scanId, progress)
    }
}
