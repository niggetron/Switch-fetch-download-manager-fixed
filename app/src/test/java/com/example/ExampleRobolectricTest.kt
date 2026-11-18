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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AetherIO", appName)
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
    fun `formatters render speed and bytes accurately`() {
        assertEquals("10.0 Mbps", Formatters.formatSpeedMbps(1_250_000L))
        assertEquals("2.00 MB", Formatters.formatBytes(2L * 1024L * 1024L))
    }
}
