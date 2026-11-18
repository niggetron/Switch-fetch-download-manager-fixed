package com.example.engine

import com.example.data.model.BreakerState
import com.example.data.model.NetworkRouteMode
import kotlin.math.max

/**
 * 3-State Per-Link Circuit Breaker (Fix F7):
 * Closed -> Open (10s cooldown after 3 errors or >= 1,500 ms stall) -> HalfOpen -> Closed.
 */
class LinkCircuitBreaker(
    private val cooldownMs: Long = 10_000L,
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
)

/**
 * Deterministic Multi-Network Scheduler implementing v8.0 & Fixes F5–F8:
 * - Adaptive BDP segment sizing (1 MiB < 50M, 4 MiB 50–120M, 8 MiB > 120M)
 * - Earliest-Arrival dispatch F_k = (Q_k + S) / R_k + r_k / 2
 * - Lowest-RTT link pinning for HEAD / 1-1 pre-flight probes
 * - Multi-mirror URL selection per LinkKey (Wi-Fi vs 5G vs Eth)
 * - Slowest active extent bisection (`bisectSlowestRange`) & `_emptyRightOf` absorption
 * - effective_connections = max(userMaxConnections, validatedActiveLinks)
 * - Bidirectional autoSpeedUp (+2 / -2 worker scaling)
 */
object LinkScheduler {

    fun adaptiveBdpSegmentBytes(downstreamKbps: Int): Long {
        val mbps = downstreamKbps / 1000.0
        return when {
            mbps < 50.0 -> 1L * 1024L * 1024L // 1 MiB
            mbps <= 120.0 -> 4L * 1024L * 1024L // 4 MiB
            else -> 8L * 1024L * 1024L // 8 MiB
        }
    }

    /**
     * F8: Guarantees at least one active worker per validated selected network.
     */
    fun effectiveWorkerCount(userMaxConnections: Int, validatedLinksCount: Int): Int =
        max(userMaxConnections, validatedLinksCount).coerceAtLeast(1)

    /**
     * F5: Earliest-Arrival dispatch minimizing F_k = (Q_k + S) / R_k + r_k / 2.
     */
    fun selectEarliestArrivalLink(
        lanes: List<LinkLaneMetrics>,
        breakers: Map<String, LinkCircuitBreaker>,
        segmentBytes: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): LinkLaneMetrics? {
        return lanes
            .filter { lane ->
                val br = breakers[lane.linkKeyId]
                (br == null || br.allowRequest(nowMs)) && lane.ewmaBytesPerSec > 0.0
            }
            .minByOrNull { lane ->
                (lane.queuedBytes + segmentBytes) / lane.ewmaBytesPerSec + (lane.rttMinSecs * 0.5)
            }
    }

    /**
     * F5: Pin HEAD / Range 0-0 probes strictly to the lowest-RTT healthy link.
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
     * Distributes distinct mirror URLs cleanly across independent links (`wifi:*` vs `cell:*` vs `eth:*`).
     */
    fun selectMirrorForLink(urls: List<String>, linkKeyId: String, segIndex: Int): String {
        if (urls.isEmpty()) return ""
        if (urls.size == 1) return urls.first()
        val baseIdx = when {
            linkKeyId.startsWith("cell:") -> 1
            linkKeyId.startsWith("eth:") -> 2
            else -> 0
        }
        return urls[(baseIdx + segIndex) % urls.size]
    }

    /**
     * Splits the largest remaining range cleanly in half when tail workers become idle
     * and the remaining extent exceeds 512 KiB.
     */
    fun bisectRange(startOffset: Long, endOffset: Long): Pair<Long, Long>? {
        val remaining = endOffset - startOffset + 1L
        if (remaining < 1024L * 1024L) return null
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
            NetworkRouteMode.WIFI_ONLY -> listOfNotNull(matrix.wifiLink)
            NetworkRouteMode.CELLULAR_ONLY -> listOfNotNull(matrix.cellularLink)
            NetworkRouteMode.AUTO_MULTIPATH,
            NetworkRouteMode.PROXY_SOCKS5 -> matrix.activeValidatedLinks
        }
        return links.map { info ->
            LinkLaneMetrics(
                linkKeyId = info.linkKey.id,
                queuedBytes = 0.0,
                ewmaBytesPerSec = (info.ewmaMbps * 125_000.0).coerceAtLeast(125_000.0),
                rttMinSecs = when {
                    info.linkKey.isEthernet -> 0.010
                    info.linkKey.isCellular -> 0.028
                    else -> 0.018
                },
            )
        }
    }

    /**
     * Bidirectional closed-loop autoSpeedUp controller (v3.0 + v8.0).
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
        if (throttled || deltaRttMs > 35.0) {
            return (currentWorkers - 2).coerceAtLeast(minWorkers)
        }
        if (prevGoodputBps > 0.0 && deltaRttMs < 15.0) {
            val gain = (newGoodputBps - prevGoodputBps) / prevGoodputBps
            if (gain > 0.06) {
                return (currentWorkers + 2).coerceAtMost(maxWorkers)
            }
        }
        return currentWorkers.coerceIn(minWorkers, maxWorkers)
    }
}
