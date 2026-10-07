package com.trotabares.argentascomandas

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import java.util.Locale
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

class ArgentasConnectionService : Service() {
    companion object {
        const val ACTION_COMMAND = "com.trotabares.argentascomandas.CONNECTION_COMMAND"
        const val ACTION_EVENT = "com.trotabares.argentascomandas.CONNECTION_EVENT"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_STATE = "state"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DEVICES = "devices"
        const val EVENT_STATE = "state"
        const val EVENT_MESSAGE = "message"
        const val EVENT_DEVICES = "devices"
        const val EVENT_AUTHORIZED = "authorized"

        private const val CHANNEL_ID = "argentas_connection"
        private const val NOTIFICATION_ID = 8988
        private const val WIFI_PORT = 8988
        private const val PREFS = "argentas_p2p"
        private const val LAST_PEER_KEY = "last_peer_address"
    }

    private val io = Executors.newCachedThreadPool()
    private val writer = Executors.newSingleThreadExecutor()
    private val reconnect: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val epoch = AtomicLong(0L)

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val devices = linkedMapOf<String, String>()
    private val serviceDevices = linkedMapOf<String, String>()
    private val serviceAddresses = mutableSetOf<String>()
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var connected = false
    @Volatile private var tcpConnecting = false
    @Volatile private var groupFormed = false
    @Volatile private var stopping = false
    @Volatile private var reconnectScheduled = false
    @Volatile private var currentState = "DESCONECTADO"
    @Volatile private var currentText = "Conexión directa no iniciada"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        registerP2P()
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerP2P() {
        if (!hasPermission()) {
            state("ERROR", "Faltan permisos para usar Wi-Fi Direct")
            return
        }

        p2p = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
        val manager = p2p ?: run {
            state("ERROR", "Este dispositivo no permite Wi-Fi Direct")
            return
        }

        initializeP2PChannel(manager)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED

                        if (!enabled) {
                            closeTransport()
                            state("ERROR", "Activá Wi-Fi para usar la conexión directa")
                        } else if (connected && socketIsAlive()) {
                            state("CONECTADO", "Conectado directamente con otro Argentas")
                        } else {
                            state("LISTO", "Wi-Fi Direct está disponible")
                            scheduleReconnect(800)
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        state("LISTO", "Listo para conexión directa entre Argentas")
        setupServiceDiscovery()
        requestConnectionInfo()
    }

    private fun socketIsAlive(): Boolean {
        val s = socket
        return s != null && s.isConnected && !s.isClosed
    }

    @SuppressLint("MissingPermission")
    private fun initializeP2PChannel(manager: WifiP2pManager) {
        if (stopping) return
        channel = manager.initialize(
            this,
            mainLooper,
            object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    channel = null
                    closeTransport()
                    state("ERROR", "Wi-Fi Direct perdió el canal; reiniciando el enlace…")
                    reconnectScheduled = false
                    reconnect.schedule({
                        if (!stopping) {
                            initializeP2PChannel(manager)
                            setupServiceDiscovery()
                            requestConnectionInfo()
                        }
                    }, 1000, TimeUnit.MILLISECONDS)
                }
            }
        )
    }

    private fun locationModeEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        return lm?.isLocationEnabled == true
    }

    @SuppressLint("MissingPermission")
    private fun setupServiceDiscovery() {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        if (!locationModeEnabled()) {
            state("ERROR", "Activá Ubicación para que Android permita el descubrimiento Wi-Fi Direct")
            return
        }

        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
            val app = record["app"]?.lowercase(Locale.ROOT)
            if (app != "argentas") return@DnsSdTxtRecordListener
            val address = device.deviceAddress
            if (address.isNullOrBlank()) return@DnsSdTxtRecordListener
            val name = record["name"]?.takeIf { it.isNotBlank() }
                ?: device.deviceName.takeIf { it.isNotBlank() }
                ?: "Argentas"
            serviceAddresses.add(address)
            serviceDevices[address] = name
            devices[address] = name
            publishDevices()
            val last = prefs.getString(LAST_PEER_KEY, null)
            if (!connected && !groupFormed && address == last) {
                connectP2P(address, automatic = true)
            }
        }

        val serviceListener = WifiP2pManager.DnsSdServiceResponseListener {
                _, registrationType, device ->
            if (registrationType != "_argentas._tcp") return@DnsSdServiceResponseListener
            val address = device.deviceAddress
            if (address.isNullOrBlank()) return@DnsSdServiceResponseListener
            val name = device.deviceName.takeIf { it.isNotBlank() } ?: "Argentas"
            serviceAddresses.add(address)
            serviceDevices[address] = name
            devices[address] = name
            publishDevices()
            val last = prefs.getString(LAST_PEER_KEY, null)
            if (!connected && !groupFormed && address == last) {
                connectP2P(address, automatic = true)
            }
        }

        manager.setDnsSdResponseListeners(ch, serviceListener, txtListener)

        val record = mapOf(
            "app" to "argentas",
            "name" to "Argentas",
            "version" to "1"
        )
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            "Argentas",
            "_argentas._tcp",
            record
        )
        localService = info

        manager.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        installServiceRequest(manager, ch)
                    }
                    override fun onFailure(reason: Int) {
                        state("ERROR", "No se pudo publicar el servicio Argentas ($reason)")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                manager.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        installServiceRequest(manager, ch)
                    }
                    override fun onFailure(addReason: Int) {
                        state("ERROR", "No se pudo publicar el servicio Argentas ($addReason)")
                    }
                })
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun installServiceRequest(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        val request = WifiP2pDnsSdServiceRequest.newInstance("_argentas._tcp")
        serviceRequest = request
        manager.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                addServiceRequest(manager, ch, request)
            }
            override fun onFailure(@Suppress("UNUSED_PARAMETER") reason: Int) {
                addServiceRequest(manager, ch, request)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun addServiceRequest(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        request: WifiP2pDnsSdServiceRequest
    ) {
        manager.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                discoverServices(manager, ch)
            }
            override fun onFailure(reason: Int) {
                state("ERROR", "No se pudo preparar la búsqueda de Argentas ($reason)")
                scheduleReconnect(3000)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        serviceAddresses.clear()
        serviceDevices.clear()
        devices.clear()
        publishDevices()
        state("BUSCANDO", "Buscando otros Argentas por Wi-Fi Direct…")
        manager.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                state("BUSCANDO", "Buscando otros Argentas…")
            }
            override fun onFailure(reason: Int) {
                state("ERROR", "No se pudo buscar Argentas por Wi-Fi Direct ($reason)")
                scheduleReconnect(3000)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        // Intencionalmente no mostramos requestPeers(): devuelve dispositivos Wi-Fi
        // genéricos (TV, impresoras, etc.). La lista visible se alimenta exclusivamente
        // del servicio DNS-SD _argentas._tcp.
        publishDevices()
    }

    @SuppressLint("MissingPermission")
    private fun discover() {
        val manager = p2p ?: return
        if (!hasPermission()) return
        if (channel == null) {
            initializeP2PChannel(manager)
            scheduleReconnect(500)
            return
        }
        setupServiceDiscovery()
    }

    private fun publishDevices() {
        val array = JSONArray()
        val preferred = prefs.getString(LAST_PEER_KEY, null)

        devices.entries
            .sortedWith(compareBy({ it.key != preferred }, { it.value.lowercase() }))
            .forEach { (address, name) ->
                array.put(
                    JSONObject()
                        .put("name", name)
                        .put("address", address)
                )
            }

        event(EVENT_DEVICES, devices = array.toString())
    }

    @SuppressLint("MissingPermission")
    private fun connectP2P(address: String, automatic: Boolean = false) {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (address.isBlank() || connected) return
        if (groupFormed) {
            requestConnectionInfo()
            return
        }

        try { manager.cancelConnect(ch, null) } catch (_: Exception) {}

        prefs.edit().putString(LAST_PEER_KEY, address).apply()
        closeTransport()
        state("CONECTANDO", if (automatic) {
            "Reconectando con el otro Argentas…"
        } else {
            "Conectando directamente con el dispositivo elegido…"
        })

        val config = WifiP2pConfig().apply {
            deviceAddress = address
            groupOwnerIntent = 7
        }

        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                state("CONECTANDO", "Wi-Fi Direct está formando el enlace…")
            }

            override fun onFailure(reason: Int) {
                state("DESCONECTADO", "Wi-Fi Direct no pudo conectar ($reason)")
                scheduleReconnect(2000)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun rememberPeerFromGroup(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        try {
            manager.requestGroupInfo(ch) { group ->
                val peer = if (group.isGroupOwner) {
                    group.clientList.firstOrNull()
                } else {
                    group.owner
                }
                val address = peer?.deviceAddress
                if (!address.isNullOrBlank()) {
                    prefs.edit().putString(LAST_PEER_KEY, address).apply()
                }
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!hasPermission()) return

        manager.requestConnectionInfo(ch) { info: WifiP2pInfo ->
            if (!info.groupFormed) {
                val wasGroup = groupFormed
                groupFormed = false
                if (connected) {
                    closeTransport()
                    state("DESCONECTADO", "La conexión directa se cerró; buscando reconexión…")
                } else if (wasGroup) {
                    state("BUSCANDO", "El enlace Wi-Fi Direct se perdió; buscando reconexión…")
                }
                scheduleReconnect(800)
                return@requestConnectionInfo
            }

            groupFormed = true
            rememberPeerFromGroup(manager, ch)

            if (connected && socketIsAlive()) {
                state("CONECTADO", "Conectado directamente con otro Argentas")
                return@requestConnectionInfo
            }

            if (info.isGroupOwner) {
                startServer()
            } else {
                info.groupOwnerAddress?.let { connectSocketWithRetry(it) }
            }
        }
    }

    private fun startServer() {
        if (server != null || stopping) return
        val myEpoch = epoch.get()

        io.execute {
            try {
                val ss = ServerSocket(WIFI_PORT)
                ss.reuseAddress = true
                server = ss
                state("CONECTANDO", "Enlace creado. Esperando al otro Argentas…")

                while (!ss.isClosed && !stopping && epoch.get() == myEpoch) {
                    try {
                        val incoming = ss.accept()
                        if (connected) {
                            try { incoming.close() } catch (_: Exception) {}
                        } else {
                            attachSocket(incoming)
                        }
                    } catch (_: Exception) {
                        if (ss.isClosed || stopping) break
                    }
                }
            } catch (_: Exception) {
                if (!stopping && !connected) {
                    state("DESCONECTADO", "No se pudo abrir el canal local de datos")
                    scheduleReconnect(2000)
                }
            } finally {
                if (server?.isClosed != false) server = null
            }
        }
    }

    private fun connectSocketWithRetry(address: InetAddress) {
        if (connected || tcpConnecting) return
        tcpConnecting = true
        val myEpoch = epoch.get()

        io.execute {
            var established = false
            try {
                while (
                    !connected &&
                    groupFormed &&
                    epoch.get() == myEpoch &&
                    !stopping
                ) {
                    try {
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        s.connect(InetSocketAddress(address, WIFI_PORT), 1500)
                        attachSocket(s)
                        established = true
                    } catch (_: Exception) {
                        try { TimeUnit.MILLISECONDS.sleep(1000) }
                        catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            } finally {
                tcpConnecting = false
            }

            if (!established && !connected && !groupFormed && !stopping) {
                scheduleReconnect(1000)
            }
        }
    }

    private fun attachSocket(s: Socket) {
        if (connected) {
            try { s.close() } catch (_: Exception) {}
            return
        }

        try {
            s.tcpNoDelay = true
            s.keepAlive = true
        } catch (_: Exception) {}
        socket = s
        connected = true
        reconnectScheduled = false
        state("CONECTADO", "Conectado directamente con otro Argentas")
        event(EVENT_AUTHORIZED)

        io.execute {
            try {
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), Charsets.UTF_8)
                )
                while (!stopping) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) event(EVENT_MESSAGE, message = line)
                }
            } catch (_: Exception) {
            } finally {
                if (socket === s) {
                    closeTransport()
                    if (!stopping) {
                        state("DESCONECTADO", "La conexión perdió el canal; intentando restablecerla…")
                        scheduleReconnect(500)
                    }
                } else {
                    try { s.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun sendMessage(message: String) {
        val s = socket
        if (!connected || s == null || s.isClosed) {
            state("DESCONECTADO", "No hay conexión directa activa")
            return
        }

        writer.execute {
            try {
                val clean = message.replace("\r", "").replace("\n", "") + "\n"
                val out = s.getOutputStream()
                synchronized(out) {
                    out.write(clean.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (_: Exception) {
                if (socket === s) {
                    closeTransport()
                    state("DESCONECTADO", "La conexión perdió el canal; intentando restablecerla…")
                    scheduleReconnect(500)
                }
            }
        }
    }

    private fun closeTransport() {
        epoch.incrementAndGet()
        connected = false
        tcpConnecting = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun scheduleReconnect(delayMs: Long = 1500) {
        if (stopping || reconnectScheduled || connected) return
        if (prefs.getString(LAST_PEER_KEY, null).isNullOrBlank()) return

        reconnectScheduled = true
        reconnect.schedule({
            reconnectScheduled = false
            if (!stopping && !connected) {
                if (groupFormed) {
                    requestConnectionInfo()
                } else {
                    discover()
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun handleCommand(intent: Intent) {
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            "refresh" -> discover()
            "start_server", "accept_incoming", "request_state" -> requestConnectionInfo()
            "connect" -> intent.getStringExtra(EXTRA_ADDRESS)?.let { connectP2P(it) }
            "reject_incoming" -> closeTransport()
            "send" -> intent.getStringExtra(EXTRA_MESSAGE)?.let { sendMessage(it) }
        }
    }

    private fun event(
        type: String,
        stateValue: String? = null,
        textValue: String? = null,
        message: String? = null,
        devices: String? = null
    ) {
        val intent = Intent(ACTION_EVENT).setPackage(packageName)
            .putExtra("type", type)
        stateValue?.let { intent.putExtra(EXTRA_STATE, it) }
        textValue?.let { intent.putExtra(EXTRA_TEXT, it) }
        message?.let { intent.putExtra(EXTRA_MESSAGE, it) }
        devices?.let { intent.putExtra(EXTRA_DEVICES, it) }
        sendBroadcast(intent)
    }

    private fun state(value: String, text: String) {
        currentState = value
        currentText = text
        event(EVENT_STATE, stateValue = value, textValue = text)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Conexión de Argentas",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene disponible la conexión directa entre los dos Argentas."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Argentas")
            .setContentText("Conexión directa entre los dos equipos")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (p2p == null && hasPermission()) {
            registerP2P()
        }
        if (intent != null) handleCommand(intent)
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        closeTransport()
        reconnect.shutdownNow()
        writer.shutdownNow()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}