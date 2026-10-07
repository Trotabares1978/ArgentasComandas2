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
import android.net.wifi.WifiManager
import java.util.Locale
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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
        const val EVENT_DIAGNOSTIC = "diagnostic"

        private const val CHANNEL_ID = "argentas_connection"
        private const val NOTIFICATION_ID = 8988
        private const val WIFI_PORT = 8988
        private const val DISCOVERY_INTERVAL_MS = 5000L
        private const val SERVICE_STALE_MS = 15000L
        private const val SERVICE_DISCOVERY_INTERVAL_MS = 15000L
        private const val HEARTBEAT_INTERVAL_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        private const val SOCKET_READ_TIMEOUT_MS = 10000
        private const val PREFS = "argentas_p2p"
        private const val LAST_PEER_KEY = "last_peer_address"
        private const val DEVICE_ID_KEY = "device_id"
        private const val ROLE_CAJA = "caja"
        private const val ROLE_COCINA = "cocina"
    }

    private val io = Executors.newCachedThreadPool()
    private val writer = Executors.newSingleThreadExecutor()
    private val reconnect: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val epoch = AtomicLong(0L)

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val deviceId: String by lazy {
        prefs.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }

    private fun deviceRole(): String {
        // Argentas-Caja runs on the fixed tablet. Android tablets normally
        // expose a smallest width of 600dp or more; the Moto G52/phones do not.
        return if (resources.configuration.smallestScreenWidthDp >= 600) ROLE_CAJA else ROLE_COCINA
    }

    private fun isCajaRegistradora(): Boolean = deviceRole() == ROLE_CAJA
    private fun diagnosticRole() {
        val sw = resources.configuration.smallestScreenWidthDp
        diagnostic("rol=" + deviceRole() + "; smallestWidthDp=" + sw + "; cajaFija=" + isCajaRegistradora())
    }


    private var p2p: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val devices = linkedMapOf<String, String>()
    private val serviceDevices = linkedMapOf<String, String>()
    private val serviceAddresses = mutableSetOf<String>()
    private val serviceDeviceIds = mutableMapOf<String, String>()
    private val serviceSeenAt = mutableMapOf<String, Long>()
    private var localService: WifiP2pDnsSdServiceInfo? = null
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null
    private var lastPeerCount = -1
    @Volatile private var lastServiceDiscoveryAt = 0L
    @Volatile private var serviceReady = false
    @Volatile private var peerDiscoveryRunning = false

    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var connected = false
    @Volatile private var tcpConnecting = false
    @Volatile private var groupFormed = false
    @Volatile private var stopping = false
    @Volatile private var reconnectScheduled = false
    private var discoveryFuture: ScheduledFuture<*>? = null
    private var heartbeatFuture: ScheduledFuture<*>? = null
    @Volatile private var authorized = false
    @Volatile private var lastHeartbeatAckAt = 0L
    @Volatile private var reconnectAttempt = 0
    @Volatile private var currentState = "DESCONECTADO"
    @Volatile private var currentText = "Conexión directa no iniciada"
    private enum class Transport { WIFI_DIRECT, NEARBY }

    @Volatile private var transport = Transport.WIFI_DIRECT
    private var nearbyManager: ArgentasNearbyManager? = null
    private var nearbyFallbackFuture: ScheduledFuture<*>? = null
    private var nearbyFallbackScheduled = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        acquireConnectionWakeLock()
        diagnostic("energia=PARTIAL_WAKE_LOCK; servicio de conexión protegido")
        registerP2P()
        nearbyManager = ArgentasNearbyManager(
            this,
            deviceId,
            deviceRole(),
            onMessage = { msg -> handleIncomingLine(msg) },
            onConnected = {
                if (connected) {
                    diagnostic("Nearby=DESCARTADO; Wi-Fi Direct ya estaba conectado")
                    nearbyManager?.stop()
                } else {
                    transport = Transport.NEARBY
                    connected = true
                    authorized = false
                    lastHeartbeatAckAt = System.currentTimeMillis()
                    reconnectScheduled = false
                    nearbyFallbackFuture?.cancel(false)
                    state("CONECTANDO", "Nearby creó el canal; verificando Argentas…")
                    sendTransportHello()
                    startHeartbeat()
                }
            },
            onDisconnected = {
                if (transport == Transport.NEARBY && !stopping) {
                    connected = false
                    authorized = false
                    lastHeartbeatAckAt = 0L
                    transport = Transport.WIFI_DIRECT
                    state("DESCONECTADO", "Nearby perdió el enlace; volviendo a buscar…")
                    diagnostic("Nearby=DESCONECTADO; no se usa como fallback automático")
                }
            },
            diagnostic = { msg -> diagnostic(msg) }
        )
    }

    private fun acquireConnectionWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Argentas::ConnectionService"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            diagnostic("energia=WAKE_LOCK_FALLO;" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun releaseConnectionWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
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
                            serviceReady = false
                            peerDiscoveryRunning = false
                            lastServiceDiscoveryAt = 0L
                            closeTransport()
                            state("ERROR", "Activá Wi-Fi para usar la conexión directa")
                        } else if (connected && socketIsAlive()) {
                            state("CONECTADO", "Conectado directamente con otro Argentas")
                        } else {
                            state("LISTO", "Wi-Fi Direct está disponible")
                            serviceReady = false
                            scheduleReconnect(800)
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()

                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                        peerDiscoveryRunning = intent.getIntExtra(
                            WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED
                        ) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        state(
            "LISTO",
            if (isCajaRegistradora())
                "Argentas Caja: preparando Group Owner fijo…"
            else
                "Argentas Cocina: buscando la Caja…"
        )
        diagnosticRole()
        setupServiceDiscovery()
        startDiscoveryLoop()
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
                    serviceReady = false
                    peerDiscoveryRunning = false
                    lastServiceDiscoveryAt = 0L
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

    private fun wifiEnabled(): Boolean {
        val wm = getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wm?.isWifiEnabled == true
    }

    private fun startDiscoveryLoop() {
        discoveryFuture?.cancel(false)
        // Wi-Fi Direct sigue siendo el primer camino. Si el fabricante no
        // entrega DNS-SD, Nearby P2P entra automáticamente como segundo
        // camino directo, sin Internet, router ni hotspot.
        scheduleNearbyFallback(8000)
        if (isCajaRegistradora()) {
            diagnostic("rol=caja; la tablet será Group Owner fijo; descubrimiento=pasivo")
            discoveryFuture = reconnect.scheduleAtFixedRate({
                if (!stopping && !connected) ensureCajaGroup()
            }, 300, DISCOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } else {
            diagnostic("rol=cocina; buscando únicamente la Caja Argentas; iniciador=activo")
            discoveryFuture = reconnect.scheduleAtFixedRate({
                if (!stopping && !connected) {
                    if (groupFormed) {
                        ensureCocinaSinGrupo()
                    } else {
                        discover()
                    }
                }
            }, 500, DISCOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureCocinaSinGrupo() {
        if (isCajaRegistradora() || stopping || connected) return
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!hasPermission()) return

        manager.requestConnectionInfo(ch) { info ->
            if (!info.groupFormed) {
                groupFormed = false
                diagnostic("cocina=LIBRE; sin grupo P2P; iniciando búsqueda de Caja")
                discover()
                return@requestConnectionInfo
            }

            diagnostic("cocina=GRUPO_P2P_EXISTENTE; GO=" + info.isGroupOwner + "; eliminando grupo previo")
            try {
                manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        groupFormed = false
                        serviceReady = false
                        lastServiceDiscoveryAt = 0L
                        diagnostic("cocina=GRUPO_PREVIO_ELIMINADO; buscando Caja")
                        scheduleReconnect(300)
                    }

                    override fun onFailure(reason: Int) {
                        diagnostic("cocina=REMOVE_GROUP_FALLO(" + reason + "); reintentando")
                        groupFormed = false
                        scheduleReconnect(1500)
                    }
                })
            } catch (e: Exception) {
                diagnostic("cocina=REMOVE_GROUP_EXCEPCION;" + (e.message ?: "sin detalle"))
                groupFormed = false
                scheduleReconnect(1500)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureCajaGroup() {
        if (!isCajaRegistradora() || stopping) return
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!hasPermission() || !wifiEnabled() || !locationModeEnabled()) return

        manager.requestConnectionInfo(ch) { info ->
            if (info.groupFormed) {
                groupFormed = true
                if (!info.isGroupOwner) {
                    diagnostic("caja=GRUPO_EXISTENTE_PERO_NO_GO; recuperando rol Group Owner")
                    try {
                        manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                groupFormed = false
                                scheduleCajaGroupCreate(500)
                            }
                            override fun onFailure(reason: Int) {
                                diagnostic("removeGroup=FALLO(" + reason + "); reintentando createGroup")
                                scheduleCajaGroupCreate(1500)
                            }
                        })
                    } catch (_: Exception) {
                        scheduleCajaGroupCreate(1500)
                    }
                } else {
                    diagnostic("caja=GROUP_OWNER_OK")
                    requestGroupMembershipDiagnostic(manager, ch)
                    requestConnectionInfo()
                }
                return@requestConnectionInfo
            }
            scheduleCajaGroupCreate(0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleCajaGroupCreate(delayMs: Long) {
        if (stopping || !isCajaRegistradora()) return
        reconnect.schedule({
            if (!stopping) createCajaGroup()
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    @SuppressLint("MissingPermission")
    private fun createCajaGroup() {
        if (!isCajaRegistradora() || stopping) return
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!hasPermission() || !wifiEnabled() || !locationModeEnabled()) return

        diagnostic("caja=createGroup; solicitando Group Owner fijo")
        manager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                groupFormed = true
                reconnectAttempt = 0
                state("BUSCANDO", "Caja lista: esperando celulares Cocina Argentas…")
                diagnostic("caja=createGroup=OK; esperando clientes")
                requestConnectionInfo()
            }

            override fun onFailure(reason: Int) {
                diagnostic("caja=createGroup=FALLO(" + reason + ")")
                if (reason == WifiP2pManager.BUSY) {
                    scheduleCajaGroupCreate(1500)
                } else {
                    scheduleCajaGroupCreate(2500)
                }
            }
        })
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
        if (!wifiEnabled()) {
            state("ERROR", "Activá Wi-Fi para buscar el otro Argentas")
            return
        }
        if (!locationModeEnabled()) {
            state("ERROR", "Activá Ubicación para que Android permita el descubrimiento Wi-Fi Direct")
            return
        }

        val txtListener = WifiP2pManager.DnsSdTxtRecordListener { _, record, device ->
            val app = record["app"]?.lowercase(Locale.ROOT)
            if (app != "argentas") return@DnsSdTxtRecordListener
            val address = device.deviceAddress
            if (address.isNullOrBlank()) return@DnsSdTxtRecordListener
            val remoteId = record["deviceId"]?.takeIf { it.isNotBlank() }
            if (remoteId == deviceId) return@DnsSdTxtRecordListener
            val remoteRole = record["role"]?.lowercase(Locale.ROOT)
            if (!isCajaRegistradora() && remoteRole != ROLE_CAJA) return@DnsSdTxtRecordListener
            if (isCajaRegistradora() && remoteRole != ROLE_COCINA) return@DnsSdTxtRecordListener
            serviceDeviceIds[address] = remoteId ?: ""
            serviceSeenAt[address] = System.currentTimeMillis()
            val name = record["name"]?.takeIf { it.isNotBlank() }
                ?: device.deviceName.takeIf { it.isNotBlank() }
                ?: if (remoteRole == ROLE_CAJA) "Argentas Caja" else "Argentas Cocina"
            serviceAddresses.add(address)
            serviceDevices[address] = name
            devices[address] = name
            publishDevices()
            val last = prefs.getString(LAST_PEER_KEY, null)
            if (!connected && !groupFormed) {
                // La primera conexión no puede depender de LAST_PEER_KEY:
                // en una instalación nueva todavía no existe un peer recordado.
                // El TXT ya verificó que el servicio pertenece a Argentas y que
                // el remoto es la Caja, así que Cocina puede iniciar el enlace.
                val preferred = address == last
                diagnostic(
                    "caja_detectada=SI; direccion=" + address +
                        "; preferida=" + preferred + "; iniciando conexión"
                )
                connectP2P(address, automatic = true)
            }
        }

        val serviceListener = WifiP2pManager.DnsSdServiceResponseListener {
                instanceName, registrationType, device ->
            if (registrationType != "_argentas._tcp") return@DnsSdServiceResponseListener
            val address = device.deviceAddress
            if (address.isNullOrBlank()) return@DnsSdServiceResponseListener
            if (address == "02:00:00:00:00:00") return@DnsSdServiceResponseListener
            diagnostic(
                "DNS_SD_SERVICIO; instancia=" + instanceName +
                    "; dispositivo=" + (device.deviceName.takeIf { it.isNotBlank() } ?: "sin nombre") +
                    "; direccion=" + address
            )
            serviceSeenAt[address] = System.currentTimeMillis()
            val name = device.deviceName.takeIf { it.isNotBlank() } ?: "Argentas"
            serviceAddresses.add(address)
            serviceDevices[address] = name
            devices[address] = name
            publishDevices()
            if (!connected && !groupFormed && serviceDeviceIds[address].isNullOrBlank()) {
                // La respuesta de servicio llegó pero todavía no tenemos el TXT.
                // Esperamos el TXT para validar app=argentas y role=caja.
                diagnostic("DNS_SD_TXT_PENDIENTE; esperando datos de la Caja")
            }
        }

        manager.setDnsSdResponseListeners(ch, serviceListener, txtListener)

        val record = mapOf(
            "app" to "argentas",
            "name" to if (isCajaRegistradora()) "Argentas Caja" else "Argentas Cocina",
            "version" to "3",
            "deviceId" to deviceId,
            "role" to deviceRole(),
            "port" to WIFI_PORT.toString()
        )
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            "Argentas-$deviceId",
            "_argentas._tcp",
            record
        )
        localService = info

        manager.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        diagnostic("addLocalService=OK; servicio=_argentas._tcp; role=" + deviceRole())
                        if (isCajaRegistradora()) {
                            state("BUSCANDO", "Caja lista: esperando celulares Cocina Argentas…")
                            requestConnectionInfo()
                        } else {
                            installServiceRequest(manager, ch)
                        }
                    }
                    override fun onFailure(reason: Int) {
                        diagnostic("addLocalService=FALLO(" + reason + ")")
                        state("ERROR", "No se pudo publicar el servicio Argentas (" + reason + ")")
                    }
                })
            }
            override fun onFailure(reason: Int) {
                manager.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        diagnostic("addLocalService=OK (fallback); servicio=_argentas._tcp; role=" + deviceRole())
                        if (isCajaRegistradora()) {
                            state("BUSCANDO", "Caja lista: esperando celulares Cocina Argentas…")
                            requestConnectionInfo()
                        } else {
                            installServiceRequest(manager, ch)
                        }
                    }
                    override fun onFailure(addReason: Int) {
                        diagnostic("addLocalService=FALLO(" + addReason + ") (fallback)")
                        state("ERROR", "No se pudo publicar el servicio Argentas (" + addReason + ")")
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
        // Igual que el ejemplo oficial de Android: pedir descubrimiento Bonjour
        // general y filtrar _argentas._tcp en DnsSdServiceResponseListener.
        // Esto evita que algunos fabricantes rechacen un ServiceRequest
        // demasiado específico.
        val request = WifiP2pDnsSdServiceRequest.newInstance()
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
                serviceReady = true
                diagnostic("addServiceRequest=OK; solicitud DNS-SD general; filtro=_argentas._tcp")
                startPeerDiscovery(manager, ch)
                discoverServices(manager, ch)
            }
            override fun onFailure(reason: Int) {
                diagnostic("addServiceRequest=FALLO(" + reason + ")")
                startPeerDiscovery(manager, ch)
                state("ERROR", "No se pudo preparar la búsqueda de Argentas (" + reason + "); reintentando…")
                scheduleReconnect(1500)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startPeerDiscovery(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        if (isCajaRegistradora()) return
        if (transport == Transport.NEARBY && connected) return
        if (!wifiEnabled() || !locationModeEnabled() || peerDiscoveryRunning) return
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                peerDiscoveryRunning = true
                diagnostic("discoverPeers=OK; inicio de escaneo P2P")
            }
            override fun onFailure(reason: Int) {
                diagnostic("discoverPeers=FALLO(" + reason + ")")
                if (reason == WifiP2pManager.BUSY) return
                peerDiscoveryRunning = false
                scheduleReconnect(1500)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun discoverServices(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        if (isCajaRegistradora()) return
        if (transport == Transport.NEARBY && connected) return
        val now = System.currentTimeMillis()
        if (now - lastServiceDiscoveryAt < SERVICE_DISCOVERY_INTERVAL_MS) return
        lastServiceDiscoveryAt = now
        val stale = serviceSeenAt.filterValues { now - it > SERVICE_STALE_MS }.keys.toList()
        stale.forEach {
            serviceSeenAt.remove(it)
            serviceAddresses.remove(it)
            serviceDevices.remove(it)
            serviceDeviceIds.remove(it)
            devices.remove(it)
        }
        publishDevices()
        state("BUSCANDO", "Buscando otros Argentas por Wi-Fi Direct…")
        manager.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                state("BUSCANDO", "Buscando otros Argentas…")
                diagnostic("discoverServices=OK; peers=" + lastPeerCount)
            }
            override fun onFailure(reason: Int) {
                lastServiceDiscoveryAt = 0L
                diagnostic("discoverServices=FALLO(" + reason + "); peers=" + lastPeerCount)
                state("ERROR", "No se pudo buscar Argentas por Wi-Fi Direct (" + reason + ")")
                scheduleReconnect(3000)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val manager = p2p ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        manager.requestPeers(ch) { list ->
            val peers = list.deviceList.toList()
            lastPeerCount = peers.size
            val names = peers.take(8).joinToString(" | ") {
                val name = it.deviceName.takeIf { n -> n.isNotBlank() } ?: "sin nombre"
                name + " [" + it.deviceAddress + "]"
            }
            diagnostic(
                "requestPeers: " + peers.size + " dispositivo(s)" +
                if (names.isNotBlank()) " → " + names else " → ninguno" +
                "; rol=" + deviceRole()
            )
            publishDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun discover() {
        val manager = p2p ?: return
        if (!hasPermission()) return

        if (isCajaRegistradora()) {
            ensureCajaGroup()
            return
        }

        // El celular busca exclusivamente la Caja Argentas; Nearby no
        // participa en la reconexión automática de producción.
        if (transport == Transport.NEARBY && connected) {
            diagnostic("discover=IGNORADO; Nearby es el transporte activo")
            return
        }

        if (channel == null) {
            initializeP2PChannel(manager)
            scheduleReconnect(500)
            return
        }
        if (groupFormed) {
            ensureCocinaSinGrupo()
            return
        }
        if (!serviceReady) {
            setupServiceDiscovery()
        } else {
            startPeerDiscovery(manager, channel!!)
            discoverServices(manager, channel!!)
        }
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
        if (isCajaRegistradora()) {
            diagnostic("connect=IGNORADO; la Caja nunca inicia conexiones")
            return
        }
        if (address.isBlank() || connected || !serviceAddresses.contains(address)) return
        if (serviceDeviceIds[address].isNullOrBlank()) return
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
            // The discovered peer is the fixed Argentas Caja / Group Owner.
            // The waiter device must join that group, never compete for GO.
            groupOwnerIntent = 0
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
    private fun requestGroupMembershipDiagnostic(
        manager: WifiP2pManager,
        ch: WifiP2pManager.Channel
    ) {
        try {
            manager.requestGroupInfo(ch) { group ->
                val clients = group.clientList.joinToString(" | ") {
                    (it.deviceName.takeIf { n -> n.isNotBlank() } ?: "sin nombre") +
                        " [" + it.deviceAddress + "]"
                }
                diagnostic(
                    "caja=GRUPO_INFO; GO=" + group.isGroupOwner +
                        "; clientes=" + group.clientList.size +
                        if (clients.isNotBlank()) " → " + clients else " → ninguno"
                )
            }
        } catch (e: Exception) {
            diagnostic("caja=GRUPO_INFO_FALLO;" + (e.message ?: "sin detalle"))
        }
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

        if (transport == Transport.NEARBY && connected) {
            diagnostic("wifi_direct=IGNORADO; Nearby es solo prueba manual")
            return
        }

        manager.requestConnectionInfo(ch) { info: WifiP2pInfo ->
            if (transport == Transport.NEARBY && connected) {
                diagnostic("wifi_direct=CALLBACK_IGNORADO; Nearby es solo prueba manual")
                return@requestConnectionInfo
            }

            if (!info.groupFormed) {
                if (connected && socketIsAlive()) return@requestConnectionInfo
                val wasGroup = groupFormed
                groupFormed = false
                if (isCajaRegistradora()) {
                    if (wasGroup) state("BUSCANDO", "La Caja perdió el grupo; reconstruyendo…")
                    scheduleCajaGroupCreate(500)
                } else {
                    if (connected) {
                        closeTransport()
                        state("DESCONECTADO", "La conexión directa se cerró; buscando la Caja…")
                    } else if (wasGroup) {
                        state("BUSCANDO", "El enlace Wi-Fi Direct se perdió; buscando la Caja…")
                    }
                    scheduleReconnect(800)
                }
                return@requestConnectionInfo
            }

            groupFormed = true
            diagnostic("P2P=GRUPO_FORMADO; rol=" + deviceRole() + "; groupOwner=" + info.isGroupOwner + "; GO_ADDRESS=" + (info.groupOwnerAddress?.hostAddress ?: "desconocida"))
            rememberPeerFromGroup(manager, ch)

            if (isCajaRegistradora() && !info.isGroupOwner) {
                diagnostic("caja=NO_ES_GO; forzando recuperación del grupo")
                scheduleCajaGroupCreate(500)
                return@requestConnectionInfo
            }

            if (connected && socketIsAlive()) {
                state("CONECTADO", if (isCajaRegistradora())
                    "Caja conectada con un Argentas"
                else
                    "Conectado directamente con la Caja")
                return@requestConnectionInfo
            }

            if (info.isGroupOwner) {
                if (isCajaRegistradora()) {
                    startServer()
                    state("BUSCANDO", "Caja lista: esperando celulares Cocina Argentas…")
                } else {
                    diagnostic("peer=GO_NO_ESPERADO; buscando una Caja")
                    scheduleReconnect(500)
                }
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
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(WIFI_PORT))
                server = ss
                diagnostic("tcp=SERVIDOR_OK; puerto=" + WIFI_PORT + "; rol=" + deviceRole())
                state("CONECTANDO", "Enlace creado. Esperando al otro Argentas…")

                while (!ss.isClosed && !stopping && epoch.get() == myEpoch) {
                    try {
                        val incoming = ss.accept()
                        diagnostic("tcp=ENTRADA; remoto=" + (incoming.inetAddress?.hostAddress ?: "desconocido"))
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
            var attempt = 0
            try {
                while (
                    !connected &&
                    groupFormed &&
                    epoch.get() == myEpoch &&
                    !stopping &&
                    attempt < 5
                ) {
                    attempt++
                    try {
                        val s = Socket()
                        s.tcpNoDelay = true
                        s.keepAlive = true
                        diagnostic("tcp=CONEXION; intento=" + attempt + "; destino=" + address.hostAddress + ":" + WIFI_PORT)
                        s.connect(InetSocketAddress(address, WIFI_PORT), 1500)
                        diagnostic("tcp=CONEXION_OK; destino=" + address.hostAddress + ":" + WIFI_PORT)
                        attachSocket(s)
                        established = true
                    } catch (_: Exception) {
                        if (attempt < 5) {
                            val delay = minOf(1000L shl (attempt - 1), 8000L)
                            try { TimeUnit.MILLISECONDS.sleep(delay) }
                            catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                    }
                }
            } finally {
                tcpConnecting = false
            }

            if (!established && !connected && !stopping) {
                diagnostic("tcp=FALLO; agotados 5 intentos; grupo=" + groupFormed)
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
            s.soTimeout = SOCKET_READ_TIMEOUT_MS
        } catch (_: Exception) {}
        socket = s
        connected = true
        authorized = false
        lastHeartbeatAckAt = System.currentTimeMillis()
        reconnectScheduled = false
        state("CONECTANDO", "Canal Wi-Fi Direct creado; verificando Argentas…")
        sendTransportHello()
        startHeartbeat()

        io.execute {
            try {
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), Charsets.UTF_8)
                )
                while (!stopping && socket === s) {
                    try {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) handleIncomingLine(line)
                    } catch (_: SocketTimeoutException) {
                        if (socket !== s || stopping) break
                    }
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

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = reconnect.scheduleAtFixedRate({
            if (!stopping && connected) {
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatAckAt > HEARTBEAT_TIMEOUT_MS) {
                    diagnostic("heartbeat=TIMEOUT; sin ACK por " + (now - lastHeartbeatAckAt) + " ms")
                    closeTransport()
                    state("DESCONECTADO", "El enlace dejó de responder; intentando reconectar…")
                    scheduleReconnect(500)
                } else {
                    sendRaw(JSONObject().put("type", "ping").put("ts", now).toString())
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendTransportHello() {
        val payload = JSONObject()
            .put("type", "hello")
            .put("app", "ARGENTAS")
            .put("protocol", 1)
            .put("deviceId", deviceId)
            .put("ts", System.currentTimeMillis())
            .toString()
        sendRaw(payload)
    }

    private fun handleIncomingLine(line: String) {
        try {
            val obj = JSONObject(line)
            when (obj.optString("type")) {
                "ping" -> {
                    lastHeartbeatAckAt = System.currentTimeMillis()
                    sendRaw(JSONObject().put("type", "pong").put("ts", System.currentTimeMillis()).toString())
                    return
                }
                "pong" -> {
                    lastHeartbeatAckAt = System.currentTimeMillis()
                    return
                }
                "hello" -> {
                    val app = obj.optString("app")
                    val protocol = obj.optInt("protocol", -1)
                    if (app != "ARGENTAS" || protocol != 1) {
                        diagnostic("hello=RECHAZADO; app/protocolo no compatibles")
                        return
                    }
                    lastHeartbeatAckAt = System.currentTimeMillis()
                    sendRaw(JSONObject().put("type", "hello-ack").put("ts", System.currentTimeMillis()).toString())
                    if (!authorized) {
                        authorized = true
                        reconnectAttempt = 0
                        nearbyFallbackFuture?.cancel(false)
                        nearbyFallbackScheduled = false
                        if (transport == Transport.WIFI_DIRECT) {
                            // Wi-Fi Direct is the active transport; Nearby is only fallback.
                            nearbyManager?.stop()
                        }
                        state("CONECTADO", if (transport == Transport.NEARBY)
                            "Conectado por Nearby con otro Argentas"
                        else
                            "Conectado directamente con otro Argentas")
                        event(EVENT_AUTHORIZED)
                    }
                }
                "hello-ack" -> {
                    lastHeartbeatAckAt = System.currentTimeMillis()
                    if (!authorized) {
                        authorized = true
                        reconnectAttempt = 0
                        nearbyFallbackFuture?.cancel(false)
                        nearbyFallbackScheduled = false
                        if (transport == Transport.WIFI_DIRECT) {
                            // Wi-Fi Direct is the active transport; Nearby is only fallback.
                            nearbyManager?.stop()
                        }
                        state("CONECTADO", if (transport == Transport.NEARBY)
                            "Conectado por Nearby con otro Argentas"
                        else
                            "Conectado directamente con otro Argentas")
                        event(EVENT_AUTHORIZED)
                    }
                }
            }
        } catch (_: Exception) {
            // Application payloads are forwarded unchanged below.
        }
        event(EVENT_MESSAGE, message = line)
    }

    private fun sendRaw(message: String) {
        if (!connected) return

        if (transport == Transport.NEARBY) {
            if (nearbyManager?.send(message) != true) {
                diagnostic("Nearby=ENVIO_FALLO")
            }
            return
        }

        val s = socket ?: return
        if (s.isClosed) return
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

    private fun sendMessage(message: String) {
        val s = socket
        if (!connected || !authorized || s == null || s.isClosed) {
            state("DESCONECTADO", "No hay conexión directa activa")
            return
        }

        sendRaw(message)
    }

    private fun closeTransport() {
        epoch.incrementAndGet()
        val wasNearby = transport == Transport.NEARBY
        connected = false
        authorized = false
        lastHeartbeatAckAt = 0L
        tcpConnecting = false
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        if (wasNearby) {
            transport = Transport.WIFI_DIRECT
            nearbyManager?.stop()
        }
    }

    private fun scheduleNearbyFallback(delayMs: Long = 8000) {
        if (stopping || connected || nearbyFallbackScheduled) return
        nearbyFallbackScheduled = true
        diagnostic("nearby=fallback_programado; espera=" + delayMs + " ms")
        nearbyFallbackFuture = reconnect.schedule({
            nearbyFallbackScheduled = false
            if (!stopping && !connected) {
                diagnostic("nearby=fallback_iniciando")
                nearbyManager?.start()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleReconnect(delayMs: Long = 1500) {
        if (stopping || reconnectScheduled || connected) return
        reconnectScheduled = true
        val baseDelay = minOf(
            MAX_RECONNECT_DELAY_MS,
            1000L * (1L shl minOf(reconnectAttempt, 5))
        )
        val jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, 1001L)
        val effectiveDelay = maxOf(delayMs, baseDelay + jitter)
        reconnectAttempt = minOf(reconnectAttempt + 1, 5)
        diagnostic("reconnect=programado; intento=" + reconnectAttempt + "; espera=" + effectiveDelay + " ms (jitter)")
        reconnect.schedule({
            reconnectScheduled = false
            if (!stopping && !connected) {
                if (isCajaRegistradora()) {
                    ensureCajaGroup()
                } else if (groupFormed) {
                    requestConnectionInfo()
                } else {
                    discover()
                }
            }
        }, effectiveDelay, TimeUnit.MILLISECONDS)
    }

    private fun handleCommand(intent: Intent) {
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            "refresh" -> discover()
            "test_nearby" -> nearbyManager?.start()
            "start_server", "accept_incoming", "request_state" -> requestConnectionInfo()
            "connect" -> if (!isCajaRegistradora()) intent.getStringExtra(EXTRA_ADDRESS)?.let { connectP2P(it) }
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

    private fun diagnostic(text: String) {
        event(EVENT_DIAGNOSTIC, message = text)
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
        nearbyManager?.stop()
        nearbyManager = null
        stopping = true
        releaseConnectionWakeLock()
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        closeTransport()
        discoveryFuture?.cancel(true)
        heartbeatFuture?.cancel(true)
        nearbyFallbackFuture?.cancel(true)
        nearbyManager?.stop()
        reconnect.shutdownNow()
        writer.shutdownNow()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}