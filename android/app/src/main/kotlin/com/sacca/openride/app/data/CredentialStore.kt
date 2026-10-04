package com.sacca.openride.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 16-byte scooter passwords, AES-GCM encrypted with an AndroidKeyStore key, ciphertext in DataStore keyed by
 * scooter serial. A "pending" slot holds a freshly generated password until a login with it succeeds.
 */
@Singleton
class CredentialStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun key(prefix: String, serial: String) = stringPreferencesKey("$prefix.$serial")

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private suspend fun put(prefix: String, serial: String, pw: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val blob = Base64.encodeToString(c.iv + c.doFinal(pw), Base64.NO_WRAP)
        context.dataStore.edit { it[key(prefix, serial)] = blob }
    }

    private suspend fun get(prefix: String, serial: String): ByteArray? {
        val blob = Base64.decode(context.dataStore.data.first()[key(prefix, serial)] ?: return null, Base64.NO_WRAP)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, 0, 12))
            c.doFinal(blob, 12, blob.size - 12)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun load(serial: String): ByteArray? = get("pw", serial)
    suspend fun save(serial: String, pw: ByteArray) = put("pw", serial, pw)
    suspend fun loadPending(serial: String): ByteArray? = get("pending", serial)

    /** Persist BEFORE SET_PWD goes out so the new password can never be lost. */
    suspend fun savePending(serial: String, pw: ByteArray) = put("pending", serial, pw)

    suspend fun forget(serial: String) {
        context.dataStore.edit { it.remove(key("pw", serial)); it.remove(key("pending", serial)) }
    }

    /** Promote the pending password after a successful login with it. */
    suspend fun promotePending(serial: String) {
        loadPending(serial)?.let { save(serial, it) }
        context.dataStore.edit { it.remove(key("pending", serial)) }
    }

    /** The credential to log in with: the stored one, else a pending one (promoted by the caller on success). */
    suspend fun loginCredential(serial: String): Pair<ByteArray, Boolean>? =
        load(serial)?.let { it to false } ?: loadPending(serial)?.let { it to true }

    private companion object {
        const val ALIAS = "openride_credentials"
    }
}
