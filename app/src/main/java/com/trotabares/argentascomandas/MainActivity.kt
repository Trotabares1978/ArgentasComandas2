package com.trotabares.argentascomandas

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val gatewayPollHandler = Handler(Looper.getMainLooper())
    private val gatewayPoll = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                ArgentasGatewayQueue.schedule(this@MainActivity)
                gatewayPollHandler.postDelayed(this, 15_000)
            }
        }
    }

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
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // JavaScript interfaces are available to every frame. Keep navigation
                // inside the trusted packaged asset origin and never load remote frames.
                if (!request.isForMainFrame) return true
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "file" && uri.host.isNullOrEmpty() &&
                    uri.path?.startsWith("/android_asset/") == true
                ) return false
                if ((scheme == "https" || scheme == "http") &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null
                ) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                        // No browser/app can handle this URL.
                    }
                }
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBridge(), "ArgentasNativeBluetooth")
        webView.addJavascriptInterface(NativeBridge(), "ArgentasNativeGateway")
        ArgentasGatewayQueue.schedulePeriodic(this)
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        ArgentasGatewayQueue.schedule(this)
        gatewayPollHandler.removeCallbacks(gatewayPoll)
        gatewayPollHandler.postDelayed(gatewayPoll, 15_000)
    }

    override fun onPause() {
        gatewayPollHandler.removeCallbacks(gatewayPoll)
        super.onPause()
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun isDebugBuild(): Boolean {
            return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }

        @JavascriptInterface
        fun configureGateway(endpoint: String, token: String): Boolean {
            if (!isDebugBuild()) return false
            return try {
                ArgentasGatewayQueue.configure(this@MainActivity, endpoint, token)
                ArgentasGatewayQueue.schedule(this@MainActivity)
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun enqueueGatewayMessage(deliveryId: String, payloadJson: String): Boolean {
            return try {
                val accepted = ArgentasGatewayQueue.enqueue(this@MainActivity, deliveryId, payloadJson)
                if (accepted) ArgentasGatewayQueue.schedule(this@MainActivity)
                accepted
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun gatewayInbox(): String {
            return try {
                ArgentasGatewayQueue.inbox(this@MainActivity)
            } catch (_: Exception) {
                "[]"
            }
        }

        @JavascriptInterface
        fun acknowledgeGatewayInbox(idsJson: String): Boolean {
            return try {
                val accepted = ArgentasGatewayQueue.acknowledgeInbox(this@MainActivity, idsJson)
                if (accepted) ArgentasGatewayQueue.schedule(this@MainActivity)
                accepted
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun gatewayStatus(): String {
            return try {
                ArgentasGatewayQueue.status(this@MainActivity)
            } catch (_: Exception) {
                """{"configured":false,"error":"secure_storage_unavailable"}"""
            }
        }

        @JavascriptInterface
        fun syncGatewayNow() {
            ArgentasGatewayQueue.schedule(this@MainActivity)
        }

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
