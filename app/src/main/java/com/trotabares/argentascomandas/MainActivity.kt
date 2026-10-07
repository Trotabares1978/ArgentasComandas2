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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val permissionRequest = 4107
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
        ContextCompat.startForegroundService(
            this,
            Intent(this, ArgentasConnectionService::class.java)
        )

        webView.loadUrl("file:///android_asset/index.html")
        ensurePermissions()
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_NETWORK_STATE,
            Manifest.permission.INTERNET
        )
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            list += Manifest.permission.ACCESS_COARSE_LOCATION
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list.distinct().toTypedArray()
    }

    private fun hasP2pPermission(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissions() {
        val needed = requiredPermissions().filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequest)
        } else {
            startConnectionService()
        }
    }

    private fun startConnectionService() {
        if (!hasP2pPermission()) return
        ContextCompat.startForegroundService(
            this,
            Intent(this, ArgentasConnectionService::class.java)
        )
        sendCommand("request_state")
    }

    private fun registerServiceReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra("type")) {
                    ArgentasConnectionService.EVENT_STATE -> {
                        val state = intent.getStringExtra(ArgentasConnectionService.EXTRA_STATE) ?: "DESCONECTADO"
                        val text = intent.getStringExtra(ArgentasConnectionService.EXTRA_TEXT) ?: state
                        dispatchJs(
                            "window.onBluetoothState&&window.onBluetoothState(" +
                                JSONObject.quote(state) + "," + JSONObject.quote(text) + ");"
                        )
                    }
                    ArgentasConnectionService.EVENT_MESSAGE -> {
                        val message = intent.getStringExtra(ArgentasConnectionService.EXTRA_MESSAGE) ?: return
                        dispatchJs(
                            "window.onBluetoothMessage&&window.onBluetoothMessage(" +
                                JSONObject.quote(message) + ");"
                        )
                    }
                    ArgentasConnectionService.EVENT_DEVICES -> {
                        val devices = intent.getStringExtra(ArgentasConnectionService.EXTRA_DEVICES) ?: "[]"
                        dispatchJs(
                            "window.dispatchEvent(new CustomEvent(" +
                                JSONObject.quote("argentas-bluetooth") +
                                ",{detail:{type:" + JSONObject.quote("devices") +
                                ",payload:{devices:" + devices + "}}}));"
                        )
                    }
                    ArgentasConnectionService.EVENT_AUTHORIZED -> {
                        dispatchJs(
                            "window.dispatchEvent(new CustomEvent('argentas-bluetooth'," +
                                "{detail:{type:'authorized'}}));"
                        )
                    }
                    ArgentasConnectionService.EVENT_DIAGNOSTIC -> {
                        val message = intent.getStringExtra(ArgentasConnectionService.EXTRA_MESSAGE) ?: return
                        dispatchJs(
                            "window.dispatchEvent(new CustomEvent('argentas-bluetooth'," +
                                "{detail:{type:'diagnostic',payload:{message:" + JSONObject.quote(message) + "}}}));"
                        )
                    }
                }
            }
        }

        val filter = IntentFilter(ArgentasConnectionService.ACTION_EVENT)
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

    private fun sendCommand(
        command: String,
        address: String? = null,
        message: String? = null
    ) {
        val intent = Intent(this, ArgentasConnectionService::class.java)
            .putExtra(ArgentasConnectionService.EXTRA_COMMAND, command)
        address?.let { intent.putExtra(ArgentasConnectionService.EXTRA_ADDRESS, it) }
        message?.let { intent.putExtra(ArgentasConnectionService.EXTRA_MESSAGE, it) }
        ContextCompat.startForegroundService(this, intent)
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface
        fun refresh() = sendCommand("refresh")

        @JavascriptInterface
        fun startServer() = sendCommand("start_server")

        @JavascriptInterface
        fun connect(address: String) = sendCommand("connect", address = address)

        @JavascriptInterface
        fun acceptIncoming() = sendCommand("accept_incoming")

        @JavascriptInterface
        fun rejectIncoming() = sendCommand("reject_incoming")

        @JavascriptInterface
        fun send(message: String) = sendCommand("send", message = message)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequest) {
            if (grantResults.isNotEmpty() && grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }) {
                startConnectionService()
            } else {
                dispatchJs(
                    "window.onBluetoothState&&window.onBluetoothState(" +
                        JSONObject.quote("ERROR") + "," +
                        JSONObject.quote("Argentas necesita permisos de Wi-Fi Direct para conectar los dos equipos") +
                        ");"
                )
            }
        }
    }

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        webViewReady = false
        super.onDestroy()
    }
}