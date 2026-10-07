package com.trotabares.argentascomandas

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.nio.charset.StandardCharsets

class ArgentasNearbyManager(
    private val context: Context,
    private val deviceId: String,
    private val localRole: String,
    private val onMessage: (String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val diagnostic: (String) -> Unit
) {
    companion object {
        private const val SERVICE_ID = "com.trotabares.argentascomandas.NEARBY"
        private const val PREFIX = "ARGENTAS:"
        private const val ROLE_CAJA = "caja"
        private const val ROLE_COCINA = "cocina"
    }

    private val client = Nearby.getConnectionsClient(context)
    @Volatile private var running = false
    @Volatile private var connectedEndpoint: String? = null

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun isConnected(): Boolean = connectedEndpoint != null

    @Synchronized
    fun start() {
        if (running) {
            diagnostic("Nearby=YA_ACTIVO")
            return
        }
        if (!hasPermissions()) {
            diagnostic("Nearby=FALLO; faltan permisos BLUETOOTH_ADVERTISE/SCAN/CONNECT")
            return
        }

        running = true
        connectedEndpoint = null
        diagnostic("Nearby=INICIANDO; serviceId=" + SERVICE_ID)

        val advertising = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        client.startAdvertising(
            (PREFIX + localRole + ":" + deviceId).toByteArray(StandardCharsets.UTF_8),
            SERVICE_ID,
            lifecycleCallback,
            advertising
        ).addOnSuccessListener {
            diagnostic("Nearby=PUBLICIDAD_OK")
        }.addOnFailureListener { e ->
            diagnostic("Nearby=PUBLICIDAD_FALLO; " + (e.message ?: e.javaClass.simpleName))
        }

        val discovery = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        client.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discovery
        ).addOnSuccessListener {
            diagnostic("Nearby=DESCUBRIMIENTO_OK")
        }.addOnFailureListener { e ->
            diagnostic("Nearby=DESCUBRIMIENTO_FALLO; " + (e.message ?: e.javaClass.simpleName))
        }
    }

    fun stop() {
        running = false
        connectedEndpoint = null
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        diagnostic("Nearby=DETENIDO")
    }

    fun send(message: String): Boolean {
        val endpoint = connectedEndpoint ?: return false
        return try {
            client.sendPayload(
                endpoint,
                Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
            )
            diagnostic("Nearby=ENVIO_OK; bytes=" + message.toByteArray(StandardCharsets.UTF_8).size)
            true
        } catch (e: Exception) {
            diagnostic("Nearby=ENVIO_EXCEPCION; " + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    private data class Remote(val role: String, val id: String)

    private fun remote(info: DiscoveredEndpointInfo): Remote? {
        return try {
            val raw = String(info.endpointInfo ?: ByteArray(0), StandardCharsets.UTF_8)
            if (!raw.startsWith(PREFIX)) return null
            val parts = raw.removePrefix(PREFIX).split(":", limit = 2)
            if (parts.size != 2) return null
            Remote(parts[0].lowercase(), parts[1].trim())
        } catch (_: Exception) {
            null
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remote = remote(info)
            if (remote == null || remote.id.isBlank() || remote.id == deviceId) {
                diagnostic("Nearby=ENDPOINT_IGNORADO")
                return
            }

            diagnostic("Nearby=ENCONTRADO; nombre=" + info.endpointName + "; rol=" + remote.role + "; id=" + remote.id)

            // En producción, Cocina inicia y Caja espera. Así evitamos que
            // ambos equipos compitan por iniciar la misma conexión.
            if (localRole == ROLE_COCINA && remote.role == ROLE_CAJA && connectedEndpoint == null) {
                diagnostic("Nearby=CAJA_ENCONTRADA; SOLICITANDO_CONEXION")
                client.requestConnection(
                    (PREFIX + localRole + ":" + deviceId).toByteArray(StandardCharsets.UTF_8),
                    endpointId,
                    lifecycleCallback
                ).addOnSuccessListener {
                    diagnostic("Nearby=SOLICITUD_OK")
                }.addOnFailureListener { e ->
                    diagnostic("Nearby=SOLICITUD_FALLO; " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            diagnostic("Nearby=PERDIDO; endpoint=" + endpointId)
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // The role was already validated in onEndpointFound() before
            // Cocina requested the connection. ConnectionInfo itself does not
            // expose endpointInfo, so do not attempt to infer the role here.
            diagnostic("Nearby=CONEXION_INICIADA; entrada=" + info.isIncomingConnection)
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    diagnostic("Nearby=ACEPTACION_FALLO; " + (e.message ?: e.javaClass.simpleName))
                }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val code = result.status.statusCode
            diagnostic("Nearby=RESULTADO; codigo=" + code)
            if (code == ConnectionsStatusCodes.STATUS_OK) {
                connectedEndpoint = endpointId
                client.stopDiscovery()
                client.stopAdvertising()
                diagnostic("Nearby=CONECTADO")
                onConnected()
            } else {
                diagnostic("Nearby=CONEXION_NO_ESTABLECIDA; codigo=" + code)
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) {
                connectedEndpoint = null
                diagnostic("Nearby=DESCONECTADO")
                onDisconnected()
                if (running) {
                    client.stopAllEndpoints()
                    android.os.Handler(context.mainLooper).postDelayed({
                        if (running && connectedEndpoint == null) start()
                    }, 1200)
                }
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes() ?: return
                val message = String(bytes, StandardCharsets.UTF_8)
                diagnostic("Nearby=DATOS_RECIBIDOS; bytes=" + bytes.size)
                onMessage(message)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        }
    }
}
