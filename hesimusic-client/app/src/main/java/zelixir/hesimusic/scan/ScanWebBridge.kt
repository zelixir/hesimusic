package zelixir.hesimusic.scan

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONObject

class ScanWebBridge(private val context: Context) {

    init {
        ScanManager.init(context)
    }

    @JavascriptInterface
    fun startScanFromJs(optionsJson: String): String {
        val obj = try { JSONObject(optionsJson) } catch (e: Throwable) { ScanManager.reportError(null, "解析扫描参数失败: ${e.message}"); JSONObject() }
        val roots = mutableListOf<String>()
        val arr = obj.optJSONArray("paths")
        if (arr != null) {
            for (i in 0 until arr.length()) roots.add(arr.getString(i))
        }
        val excluded = mutableListOf<String>()
        val ex = obj.optJSONArray("excluded")
        if (ex != null) for (i in 0 until ex.length()) excluded.add(ex.getString(i))
        val minDuration = obj.optLong("minDurationMs", 0L)

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

    // Bridge method to allow native to call into web page via evaluateJavascript (not used here)
    fun onProgressUpdate(scanId: String, progress: ScanProgress) {
        // no-op: WebView client should receive messages from Activity hosting the WebView
        // referenced to avoid unused parameter warnings
        @Suppress("UNUSED_VARIABLE")
        val _ref = Pair(scanId, progress)
    }
}
