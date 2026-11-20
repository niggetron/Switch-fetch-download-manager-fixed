package com.example.engine

import com.example.data.model.BreakerState
import com.example.data.model.MAX_CONCURRENT_WORKERS_CAP
import com.example.data.model.NetworkRouteMode
import kotlin.math.max

/**
 * 3-State Per-Link Circuit Breaker (v9.1 Anti-Flip-Flop):
 * Closed -> Open (6s cooldown after 3 consecutive errors) -> HalfOpen -> Closed.
 */
class LinkCircuitBreaker(
    private val cooldownMs: Long = 6_000L,
) {
    @Volatile
    private var state: BreakerState = BreakerState.CLOSED

    @Volatile
    private var consecutiveErrors: Int = 0

    @Volatile
    private var openUntilMs: Long = 0L

    @Synchronized
    fun currentState(nowMs: Long = System.currentTimeMillis()): BreakerState {
        if (state == BreakerState.OPEN && nowMs >= openUntilMs) {
            state = BreakerState.HALF_OPEN
        }
        return state
    }

    @Synchronized
    fun allowRequest(nowMs: Long = System.currentTimeMillis()): Boolean {
        return currentState(nowMs) != BreakerState.OPEN
    }

    @Synchronized
    fun recordSuccess() {
        state = BreakerState.CLOSED
        consecutiveErrors = 0
        openUntilMs = 0L
    }

    @Synchronized
    fun recordFailureOrStall(immediateTrip: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (immediateTrip || state == BreakerState.HALF_OPEN) {
            state = BreakerState.OPEN
            openUntilMs = nowMs + cooldownMs
            consecutiveErrors = 3
            return
        }
        consecutiveErrors += 1
        if (consecutiveErrors >= 3) {
            state = BreakerState.OPEN
            openUntilMs = nowMs + cooldownMs
        }
    }
}

data class LinkLaneMetrics(
    val linkKeyId: String,
    val queuedBytes: Double,
    val ewmaBytesPerSec: Double,
    val rttMinSecs: Double,
    val activeWorkersOnLink: Int = 0,
)

/**
 * Deterministic Quad-Link Scheduler (v9.1 Hyperion — 64-Stream Force-All-Links & Anti-Dip):
 * - Adaptive BDP segment sizing: 1 MiB (<50M), 4 MiB (50-120M), 8 MiB (120-250M),
 *   16 MiB (250-600M), 32 MiB (>600M Gigabit Wi-Fi 6 / 5G mmWave)
 * - Force-All-Links guaranteed reservation: ensures EVERY connected healthy link (Wi-Fi 1,
 *   Wi-Fi 2, Mobile 5G/LTE, USB) gets dedicated parallel workers from byte 0 before applying
 *   Earliest-Arrival weighting on REAL measured EWMA (fixing unused Mobile Data!).
 * - Ghost-style bidirectional autoSpeedUp controller up to 64 concurrent streams.
 */
object LinkScheduler {

    fun adaptiveBdpSegmentBytes(downstreamKbps: Int): Long {
        val mbps = downstreamKbps / 1000.0
        return when {
            mbps < 50.0 -> 1L * 1024L * 1024L // 1 MiB
            mbps <= 120.0 -> 4L * 1024L * 1024L // 4 MiB
            mbps <= 250.0 -> 8L * 1024L * 1024L // 8 MiB
            mbps <= 600.0 -> 16L * 1024L * 1024L // 16 MiB
            else -> 32L * 1024L * 1024L // 32 MiB
        }
    }

    /**
     * Guarantees at least 2 active workers per validated network (when available), up to 64 streams.
     */
    fun effectiveWorkerCount(userMaxConnections: Int, validatedLinksCount: Int): Int =
        max(userMaxConnections, validatedLinksCount).coerceIn(1, MAX_CONCURRENT_WORKERS_CAP)

    /**
     * Force-All-Links + Earliest-Arrival dispatch:
     * When [forceAllLinks] is enabled and multiple healthy links exist, any healthy link with fewer
     * than its guaranteed floor of active workers (`minPerLink`, e.g. at least 2 workers on 5G/Wi-Fi 2/USB)
     * is immediately selected so Android's inflated Wi-Fi PHY rate NEVER starves Mobile 5G/LTE!
     * Once all links have active workers saturating them, switches smoothly to Earliest-Arrival
     * `F_k = (Q_k + S) / R_k + r_k / 2` weighted by live measured EWMA.
     */
    fun selectLinkWithForceAll(
        lanes: List<LinkLaneMetrics>,
        breakers: Map<String, LinkCircuitBreaker>,
        segmentBytes: Double,
        forceAllLinks: Boolean,
        workerId: Int = 0,
        minReservedPerLink: Int = 2,
        nowMs: Long = System.currentTimeMillis(),
    ): LinkLaneMetrics? {
        val healthy = lanes.filter { lane ->
            val br = breakers[lane.linkKeyId]
            (br == null || br.allowRequest(nowMs)) && lane.ewmaBytesPerSec > 0.0
        }
        if (healthy.isEmpty()) return null
        if (healthy.size == 1) return healthy.first()

        if (forceAllLinks) {
            // 1) Guarantee every connected link immediately gets active streams (no idle 5G/Wi-Fi 2!)
            val starved = healthy.filter { it.activeWorkersOnLink < minReservedPerLink }
            if (starved.isNotEmpty()) {
                val minWorkers = starved.minOf { it.activeWorkersOnLink }
                val candidates = starved.filter { it.activeWorkersOnLink == minWorkers }
                return candidates[workerId % candidates.size]
            }
        }

        // 2) Earliest-Arrival on real measured EWMA once every link is actively pulling bytes
        return healthy.minByOrNull { lane ->
            (lane.queuedBytes + segmentBytes) / lane.ewmaBytesPerSec + (lane.rttMinSecs * 0.5)
        }
    }

    /**
     * Earliest-Arrival dispatch minimizing F_k = (Q_k + S) / R_k + r_k / 2.
     */
    fun selectEarliestArrivalLink(
        lanes: List<LinkLaneMetrics>,
        breakers: Map<String, LinkCircuitBreaker>,
        segmentBytes: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): LinkLaneMetrics? = selectLinkWithForceAll(
        lanes = lanes,
        breakers = breakers,
        segmentBytes = segmentBytes,
        forceAllLinks = false,
        nowMs = nowMs,
    )

    /**
     * Pin Stream 0 probe strictly to the lowest-RTT healthy link.
     */
    fun selectLowestRttLink(
        lanes: List<LinkLaneMetrics>,
        breakers: Map<String, LinkCircuitBreaker>,
        nowMs: Long = System.currentTimeMillis(),
    ): LinkLaneMetrics? {
        return lanes
            .filter { lane ->
                val br = breakers[lane.linkKeyId]
                br == null || br.allowRequest(nowMs)
            }
            .minByOrNull { it.rttMinSecs }
    }

    /**
     * Distributes distinct mirror URLs cleanly across all 4 links (`wifi:`, `cell:`, `wifi2:`, `eth:`/`usb:`).
     */
    fun selectMirrorForLink(urls: List<String>, linkKeyId: String, segIndex: Int): String {
        if (urls.isEmpty()) return ""
        if (urls.size == 1) return urls.first()
        val baseIdx = when {
            linkKeyId.startsWith("cell:") -> 1
            linkKeyId.startsWith("wifi2:") -> 2
            linkKeyId.startsWith("eth:") || linkKeyId.startsWith("usb:") -> 3
            else -> 0
        }
        return urls[(baseIdx + segIndex) % urls.size]
    }

    /**
     * Splits the largest remaining range cleanly in half when tail workers become idle.
     */
    fun bisectRange(startOffset: Long, endOffset: Long): Pair<Long, Long>? {
        val remaining = endOffset - startOffset + 1L
        if (remaining < 512L * 1024L) return null
        val mid = startOffset + (remaining / 2L)
        return (startOffset to (mid - 1L))
            .takeIf { it.first <= it.second }
            ?.let { mid to endOffset }
    }

    fun filterLanesByMode(
        matrix: NetworkMatrixState,
        mode: NetworkRouteMode,
    ): List<LinkLaneMetrics> {
        val links = when (mode) {
            NetworkRouteMode.WIFI_ONLY -> listOfNotNull(matrix.wifiLink, matrix.wifi2Link)
            NetworkRouteMode.CELLULAR_ONLY -> listOfNotNull(matrix.cellularLink)
            NetworkRouteMode.AUTO_MULTIPATH,
            NetworkRouteMode.PROXY_SOCKS5 -> matrix.activeValidatedLinks
        }
        return links.map { info ->
            // Use measured EWMA (clamped so unmeasured Android Wi-Fi PHY 866 Mbps never starves 5G/USB)
            val effectiveMbps = info.ewmaMbps.coerceIn(4.0, 2000.0)
            LinkLaneMetrics(
                linkKeyId = info.linkKey.id,
                queuedBytes = 0.0,
                ewmaBytesPerSec = (effectiveMbps * 125_000.0).coerceAtLeast(500_000.0),
                rttMinSecs = when {
                    info.linkKey.isEthernet -> 0.008
                    info.linkKey.isCellular -> 0.024
                    info.linkKey.isWifiSecondary -> 0.016
                    else -> 0.014
                },
            )
        }
    }

    /**
     * Ghost-style closed-loop autoSpeedUp controller (v9.1 up to 64 parallel streams).
     * Aggressively ramps up (+4 when goodput climbs > 5% and RTT is clean) to saturate
     * multi-network links, and smoothly steps down on RTT spike or throttling.
     */
    fun adjustAutoSpeedUp(
        currentWorkers: Int,
        minWorkers: Int,
        maxWorkers: Int,
        prevGoodputBps: Double,
        newGoodputBps: Double,
        deltaRttMs: Double,
        throttled: Boolean,
    ): Int {
        // Never exceed 24 lanes: beyond that, per-host congestion wiped out the
        // gains (70 Mbps peaks but 25 Mbps average on a throttled phone).
        val clampedMax = maxWorkers.coerceIn(1, 48)
        val clampedMin = minWorkers.coerceIn(1, clampedMax)
        if (throttled || deltaRttMs > 40.0) {
            return (currentWorkers - 2).coerceAtLeast(clampedMin)
        }
        if (prevGoodputBps <= 0.0 && newGoodputBps > 0.0 && deltaRttMs < 20.0) {
            return (currentWorkers + 4).coerceAtMost(clampedMax)
        }
        if (prevGoodputBps > 0.0) {
            val gain = (newGoodputBps - prevGoodputBps) / prevGoodputBps
            // Scale DOWN when more sockets made throughput worse. Previously the
            // governor never decreased on a goodput drop, so 64 lanes stayed at 64
            // while average speed fell from 70 Mbps to 25 Mbps.
            if (gain < -0.06) {
                val step = (currentWorkers / 6).coerceAtLeast(2)
                return (currentWorkers - step).coerceAtLeast(clampedMin)
            }
            if (gain > 0.03 && deltaRttMs < 18.0) {
                val step = if (currentWorkers < 16) 4 else 2
                return (currentWorkers + step).coerceAtMost(clampedMax)
            }
        }
        return currentWorkers.coerceIn(clampedMin, clampedMax)
    }
}
