package com.hesimusic

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

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

        // Load local UI (for development, load remote dev server)
        webView.loadUrl("http://10.0.2.2:5173")
    }
}
