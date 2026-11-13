package com.yourcompany.videoplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class NetworkMountType {
    SMB,
    WEBDAV
}

internal data class NetworkMount(
    val id: String,
    val name: String,
    val type: NetworkMountType,
    val serverUrl: String,
    val username: String,
    val password: String,
    val domain: String = ""
)

internal data class NetworkMountEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long
)

internal object NetworkMountStore {
    private const val PREFERENCES = "NETWORK_MOUNTS"
    private const val ENCRYPTED_MOUNTS_KEY = "encrypted_mounts"
    private const val KEY_ALIAS = "network_mount_credentials_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val GCM_NONCE_SIZE = 12

    fun getAll(context: Context): List<NetworkMount> {
        val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ENCRYPTED_MOUNTS_KEY, null) ?: return emptyList()
        val encrypted = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IOException("Saved network mount settings are corrupted.", error)
        }
        val plaintext = decrypt(encrypted)
        return try {
            val array = JSONArray(String(plaintext, Charsets.UTF_8))
            List(array.length()) { index ->
                val json = array.getJSONObject(index)
                NetworkMount(
                    id = json.getString("id"),
                    name = json.getString("name"),
                    type = NetworkMountType.valueOf(json.getString("type")),
                    serverUrl = json.getString("serverUrl"),
                    username = json.getString("username"),
                    password = json.getString("password"),
                    domain = json.optString("domain")
                )
            }
        } catch (error: Exception) {
            throw IOException("Saved network mount settings could not be read.", error)
        } finally {
            plaintext.fill(0)
        }
    }

    fun get(context: Context, id: String): NetworkMount? =
        getAll(context).firstOrNull { it.id == id }

    fun put(context: Context, mount: NetworkMount) {
        require(mount.id.isNotBlank() && mount.name.isNotBlank())
        val mounts = getAll(context).filterNot { it.id == mount.id } + mount
        save(context, mounts)
    }

    fun remove(context: Context, id: String) {
        val mounts = getAll(context).filterNot { it.id == id }
        save(context, mounts)
    }

    private fun save(context: Context, mounts: List<NetworkMount>) {
        val plaintext = JSONArray().apply {
            mounts.forEach { mount ->
                put(
                    JSONObject()
                        .put("id", mount.id)
                        .put("name", mount.name)
                        .put("type", mount.type.name)
                        .put("serverUrl", mount.serverUrl)
                        .put("username", mount.username)
                        .put("password", mount.password)
                        .put("domain", mount.domain)
                )
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            Base64.encodeToString(encrypt(plaintext), Base64.NO_WRAP)
        } finally {
            plaintext.fill(0)
        }
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(ENCRYPTED_MOUNTS_KEY, encrypted)
            .commit()
        if (!saved) throw IOException("Network mount settings could not be saved.")
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val nonce = cipher.iv
        if (nonce.size != GCM_NONCE_SIZE) {
            throw GeneralSecurityException("Keystore returned an unsupported nonce.")
        }
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    private fun decrypt(encoded: ByteArray): ByteArray {
        if (encoded.size <= GCM_NONCE_SIZE) {
            throw IOException("Saved network mount settings are incomplete.")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, encoded, 0, GCM_NONCE_SIZE)
        )
        return cipher.doFinal(encoded, GCM_NONCE_SIZE, encoded.size - GCM_NONCE_SIZE)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
