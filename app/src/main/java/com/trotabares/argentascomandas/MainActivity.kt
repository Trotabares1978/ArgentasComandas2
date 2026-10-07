package com.trotabares.argentascomandas

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var webViewReady = false
    private var pendingEvents = mutableListOf<String>()
    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webViewReady = true
                sendCommand("request_state")
                flushPendingEvents()
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBluetoothBridge(), "ArgentasNativeBluetooth")
        setContentView(webView)

        registerServiceReceiver()
        startLanService()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun startLanService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, ArgentasLanConnectionService::class.java)
        )
    }

    private fun registerServiceReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra("type")) {
                    ArgentasLanConnectionService.EVENT_STATE -> {
                        val state = intent.getStringExtra(ArgentasLanConnectionService.EXTRA_STATE) ?: "DESCONECTADO"
                        val text = intent.getStringExtra(ArgentasLanConnectionService.EXTRA_TEXT) ?: state
                        dispatchJs(
                            "window.onBluetoothState&&window.onBluetoothState(" +
                                JSONObject.quote(state) + "," + JSONObject.quote(text) + ");"
                        )
                    }
                    ArgentasLanConnectionService.EVENT_MESSAGE -> {
                        val message = intent.getStringExtra(ArgentasLanConnectionService.EXTRA_MESSAGE) ?: return
                        dispatchJs(
                            "window.onBluetoothMessage&&window.onBluetoothMessage(" +
                                JSONObject.quote(message) + ");"
                        )
                    }
                    ArgentasLanConnectionService.EVENT_DEVICES -> {
                        val devices = intent.getStringExtra(ArgentasLanConnectionService.EXTRA_DEVICES) ?: "[]"
                        dispatchJs(
                            "window.dispatchEvent(new CustomEvent(" +
                                JSONObject.quote("argentas-bluetooth") +
                                ",{detail:{type:" + JSONObject.quote("devices") +
                                ",payload:{devices:" + devices + "}}}));"
                        )
                    }
                    ArgentasLanConnectionService.EVENT_AUTHORIZED -> {
                        dispatchJs(
                            "window.dispatchEvent(new CustomEvent('argentas-bluetooth'," +
                                "{detail:{type:'authorized'}}));"
                        )
                    }
                    ArgentasLanConnectionService.EVENT_DIAGNOSTIC -> {
                        val message = intent.getStringExtra(ArgentasLanConnectionService.EXTRA_MESSAGE) ?: return
                        dispatchJs(
                            "window.dispatchEvent(new CustomEvent('argentas-bluetooth'," +
                                "{detail:{type:'diagnostic',payload:{message:" + JSONObject.quote(message) + "}}}));"
                        )
                    }
                }
            }
        }

        val filter = IntentFilter(ArgentasLanConnectionService.ACTION_EVENT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun dispatchJs(script: String) {
        if (!webViewReady) {
            pendingEvents.add(script)
            if (pendingEvents.size > 100) pendingEvents.removeAt(0)
            return
        }
        webView.evaluateJavascript(script, null)
    }

    private fun flushPendingEvents() {
        val events = pendingEvents.toList()
        pendingEvents.clear()
        events.forEach { webView.evaluateJavascript(it, null) }
    }

    private fun sendCommand(command: String, address: String? = null, message: String? = null) {
        val intent = Intent(this, ArgentasLanConnectionService::class.java)
            .putExtra(ArgentasLanConnectionService.EXTRA_COMMAND, command)
        address?.let { intent.putExtra(ArgentasLanConnectionService.EXTRA_ADDRESS, it) }
        message?.let { intent.putExtra(ArgentasLanConnectionService.EXTRA_MESSAGE, it) }
        ContextCompat.startForegroundService(this, intent)
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface fun refresh() = sendCommand("refresh")
        @JavascriptInterface fun startNearby() = sendCommand("test_nearby")
        @JavascriptInterface fun stopNearby() = sendCommand("nearby_stop")
        @JavascriptInterface fun startServer() = sendCommand("start_server")
        @JavascriptInterface fun connect(address: String) = sendCommand("connect", address = address)
        @JavascriptInterface fun acceptIncoming() = sendCommand("accept_incoming")
        @JavascriptInterface fun rejectIncoming() = sendCommand("reject_incoming")
        @JavascriptInterface fun send(message: String) = sendCommand("send", message = message)
    }

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        webViewReady = false
        super.onDestroy()
    }
}
