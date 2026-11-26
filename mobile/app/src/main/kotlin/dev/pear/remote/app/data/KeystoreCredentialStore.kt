package dev.pear.remote.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.pear.remote.client.CredentialStore
import dev.pear.remote.client.DesktopCredentials
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Paired desktops, encrypted with an AES-256-GCM key that lives in the
 * Android Keystore and never leaves it (SPEC 6.3 step 6). The file is in
 * noBackupFilesDir and backups are disabled, so credentials stay on this
 * device. If the key is lost (for example after a factory reset of the
 * Keystore), the file can't be decrypted and the phone simply has to pair again.
 *
 * Never logs: the plaintext holds device tokens.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "desktops.bin"))
    private var cache: LinkedHashMap<String, DesktopCredentials>? = null

    @Synchronized
    override fun all(): List<DesktopCredentials> = load().values.toList()

    @Synchronized
    override fun put(credentials: DesktopCredentials) {
        val m = load()
        m[credentials.instanceId] = credentials
        save(m)
    }

    @Synchronized
    override fun remove(instanceId: String) {
        val m = load()
        if (m.remove(instanceId) != null) save(m)
    }

    private fun load(): LinkedHashMap<String, DesktopCredentials> {
        cache?.let { return it }
        val result = LinkedHashMap<String, DesktopCredentials>()
        val bytes = try {
            file.readFully()
        } catch (_: java.io.FileNotFoundException) {
            null
        }
        if (bytes != null) {
            try {
                val json = Json.parseToJsonElement(decrypt(bytes).decodeToString()).jsonObject
                for (e in json["desktops"]!!.jsonArray) {
                    val o = e.jsonObject
                    val c = DesktopCredentials(
                        o.s("instanceId"), o.s("deviceId"), o.s("token"), o.s("fingerprint"),
                        o.s("lastHost"), o["lastPort"]!!.jsonPrimitive.int, o.s("name"),
                    )
                    result[c.instanceId] = c
                }
            } catch (_: Exception) {
                // Unreadable (key gone or file corrupt): start empty. The user pairs again.
                file.delete()
            }
        }
        cache = result
        return result
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private fun save(m: LinkedHashMap<String, DesktopCredentials>) {
        val json = buildJsonObject {
            put("v", JsonPrimitive(1))
            put("desktops", buildJsonArray {
                m.values.forEach { c ->
                    add(buildJsonObject {
                        put("instanceId", JsonPrimitive(c.instanceId))
                        put("deviceId", JsonPrimitive(c.deviceId))
                        put("token", JsonPrimitive(c.token))
                        put("fingerprint", JsonPrimitive(c.fingerprint))
                        put("lastHost", JsonPrimitive(c.lastHost))
                        put("lastPort", JsonPrimitive(c.lastPort))
                        put("name", JsonPrimitive(c.name))
                    })
                }
            })
        }
        val encrypted = encrypt(json.toString().encodeToByteArray())
        val out = file.startWrite()
        try {
            out.write(encrypted)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
        cache = m
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    /** Layout: [1 byte version][12 byte IV][ciphertext + tag]. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return ByteBuffer.allocate(1 + iv.size + ct.size).put(1).put(iv).put(ct).array()
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > 13 && data[0] == 1.toByte())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, 12))
        cipher.updateAAD(AAD)
        return cipher.doFinal(data, 13, data.size - 13)
    }

    private companion object {
        const val ALIAS = "pear-remote-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "pear-remote/desktops/v1".encodeToByteArray()
    }
}
