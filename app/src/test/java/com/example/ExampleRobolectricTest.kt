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
import com.example.data.model.EngineSettings
import com.example.engine.DiagnosticsCenter
import com.example.engine.NetworkMatrixState
import com.example.engine.TelemetrySnapshot
import com.example.ui.components.PerNetworkLiveSpeedPanel
import com.example.ui.screens.TelemetryScreen
import com.example.ui.theme.AetherTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
    fun `telemetry screen displays dual-engine individual toggles`() {
        composeRule.setContent {
            AetherTheme {
                TelemetryScreen(
                    telemetry = TelemetrySnapshot(),
                    networkMatrix = NetworkMatrixState(),
                    settings = EngineSettings(),
                    downloads = emptyList(),
                    fontLicenseAudit = "SIL OFL 1.1 Verified (3/3 local .ttf)",
                    diagnosticsPreview = "Diagnostics ready",
                    aria2Secret = "deadbeef",
                    onUpdateSettings = {},
                    onRefreshNetwork = {},
                    onPruneCas = {},
                    onCopyDiagnostics = {},
                    onCopyAria2Secret = {},
                )
            }
        }
        composeRule.onNodeWithTag("toggle_rust_engine").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle_kotlin_engine").assertIsDisplayed()
    }

    @Test
    fun `formatters render speed and bytes accurately`() {
        assertEquals("10.0 Mbps", Formatters.formatSpeedMbps(1_250_000L))
        assertEquals("2.00 MB", Formatters.formatBytes(2L * 1024L * 1024L))
    }
}
