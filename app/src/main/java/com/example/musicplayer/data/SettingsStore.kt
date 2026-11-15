package com.example.musicplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** User preferences shown on the Settings screen. */
class SettingsStore(private val context: Context) {

    val showAlbumsTab: Flow<Boolean> = context.settingsDataStore.data.map { it[SHOW_ALBUMS] ?: false }

    suspend fun setShowAlbumsTab(show: Boolean) {
        context.settingsDataStore.edit { it[SHOW_ALBUMS] = show }
    }

    /** Fix song metadata in the background (local clean-up plus online lookups). */
    val enrichEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[ENRICH_ENABLED] ?: true }

    suspend fun setEnrichEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[ENRICH_ENABLED] = enabled }
    }

    /** Only look metadata up online over unmetered (Wi-Fi) connections. */
    val enrichWifiOnly: Flow<Boolean> = context.settingsDataStore.data.map { it[ENRICH_WIFI_ONLY] ?: true }

    suspend fun setEnrichWifiOnly(wifiOnly: Boolean) {
        context.settingsDataStore.edit { it[ENRICH_WIFI_ONLY] = wifiOnly }
    }

    private companion object {
        val SHOW_ALBUMS = booleanPreferencesKey("show_albums_tab")
        val ENRICH_ENABLED = booleanPreferencesKey("enrich_enabled")
        val ENRICH_WIFI_ONLY = booleanPreferencesKey("enrich_wifi_only")
    }
}
