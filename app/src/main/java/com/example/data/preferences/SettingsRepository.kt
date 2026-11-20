package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.BrowserProfile
import com.example.data.model.EngineBackendPreference
import com.example.data.model.EngineSettings
import com.example.data.model.F2FS_SEGMENT_BYTES
import com.example.data.model.MAX_CONCURRENT_WORKERS_CAP
import com.example.data.model.NetworkRouteMode
import com.example.data.model.ProtocolMode
import com.example.data.model.SpeedometerStyle
import com.example.data.model.ThroughputProfile
import com.example.data.model.WifiBandPreference
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.UiThemeConfig
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
        val FORCE_ALL_LINKS = booleanPreferencesKey("force_all_links")
        val ROUTE_MODE = stringPreferencesKey("default_route_mode")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val CELL_PROXY_HOST = stringPreferencesKey("cell_proxy_host")
        val CELL_PROXY_PORT = intPreferencesKey("cell_proxy_port")
        val THEME_MODE = stringPreferencesKey("theme_mode")

        // Dual-engine A/B testing & v8.0 flags
        val ENABLE_RUST_ENGINE = booleanPreferencesKey("enable_rust_engine")
        val ENABLE_KOTLIN_OKHTTP = booleanPreferencesKey("enable_kotlin_okhttp_engine")
        val ENABLE_ARIA2_NEXT = booleanPreferencesKey("enable_aria2_next_engine")
        val PREFERRED_BACKEND = stringPreferencesKey("preferred_engine_backend")
        val ENABLE_HTTP3_QUIC = booleanPreferencesKey("enable_http3_quic")
        val ENABLE_WREQ_EMULATION = booleanPreferencesKey("enable_wreq_emulation")
        val ENABLE_IO_URING_PROBE = booleanPreferencesKey("enable_io_uring_probe")
        val ENABLE_ARIA2_RPC = booleanPreferencesKey("enable_aria2_rpc")
        val MAX_CELLULAR_BYTES = longPreferencesKey("max_cellular_bytes_session")
        val BROWSER_PROFILE = stringPreferencesKey("default_browser_profile")
        val PROTOCOL_MODE = stringPreferencesKey("default_protocol_mode")

        // v9.0/v9.1 Quad-Link Superbike, Shizuku & Anti-Dip keys
        val SPEEDOMETER_STYLE = stringPreferencesKey("speedometer_style")
        val THROUGHPUT_PROFILE = stringPreferencesKey("throughput_profile")
        val DUAL_WIFI_ENABLED = booleanPreferencesKey("dual_wifi_enabled")
        val USB_BONDING_ENABLED = booleanPreferencesKey("usb_bonding_enabled")
        val SHIZUKU_WIFI_BOOST = booleanPreferencesKey("shizuku_wifi_boost")
        val WIFI2_SSID = stringPreferencesKey("wifi2_ssid")
        val WIFI2_BAND_PREF = stringPreferencesKey("wifi2_band_pref")
        val WIFI2_RELAY_HOST = stringPreferencesKey("wifi2_relay_host")
        val WIFI2_RELAY_PORT = intPreferencesKey("wifi2_relay_port")
        val WIFI2_RELAY_IS_HTTP = booleanPreferencesKey("wifi2_relay_is_http")

        // v9.1 Telegram-style Color Studio & App-Wide Neon Glow keys
        val THEME_PRESET_ID = stringPreferencesKey("theme_preset_id")
        val NEON_GLOW_ENABLED = booleanPreferencesKey("neon_glow_enabled")
        val NEON_GLOW_INTENSITY = floatPreferencesKey("neon_glow_intensity")
        val CUSTOM_BG_ARGB = longPreferencesKey("custom_bg_argb")
        val CUSTOM_SURFACE_ARGB = longPreferencesKey("custom_surface_argb")
        val CUSTOM_PRIMARY_ARGB = longPreferencesKey("custom_primary_argb")
        val CUSTOM_SECONDARY_ARGB = longPreferencesKey("custom_secondary_argb")
        val CUSTOM_TEXT_PRIMARY_ARGB = longPreferencesKey("custom_text_primary_argb")
        val CUSTOM_TEXT_SECONDARY_ARGB = longPreferencesKey("custom_text_secondary_argb")
        val CUSTOM_NEON_GLOW_ARGB = longPreferencesKey("custom_neon_glow_argb")
        val CUSTOM_CARD_BORDER_ARGB = longPreferencesKey("custom_card_border_argb")
    }

    val settingsFlow: Flow<EngineSettings> = context.aetherDataStore.data.map { prefs ->
        val themeMode = prefs[Keys.THEME_MODE]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK_OBSIDIAN
        val uiThemeConfig = UiThemeConfig(
            presetId = prefs[Keys.THEME_PRESET_ID] ?: if (themeMode == ThemeMode.LIGHT_PRECISION) {
                "solar_daylight"
            } else {
                "obsidian_cyber"
            },
            neonGlowEnabled = prefs[Keys.NEON_GLOW_ENABLED] ?: true,
            neonGlowIntensity = (prefs[Keys.NEON_GLOW_INTENSITY] ?: 0.72f).coerceIn(0f, 1f),
            customBackgroundArgb = prefs[Keys.CUSTOM_BG_ARGB] ?: 0L,
            customSurfaceArgb = prefs[Keys.CUSTOM_SURFACE_ARGB] ?: 0L,
            customPrimaryArgb = prefs[Keys.CUSTOM_PRIMARY_ARGB] ?: 0L,
            customSecondaryArgb = prefs[Keys.CUSTOM_SECONDARY_ARGB] ?: 0L,
            customTextPrimaryArgb = prefs[Keys.CUSTOM_TEXT_PRIMARY_ARGB] ?: 0L,
            customTextSecondaryArgb = prefs[Keys.CUSTOM_TEXT_SECONDARY_ARGB] ?: 0L,
            customNeonGlowArgb = prefs[Keys.CUSTOM_NEON_GLOW_ARGB] ?: 0L,
            customCardBorderArgb = prefs[Keys.CUSTOM_CARD_BORDER_ARGB] ?: 0L,
        )
        EngineSettings(
            maxConcurrentWorkers = (prefs[Keys.MAX_WORKERS] ?: 16).coerceIn(1, MAX_CONCURRENT_WORKERS_CAP),
            segmentSizeBytes = prefs[Keys.SEGMENT_SIZE] ?: F2FS_SEGMENT_BYTES,
            enableTailHedge = prefs[Keys.TAIL_HEDGE] ?: true,
            enableAutoSpeedUp = prefs[Keys.AUTO_SPEED_UP] ?: true,
            enableCasVerification = prefs[Keys.CAS_VERIFY] ?: true,
            stagedSafPlacement = prefs[Keys.STAGED_SAF] ?: true,
            retainCellularOnWifi = prefs[Keys.RETAIN_CELLULAR] ?: true,
            forceAllLinks = prefs[Keys.FORCE_ALL_LINKS] ?: true,
            defaultRouteMode = prefs[Keys.ROUTE_MODE]
                ?.let { runCatching { NetworkRouteMode.valueOf(it) }.getOrNull() }
                ?: NetworkRouteMode.AUTO_MULTIPATH,
            proxyHost = prefs[Keys.PROXY_HOST] ?: "",
            proxyPort = (prefs[Keys.PROXY_PORT] ?: 1080).coerceIn(1, 65535),
            cellularProxyHost = prefs[Keys.CELL_PROXY_HOST] ?: "",
            cellularProxyPort = (prefs[Keys.CELL_PROXY_PORT] ?: 1080).coerceIn(1, 65535),
            themeMode = themeMode,
            uiThemeConfig = uiThemeConfig,
            enableRustEngine = prefs[Keys.ENABLE_RUST_ENGINE] ?: true,
            enableKotlinOkHttpEngine = prefs[Keys.ENABLE_KOTLIN_OKHTTP] ?: true,
            enableAria2NextEngine = prefs[Keys.ENABLE_ARIA2_NEXT] ?: false,
            preferredEngineBackend = prefs[Keys.PREFERRED_BACKEND]
                ?.let { runCatching { EngineBackendPreference.valueOf(it) }.getOrNull() }
                ?: EngineBackendPreference.AUTO_PREFER_RUST,
            enableHttp3Quic = prefs[Keys.ENABLE_HTTP3_QUIC] ?: true,
            enableWreqEmulation = prefs[Keys.ENABLE_WREQ_EMULATION] ?: true,
            enableIoUringProbe = prefs[Keys.ENABLE_IO_URING_PROBE] ?: false,
            enableAria2Rpc = prefs[Keys.ENABLE_ARIA2_RPC] ?: false,
            maxCellularBytesPerSession = (prefs[Keys.MAX_CELLULAR_BYTES] ?: 0L).coerceAtLeast(0L),
            defaultBrowserProfile = prefs[Keys.BROWSER_PROFILE]
                ?.let { runCatching { BrowserProfile.valueOf(it) }.getOrNull() }
                ?: BrowserProfile.CHROME_134,
            defaultProtocolMode = prefs[Keys.PROTOCOL_MODE]
                ?.let { runCatching { ProtocolMode.valueOf(it) }.getOrNull() }
                ?: ProtocolMode.AUTO_HYBRID,
            speedometerStyle = prefs[Keys.SPEEDOMETER_STYLE]
                ?.let { runCatching { SpeedometerStyle.valueOf(it) }.getOrNull() }
                ?: SpeedometerStyle.SUPERBIKE_GRID,
            throughputProfile = prefs[Keys.THROUGHPUT_PROFILE]
                ?.let { runCatching { ThroughputProfile.valueOf(it) }.getOrNull() }
                ?: ThroughputProfile.TURBO_ANTI_DIP,
            dualWifiEnabled = prefs[Keys.DUAL_WIFI_ENABLED] ?: true,
            usbBondingEnabled = prefs[Keys.USB_BONDING_ENABLED] ?: true,
            enableShizukuWifiBoost = prefs[Keys.SHIZUKU_WIFI_BOOST] ?: true,
            wifi2Ssid = prefs[Keys.WIFI2_SSID] ?: "",
            wifi2BandPreference = prefs[Keys.WIFI2_BAND_PREF]
                ?.let { runCatching { WifiBandPreference.valueOf(it) }.getOrNull() }
                ?: WifiBandPreference.BAND_5GHZ,
            wifi2RelayHost = prefs[Keys.WIFI2_RELAY_HOST] ?: "",
            wifi2RelayPort = (prefs[Keys.WIFI2_RELAY_PORT] ?: 1080).coerceIn(1, 65535),
            wifi2RelayIsHttp = prefs[Keys.WIFI2_RELAY_IS_HTTP] ?: false,
        )
    }

    suspend fun current(): EngineSettings = settingsFlow.first()

    suspend fun update(transform: (EngineSettings) -> EngineSettings) {
        val updated = transform(current())
        context.aetherDataStore.edit { prefs ->
            prefs[Keys.MAX_WORKERS] = updated.maxConcurrentWorkers.coerceIn(1, MAX_CONCURRENT_WORKERS_CAP)
            prefs[Keys.SEGMENT_SIZE] = updated.segmentSizeBytes
            prefs[Keys.TAIL_HEDGE] = updated.enableTailHedge
            prefs[Keys.AUTO_SPEED_UP] = updated.enableAutoSpeedUp
            prefs[Keys.CAS_VERIFY] = updated.enableCasVerification
            prefs[Keys.STAGED_SAF] = updated.stagedSafPlacement
            prefs[Keys.RETAIN_CELLULAR] = updated.retainCellularOnWifi
            prefs[Keys.FORCE_ALL_LINKS] = updated.forceAllLinks
            prefs[Keys.ROUTE_MODE] = updated.defaultRouteMode.name
            prefs[Keys.PROXY_HOST] = updated.proxyHost.trim()
            prefs[Keys.PROXY_PORT] = updated.proxyPort.coerceIn(1, 65535)
            prefs[Keys.CELL_PROXY_HOST] = updated.cellularProxyHost.trim()
            prefs[Keys.CELL_PROXY_PORT] = updated.cellularProxyPort.coerceIn(1, 65535)
            prefs[Keys.THEME_MODE] = updated.themeMode.name
            prefs[Keys.ENABLE_RUST_ENGINE] = updated.enableRustEngine
            prefs[Keys.ENABLE_KOTLIN_OKHTTP] = updated.enableKotlinOkHttpEngine
            prefs[Keys.ENABLE_ARIA2_NEXT] = updated.enableAria2NextEngine
            prefs[Keys.PREFERRED_BACKEND] = updated.preferredEngineBackend.name
            prefs[Keys.ENABLE_HTTP3_QUIC] = updated.enableHttp3Quic
            prefs[Keys.ENABLE_WREQ_EMULATION] = updated.enableWreqEmulation
            prefs[Keys.ENABLE_IO_URING_PROBE] = updated.enableIoUringProbe
            prefs[Keys.ENABLE_ARIA2_RPC] = updated.enableAria2Rpc
            prefs[Keys.MAX_CELLULAR_BYTES] = updated.maxCellularBytesPerSession.coerceAtLeast(0L)
            prefs[Keys.BROWSER_PROFILE] = updated.defaultBrowserProfile.name
            prefs[Keys.PROTOCOL_MODE] = updated.defaultProtocolMode.name
            prefs[Keys.SPEEDOMETER_STYLE] = updated.speedometerStyle.name
            prefs[Keys.THROUGHPUT_PROFILE] = updated.throughputProfile.name
            prefs[Keys.DUAL_WIFI_ENABLED] = updated.dualWifiEnabled
            prefs[Keys.USB_BONDING_ENABLED] = updated.usbBondingEnabled
            prefs[Keys.SHIZUKU_WIFI_BOOST] = updated.enableShizukuWifiBoost
            prefs[Keys.WIFI2_SSID] = updated.wifi2Ssid.trim()
            prefs[Keys.WIFI2_BAND_PREF] = updated.wifi2BandPreference.name
            prefs[Keys.WIFI2_RELAY_HOST] = updated.wifi2RelayHost.trim()
            prefs[Keys.WIFI2_RELAY_PORT] = updated.wifi2RelayPort.coerceIn(1, 65535)
            prefs[Keys.WIFI2_RELAY_IS_HTTP] = updated.wifi2RelayIsHttp

            val tc = updated.uiThemeConfig
            prefs[Keys.THEME_PRESET_ID] = tc.presetId
            prefs[Keys.NEON_GLOW_ENABLED] = tc.neonGlowEnabled
            prefs[Keys.NEON_GLOW_INTENSITY] = tc.neonGlowIntensity.coerceIn(0f, 1f)
            prefs[Keys.CUSTOM_BG_ARGB] = tc.customBackgroundArgb
            prefs[Keys.CUSTOM_SURFACE_ARGB] = tc.customSurfaceArgb
            prefs[Keys.CUSTOM_PRIMARY_ARGB] = tc.customPrimaryArgb
            prefs[Keys.CUSTOM_SECONDARY_ARGB] = tc.customSecondaryArgb
            prefs[Keys.CUSTOM_TEXT_PRIMARY_ARGB] = tc.customTextPrimaryArgb
            prefs[Keys.CUSTOM_TEXT_SECONDARY_ARGB] = tc.customTextSecondaryArgb
            prefs[Keys.CUSTOM_NEON_GLOW_ARGB] = tc.customNeonGlowArgb
            prefs[Keys.CUSTOM_CARD_BORDER_ARGB] = tc.customCardBorderArgb
        }
    }
}
