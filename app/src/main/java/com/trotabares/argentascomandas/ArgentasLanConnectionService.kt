package com.trotabares.argentascomandas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Transporte LAN simple para Argentas.
 *
 * No depende de Internet, Wi-Fi Direct, Nearby ni ArgentasLink.
 * Ambos equipos solamente tienen que estar en la misma red local
 * (por ejemplo, el hotspot de un celular).
 */
class ArgentasLanConnectionService : Service() {
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

        private const val CHANNEL_ID = "argentas_lan_connection"
        private const val NOTIFICATION_ID = 8990
        private const val TCP_PORT = 8988
        private const val UDP_PORT = 8989
        private const val DISCOVERY_MAGIC = "ARGENTAS_LAN_1"
        private const val HEARTBEAT_MS = 3000L
        private const val HEARTBEAT_TIMEOUT_MS = 10000L
        private const val DISCOVERY_MS = 2000L
    }

    private val io = Executors.newCachedThreadPool()
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    @Volatile private var stopping = false
    @Volatile private var connected = false
    @Volatile private var authorized = false
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var lastPeerIp: String? = null
    @Volatile private var lastHeartbeatAt = 0L
    @Volatile private var connecting = false
    @Volatile private var discoveryRunning = false
    @Volatile private var currentState = "DESCONECTADO"

    private val prefs by lazy { getSharedPreferences("argentas_lan", MODE_PRIVATE) }
    private val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private val writer = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null

    // Algunas tablets reportan menos de 600dp de ancho mínimo según su densidad/resolución.
    // 480dp sigue separando de forma segura los teléfonos habituales de las tablets.
    private fun isCaja(): Boolean =
        resources.configuration.smallestScreenWidthDp >= 480

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        acquireWakeLock()
        startServerIfCaja()
        startDiscovery()
        startHeartbeat()
        diagnostic("LAN iniciado; rol=" + if (isCaja()) "caja" else "cocina")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) handleCommand(intent)
        return START_STICKY
    }

    private fun handleCommand(intent: Intent) {
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            "refresh", "start_server", "accept_incoming", "request_state" -> {
                if (isCaja()) startServerIfCaja()
                if (!connected) {
                    state("BUSCANDO", "Buscando la otra Argentas en la red local…")
                }
            }
            "connect" -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (!address.isNullOrBlank()) connectTo(address)
            }
            "reject_incoming" -> closeConnection("rechazado")
            "send" -> intent.getStringExtra(EXTRA_MESSAGE)?.let { sendRaw(it) }
            "test_nearby", "nearby_stop" -> {
                // Compatibilidad con la interfaz existente. No se utiliza Nearby.
                diagnostic("comando=test_nearby ignorado; transporte=LAN")
            }
        }
    }

    private fun startServerIfCaja() {
        if (!isCaja() || stopping || server != null) return
        io.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(TCP_PORT))
                server = ss
                diagnostic("tcp servidor LAN listo en puerto $TCP_PORT")
                while (!stopping && !ss.isClosed) {
                    try {
                        val incoming = ss.accept()
                        diagnostic("tcp entrada desde " + (incoming.inetAddress?.hostAddress ?: "?"))
                        if (connected) {
                            incoming.close()
                        } else {
                            attach(incoming)
                        }
                    } catch (_: Exception) {
                        if (ss.isClosed || stopping) break
                    }
                }
            } catch (e: Exception) {
                diagnostic("tcp servidor error=" + (e.message ?: e.javaClass.simpleName))
                if (!stopping) scheduler.schedule({ server = null; startServerIfCaja() }, 2, TimeUnit.SECONDS)
            }
        }
    }

    private fun startDiscovery() {
        if (discoveryRunning || stopping) return
        discoveryRunning = true
        io.execute {
            try {
                if (isCaja()) {
                    listenForDiscovery()
                } else {
                    discoverCajaLoop()
                }
            } finally {
                discoveryRunning = false
            }
        }
    }

    private fun listenForDiscovery() {
        val ds = DatagramSocket(null)
        ds.reuseAddress = true
        ds.bind(InetSocketAddress(UDP_PORT))
        discoverySocket = ds
        val buf = ByteArray(1024)
        val packet = DatagramPacket(buf, buf.size)
        while (!stopping && !ds.isClosed) {
            try {
                packet.length = buf.size
                ds.receive(packet)
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                if (text.startsWith(DISCOVERY_MAGIC + "|")) {
                    val parts = text.split("|")
                    if (parts.size >= 3 && parts[1] != deviceId && parts[2] == "COCINA") {
                        sendDiscoveryReply(ds, packet.address)
                    }
                }
            } catch (_: Exception) {
                if (ds.isClosed || stopping) break
            }
        }
    }

    private fun sendDiscoveryReply(ds: DatagramSocket, address: InetAddress) {
        try {
            val msg = "$DISCOVERY_MAGIC|$deviceId|CAJA".toByteArray(Charsets.UTF_8)
            ds.send(DatagramPacket(msg, msg.size, address, UDP_PORT))
        } catch (_: Exception) {}
    }

    private fun discoverCajaLoop() {
        while (!stopping) {
            if (!connected && !connecting) {
                broadcastDiscovery()
            }
            try { Thread.sleep(DISCOVERY_MS) } catch (_: InterruptedException) { break }
        }
    }

    private fun broadcastDiscovery() {
        var ds: DatagramSocket? = null
        try {
            ds = DatagramSocket()
            ds.broadcast = true
            val msg = "$DISCOVERY_MAGIC|$deviceId|COCINA".toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(msg, msg.size, InetAddress.getByName("255.255.255.255"), UDP_PORT)
            ds.send(packet)

            // Algunos hotspots no propagan 255.255.255.255 correctamente.
            // También probamos las redes IPv4 privadas habituales sin depender
            // de una IP fija del teléfono que comparte Internet.
            listOf(
                "192.168.43.255",
                "192.168.42.255",
                "192.168.137.255",
                "192.168.1.255",
                "192.168.0.255"
            ).forEach {
                try {
                    ds.send(DatagramPacket(msg, msg.size, InetAddress.getByName(it), UDP_PORT))
                } catch (_: Exception) {}
            }

            ds.soTimeout = 350
            val buf = ByteArray(1024)
            val reply = DatagramPacket(buf, buf.size)
            val until = System.currentTimeMillis() + 350
            while (!stopping && System.currentTimeMillis() < until) {
                try {
                    reply.length = buf.size
                    ds.receive(reply)
                    val text = String(reply.data, reply.offset, reply.length, Charsets.UTF_8)
                    if (text == "$DISCOVERY_MAGIC|$deviceId|CAJA" ||
                        text.startsWith("$DISCOVERY_MAGIC|") && text.endsWith("|CAJA")) {
                        val ip = reply.address.hostAddress ?: continue
                        lastPeerIp = ip
                        connectTo(ip)
                        return
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            diagnostic("udp descubrimiento error=" + (e.message ?: e.javaClass.simpleName))
        } finally {
            ds?.close()
        }
    }

    private fun connectTo(ip: String) {
        if (stopping || connected || connecting || isCaja()) return
        connecting = true
        lastPeerIp = ip
        io.execute {
            try {
                diagnostic("tcp conectando a $ip:$TCP_PORT")
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, TCP_PORT), 2000)
                attach(s)
            } catch (e: Exception) {
                diagnostic("tcp conexión falló=" + (e.message ?: e.javaClass.simpleName))
            } finally {
                connecting = false
            }
        }
    }

    private fun attach(s: Socket) {
        if (stopping) {
            try { s.close() } catch (_: Exception) {}
            return
        }
        val old = socket
        if (old != null && old !== s) {
            try { old.close() } catch (_: Exception) {}
        }
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.soTimeout = 0
        } catch (_: Exception) {}

        socket = s
        connected = true
        authorized = false
        lastHeartbeatAt = System.currentTimeMillis()
        state("CONECTANDO", "Conectando con la otra Argentas…")
        sendHello()

        io.execute {
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (!stopping && socket === s) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) handleIncoming(line)
                }
            } catch (e: Exception) {
                if (!stopping) diagnostic("tcp lectura=" + (e.message ?: e.javaClass.simpleName))
            } finally {
                if (socket === s) {
                    closeConnection("canal cerrado")
                    if (!stopping) state("BUSCANDO", "Conexión perdida; buscando automáticamente…")
                } else {
                    try { s.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun sendHello() {
        val msg = org.json.JSONObject()
            .put("type", "hello")
            .put("app", "ARGENTAS")
            .put("protocol", 2)
            .put("deviceId", deviceId)
            .put("ts", System.currentTimeMillis())
            .toString()
        sendRaw(msg)
    }

    private fun handleIncoming(line: String) {
        try {
            val obj = org.json.JSONObject(line)
            when (obj.optString("type")) {
                "ping" -> {
                    lastHeartbeatAt = System.currentTimeMillis()
                    sendRaw(org.json.JSONObject().put("type", "pong").put("ts", System.currentTimeMillis()).toString())
                    return
                }
                "pong" -> {
                    lastHeartbeatAt = System.currentTimeMillis()
                    return
                }
                "hello" -> {
                    if (obj.optString("app") != "ARGENTAS") return
                    lastHeartbeatAt = System.currentTimeMillis()
                    sendRaw(org.json.JSONObject().put("type", "hello-ack").put("ts", System.currentTimeMillis()).toString())
                    authorized = true
                    state("CONECTADO", "Conectado con la otra Argentas")
                    event(EVENT_AUTHORIZED)
                    return
                }
                "hello-ack" -> {
                    lastHeartbeatAt = System.currentTimeMillis()
                    authorized = true
                    state("CONECTADO", "Conectado con la otra Argentas")
                    event(EVENT_AUTHORIZED)
                    return
                }
            }
        } catch (_: Exception) {}
        event(EVENT_MESSAGE, message = line)
    }

    private fun startHeartbeat() {
        scheduler.scheduleAtFixedRate({
            if (stopping || !connected) return@scheduleAtFixedRate
            val age = System.currentTimeMillis() - lastHeartbeatAt
            if (age > HEARTBEAT_TIMEOUT_MS) {
                closeConnection("heartbeat timeout")
                state("BUSCANDO", "El enlace dejó de responder; buscando automáticamente…")
            } else {
                sendRaw(org.json.JSONObject().put("type", "ping").put("ts", System.currentTimeMillis()).toString())
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendRaw(message: String) {
        if (!connected) return
        val s = socket ?: return
        if (s.isClosed) return
        writer.execute {
            try {
                val clean = message.replace("\r", "").replace("\n", "") + "\n"
                synchronized(s) {
                    s.getOutputStream().write(clean.toByteArray(Charsets.UTF_8))
                    s.getOutputStream().flush()
                }
            } catch (e: Exception) {
                if (socket === s) {
                    diagnostic("tcp escritura=" + (e.message ?: e.javaClass.simpleName))
                    closeConnection("error de escritura")
                    if (!stopping) state("BUSCANDO", "Conexión perdida; buscando automáticamente…")
                }
            }
        }
    }

    private fun closeConnection(reason: String) {
        connected = false
        authorized = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        diagnostic("tcp cerrado: $reason")
    }

    private fun state(value: String, text: String) {
        currentState = value
        event(EVENT_STATE, stateValue = value, textValue = text)
    }

    private fun diagnostic(text: String) {
        event(EVENT_DIAGNOSTIC, message = text)
    }

    private fun event(type: String, stateValue: String? = null, textValue: String? = null, message: String? = null, devices: String? = null) {
        val intent = Intent(ACTION_EVENT).setPackage(packageName).putExtra("type", type)
        stateValue?.let { intent.putExtra(EXTRA_STATE, it) }
        textValue?.let { intent.putExtra(EXTRA_TEXT, it) }
        message?.let { intent.putExtra(EXTRA_MESSAGE, it) }
        devices?.let { intent.putExtra(EXTRA_DEVICES, it) }
        sendBroadcast(intent)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Argentas::LanConnection").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Conexión local de Argentas", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Argentas")
            .setContentText("Conexión local entre los equipos")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        stopping = true
        try { discoverySocket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        closeConnection("servicio detenido")
        releaseWakeLock()
        scheduler.shutdownNow()
        writer.shutdownNow()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
