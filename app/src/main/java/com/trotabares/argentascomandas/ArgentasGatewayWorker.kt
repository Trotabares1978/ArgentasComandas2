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
    private const val CURSOR = "cursor_v1"
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
        val acknowledged = HashSet<String>()
        for (i in 0 until ids.length()) {
            val id = ids.opt(i)?.toString()?.takeIf { it.isNotBlank() } ?: continue
            acknowledged.add(id)
        }
        val old = readArray(context, INBOX)
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
    internal fun cursor(context: Context): JSONObject? =
        GatewaySecureStore.get(context, CURSOR)?.let { JSONObject(it) }

    internal fun saveCursor(context: Context, cursor: JSONObject) =
        GatewaySecureStore.put(context, CURSOR, cursor.toString())

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
        if (old.length() >= MAX_QUEUE) return false
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
                val payload = item.optJSONObject("payload") ?: continue
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

            // Persist each page in the inbox before advancing its cursor. A crash can
            // therefore cause duplicates, but never silently skip an unpersisted page.
            for (page in 0 until 5) {
                val cursor = ArgentasGatewayQueue.cursor(applicationContext)
                val query = buildString {
                    append("$endpoint?")
                    // Keep a worst-case page comfortably below the encrypted inbox byte ceiling.
                    append("limit=10")
                    if (cursor != null) {
                        append("&after=").append(URLEncoder.encode(cursor.optString("after"), "UTF-8"))
                        append("&after_id=").append(URLEncoder.encode(cursor.optString("after_id", "0"), "UTF-8"))
                    }
                }
                val response = request(query, token, "GET", null)
                if (response.code != 200) {
                    ArgentasGatewayQueue.setError(applicationContext, "GET HTTP ${response.code}")
                    return if (response.code == 429 || response.code >= 500) Result.retry() else Result.failure()
                }
                val body = JSONObject(response.body)
                val messages = body.optJSONArray("messages") ?: JSONArray()
                if (messages.length() == 0) break
                if (!ArgentasGatewayQueue.addInboxPage(applicationContext, messages)) {
                    ArgentasGatewayQueue.setError(applicationContext, "La bandeja de entrada está llena; se detuvo la descarga")
                    return Result.success()
                }
                val next = body.optJSONObject("nextCursor") ?: break
                val previous = cursor?.toString()
                ArgentasGatewayQueue.saveCursor(applicationContext, next)
                if (previous == next.toString()) break
        }
            ArgentasGatewayQueue.markSuccess(applicationContext)
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
