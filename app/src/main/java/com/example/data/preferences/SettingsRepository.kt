package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.EngineSettings
import com.example.data.model.F2FS_SEGMENT_BYTES
import com.example.data.model.NetworkRouteMode
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.aetherDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aether_engine_prefs",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val MAX_WORKERS = intPreferencesKey("max_workers")
        val SEGMENT_SIZE = longPreferencesKey("segment_size_bytes")
        val TAIL_HEDGE = booleanPreferencesKey("enable_tail_hedge")
        val AUTO_SPEED_UP = booleanPreferencesKey("enable_auto_speed_up")
        val CAS_VERIFY = booleanPreferencesKey("enable_cas_verify")
        val STAGED_SAF = booleanPreferencesKey("staged_saf_placement")
        val RETAIN_CELLULAR = booleanPreferencesKey("retain_cellular_on_wifi")
        val ROUTE_MODE = stringPreferencesKey("default_route_mode")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val CELL_PROXY_HOST = stringPreferencesKey("cell_proxy_host")
        val CELL_PROXY_PORT = intPreferencesKey("cell_proxy_port")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settingsFlow: Flow<EngineSettings> = context.aetherDataStore.data.map { prefs ->
        EngineSettings(
            maxConcurrentWorkers = (prefs[Keys.MAX_WORKERS] ?: 4).coerceIn(1, 8),
            segmentSizeBytes = prefs[Keys.SEGMENT_SIZE] ?: F2FS_SEGMENT_BYTES,
            enableTailHedge = prefs[Keys.TAIL_HEDGE] ?: true,
            enableAutoSpeedUp = prefs[Keys.AUTO_SPEED_UP] ?: true,
            enableCasVerification = prefs[Keys.CAS_VERIFY] ?: true,
            stagedSafPlacement = prefs[Keys.STAGED_SAF] ?: true,
            retainCellularOnWifi = prefs[Keys.RETAIN_CELLULAR] ?: true,
            defaultRouteMode = prefs[Keys.ROUTE_MODE]
                ?.let { runCatching { NetworkRouteMode.valueOf(it) }.getOrNull() }
                ?: NetworkRouteMode.AUTO_MULTIPATH,
            proxyHost = prefs[Keys.PROXY_HOST] ?: "",
            proxyPort = (prefs[Keys.PROXY_PORT] ?: 1080).coerceIn(1, 65535),
            cellularProxyHost = prefs[Keys.CELL_PROXY_HOST] ?: "",
            cellularProxyPort = (prefs[Keys.CELL_PROXY_PORT] ?: 1080).coerceIn(1, 65535),
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK_OBSIDIAN,
        )
    }

    suspend fun current(): EngineSettings = settingsFlow.first()

    suspend fun update(transform: (EngineSettings) -> EngineSettings) {
        val updated = transform(current())
        context.aetherDataStore.edit { prefs ->
            prefs[Keys.MAX_WORKERS] = updated.maxConcurrentWorkers.coerceIn(1, 8)
            prefs[Keys.SEGMENT_SIZE] = updated.segmentSizeBytes
            prefs[Keys.TAIL_HEDGE] = updated.enableTailHedge
            prefs[Keys.AUTO_SPEED_UP] = updated.enableAutoSpeedUp
            prefs[Keys.CAS_VERIFY] = updated.enableCasVerification
            prefs[Keys.STAGED_SAF] = updated.stagedSafPlacement
            prefs[Keys.RETAIN_CELLULAR] = updated.retainCellularOnWifi
            prefs[Keys.ROUTE_MODE] = updated.defaultRouteMode.name
            prefs[Keys.PROXY_HOST] = updated.proxyHost.trim()
            prefs[Keys.PROXY_PORT] = updated.proxyPort.coerceIn(1, 65535)
            prefs[Keys.CELL_PROXY_HOST] = updated.cellularProxyHost.trim()
            prefs[Keys.CELL_PROXY_PORT] = updated.cellularProxyPort.coerceIn(1, 65535)
            prefs[Keys.THEME_MODE] = updated.themeMode.name
        }
    }
}
