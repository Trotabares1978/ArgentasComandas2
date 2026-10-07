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
    private val diagnostic: (String) -> Unit
) {
    companion object {
        private const val SERVICE_ID = "com.trotabares.argentascomandas.NEARBY"
        private const val PREFIX = "ARGENTAS:"
    }

    private val client = Nearby.getConnectionsClient(context)
    private var running = false
    private var connectedEndpoint: String? = null

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

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
            PREFIX + deviceId,
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

    private fun remoteId(info: DiscoveredEndpointInfo): String {
        return try {
            String(info.endpointInfo ?: ByteArray(0), StandardCharsets.UTF_8)
                .removePrefix(PREFIX)
                .trim()
        } catch (_: Exception) {
            ""
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remoteId = remoteId(info)
            if (remoteId.isBlank() || remoteId == deviceId) {
                diagnostic("Nearby=ENDPOINT_IGNORADO")
                return
            }

            diagnostic("Nearby=ENCONTRADO; nombre=" + info.endpointName + "; id=" + remoteId)

            if (deviceId < remoteId && connectedEndpoint == null) {
                diagnostic("Nearby=SOLICITANDO_CONEXION")
                client.requestConnection(
                    PREFIX + deviceId,
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
            diagnostic("Nearby=CONEXION_INICIADA")
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
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (connectedEndpoint == endpointId) connectedEndpoint = null
            diagnostic("Nearby=DESCONECTADO")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val bytes = payload.asBytes()
                diagnostic("Nearby=DATOS_RECIBIDOS; bytes=" + (bytes?.size ?: 0))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        }
    }
}
