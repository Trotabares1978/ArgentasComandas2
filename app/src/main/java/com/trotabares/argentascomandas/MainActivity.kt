package com.trotabares.argentascomandas

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(), "ArgentasNativeBluetooth")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    inner class NativeBridge {
        // Compatibilidad con el HTML histórico: la sincronización actual no usa
        // Wi-Fi Direct, Bluetooth ni Nearby; estas operaciones ya no arrancan servicios.
        @JavascriptInterface fun refresh() {}
        @JavascriptInterface fun startNearby() {}
        @JavascriptInterface fun stopNearby() {}
        @JavascriptInterface fun startServer() {}
        @JavascriptInterface fun connect(address: String) {}
        @JavascriptInterface fun acceptIncoming() {}
        @JavascriptInterface fun rejectIncoming() {}
        @JavascriptInterface fun send(message: String) {}

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun shareText(text: String) {
            try {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(sendIntent, "Compartir Pizarra"))
            } catch (_: Exception) {}
        }
    }
}
