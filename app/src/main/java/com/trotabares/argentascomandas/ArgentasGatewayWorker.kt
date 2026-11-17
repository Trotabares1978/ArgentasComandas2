package com.trotabares.argentascomandas

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal object ArgentasGatewayQueue {
    private const val OUTBOX = "outbox_v1"
    private const val INBOX = "inbox_v1"
    private const val SERVER_ACKS = "server_acks_v1"
    private const val MAX_QUEUE = 500
    private const val MAX_QUEUE_BYTES = 4 * 1024 * 1024
    private val lock = Any()

    fun configure(context: Context, endpoint: String, token: String) = synchronized(lock) {
        val uri = Uri.parse(endpoint)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && host.endsWith(".supabase.co") && uri.userInfo == null)
        require(uri.port == -1 || uri.port == 443)
        require(uri.path == "/functions/v1/argentas-sync-gateway")
        require(uri.query.isNullOrEmpty() && uri.fragment.isNullOrEmpty())
        require(token.matches(Regex("[A-Za-z0-9_-]{32,256}")))
        GatewaySecureStore.put(context, "endpoint", endpoint.trimEnd('/'))
        GatewaySecureStore.put(context, "device_token", token)
        GatewaySecureStore.put(context, "last_error", "")
    }

    fun enqueue(context: Context, deliveryId: String, payloadJson: String): Boolean = synchronized(lock) {
        if (deliveryId.isBlank() || deliveryId.length > 200) return false
        val payload = try { JSONObject(payloadJson) } catch (_: Exception) { return false }
        val queue = readArray(context, OUTBOX)
        for (i in 0 until queue.length()) {
            if (queue.optJSONObject(i)?.optString("deliveryId") == deliveryId) return true
        }
        if (queue.length() >= MAX_QUEUE) {
            GatewaySecureStore.put(context, "last_error", "La cola de envíos está llena; no se descartaron mensajes")
            return false
        }
        queue.put(JSONObject().put("deliveryId", deliveryId).put("payload", payload).put("queuedAt", System.currentTimeMillis()))
        if (queue.toString().toByteArray(Charsets.UTF_8).size > MAX_QUEUE_BYTES) {
            GatewaySecureStore.put(context, "last_error", "La cola de envíos alcanzó el límite de almacenamiento; no se descartaron mensajes")
            return false
        }
        writeArray(context, OUTBOX, queue)
        true
    }

    fun inbox(context: Context): String = synchronized(lock) { readArray(context, INBOX).toString() }

    fun acknowledgeInbox(context: Context, idsJson: String): Boolean = synchronized(lock) {
        val ids = try { JSONArray(idsJson) } catch (_: Exception) { return false }
        val old = readArray(context, INBOX)
        val present = HashSet<String>()
        for (i in 0 until old.length()) old.optJSONObject(i)?.optString("id")?.let { present.add(it) }
        val acknowledged = HashSet<String>()
        for (i in 0 until ids.length()) {
            val id = ids.opt(i)?.toString()?.takeIf { it.matches(Regex("\\d+")) && it != "0" } ?: continue
            if (present.contains(id)) acknowledged.add(id)
        }
        if (acknowledged.isEmpty()) return true
        val oldAcks = readArray(context, SERVER_ACKS)
        val ackSet = HashSet<String>()
        for (i in 0 until oldAcks.length()) oldAcks.opt(i)?.toString()?.let { ackSet.add(it) }
        acknowledged.forEach { ackSet.add(it) }
        val newAcks = JSONArray()
        ackSet.forEach { newAcks.put(it) }
        // Persist the remote receipt queue BEFORE removing local inbox rows.
        // A crash can cause a harmless duplicate, never a lost server ACK.
        writeArray(context, SERVER_ACKS, newAcks)
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (!acknowledged.contains(item.optString("id"))) kept.put(item)
        }
        writeArray(context, INBOX, kept)
        true
    }

    fun status(context: Context): String = synchronized(lock) {
        val configured = !GatewaySecureStore.get(context, "endpoint").isNullOrBlank() &&
            !GatewaySecureStore.get(context, "device_token").isNullOrBlank()
        JSONObject()
            .put("configured", configured)
            .put("pending", readArray(context, OUTBOX).length())
            .put("inbox", readArray(context, INBOX).length())
            .put("lastError", GatewaySecureStore.get(context, "last_error") ?: "")
            .put("lastSuccess", GatewaySecureStore.get(context, "last_success") ?: "")
            .toString()
    }

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<ArgentasGatewayWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "argentas-gateway-sync-now",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    internal fun scheduleContinuation(context: Context) {
        val request = OneTimeWorkRequestBuilder<ArgentasGatewayWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        // Append behind the current worker instead of KEEP-dropping the continuation.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "argentas-gateway-sync-now",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<ArgentasGatewayWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "argentas-gateway-sync-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    internal fun endpoint(context: Context) = GatewaySecureStore.get(context, "endpoint")
    internal fun token(context: Context) = GatewaySecureStore.get(context, "device_token")
    internal fun pendingServerAcks(context: Context) = synchronized(lock) { readArray(context, SERVER_ACKS) }

    internal fun removeServerAcks(context: Context, ids: Set<String>) = synchronized(lock) {
        val old = readArray(context, SERVER_ACKS)
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val id = old.opt(i)?.toString() ?: continue
            if (!ids.contains(id)) kept.put(id)
        }
        writeArray(context, SERVER_ACKS, kept)
    }

    internal fun outbox(context: Context) = synchronized(lock) { readArray(context, OUTBOX) }

    internal fun removeOutbox(context: Context, deliveryId: String) = synchronized(lock) {
        val old = readArray(context, OUTBOX)
        val kept = JSONArray()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("deliveryId") != deliveryId) kept.put(item)
        }
        writeArray(context, OUTBOX, kept)
    }

    internal fun addInboxPage(context: Context, messages: JSONArray): Boolean = synchronized(lock) {
        val old = readArray(context, INBOX)
        val known = HashSet<String>()
        for (i in 0 until old.length()) known.add(old.optJSONObject(i)?.optString("id") ?: "")
        val additions = ArrayList<JSONObject>()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val id = message.opt("id")?.toString() ?: continue
            if (known.add(id)) additions.add(message)
        }
        // Never advance the cursor after persisting only part of a server page.
        if (old.length() + additions.size > MAX_QUEUE) return false
        val merged = JSONArray()
        for (i in 0 until old.length()) old.optJSONObject(i)?.let { merged.put(it) }
        additions.forEach { merged.put(it) }
        if (merged.toString().toByteArray(Charsets.UTF_8).size > MAX_QUEUE_BYTES) return false
        writeArray(context, INBOX, merged)
        true
    }

    internal fun setError(context: Context, message: String) =
        GatewaySecureStore.put(context, "last_error", message.take(500))

    internal fun markSuccess(context: Context) {
        GatewaySecureStore.put(context, "last_error", "")
        GatewaySecureStore.put(context, "last_success", System.currentTimeMillis().toString())
    }

    private fun readArray(context: Context, key: String): JSONArray =
        GatewaySecureStore.get(context, key)?.let { JSONArray(it) } ?: JSONArray()

    private fun writeArray(context: Context, key: String, value: JSONArray) =
        GatewaySecureStore.put(context, key, value.toString())
}

class ArgentasGatewayWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private data class HttpResult(val code: Int, val body: String)
    private companion object {
        // Bound each WorkManager execution well below Android's 10-minute worker limit.
        const val MAX_POSTS_PER_RUN = 15
    }

    override fun doWork(): Result {
        return try {
            val endpoint = ArgentasGatewayQueue.endpoint(applicationContext) ?: return Result.success()
            val token = ArgentasGatewayQueue.token(applicationContext) ?: return Result.success()
            val outgoing = ArgentasGatewayQueue.outbox(applicationContext)
            for (i in 0 until minOf(outgoing.length(), MAX_POSTS_PER_RUN)) {
                val item = outgoing.optJSONObject(i) ?: continue
                val deliveryId = item.optString("deliveryId")
                val payload = item.optJSONObject("payload")
                if (deliveryId.isBlank() || payload == null) {
                    ArgentasGatewayQueue.setError(applicationContext, "Hay un mensaje dañado en la cola de envíos; se conservó para diagnóstico")
                    return Result.failure()
                }
                val response = request(
                    "$endpoint",
                    token,
                    "POST",
                    JSONObject().put("deliveryId", deliveryId).put("payload", payload).toString()
                )
                if (response.code == 200 || response.code == 201) {
                    ArgentasGatewayQueue.removeOutbox(applicationContext, deliveryId)
                } else {
                    ArgentasGatewayQueue.setError(applicationContext, "POST HTTP ${response.code}")
                    return if (response.code == 429 || response.code >= 500) Result.retry() else Result.failure()
                }
            }

            // Flush durable server-side receipts before asking for another pending page.
            // The local inbox ACK is queued before its row is removed, so crashes are safe.
            val pendingAcks = ArgentasGatewayQueue.pendingServerAcks(applicationContext)
            if (pendingAcks.length() > 0) {
                val batch = JSONArray()
                val ids = HashSet<String>()
                for (i in 0 until minOf(pendingAcks.length(), 100)) {
                    val id = pendingAcks.opt(i)?.toString() ?: continue
                    val numeric = id.toLongOrNull() ?: continue
                    batch.put(numeric)
                    ids.add(id)
                }
                if (batch.length() > 0) {
                    val ackResponse = request(
                        endpoint, token, "POST",
                        JSONObject().put("action", "ack").put("messageIds", batch).toString()
                    )
                    if (ackResponse.code != 200) {
                        ArgentasGatewayQueue.setError(applicationContext, "ACK HTTP ${ackResponse.code}")
                        return if (ackResponse.code == 429 || ackResponse.code >= 500) Result.retry() else Result.failure()
                    }
                    ArgentasGatewayQueue.removeServerAcks(applicationContext, ids)
                }
            }

            // The server returns the oldest unacknowledged page. No timestamp cursor
            // is used: late commits remain visible until this device ACKs them.
            val response = request("$endpoint?limit=10", token, "GET", null)
            if (response.code != 200) {
                ArgentasGatewayQueue.setError(applicationContext, "GET HTTP ${response.code}")
                return if (response.code == 429 || response.code >= 500) Result.retry() else Result.failure()
            }
            val body = JSONObject(response.body)
            val messages = body.optJSONArray("messages") ?: JSONArray()
            if (messages.length() > 0 && !ArgentasGatewayQueue.addInboxPage(applicationContext, messages)) {
                ArgentasGatewayQueue.setError(applicationContext, "La bandeja de entrada está llena; se detuvo la descarga")
                return Result.success()
            }
            ArgentasGatewayQueue.markSuccess(applicationContext)
            // A bounded run must chain another worker when backlog remains.
            // Otherwise >15 pending sends or >50 received messages wait until the
            // next periodic poll, which can be a long delay in foreground use.
            if (outgoing.length() > MAX_POSTS_PER_RUN) {
                ArgentasGatewayQueue.scheduleContinuation(applicationContext)
            }
            Result.success()
        } catch (_: IllegalStateException) {
            // Keystore invalidation or authenticated-storage corruption requires
            // explicit re-provisioning; retrying the same unreadable ciphertext cannot help.
            Result.failure()
        } catch (e: Exception) {
            try {
                ArgentasGatewayQueue.setError(applicationContext, e.message ?: "Error de sincronización")
            } catch (_: Exception) {
                // Preserve WorkManager's retry result even if diagnostics cannot be persisted.
            }
            Result.retry()
        }
    }

    private fun request(url: String, token: String, method: String, body: String?): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text)
        } finally {
            connection.disconnect()
        }
    }
}
