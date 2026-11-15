package com.tubepilot.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tubepilot.core.domain.SettingsRepository
import com.tubepilot.core.model.AppSettings
import com.tubepilot.core.util.TubeJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "tubepilot_settings")

/** Settings live in one JSON document so new fields get defaults without a migration. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private val key = stringPreferencesKey("settings_json")

    override val settings: Flow<AppSettings> = context.settingsStore.data
        .map { prefs -> decode(prefs[key]) }
        .distinctUntilChanged()

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        var result = AppSettings()
        context.settingsStore.edit { prefs ->
            result = transform(decode(prefs[key]))
            prefs[key] = TubeJson.encodeToString(AppSettings.serializer(), result)
        }
        return result
    }

    private fun decode(json: String?): AppSettings =
        json?.let { runCatching { TubeJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
}
