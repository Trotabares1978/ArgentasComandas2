package com.trotabares.argentascomandas

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var webViewReady = false
    private val pendingEvents = mutableListOf<String>()
    private val io: ExecutorService = Executors.newCachedThreadPool()
    @Volatile private var bridgeSocket: Socket? = null
    @Volatile private var bridgeOut: OutputStream? = null
    @Volatile private var running = true
    @Volatile private var bridgeConnectorStarted = false

    companion object {
        private const val BRIDGE_HOST = "127.0.0.1"
        private const val BRIDGE_PORT = 45680
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                webViewReady = true
                connectToArgentasLink()
                flushPendingEvents()
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(NativeBluetoothBridge(), "ArgentasNativeBluetooth")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun connectToArgentasLink() {
        if (bridgeConnectorStarted) return
        bridgeConnectorStarted = true
        io.execute {
            while (running) {
                try {
                    if (bridgeSocket?.isConnected == true && bridgeSocket?.isClosed == false) {
                        dispatchState("CONECTADO", "ArgentasLink disponible")
                        Thread.sleep(1500)
                        continue
                    }
                    dispatchState("CONECTANDO", "Buscando el puente local de ArgentasLink…")
                    val s = Socket()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(BRIDGE_HOST, BRIDGE_PORT), 1200)
                    synchronized(this) {
                        bridgeSocket = s
                        bridgeOut = s.getOutputStream()
                    }
                    dispatchState("CONECTADO", "ArgentasLink disponible")
                    io.execute { readBridge(s) }
                    while (running && bridgeSocket === s && !s.isClosed) Thread.sleep(1000)
                } catch (_: Exception) {
                    closeBridge()
                    dispatchState("DESCONECTADO", "ArgentasLink no está abierto. Tocá REINTENTAR para abrirlo.")
                    Thread.sleep(1500)
                }
            }
        }
    }

    private fun readBridge(s: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            while (running && bridgeSocket === s && !s.isClosed) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("APP|") -> dispatchMessage(line.substring(4))
                    line.startsWith("LINK_STATE|") -> {
                        val state = line.substringAfter("LINK_STATE|")
                        if (state == "CONECTADO") dispatchState("CONECTADO", "ArgentasLink conectado con el otro equipo")
                        else dispatchState("DESCONECTADO", "ArgentasLink esperando al otro equipo")
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (bridgeSocket === s) {
                closeBridge()
                dispatchState("DESCONECTADO", "ArgentasLink no está disponible")
            }
        }
    }

    private fun sendRawLocal(message: String) {
        io.execute {
            try {
                val out = bridgeOut ?: return@execute
                synchronized(out) {
                    out.write((message.replace("\r", "").replace("\n", "") + "\n").toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
                closeBridge()
            }
        }
    }

    private fun openArgentasLink() {
        try {
            val launch = packageManager.getLaunchIntentForPackage("com.trotabares.argentaslink")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                dispatchState("CONECTANDO", "Abriendo ArgentasLink…")
            } else {
                dispatchState("DESCONECTADO", "ArgentasLink no está instalado en este equipo")
            }
        } catch (_: ActivityNotFoundException) {
            dispatchState("DESCONECTADO", "No se pudo abrir ArgentasLink")
        }
    }

    private fun reconnectAndOpenArgentasLink() {
        closeBridge()
        dispatchState("CONECTANDO", "Abriendo ArgentasLink y conectando…")
        openArgentasLink()
    }

    private fun closeBridge() {
        try { bridgeSocket?.close() } catch (_: Exception) {}
        bridgeSocket = null
        bridgeOut = null
    }

    private fun dispatchMessage(message: String) {
        dispatchJs("window.onBluetoothMessage&&window.onBluetoothMessage(" +
            org.json.JSONObject.quote(message) + ");")
    }

    private fun dispatchState(state: String, text: String) {
        dispatchJs("window.onBluetoothState&&window.onBluetoothState(" +
            org.json.JSONObject.quote(state) + "," +
            org.json.JSONObject.quote(text) + ");")
    }

    private fun dispatchJs(script: String) {
        runOnUiThread {
            if (!webViewReady) {
                synchronized(pendingEvents) {
                    pendingEvents.add(script)
                    if (pendingEvents.size > 100) pendingEvents.removeAt(0)
                }
            } else {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun flushPendingEvents() {
        val events = synchronized(pendingEvents) {
            val copy = pendingEvents.toList()
            pendingEvents.clear()
            copy
        }
        events.forEach { webView.evaluateJavascript(it, null) }
    }

    inner class NativeBluetoothBridge {
        @JavascriptInterface fun refresh() = reconnectAndOpenArgentasLink()
        @JavascriptInterface fun startNearby() = reconnectAndOpenArgentasLink()
        @JavascriptInterface fun stopNearby() {}
        @JavascriptInterface fun startServer() = reconnectAndOpenArgentasLink()
        @JavascriptInterface fun connect(address: String) = reconnectAndOpenArgentasLink()
        @JavascriptInterface fun acceptIncoming() = reconnectAndOpenArgentasLink()
        @JavascriptInterface fun rejectIncoming() {}
        @JavascriptInterface fun send(message: String) = sendRawLocal("APP|$message")
    }

    override fun onDestroy() {
        running = false
        bridgeConnectorStarted = false
        closeBridge()
        io.shutdownNow()
        webViewReady = false
        super.onDestroy()
    }
}