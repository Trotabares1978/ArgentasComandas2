package com.trotabares.argentascomandas

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The app is loaded from android_asset; keep local asset access but
            // prevent file-origin JavaScript from reading arbitrary local files.
            allowFileAccess = true
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(), "ArgentasNativeBluetooth")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun openExternalUrl(url: String) {
            val uri = try {
                Uri.parse(url)
            } catch (_: Exception) {
                return
            }

            // Never pass javascript:, file:, content:, intent:, or other
            // schemes from WebView JavaScript to Android's intent resolver.
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "https" && scheme != "http") ||
                uri.host.isNullOrBlank() ||
                uri.userInfo != null
            ) return

            runOnUiThread {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: Exception) {
                    // No browser/app can handle this URL.
                }
            }
        }

        @JavascriptInterface
        fun shareText(text: String) {
            runOnUiThread {
                try {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    startActivity(Intent.createChooser(sendIntent, "Compartir Pizarra"))
                } catch (_: Exception) {
                    // Sharing is optional and must not crash the app.
                }
            }
        }
    }
}
