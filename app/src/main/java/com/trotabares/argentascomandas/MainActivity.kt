package com.trotabares.argentascomandas

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val io: ExecutorService = Executors.newCachedThreadPool()
    private val bluetoothLock = Any()
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var pendingBluetoothAction: String? = null

    companion object {
        private const val REQUEST_BLUETOOTH_CONNECT = 701
        private const val SERVICE_NAME = "ArgentasComandas"
        private val SERVICE_UUID: UUID = UUID.fromString("7a1d6c20-4e7b-4c8d-9f12-6d4e7a9b2031")
        private const val MAX_MESSAGE_CHARS = 512_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
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

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensureBluetoothPermission(action: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetoothPermission()) return true
        pendingBluetoothAction = action
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH_CONNECT)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_CONNECT) return
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            when (action) {
                "server" -> startBluetoothServer()
                "refresh" -> refreshBondedDevices()
                else -> if (action?.startsWith("connect:") == true) connectBluetooth(action.removePrefix("connect:"))
            }
        } else {
            reportBluetoothState("ERROR", "Permiso Bluetooth denegado")
        }
    }

    private fun reportBluetoothState(state: String, message: String = state) {
        val s = JSONObject.quote(state)
        val m = JSONObject.quote(message)
        runOnUiThread {
            if (::webView.isInitialized) webView.evaluateJavascript("window.onBluetoothState && window.onBluetoothState($s,$m)", null)
        }
    }

    private fun reportBluetoothMessage(line: String) {
        val encoded = JSONObject.quote(line)
        runOnUiThread {
            if (::webView.isInitialized) webView.evaluateJavascript("window.onBluetoothMessage && window.onBluetoothMessage($encoded)", null)
        }
    }

    private fun closeConnection() {
        synchronized(bluetoothLock) {
            try { writer?.close() } catch (_: Exception) {}
            writer = null
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }
    }

    private fun attachConnection(connectedSocket: BluetoothSocket) {
        closeConnection()
        synchronized(bluetoothLock) { socket = connectedSocket }
        try {
            val out = BufferedWriter(OutputStreamWriter(connectedSocket.outputStream, Charsets.UTF_8))
            synchronized(bluetoothLock) { writer = out }
            reportBluetoothState("CONECTADO", "Conectado con ${connectedSocket.remoteDevice.name ?: connectedSocket.remoteDevice.address}")
            io.execute {
                try {
                    val input = BufferedReader(InputStreamReader(connectedSocket.inputStream, Charsets.UTF_8))
                    while (!Thread.currentThread().isInterrupted) {
                        val line = input.readLine() ?: break
                        if (line.length <= MAX_MESSAGE_CHARS) reportBluetoothMessage(line)
                    }
                } catch (_: Exception) {
                } finally {
                    if (socket === connectedSocket) {
                        closeConnection()
                        reportBluetoothState("DESCONECTADO", "Se perdió la conexión Bluetooth")
                    }
                }
            }
        } catch (e: Exception) {
            try { connectedSocket.close() } catch (_: Exception) {}
            reportBluetoothState("ERROR", "No se pudo abrir el canal Bluetooth: ${e.message ?: "error"}")
        }
    }

    private fun startBluetoothServer() {
        if (!ensureBluetoothPermission("server")) return
        val bt = adapter()
        if (bt == null) return reportBluetoothState("ERROR", "Este equipo no tiene Bluetooth")
        if (!bt.isEnabled) {
            reportBluetoothState("DESCONECTADO", "Activá Bluetooth en Android y volvé a intentar")
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            return
        }
        io.execute {
            try {
                try { serverSocket?.close() } catch (_: Exception) {}
                val server = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket = server
                reportBluetoothState("ESPERANDO", "Esperando al otro equipo. Ambos deben estar emparejados.")
                val accepted = server.accept()
                try { server.close() } catch (_: Exception) {}
                serverSocket = null
                attachConnection(accepted)
            } catch (e: Exception) {
                serverSocket = null
                reportBluetoothState("ERROR", "No se pudo iniciar la espera Bluetooth: ${e.message ?: "error"}")
            }
        }
    }

    private fun refreshBondedDevices() {
        if (!ensureBluetoothPermission("refresh")) return
        val bt = adapter()
        if (bt == null) return reportBluetoothState("ERROR", "Este equipo no tiene Bluetooth")
        try {
            val devices = bt.bondedDevices.map { device ->
                JSONObject().put("name", device.name ?: "Dispositivo").put("address", device.address)
            }
            val payload = JSONObject().put("devices", org.json.JSONArray(devices))
            val encoded = JSONObject.quote(payload.toString())
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('argentas-bluetooth',{detail:{type:'devices',payload:JSON.parse($encoded)}}))",
                    null
                )
            }
            reportBluetoothState(if (bt.isEnabled) "LISTO" else "DESCONECTADO", "Dispositivos emparejados: ${devices.size}")
        } catch (e: Exception) {
            reportBluetoothState("ERROR", "No se pudieron listar los dispositivos: ${e.message ?: "error"}")
        }
    }

    private fun connectBluetooth(address: String) {
        if (!ensureBluetoothPermission("connect:$address")) return
        val bt = adapter()
        if (bt == null) return reportBluetoothState("ERROR", "Este equipo no tiene Bluetooth")
        if (!bt.isEnabled) {
            reportBluetoothState("DESCONECTADO", "Activá Bluetooth en Android y volvé a intentar")
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            return
        }
        if (!Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$").matches(address)) {
            return reportBluetoothState("ERROR", "Dirección Bluetooth inválida")
        }
        io.execute {
            try {
                reportBluetoothState("CONECTANDO", "Conectando con $address")
                val device: BluetoothDevice = bt.getRemoteDevice(address)
                val client = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                bt.cancelDiscovery()
                client.connect()
                attachConnection(client)
            } catch (e: Exception) {
                reportBluetoothState("ERROR", "Falló la conexión: ${e.message ?: "error"}")
            }
        }
    }

    private fun sendBluetoothMessage(message: String) {
        if (message.length > MAX_MESSAGE_CHARS) return reportBluetoothState("ERROR", "Mensaje demasiado grande")
        io.execute {
            try {
                synchronized(bluetoothLock) {
                    val out = writer ?: throw IllegalStateException("No hay conexión Bluetooth")
                    out.write(message)
                    out.newLine()
                    out.flush()
                }
            } catch (e: Exception) {
                reportBluetoothState("ERROR", "No se pudo enviar: ${e.message ?: "error"}")
            }
        }
    }

    override fun onDestroy() {
        try { serverSocket?.close() } catch (_: Exception) {}
        closeConnection()
        io.shutdownNow()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("ArgentasNativeBluetooth")
            webView.destroy()
        }
        super.onDestroy()
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun startServer() { startBluetoothServer() }

        @JavascriptInterface
        fun refresh() { refreshBondedDevices() }

        @JavascriptInterface
        fun connect(address: String) { connectBluetooth(address) }

        @JavascriptInterface
        fun send(message: String) { sendBluetoothMessage(message) }

        @JavascriptInterface
        fun openExternalUrl(url: String) {
            val uri = try { Uri.parse(url) } catch (_: Exception) { return }
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "https" && scheme != "http") || uri.host.isNullOrBlank() || uri.userInfo != null) return
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
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
                    startActivity(Intent.createChooser(sendIntent, "Compartir Argentas"))
                } catch (_: Exception) {}
            }
        }
    }
}
