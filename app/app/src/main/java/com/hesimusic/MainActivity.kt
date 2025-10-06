package com.hesimusic

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        // Prepare local frontend files by extracting bundled zip (if present)
        val frontendDir = File(filesDir, "frontend")
        if (!frontendDir.exists()) {
            frontendDir.mkdirs()
            try {
                assets.open("frontend.zip").use { inputStream ->
                    ZipInputStream(inputStream).use { zipIn ->
                        var entry: ZipEntry? = zipIn.nextEntry
                        while (entry != null) {
                            val outFile = File(frontendDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    zipIn.copyTo(out)
                                }
                            }
                            zipIn.closeEntry()
                            entry = zipIn.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("HesiMusic", "Failed to extract frontend.zip: ${e.message}")
            }
        }

        // Intercept requests to the virtual domain and serve files from internal storage
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri = request?.url ?: return super.shouldInterceptRequest(view, request)
                if (uri.host == "app.frontend") {
                    var relPath = uri.path?.removePrefix("/") ?: ""
                    if (relPath.isEmpty() || relPath == "/") relPath = "index.html"
                    val outFile = File(frontendDir, relPath)
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

        // Expose a simple JS bridge for development. In the real app implement secure, typed APIs.
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun call(name: String, argsJson: String?): String {
                // Very small demo: support getAllTracks and play
                when (name) {
                    "getAllTracks" -> {
                        // return a JSON array string
                        return "[{\"id\":\"1\",\"title\":\"Song A\",\"artist\":\"Artist 1\"}]"
                    }
                    "play" -> {
                        // argsJson expected like {"id":"1"}
                        android.util.Log.d("HesiMusic", "JS play: $argsJson")
                        return "{\"ok\":true}"
                    }
                }
                return "null"
            }
        }, "HesiMusicBridge")

        // Load the frontend index from the virtual domain
        webView.loadUrl("https://app.frontend/")
    }
}
