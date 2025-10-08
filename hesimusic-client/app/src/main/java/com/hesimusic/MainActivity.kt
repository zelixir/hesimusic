package com.hesimusic

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var bundleManager: StaticBundleManager
    private lateinit var safLauncher: ActivityResultLauncher<android.net.Uri?>
    private var pendingJsRequestIdForPick: String? = null
    private var pendingJsRequestMethod: String? = null
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
    // Prepare StaticBundleManager to manage frontend bundle extraction and updates
    bundleManager = StaticBundleManager(this)
    // Initialize UiHelper for global toast usage
    UiHelper.init(this)
        // Ensure bundle exists at least once (synchronous on startup)
        bundleManager.ensureBundleUpToDate()

        // Intercept requests to the virtual domain and serve files from internal storage
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (uri.host == "app.frontend") {
                    var relPath = uri.path?.removePrefix("/") ?: ""
                    if (relPath.isEmpty() || relPath == "/") relPath = "index.html"
                    val outFile = File(bundleManager.getFrontendDir(), relPath)
                    if (outFile.exists() && outFile.isFile) {
                        val mime = when (outFile.extension.lowercase()) {
                            "html" -> "text/html"
                            "js" -> "application/javascript"
                            "css" -> "text/css"
                            "json" -> "application/json"
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "svg" -> "image/svg+xml"
                            "woff" -> "font/woff"
                            "woff2" -> "font/woff2"
                            else -> "application/octet-stream"
                        }
                        return WebResourceResponse(mime, "UTF-8", outFile.inputStream())
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        // Expose a JS bridge implementation that supports the scan/folder APIs used by the web UI.
        // Implemented as a dedicated object so it's easier to extend and test.
        webView.addJavascriptInterface(HesiMusicBridgeImpl(this, webView) { reqId, method ->
            // store pending request id and method, then launch SAF picker
            pendingJsRequestIdForPick = reqId
            pendingJsRequestMethod = method
          try {
              android.util.Log.d("HesiMusicBridge", "onPickRequested: reqId=$reqId, method=$method")
              safLauncher.launch(null)
          } catch (e: Throwable) {
              zelixir.hesimusic.scan.ScanManager.reportError(null, "无法启动选择目录: ${e.message}")
          }
        }, "HesiMusicBridge")

        // Register ScanWebBridge so the frontend can call startScan/stopScan via ScanBridge special-case
        webView.addJavascriptInterface(zelixir.hesimusic.scan.ScanWebBridge(this), "ScanBridge")

        // Prepare SAF launcher to handle pickFolder flows from JS
        prefs = getSharedPreferences("hesimusic", MODE_PRIVATE)
        safLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            try {
                if (uri != null) {
                    // Persist permission and save uri
                    zelixir.hesimusic.scan.PermissionHelper.requestSafAccess(this, uri)
                    prefs.edit().putString("saf_uri", uri.toString()).apply()

                    // If there was a pending JS request, return result via global callback
                    val req = pendingJsRequestIdForPick
                    if (req != null) {
                        // Always return a single pickFolder result (path). Legacy multi-folder permission
                        // flow (requestFolderPermissions) has been removed; frontend no longer expects it.
                        val obj = org.json.JSONObject()
                        obj.put("path", uri.toString())
                        
                        // Get display name from DocumentFile
                        try {
                            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                            val displayName = docFile?.name ?: uri.lastPathSegment ?: ""
                            obj.put("displayName", displayName)
                        } catch (e: Throwable) {
                            // Fallback to last path segment
                            obj.put("displayName", uri.lastPathSegment ?: "")
                        }
                        
                        android.util.Log.d("HesiMusicBridge", "returning pick result to JS: req=$req, path=${uri}, displayName=${obj.optString("displayName")}")
                        webView.post {
                            webView.evaluateJavascript("window.__music_api_return__('$req', ${obj.toString()})", null)
                        }
                    }
                } else {
                    // user cancelled: reply with appropriate structure to pending request
                    val req = pendingJsRequestIdForPick
                    if (req != null) {
                        // Treat cancellation as no selection (null) for pickFolder.
                        android.util.Log.d("HesiMusicBridge", "user cancelled pick, returning null for req=$req")
                        webView.post {
                            webView.evaluateJavascript("window.__music_api_return__('$req', null)", null)
                        }
                    }
                }
            } catch (e: Throwable) {
                zelixir.hesimusic.scan.ScanManager.reportError(null, "选择目录失败: ${e.message}")
            } finally {
                pendingJsRequestIdForPick = null
                pendingJsRequestMethod = null
            }
        }

        // Load the frontend index from the virtual domain
        webView.loadUrl("https://app.frontend/")
        // Register aggregated scan error callback to show toast and forward to web UI
        zelixir.hesimusic.scan.ScanManager.setErrorCallback { scanId, summaryJson ->
            try {
                val count = try { org.json.JSONObject(summaryJson).optInt("count", 0) } catch (_: Throwable) { 0 }
                UiHelper.showToast("扫描出错: $count 次，点击查看日志")
            } catch (e: Throwable) { zelixir.hesimusic.scan.ScanManager.reportError(null, "Error showing error toast: ${e.message}") }
            try {
                // forward to webview via evaluateJavascript -> __music_api_emit__('scanError', payload)
                val js = "window.__music_api_emit__('scanError', { scanId: '${scanId}', summary: ${summaryJson} })"
                webView.post { webView.evaluateJavascript(js, null) }
            } catch (e: Throwable) { zelixir.hesimusic.scan.ScanManager.reportError(null, "Error forwarding scanError to webview: ${e.message}") }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check for bundle updates; if updated, reload the WebView to pick up new files.
        Thread {
            try {
                val updated = bundleManager.ensureBundleUpToDate()
                if (updated) {
                    runOnUiThread {
                        webView.reload()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("HesiMusic", "Bundle update check failed: ${e.message}")
            }
        }.start()
    }
}

class HesiMusicBridgeImpl(
    private val activity: ComponentActivity,
    private val webView: WebView,
    // callback(requestId, method)
    private val onPickRequested: ((String, String?) -> Unit)? = null
) {
    private val tag = "HesiMusicBridge"

    @android.webkit.JavascriptInterface
    fun call(name: String, argsJson: String?): String {
        try {
            android.util.Log.d(tag, "JS -> native call: name=$name, argsJson=$argsJson")
            when (name) {
                "getAllTracks" -> {
                    return "[{\"id\":\"1\",\"title\":\"Song A\",\"artist\":\"Artist 1\"}]"
                }
                "play" -> {
                    android.util.Log.d(tag, "JS play: $argsJson")
                    return "{\"ok\":true}"
                }
                "listFolders" -> {
                    // argsJson may contain { requestId, args:{ parent: string } }
                    val parent = try {
                        if (argsJson == null) null else org.json.JSONObject(argsJson).optJSONObject("args")?.optString("parent")?.takeIf { it.isNotEmpty() }
                    } catch (e: Throwable) {
                        zelixir.hesimusic.scan.ScanManager.reportError(null, "listFolders parent parse failed: ${e.message}")
                        android.util.Log.w(tag, "listFolders parent parse failed: ${e.message}")
                        null
                    }
                    val list = listFoldersSync(parent)
                    android.util.Log.d(tag, "listFolders returning for parent=$parent; items=${list.length()}")
                    return list.toString()
                }
                // note: legacy requestFolderPermissions flow removed. Frontend uses pickFolder only.
                "pickFolder" -> {
                    // Support async pick flow: if the JS provided a requestId we will launch SAF via the
                    // onPickRequested callback and return null so the JS side waits for window.__music_api_return__.
                    if (argsJson != null) {
                        val jo = org.json.JSONObject(argsJson)
                        val req = jo.optString("requestId").takeIf { it.isNotEmpty() }
                        if (!req.isNullOrEmpty() && onPickRequested != null) {
                            android.util.Log.d(tag, "pickFolder requested with requestId=$req; invoking onPickRequested(method=pickFolder)")
                            onPickRequested.invoke(req, "pickFolder")
                            return "null"
                        }
                    }

                    // Best-effort synchronous fallback: return a likely root path for the UI.
                    val res = org.json.JSONObject()
                    res.put("path", "/storage/emulated/0/Music")
                    android.util.Log.d(tag, "pickFolder returning fallback path: ${res}")
                    return res.toString()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w(tag, "bridge call error for $name: ${e.message}")
        }
        return "null"
    }

    private fun listFoldersSync(parent: String?): org.json.JSONArray {
        val result = org.json.JSONArray()
        // If parent is null, return top-level roots
        if (parent == null || parent.isEmpty()) {
            // common root for many devices
            val root = org.json.JSONObject()
            root.put("path", "/storage/emulated/0")
            root.put("name", "根目录")
            root.put("count", 0)
            result.put(root)
            return result
        }

        // Try using java.io.File as a fallback. This will work on older Androids or for accessible dirs.
        val f = java.io.File(parent)
        val children = f.listFiles()
        if (children != null) {
                for (c in children) {
                try {
                    if (c.isDirectory && c.canRead()) {
                        val o = org.json.JSONObject()
                        o.put("path", c.absolutePath)
                        o.put("name", c.name)
                        o.put("count", 0)
                        result.put(o)
                    }
                } catch (e: Throwable) { zelixir.hesimusic.scan.ScanManager.reportError(null, "listFolders child iteration failed for ${c?.absolutePath ?: "<unknown>"}: ${e.message}") }
            }
        }
        return result
    }
}
