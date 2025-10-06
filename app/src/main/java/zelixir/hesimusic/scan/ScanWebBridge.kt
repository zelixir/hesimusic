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
        val obj = try { JSONObject(optionsJson) } catch (_: Throwable) { JSONObject() }
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

    // Bridge method to allow native to call into web page via evaluateJavascript (not used here)
    fun onProgressUpdate(scanId: String, progress: ScanProgress) {
        // no-op: WebView client should receive messages from Activity hosting the WebView
    }
}
