package com.vocabreminder.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The server this device paired with and the token it was given for it. */
data class PairedServer(
    val serverBaseUrl: String,
    val token: String,
    val deviceId: String
)

/**
 * Keeps the paired server's token encrypted with an AES key that never leaves
 * the Android Keystore, so the token is not readable from a backup or a copy
 * of the app's files. Nothing touches the Keystore until a device pairs.
 */
internal class PairedServerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PairedServer? {
        val sealed = prefs.getString(KEY_SEALED, null) ?: return null
        return runCatching {
            val json = JSONObject(open(sealed))
            PairedServer(json.getString("server"), json.getString("token"), json.optString("device_id"))
        }.getOrNull()
    }

    fun save(server: PairedServer) {
        val json = JSONObject()
            .put("server", server.serverBaseUrl)
            .put("token", server.token)
            .put("device_id", server.deviceId)
        prefs.edit().putString(KEY_SEALED, seal(json.toString())).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_SEALED).apply()
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "vocab_paired_server"
        const val KEY_SEALED = "sealed"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vocab_paired_server"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
