package com.tradelens.app.data.secure

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * All API keys, secrets and session tokens live here, encrypted with an AES-256 key held in the
 * Android Keystore. Passwords / PINs are never persisted (they are only used once for login).
 */
@Singleton
class SecureStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "tradelens_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val _version = MutableStateFlow(0)
    /** Bumps whenever a value changes so UI can recompose. */
    val version: StateFlow<Int> = _version

    fun get(key: String): String? = prefs.getString(key, null)?.takeIf { it.isNotEmpty() }
    fun put(key: String, value: String?) {
        prefs.edit { if (value.isNullOrEmpty()) remove(key) else putString(key, value) }
        _version.value++
    }
    fun getLong(key: String): Long = prefs.getLong(key, 0L)
    fun putLong(key: String, v: Long) { prefs.edit { putLong(key, v) }; _version.value++ }
    fun remove(vararg keys: String) { prefs.edit { keys.forEach { remove(it) } }; _version.value++ }

    companion object {
        fun brokerKey(broker: String, field: String) = "broker.$broker.$field"
        const val DELTA_KEY = "delta.api_key"
        const val DELTA_SECRET = "delta.api_secret"
        const val ENGINE_TOKEN = "engine.token"
        const val RELAY_TOKEN = "relay.token"
        fun llmKey(provider: String) = "llm.$provider.key"
    }
}
