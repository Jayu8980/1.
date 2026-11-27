package com.aitrader.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aitrader.security.KeystoreCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val demoMode: Boolean = false,
    val engineUrl: String = "",
    val certPin: String = "",       // "sha256/AAAA...=" ; required when demoMode = false
    val username: String = "",
    val hasToken: Boolean = false,
    val onboarded: Boolean = false,
    val layout: String = "PRO",
    val theme: String = "MIDNIGHT",
    val accent: String = "AZURE",
    val compact: Boolean = false,
)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val ctx: Context) {
    private object K {
        val demo = booleanPreferencesKey("demo")
        val url = stringPreferencesKey("url")
        val pin = stringPreferencesKey("pin")
        val user = stringPreferencesKey("user")
        val token = stringPreferencesKey("token_enc")
        val onboarded = booleanPreferencesKey("onboarded")
        val layout = stringPreferencesKey("ui_layout")
        val theme = stringPreferencesKey("ui_theme")
        val accent = stringPreferencesKey("ui_accent")
        val compact = booleanPreferencesKey("ui_compact")
    }

    val settings: Flow<AppSettings> = ctx.dataStore.data.map {
        AppSettings(
            demoMode = it[K.demo] ?: false,
            engineUrl = it[K.url] ?: "",
            certPin = it[K.pin] ?: "",
            username = it[K.user] ?: "",
            hasToken = it[K.token] != null,
            onboarded = it[K.onboarded] ?: false,
            layout = it[K.layout] ?: "PRO",
            theme = it[K.theme] ?: "MIDNIGHT",
            accent = it[K.accent] ?: "AZURE",
            compact = it[K.compact] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(demoMode: Boolean, engineUrl: String, certPin: String, username: String) {
        ctx.dataStore.edit {
            it[K.demo] = demoMode; it[K.url] = engineUrl.trim(); it[K.pin] = certPin.trim(); it[K.user] = username.trim()
        }
    }

    suspend fun setAppearance(layout: String, theme: String, accent: String, compact: Boolean) {
        ctx.dataStore.edit { it[K.layout] = layout; it[K.theme] = theme; it[K.accent] = accent; it[K.compact] = compact }
    }

    suspend fun finishOnboarding(demo: Boolean) {
        ctx.dataStore.edit { it[K.onboarded] = true; it[K.demo] = demo }
    }

    suspend fun token(): String? = ctx.dataStore.data.first()[K.token]?.let(KeystoreCrypto::decrypt)

    suspend fun setToken(token: String?) {
        ctx.dataStore.edit { if (token == null) it.remove(K.token) else it[K.token] = KeystoreCrypto.encrypt(token) }
    }
}
