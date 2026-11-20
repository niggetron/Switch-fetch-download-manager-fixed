package com.example.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Learned per-host connection ceiling, adapted from Mister Download Manager's
 * `crates/engine/src/hosts.rs`.
 *
 * A host that refuses extra connections while others are already receiving is
 * telling us its real cap. Two independent refusals are required before the cap
 * is applied (a single refusal can be a hiccup). The cap is raised when more
 * connections are later observed receiving data, and forgotten after 10 minutes.
 *
 * This prevents the classic "open 480 sockets when the server allows 2" failure
 * mode: extra connections burn retries, slow the transfer and risk an IP ban.
 */
class HostLimits(
    private val decayMs: Long = 10L * 60L * 1000L,
    private val observationsRequired: Int = 2,
) {
    private data class Learned(
        var cap: Int,
        var seen: Int,
        var atMs: Long,
    )

    private val hosts = ConcurrentHashMap<String, Learned>()

    fun cap(host: String): Int? {
        val now = System.currentTimeMillis()
        val entry = hosts[host] ?: return null
        if (now - entry.atMs >= decayMs) {
            hosts.remove(host, entry)
            return null
        }
        return if (entry.seen >= observationsRequired) entry.cap else null
    }

    /** Returns the learned cap once it applies, or null. */
    fun refused(host: String, working: Int): Int? {
        val now = System.currentTimeMillis()
        val workingCount = max(1, working)
        val entry = hosts.compute(host) { _, existing ->
            val current = if (existing == null || now - existing.atMs >= decayMs) {
                Learned(cap = workingCount, seen = 0, atMs = now)
            } else existing
            current.cap = max(current.cap, workingCount)
            current.seen += 1
            current.atMs = now
            current
        } ?: return null
        return if (entry.seen >= observationsRequired) entry.cap else null
    }

    fun working(host: String, receiving: Int) {
        val now = System.currentTimeMillis()
        hosts.computeIfPresent(host) { _, entry ->
            if (now - entry.atMs >= decayMs) {
                null
            } else {
                if (receiving > entry.cap) entry.cap = receiving
                entry.atMs = now
                entry
            }
        }
    }

    fun snapshot(): Map<String, Int> = hosts.mapValues { it.value.cap }
}
