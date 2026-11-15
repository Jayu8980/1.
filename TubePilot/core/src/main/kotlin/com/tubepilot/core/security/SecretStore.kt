package com.tubepilot.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey as CryptoKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Names of the secrets the app stores. Values never leave the device except to their own provider. */
enum class SecretKey(val label: String) {
    ANTHROPIC_API_KEY("Anthropic API key"),
    OPENAI_API_KEY("OpenAI API key"),
    GEMINI_API_KEY("Google Gemini API key"),
    ELEVENLABS_API_KEY("ElevenLabs API key"),
    ELEVENLABS_VOICE_ID("ElevenLabs voice ID"),
}

interface SecretStore {
    /** Emits a counter that changes whenever any secret changes, so clients can rebuild. */
    val version: StateFlow<Int>
    suspend fun get(key: SecretKey): String?
    suspend fun put(key: SecretKey, value: String?)
    suspend fun isSet(key: SecretKey): Boolean = !get(key).isNullOrBlank()
}

/**
 * Stores secrets AES-256-GCM encrypted with a non-exportable key held in the Android Keystore
 * (hardware-backed where the device supports it). Only ciphertext and IV reach SharedPreferences,
 * and the preferences file is excluded from backups in the app manifest rules.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext context: Context,
) : SecretStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version.asStateFlow()

    override suspend fun get(key: SecretKey): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key.name, null) ?: return@withContext null
        runCatching { decrypt(stored) }.getOrNull()
    }

    override suspend fun put(key: SecretKey, value: String?) = withContext(Dispatchers.IO) {
        val editor = prefs.edit()
        if (value.isNullOrBlank()) editor.remove(key.name) else editor.putString(key.name, encrypt(value.trim()))
        editor.commit()
        _version.value = _version.value + 1
    }

    private fun secretKey(): CryptoKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (ivB64, ctB64) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private companion object {
        const val PREFS = "tubepilot_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "tubepilot_secret_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
