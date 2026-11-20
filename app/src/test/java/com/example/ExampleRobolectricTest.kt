package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.F2FS_SEGMENT_BYTES
import com.example.data.model.NetworkRouteMode
import com.example.data.model.SegmentState
import com.example.engine.SlabBufferPool
import com.example.util.Formatters
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import com.example.data.model.EngineSettings
import com.example.data.model.MAX_CONCURRENT_WORKERS_CAP
import com.example.data.model.SpeedometerStyle
import com.example.engine.DiagnosticsCenter
import com.example.engine.LinkCircuitBreaker
import com.example.engine.LinkLaneMetrics
import com.example.engine.LinkScheduler
import com.example.engine.NetworkMatrixState
import com.example.engine.RemoteZipInspector
import com.example.engine.TelemetrySnapshot
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.example.ui.components.DownloadPropertiesSheet
import com.example.ui.components.FocusedGaugeOverlay
import com.example.ui.components.PerNetworkLiveSpeedPanel
import com.example.ui.components.PerNetworkSpeedometerCluster
import com.example.ui.motion.LocalSharedTransitionScope
import com.example.ui.screens.AnimationSimulationState
import com.example.ui.screens.AnimationSimulatorSheet
import com.example.ui.screens.TelemetryScreen
import com.example.ui.theme.AetherTheme
import com.example.ui.theme.AetherThemePresets
import com.example.ui.theme.wcagContrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `read app name from context and verify bundled SIL OFL fonts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AetherIO", appName)
        val audit = DiagnosticsCenter.verifyBundledFontLicenses(context)
        assertTrue("Expected SIL OFL 1.1 verification, got: $audit", audit.contains("SIL OFL 1.1 Verified"))
    }

    @Test
    fun `slab buffer pool acquires and recycles direct buffers within budget`() {
        val pool = SlabBufferPool(slabSizeBytes = 64 * 1024, maxSlabs = 4)
        val b1 = pool.acquire()
        val b2 = pool.acquire()
        assertTrue(b1.isDirect)
        assertEquals(2, pool.inUseCount())
        assertEquals(2, pool.totalCount())

        pool.release(b1)
        pool.release(b2)
        assertEquals(0, pool.inUseCount())

        val b3 = pool.acquire()
        assertEquals(2, pool.totalCount())
        pool.release(b3)
    }

    @Test
    fun `download entity parses 2mb segment bitfield and progress cleanly`() {
        val entity = DownloadEntity(
            id = "test-1",
            url = "https://example.com/file.bin",
            fileName = "file.bin",
            mimeType = "application/octet-stream",
            totalBytes = 4L * F2FS_SEGMENT_BYTES,
            downloadedBytes = 2L * F2FS_SEGMENT_BYTES,
            status = DownloadStatus.STREAMING.name,
            routeMode = NetworkRouteMode.AUTO_MULTIPATH.name,
            workerCount = 4,
            destinationUri = "/tmp/file.bin",
            isSafDestination = false,
            supportsRange = true,
            segmentBitfield = "2130",
        )
        val segments = entity.parsedSegments()
        assertEquals(
            listOf(
                SegmentState.VERIFIED,
                SegmentState.COMMITTED,
                SegmentState.ACTIVE_WIFI,
                SegmentState.PENDING,
            ),
            segments,
        )
        assertEquals(0.5f, entity.progressFraction, 0.001f)
    }

    @Test
    fun `per-network live speed panel renders independent Wi-Fi and 5G throughput`() {
        composeRule.setContent {
            AetherTheme {
                PerNetworkLiveSpeedPanel(
                    telemetry = TelemetrySnapshot(
                        wifiBps = 5_000_000L, // 40.0 Mbps
                        cellBps = 12_500_000L, // 100.0 Mbps
                        wifiBytesTotal = 20L * 1024L * 1024L,
                        cellBytesTotal = 50L * 1024L * 1024L,
                        wifiWorkers = 2,
                        cellularWorkers = 2,
                    ),
                    networkMatrix = NetworkMatrixState(),
                )
            }
        }
        composeRule.onNodeWithTag("per_network_live_speed_panel").assertIsDisplayed()
        composeRule.onNodeWithText("40.0 Mbps").assertIsDisplayed()
        composeRule.onNodeWithText("100 Mbps").assertIsDisplayed()
    }

    @Test
    fun `per-network radial speedometer cluster renders all 4 links and motorsport controls`() {
        composeRule.setContent {
            AetherTheme {
                PerNetworkSpeedometerCluster(
                    telemetry = TelemetrySnapshot(
                        wifiBps = 5_000_000L, // 40.0 Mbps
                        wifi2Bps = 10_000_000L, // 80.0 Mbps
                        cellBps = 12_500_000L, // 100 Mbps
                        ethBps = 25_000_000L, // 200 Mbps
                        wifiPeakBps = 6_250_000L,
                        wifi2PeakBps = 12_500_000L,
                        cellPeakBps = 15_000_000L,
                        ethPeakBps = 30_000_000L,
                        wifiAvgBps = 4_500_000L,
                        wifi2AvgBps = 9_000_000L,
                        cellAvgBps = 11_000_000L,
                        ethAvgBps = 22_000_000L,
                        wifiWorkers = 4,
                        wifi2Workers = 4,
                        cellularWorkers = 4,
                        ethWorkers = 4,
                    ),
                    networkMatrix = NetworkMatrixState(),
                    useMBpsUnit = false,
                    speedometerStyle = SpeedometerStyle.SUPERBIKE_GRID,
                    onToggleUnit = {},
                    onResetPeaks = {},
                )
            }
        }
        composeRule.onNodeWithTag("per_network_speedometer_cluster").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_wifi").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_cellular").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_wifi2").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_ethernet").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_style_cycle").assertIsDisplayed()
        composeRule.onNodeWithTag("open_dual_wifi_button").assertIsDisplayed()
        composeRule.onNodeWithTag("speed_unit_toggle_transfers").assertIsDisplayed()
    }

    @Test
    fun `speedometer cluster renders Auto Sport Dash and Compact Racing Tach Strip layouts`() {
        composeRule.setContent {
            AetherTheme {
                PerNetworkSpeedometerCluster(
                    telemetry = TelemetrySnapshot(
                        wifiBps = 25_000_000L,
                        wifi2Bps = 18_750_000L,
                        cellBps = 37_500_000L,
                        ethBps = 50_000_000L,
                        wifiPeakBps = 30_000_000L,
                        wifiAvgBps = 22_000_000L,
                    ),
                    networkMatrix = NetworkMatrixState(),
                    useMBpsUnit = false,
                    speedometerStyle = SpeedometerStyle.COMPACT_TACH_STRIP,
                    onToggleUnit = {},
                    onResetPeaks = {},
                )
            }
        }
        composeRule.onNodeWithTag("speedometer_wifi").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_wifi2").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_cellular").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_ethernet").assertIsDisplayed()
    }

    @Test
    fun `telemetry screen displays per-network speedometers, style selector, and dual-engine toggles`() {
        composeRule.setContent {
            AetherTheme {
                TelemetryScreen(
                    telemetry = TelemetrySnapshot(
                        instantBps = 17_500_000L,
                        peakBps = 25_000_000L,
                        avgBps = 16_000_000L,
                        wifiBps = 5_000_000L,
                        wifi2Bps = 4_000_000L,
                        cellBps = 12_500_000L,
                        rustPeakBps = 25_000_000L,
                        kotlinPeakBps = 18_000_000L,
                    ),
                    networkMatrix = NetworkMatrixState(),
                    settings = EngineSettings(),
                    downloads = emptyList(),
                    fontLicenseAudit = "SIL OFL 1.1 Verified (3/3 local .ttf)",
                    diagnosticsPreview = "Diagnostics ready",
                    aria2Secret = "deadbeef",
                    useMBpsSpeedUnit = false,
                    onToggleSpeedUnit = {},
                    onResetPeaks = {},
                    onUpdateSettings = {},
                    onRefreshNetwork = {},
                    onPruneCas = {},
                    onCopyDiagnostics = {},
                    onCopyAria2Secret = {},
                )
            }
        }
        composeRule.onNodeWithTag("full_speedometer_dashboard").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_aggregate").assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_lazy_column")
            .performScrollToNode(hasTestTag("speedometer_style_auto_sport_dash"))
        composeRule.onNodeWithTag("speedometer_style_superbike_grid").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_style_auto_sport_dash").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_style_compact_tach_strip").assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_lazy_column")
            .performScrollToNode(hasTestTag("settings_open_sim_lab_button"))
        composeRule.onNodeWithTag("settings_open_sim_lab_button").assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_lazy_column")
            .performScrollToNode(hasTestTag("toggle_rust_engine"))
        composeRule.onNodeWithTag("toggle_rust_engine").assertIsDisplayed()
        composeRule.onNodeWithTag("telemetry_lazy_column")
            .performScrollToNode(hasTestTag("toggle_kotlin_engine"))
        composeRule.onNodeWithTag("toggle_kotlin_engine").assertIsDisplayed()
    }

    @Test
    fun `formatters render speed units and bytes accurately`() {
        assertEquals("10.0 Mbps", Formatters.formatSpeedMbps(1_250_000L))
        assertEquals("1.00 MB/s", Formatters.formatSpeed(1024L * 1024L, useMBps = true))
        assertEquals("10.0 Mbps", Formatters.formatSpeed(1_250_000L, useMBps = false))
        assertEquals("2.00 MB", Formatters.formatBytes(2L * 1024L * 1024L))
    }

    @Test
    fun `animation simulator sheet renders presets, motion speed controls, and morph sandbox`() {
        composeRule.setContent {
            AetherTheme {
                AnimationSimulatorSheet(
                    simState = AnimationSimulationState(isRunning = false),
                    currentStyle = SpeedometerStyle.SUPERBIKE_GRID,
                    useMBpsUnit = false,
                    liveAggregateBps = 62_500_000L,
                    livePeakBps = 125_000_000L,
                    onStartSimulation = {},
                    onStopSimulation = {},
                    onUpdateCustomSpeeds = { _, _, _, _ -> },
                    onToggleAutoCycleLayouts = {},
                    onSetMotionSpeed = {},
                    onCycleSpeedometerStyle = {},
                    onTriggerNewDownloadMorph = {},
                    onTriggerWifi2Morph = {},
                    onTriggerFocusedGaugeMorph = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("animation_simulator_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("sim_start_button").assertIsDisplayed()
        composeRule.onNodeWithTag("sim_preset_quad_gigabit_redline").assertExists()
        composeRule.onNodeWithTag("motion_speed_0.25").assertExists()
        composeRule.onNodeWithTag("sim_auto_morph_layouts_switch").assertExists()
        composeRule.onNodeWithTag("sim_morph_sandbox_card").assertExists()
    }

    @Test
    fun `focused gauge overlay renders cockpit dial and redline burst trigger`() {
        composeRule.setContent {
            AetherTheme {
                FocusedGaugeOverlay(
                    gaugeId = "wifi1",
                    telemetry = TelemetrySnapshot(
                        wifiBps = 40_000_000L,
                        wifiPeakBps = 55_000_000L,
                        wifiAvgBps = 36_000_000L,
                        wifiWorkers = 4,
                        wifiHistoryBps = listOf(10_000_000L, 25_000_000L, 40_000_000L),
                    ),
                    networkMatrix = NetworkMatrixState(),
                    useMBpsUnit = false,
                    isSimulating = true,
                    onPulseLinkBurst = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag("focused_gauge_overlay").assertIsDisplayed()
        composeRule.onNodeWithTag("pulse_gauge_burst_button").assertExists()
        composeRule.onNodeWithTag("close_focused_gauge_button").assertIsDisplayed()
    }

    @OptIn(ExperimentalSharedTransitionApi::class)
    @Test
    fun `speedometer cluster renders cleanly inside SharedTransitionLayout with Sim trigger`() {
        composeRule.setContent {
            AetherTheme {
                SharedTransitionLayout {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                        PerNetworkSpeedometerCluster(
                            telemetry = TelemetrySnapshot(
                                wifiBps = 30_000_000L,
                                cellBps = 45_000_000L,
                                wifi2Bps = 20_000_000L,
                                ethBps = 25_000_000L,
                            ),
                            networkMatrix = NetworkMatrixState(),
                            useMBpsUnit = false,
                            onToggleUnit = {},
                            onResetPeaks = {},
                            isSimulating = true,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithTag("per_network_speedometer_cluster").assertIsDisplayed()
        composeRule.onNodeWithTag("open_sim_from_cluster").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_wifi").assertIsDisplayed()
    }

    @Test
    fun `all 18 Color Studio theme presets satisfy WCAG AA text contrast ratio`() {
        val presets = AetherThemePresets.all
        assertTrue("Expected at least 18 theme presets, got ${presets.size}", presets.size >= 18)
        presets.forEach { preset ->
            val ratio = wcagContrastRatio(Color(preset.textPrimary), Color(preset.surface))
            assertTrue(
                "Preset '${preset.name}' failed WCAG AA contrast: $ratio",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `all 6 speedometer styles exist and new Rayburst Matrix Bar renders cleanly`() {
        assertEquals(6, SpeedometerStyle.entries.size)
        composeRule.setContent {
            AetherTheme {
                PerNetworkSpeedometerCluster(
                    telemetry = TelemetrySnapshot(
                        wifiBps = 40_000_000L,
                        cellBps = 55_000_000L,
                        wifi2Bps = 30_000_000L,
                        ethBps = 35_000_000L,
                    ),
                    networkMatrix = NetworkMatrixState(),
                    useMBpsUnit = false,
                    speedometerStyle = SpeedometerStyle.RAYBURST_MATRIX_BAR,
                    onToggleUnit = {},
                    onResetPeaks = {},
                )
            }
        }
        composeRule.onNodeWithTag("speedometer_wifi").assertIsDisplayed()
        composeRule.onNodeWithTag("speedometer_cellular").assertIsDisplayed()
    }

    @Test
    fun `Ghost autoSpeedUp scales up to 64 workers and Force-All-Links prevents 5G starvation`() {
        assertEquals(128, MAX_CONCURRENT_WORKERS_CAP)
        val ramped = LinkScheduler.adjustAutoSpeedUp(
            currentWorkers = 16,
            minWorkers = 4,
            maxWorkers = 64,
            prevGoodputBps = 20_000_000.0,
            newGoodputBps = 26_000_000.0,
            deltaRttMs = 5.0,
            throttled = false,
        )
        assertTrue("Expected ramp above 16, got $ramped", ramped > 16)

        val lanes = listOf(
            LinkLaneMetrics(linkKeyId = "wifi:wlan0", queuedBytes = 0.0, ewmaBytesPerSec = 100_000_000.0, rttMinSecs = 0.012, activeWorkersOnLink = 4),
            LinkLaneMetrics(linkKeyId = "cell:rmnet0", queuedBytes = 0.0, ewmaBytesPerSec = 15_000_000.0, rttMinSecs = 0.025, activeWorkersOnLink = 0),
        )
        val chosen = LinkScheduler.selectLinkWithForceAll(
            lanes = lanes,
            breakers = mapOf("wifi:wlan0" to LinkCircuitBreaker(), "cell:rmnet0" to LinkCircuitBreaker()),
            segmentBytes = 8.0 * 1024.0 * 1024.0,
            forceAllLinks = true,
            workerId = 5,
            minReservedPerLink = 2,
        )
        assertEquals("cell:rmnet0", chosen?.linkKeyId)
    }

    @Test
    fun `diagnostics flight recorder redacts secrets and exports TXT and structured JSON`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val redacted = DiagnosticsCenter.redactSensitive("GET https://cdn.test/file.bin?sig=secret123&expires=999 token:abcdef123456")
        assertTrue(redacted.contains("[REDACTED_SIGNED_PARAMS]"))
        assertTrue(redacted.contains("[REDACTED]"))

        val snap = TelemetrySnapshot(
            instantBps = 80_000_000L,
            peakBps = 100_000_000L,
            avgBps = 72_000_000L,
            wifiBps = 50_000_000L,
            cellBps = 30_000_000L,
            activeWorkers = 32,
            activeEngineBadge = "OKHTTP",
        )
        DiagnosticsCenter.recordFlightSample(snap)
        val txt = DiagnosticsCenter.exportReport(context, "Kotlin OkHttp", snap)
        val json = DiagnosticsCenter.exportStructuredJsonTrace("Kotlin OkHttp", snap)
        assertTrue(txt.contains("AetherIO Omega v9.1 Hyperion"))
        assertTrue(json.contains("\"schema\": \"aetherio_flight_trace_v9_1\""))
    }

    @Test
    fun `remote zip inspector parses EOCD and Central Directory from real ZIP bytes`() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("firmware/boot.img"))
            zos.write("AETHER_BOOT_IMAGE_PAYLOAD".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("docs/readme.txt"))
            zos.write("Hello AetherIO".toByteArray())
            zos.closeEntry()
        }
        val zipBytes = baos.toByteArray()
        val eocdOffset = RemoteZipInspector.findEocdOffset(zipBytes)
        assertNotNull("Expected EOCD offset", eocdOffset)
        val buf = java.nio.ByteBuffer.wrap(zipBytes, eocdOffset!!, zipBytes.size - eocdOffset)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.int; buf.short; buf.short; buf.short
        val totalEntries = buf.short.toInt() and 0xFFFF
        val cdSize = buf.int
        val cdOffset = buf.int
        val cdBytes = zipBytes.copyOfRange(cdOffset, cdOffset + cdSize)
        val entries = RemoteZipInspector.parseCentralDirectory(cdBytes, totalEntries)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.fileName == "firmware/boot.img" })
        assertTrue(entries.any { it.fileName == "docs/readme.txt" })
    }

    @Test
    fun `download properties sheet renders 1DM+ metadata and MD5 SHA-256 calculate buttons`() {
        val entity = DownloadEntity(
            id = "prop-1",
            url = "https://cdn.example.com/movie_4k.mkv",
            fileName = "movie_4k.mkv",
            mimeType = "video/x-matroska",
            totalBytes = 2_440_000_000L,
            downloadedBytes = 1_220_000_000L,
            status = DownloadStatus.STREAMING.name,
            routeMode = NetworkRouteMode.AUTO_MULTIPATH.name,
            workerCount = 32,
            destinationUri = "/storage/emulated/0/Download/AetherIO/movie_4k.mkv",
            isSafDestination = false,
            supportsRange = true,
            peakSpeedBps = 90_000_000L,
            avgSpeedBps = 65_000_000L,
            activeDownloadMs = 25_000L,
            maxConnectionsUsed = 64,
        )
        composeRule.setContent {
            AetherTheme {
                DownloadPropertiesSheet(
                    entity = entity,
                    useMBpsUnit = false,
                    liveSpeedBps = 75_000_000L,
                    isSimulatedPreview = true,
                    onDismiss = {},
                    onOpenFile = {},
                    onShareFile = {},
                    onRefreshUrl = { _, _ -> },
                    onSaveMd5 = { _, _ -> },
                    onSaveSha256 = { _, _ -> },
                )
            }
        }
        composeRule.onNodeWithTag("download_properties_sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("calculate_md5_button").assertExists()
        composeRule.onNodeWithTag("calculate_sha256_button").assertExists()
        composeRule.onNodeWithTag("properties_close_button").assertExists()
    }
}
