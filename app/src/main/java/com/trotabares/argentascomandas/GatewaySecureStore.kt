package com.trotabares.argentascomandas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key/value store for gateway credentials and queued business messages.
 * AES-256-GCM keys are generated and retained by Android Keystore; plaintext is never
 * written to SharedPreferences.
 */
internal object GatewaySecureStore {
    private const val PREFS = "argentas_gateway_secure_v1"
    private const val KEY_ALIAS = "argentas.gateway.storage.aes.v1"
    private const val IV_BYTES = 12
    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun put(context: Context, name: String, value: String) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(prefs(context).edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            "No se pudo persistir el dato cifrado"
        }
    }

    fun get(context: Context, name: String): String? = synchronized(lock) {
        val packedText = prefs(context).getString(name, null) ?: return null
        try {
            val packed = Base64.decode(packedText, Base64.NO_WRAP)
            require(packed.size > IV_BYTES) { "Dato cifrado incompleto" }
            val iv = packed.copyOfRange(0, IV_BYTES)
            val encrypted = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            // Never silently replace unreadable data with empty plaintext.
            throw IllegalStateException("No se pudo descifrar el almacenamiento de sincronización", e)
        }
    }

    fun remove(context: Context, name: String) = synchronized(lock) {
        check(prefs(context).edit().remove(name).commit()) { "No se pudo eliminar el dato cifrado" }
    }
}
