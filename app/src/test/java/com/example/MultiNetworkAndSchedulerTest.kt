package com.example

import com.example.data.model.BreakerState
import com.example.data.model.LinkKey
import com.example.data.model.NetworkRouteMode
import com.example.engine.AetherBinaryLedger
import com.example.engine.LinkCircuitBreaker
import com.example.engine.LinkLaneMetrics
import com.example.engine.LinkScheduler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-network hardening regression suite (Fixes F1–F10 & v7.1 LLD):
 * - F3: Logical LinkKey identity across rotating raw networkHandles
 * - F5: Earliest-Arrival dispatch F_k = (Q_k + S)/R_k + r_k/2 on unequal links (33M Wi-Fi + 163M 5G)
 * - F5: Lowest-RTT link pinning for 1-1 / HEAD probes
 * - F7: 3-state LinkCircuitBreaker (3 consecutive failures, immediate stall trip, cooldown -> HalfOpen -> Closed)
 * - F8: effectiveWorkerCount >= validated active link count
 * - v7.1: Adaptive BDP sizing, bidirectional autoSpeedUp, and AETHLDG1 crash-safe binary ledger
 */
class MultiNetworkAndSchedulerTest {

    @Test
    fun `F5 earliest arrival picks faster 5G lane when queued bytes match`() {
        // 33 Mbps Wi-Fi vs 163 Mbps 5G (18 ms vs 28 ms RTT)
        val wifi = LinkLaneMetrics(
            linkKeyId = LinkKey.Wifi("wlan0").id,
            queuedBytes = 0.0,
            ewmaBytesPerSec = 33.0 * 125_000.0, // 4.125 MB/s
            rttMinSecs = 0.018,
        )
        val fiveG = LinkLaneMetrics(
            linkKeyId = LinkKey.Cellular("rmnet0").id,
            queuedBytes = 0.0,
            ewmaBytesPerSec = 163.0 * 125_000.0, // 20.375 MB/s
            rttMinSecs = 0.028,
        )
        val segBytes = 4.0 * 1024.0 * 1024.0 // 4 MiB
        val breakers = mapOf(
            wifi.linkKeyId to LinkCircuitBreaker(),
            fiveG.linkKeyId to LinkCircuitBreaker(),
        )

        val chosen = LinkScheduler.selectEarliestArrivalLink(listOf(wifi, fiveG), breakers, segBytes)
        assertNotNull(chosen)
        assertEquals(fiveG.linkKeyId, chosen!!.linkKeyId)

        // When 5G already has heavy queued backlog (e.g. 24 MiB) and Wi-Fi is idle, Earliest-Arrival routes to Wi-Fi
        val loadedFiveG = fiveG.copy(queuedBytes = 24.0 * 1024.0 * 1024.0)
        val balanced = LinkScheduler.selectEarliestArrivalLink(listOf(wifi, loadedFiveG), breakers, segBytes)
        assertNotNull(balanced)
        assertEquals(wifi.linkKeyId, balanced!!.linkKeyId)
    }

    @Test
    fun `F5 lowest RTT link pins preflight probes to Wi-Fi`() {
        val wifi = LinkLaneMetrics("wifi:wlan0", 0.0, 4_000_000.0, 0.018)
        val fiveG = LinkLaneMetrics("cell:rmnet0", 0.0, 20_000_000.0, 0.028)
        val breakers = mapOf(
            wifi.linkKeyId to LinkCircuitBreaker(),
            fiveG.linkKeyId to LinkCircuitBreaker(),
        )

        val probeLink = LinkScheduler.selectLowestRttLink(listOf(wifi, fiveG), breakers)
        assertEquals("wifi:wlan0", probeLink?.linkKeyId)

        // If Wi-Fi breaker opens, probe seamlessly fails over to 5G
        breakers["wifi:wlan0"]!!.recordFailureOrStall(immediateTrip = true, nowMs = 1_000L)
        val failoverProbe = LinkScheduler.selectLowestRttLink(listOf(wifi, fiveG), breakers, nowMs = 2_000L)
        assertEquals("cell:rmnet0", failoverProbe?.linkKeyId)
    }

    @Test
    fun `F7 3-state LinkCircuitBreaker trips on 3 errors or stall and transitions Open to HalfOpen to Closed`() {
        val breaker = LinkCircuitBreaker(cooldownMs = 10_000L)
        assertEquals(BreakerState.CLOSED, breaker.currentState(0L))
        assertTrue(breaker.allowRequest(0L))

        breaker.recordFailureOrStall(immediateTrip = false, nowMs = 100L)
        breaker.recordFailureOrStall(immediateTrip = false, nowMs = 200L)
        assertEquals(BreakerState.CLOSED, breaker.currentState(250L))

        // 3rd consecutive error opens breaker for 10s
        breaker.recordFailureOrStall(immediateTrip = false, nowMs = 300L)
        assertEquals(BreakerState.OPEN, breaker.currentState(300L))
        assertFalse(breaker.allowRequest(5_000L))

        // Cooldown expires -> HALF_OPEN
        assertEquals(BreakerState.HALF_OPEN, breaker.currentState(10_300L))
        assertTrue(breaker.allowRequest(10_300L))

        // Failure while HALF_OPEN immediately re-opens
        breaker.recordFailureOrStall(immediateTrip = false, nowMs = 10_400L)
        assertEquals(BreakerState.OPEN, breaker.currentState(10_500L))

        // Cooldown expires again -> success closes breaker
        assertEquals(BreakerState.HALF_OPEN, breaker.currentState(20_500L))
        breaker.recordSuccess()
        assertEquals(BreakerState.CLOSED, breaker.currentState(20_501L))

        // Immediate stall trip opens in 1 event
        breaker.recordFailureOrStall(immediateTrip = true, nowMs = 30_000L)
        assertEquals(BreakerState.OPEN, breaker.currentState(30_000L))
    }

    @Test
    fun `F8 effectiveWorkerCount clamps above validated active links`() {
        assertEquals(2, LinkScheduler.effectiveWorkerCount(userMaxConnections = 1, validatedLinksCount = 2))
        assertEquals(4, LinkScheduler.effectiveWorkerCount(userMaxConnections = 4, validatedLinksCount = 2))
        assertEquals(1, LinkScheduler.effectiveWorkerCount(userMaxConnections = 0, validatedLinksCount = 0))
    }

    @Test
    fun `v9_1 adaptive BDP sizing up to 32 MiB and bidirectional autoSpeedUp scaling up to 64 workers`() {
        assertEquals(256L * 1024L, LinkScheduler.adaptiveBdpSegmentBytes(33_000))
        assertEquals(512L * 1024L, LinkScheduler.adaptiveBdpSegmentBytes(90_000))
        assertEquals(1L * 1024L * 1024L, LinkScheduler.adaptiveBdpSegmentBytes(163_000))
        assertEquals(2L * 1024L * 1024L, LinkScheduler.adaptiveBdpSegmentBytes(400_000))
        assertEquals(4L * 1024L * 1024L, LinkScheduler.adaptiveBdpSegmentBytes(900_000))
        assertEquals(24, LinkScheduler.effectiveWorkerCount(userMaxConnections = 24, validatedLinksCount = 4))
        assertEquals(64, LinkScheduler.effectiveWorkerCount(userMaxConnections = 96, validatedLinksCount = 4))

        // Goodput improves > 5% with low RTT growth -> +4 workers below 24 (clamped at maxWorkers)
        assertEquals(
            8,
            LinkScheduler.adjustAutoSpeedUp(
                currentWorkers = 4,
                minWorkers = 2,
                maxWorkers = 8,
                prevGoodputBps = 10_000_000.0,
                newGoodputBps = 11_500_000.0,
                deltaRttMs = 4.0,
                throttled = false,
            )
        )

        // RTT spikes > 35ms or throttled -> -2 workers clamped at minWorkers
        assertEquals(
            2,
            LinkScheduler.adjustAutoSpeedUp(
                currentWorkers = 4,
                minWorkers = 2,
                maxWorkers = 8,
                prevGoodputBps = 10_000_000.0,
                newGoodputBps = 12_000_000.0,
                deltaRttMs = 45.0,
                throttled = false,
            )
        )
    }

    @Test
    fun `F3 logical LinkKey stays stable while netHandles rotate`() {
        val k1 = LinkKey.Wifi("wlan0")
        val k2 = LinkKey.Wifi("wlan0")
        val c1 = LinkKey.Cellular("rmnet0")
        assertEquals(k1, k2)
        assertEquals("wifi:wlan0", k1.id)
        assertEquals("cell:rmnet0", c1.id)
        assertFalse(k1.isCellular)
        assertTrue(c1.isCellular)
    }

    @Test
    fun `AETHLDG1 binary ledger encodes CRC32C and resets leased states to pending on recovery`() {
        val vHash = AetherBinaryLedger.validatorHash("\"etag-v71\"")
        val states = byteArrayOf(
            AetherBinaryLedger.STATE_DONE,
            AetherBinaryLedger.STATE_LEASED,
            AetherBinaryLedger.STATE_PENDING,
            AetherBinaryLedger.STATE_DONE,
        )
        val ledger = AetherBinaryLedger(
            totalLen = 16L * 1024L * 1024L,
            segmentBytes = 4L * 1024L * 1024L,
            validatorHash = vHash,
            states = states,
        )
        val encoded = ledger.encode()
        assertEquals(AetherBinaryLedger.HEADER_LEN_V2 + 4 + 4, encoded.size)

        val decoded = AetherBinaryLedger.decode(encoded)
        assertNotNull(decoded)
        assertEquals(ledger.totalLen, decoded!!.totalLen)
        assertArrayEquals(vHash, decoded.validatorHash)
        // STATE_LEASED must recover cleanly to STATE_PENDING
        assertArrayEquals(
            byteArrayOf(
                AetherBinaryLedger.STATE_DONE,
                AetherBinaryLedger.STATE_PENDING,
                AetherBinaryLedger.STATE_PENDING,
                AetherBinaryLedger.STATE_DONE,
            ),
            decoded.states,
        )

        // Corrupted CRC must reject
        val corrupt = encoded.copyOf()
        corrupt[AetherBinaryLedger.HEADER_LEN_V2] = 99
        assertNull(AetherBinaryLedger.decode(corrupt))
    }

    @Test
    fun `AETHLDG2 encodes slab digests, contiguous verified offset, and rolls back leased extents`() {
        val digest = ByteArray(AetherBinaryLedger.SLAB_DIGEST_BYTES) { (it + 1).toByte() }
        val ledger = AetherBinaryLedger(
            totalLen = 8L * 1024L * 1024L,
            segmentBytes = 4L * 1024L * 1024L,
            validatorHash = AetherBinaryLedger.validatorHash("v8-etag"),
            states = byteArrayOf(AetherBinaryLedger.STATE_DONE, AetherBinaryLedger.STATE_LEASED),
            contiguousVerifiedOffset = 4L * 1024L * 1024L,
            slabDigestsRaw = digest,
        )
        assertTrue(ledger.rollbackLeasedSegments())
        assertEquals(AetherBinaryLedger.STATE_PENDING, ledger.states[1])

        val decoded = AetherBinaryLedger.decode(ledger.encode())
        assertNotNull(decoded)
        assertEquals(4L * 1024L * 1024L, decoded!!.contiguousVerifiedOffset)
        assertEquals(1, decoded.slabDigestCount)
        assertArrayEquals(digest, decoded.slabDigestsRaw)
    }

    @Test
    fun `dual-engine backend toggles support independent Rust and Kotlin OkHttp control`() {
        val bothOnAuto = com.example.data.model.EngineSettings(
            enableRustEngine = true,
            enableKotlinOkHttpEngine = true,
            preferredEngineBackend = com.example.data.model.EngineBackendPreference.AUTO_PREFER_RUST,
        )
        val resRustLoaded = com.example.engine.StoragePlacementHelper.selectBackend(bothOnAuto, nativeLoaded = true)
        assertEquals(
            com.example.engine.BackendSelectionResult.Selected(com.example.engine.ResolvedEngineBackend.RUST_LIBAETHER_CORE),
            resRustLoaded,
        )
        val resRustMissingFallback = com.example.engine.StoragePlacementHelper.selectBackend(bothOnAuto, nativeLoaded = false)
        assertEquals(
            com.example.engine.BackendSelectionResult.Selected(com.example.engine.ResolvedEngineBackend.KOTLIN_OKHTTP),
            resRustMissingFallback,
        )

        val onlyKotlin = bothOnAuto.copy(enableRustEngine = false)
        assertTrue(
            com.example.engine.StoragePlacementHelper.selectBackend(
                onlyKotlin,
                nativeLoaded = true,
                overridePreference = com.example.data.model.EngineBackendPreference.FORCE_RUST_NATIVE,
            ) is com.example.engine.BackendSelectionResult.Unavailable
        )

        val bothOff = bothOnAuto.copy(enableRustEngine = false, enableKotlinOkHttpEngine = false)
        assertTrue(
            com.example.engine.StoragePlacementHelper.selectBackend(bothOff, nativeLoaded = true)
                is com.example.engine.BackendSelectionResult.Unavailable
        )
    }

    @Test
    fun `1 GiB direct MediaStore FD threshold and RFC 6266 Content-Disposition parser`() {
        assertTrue(
            com.example.engine.StoragePlacementHelper.shouldUseDirectMediaStoreFd(
                totalBytes = 250L * 1024L * 1024L,
                isSafDestination = false,
            )
        )
        assertFalse(
            com.example.engine.StoragePlacementHelper.shouldUseDirectMediaStoreFd(
                totalBytes = com.example.data.model.MEDIASTORE_DIRECT_FD_THRESHOLD_BYTES,
                isSafDestination = false,
            )
        )
        assertFalse(
            com.example.engine.StoragePlacementHelper.shouldUseDirectMediaStoreFd(
                totalBytes = 100L * 1024L * 1024L,
                isSafDestination = true,
            )
        )

        assertEquals(
            "ubuntu 26.04.iso",
            com.example.engine.StoragePlacementHelper.parseContentDispositionFilename(
                "attachment; filename=\"fallback.bin\"; filename*=UTF-8''ubuntu%2026.04.iso"
            ),
        )
        assertEquals(
            "archive.tar.zst",
            com.example.engine.StoragePlacementHelper.parseContentDispositionFilename(
                "attachment; filename=\"../../etc/archive.tar.zst\""
            ),
        )
    }

    @Test
    fun `Quad-Link LinkKeys, 4-way mirror distribution, memfd quad worker packing, and range bisection`() {
        val wifi2 = LinkKey.WifiSecondary("wlan1")
        assertTrue(wifi2.isWifiSecondary)
        assertFalse(wifi2.isCellular)
        assertEquals("wifi2:wlan1", wifi2.id)

        val eth = LinkKey.Ethernet("usb0")
        assertTrue(eth.isEthernet)
        assertFalse(eth.isCellular)
        assertEquals("eth:usb0", eth.id)

        val mirrors = listOf(
            "https://m0.cdn/a.bin",
            "https://m1.cdn/a.bin",
            "https://m2.cdn/a.bin",
            "https://m3.cdn/a.bin",
        )
        assertEquals("https://m0.cdn/a.bin", LinkScheduler.selectMirrorForLink(mirrors, "wifi:wlan0", 0))
        assertEquals("https://m1.cdn/a.bin", LinkScheduler.selectMirrorForLink(mirrors, "cell:rmnet0", 0))
        assertEquals("https://m2.cdn/a.bin", LinkScheduler.selectMirrorForLink(mirrors, "wifi2:wlan1", 0))
        assertEquals("https://m3.cdn/a.bin", LinkScheduler.selectMirrorForLink(mirrors, "eth:usb0", 0))

        val packed = com.example.engine.MemfdPageSnapshot.packQuadWorkerCounts(
            total = 16,
            wifi1 = 4,
            wifi2 = 4,
            cell = 4,
            usb = 4,
        )
        val snap = com.example.engine.MemfdPageSnapshot(
            totalWritten = 0L,
            totalExpected = 0L,
            goodputBps = 0L,
            stateFlags = 0L,
            wifiGoodputBps = 0L,
            cellGoodputBps = 0L,
            ethGoodputBps = 0L,
            ttfbMs = 0L,
            producerSeq = 0L,
            wifiBytesTotal = 0L,
            cellBytesTotal = 0L,
            workerCountsPacked = packed,
            blockedReasonCode = 0L,
            contiguousVerifiedOffset = 0L,
            cellularQuotaRemaining = 0L,
            wifi2GoodputBps = 0L,
            consumerSeq = 0L,
            wifi2BytesTotal = 0L,
            ethBytesTotal = 0L,
            antiDipRescues = 0L,
        )
        assertEquals(4, snap.wifiWorkers)
        assertEquals(4, snap.cellWorkers)
        assertEquals(4, snap.wifi2Workers)
        assertEquals(4, snap.ethWorkers)
        assertEquals(16, snap.totalWorkers)

        val bisected = LinkScheduler.bisectRange(0L, 4L * 1024L * 1024L - 1L)
        assertNotNull(bisected)
        assertEquals(2L * 1024L * 1024L, bisected!!.first)
        assertEquals(4L * 1024L * 1024L - 1L, bisected.second)
        assertNull(LinkScheduler.bisectRange(0L, 256L * 1024L))
    }

    @Test
    fun `diagnostics redactor scrubs bearer tokens, cookies, and signed query params`() {
        val raw = "GET https://cdn.example.com/file.bin?X-Amz-Signature=deadbeef&Expires=999 token:abc123 authorization:Basic_xyz"
        val cleaned = com.example.engine.DiagnosticsCenter.redactSensitive(raw)
        assertFalse(cleaned.contains("deadbeef"))
        assertFalse(cleaned.contains("abc123"))
        assertFalse(cleaned.contains("Basic_xyz"))
        assertTrue(cleaned.contains("[REDACTED_SIGNED_PARAMS]"))
        assertTrue(cleaned.contains("[REDACTED]"))
    }

    @Test
    fun `NetworkRouteMode enum covers multipath, wifi-only, cellular-only, and proxy`() {
        assertEquals(4, NetworkRouteMode.entries.size)
    }
}
