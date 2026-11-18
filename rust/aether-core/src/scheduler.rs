use std::collections::HashMap;
use std::time::{Duration, Instant};

/// 1 MiB for slower Wi-Fi (< 50 Mbps), 4 MiB mid-band, 8 MiB for high-BDP 5G (> 120 Mbps)
pub fn adaptive_bdp_segment_bytes(downstream_kbps: u64) -> u64 {
    let mbps = downstream_kbps as f64 / 1000.0;
    if mbps < 50.0 {
        1024 * 1024 // 1 MiB
    } else if mbps <= 120.0 {
        4 * 1024 * 1024 // 4 MiB
    } else {
        8 * 1024 * 1024 // 8 MiB
    }
}

/// F8: Always allocate at least one active worker lane per validated active link.
pub fn effective_worker_count(user_max_connections: usize, validated_active_links: usize) -> usize {
    user_max_connections.max(validated_active_links).max(1)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BreakerState {
    Closed,
    Open,
    HalfOpen,
}

/// F7: 3-State Per-Link Circuit Breaker (Closed -> Open for 10s -> HalfOpen).
/// Three consecutive errors or a >= 1,500 ms zero-byte stall trips the breaker.
#[derive(Debug, Clone)]
pub struct LinkCircuitBreaker {
    pub state: BreakerState,
    pub consecutive_errors: u32,
    pub open_until: Option<Instant>,
    pub cooldown: Duration,
}

impl LinkCircuitBreaker {
    pub fn new(cooldown: Duration) -> Self {
        Self {
            state: BreakerState::Closed,
            consecutive_errors: 0,
            open_until: None,
            cooldown,
        }
    }

    pub fn allow_request(&mut self, now: Instant) -> bool {
        match self.state {
            BreakerState::Closed | BreakerState::HalfOpen => true,
            BreakerState::Open => {
                if let Some(until) = self.open_until {
                    if now >= until {
                        self.state = BreakerState::HalfOpen;
                        true
                    } else {
                        false
                    }
                } else {
                    true
                }
            }
        }
    }

    pub fn record_success(&mut self) {
        self.state = BreakerState::Closed;
        self.consecutive_errors = 0;
        self.open_until = None;
    }

    pub fn record_error_or_stall(&mut self, now: Instant, immediate_trip: bool) {
        if immediate_trip || self.state == BreakerState::HalfOpen {
            self.state = BreakerState::Open;
            self.open_until = Some(now + self.cooldown);
            self.consecutive_errors = 3;
            return;
        }
        self.consecutive_errors += 1;
        if self.consecutive_errors >= 3 {
            self.state = BreakerState::Open;
            self.open_until = Some(now + self.cooldown);
        }
    }
}

#[derive(Debug, Clone)]
pub struct LinkDispatchStats {
    pub link_id: String,
    pub queued_bytes: f64,
    pub ewma_bytes_per_sec: f64,
    pub rtt_min_secs: f64,
}

/// F5: Earliest-Arrival scheduler minimizing F_k = (Q_k + S) / R_k + r_k / 2
pub fn select_earliest_arrival_link<'a>(
    candidates: &'a [LinkDispatchStats],
    breakers: &mut HashMap<String, LinkCircuitBreaker>,
    segment_bytes: f64,
    now: Instant,
) -> Option<&'a str> {
    candidates
        .iter()
        .filter(|c| {
            let b = breakers
                .entry(c.link_id.clone())
                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(10)));
            b.allow_request(now) && c.ewma_bytes_per_sec > 0.0
        })
        .min_by(|a, b| {
            let fa = (a.queued_bytes + segment_bytes) / a.ewma_bytes_per_sec + (a.rtt_min_secs * 0.5);
            let fb = (b.queued_bytes + segment_bytes) / b.ewma_bytes_per_sec + (b.rtt_min_secs * 0.5);
            fa.total_cmp(&fb)
        })
        .map(|c| c.link_id.as_str())
}

/// F5: Route HEAD / 1-1 probes strictly to the lowest-RTT healthy link.
pub fn select_lowest_rtt_link<'a>(
    candidates: &'a [LinkDispatchStats],
    breakers: &mut HashMap<String, LinkCircuitBreaker>,
    now: Instant,
) -> Option<&'a str> {
    candidates
        .iter()
        .filter(|c| {
            let b = breakers
                .entry(c.link_id.clone())
                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(10)));
            b.allow_request(now)
        })
        .min_by(|a, b| a.rtt_min_secs.total_cmp(&b.rtt_min_secs))
        .map(|c| c.link_id.as_str())
}

/// F6: Immutable SegmentLease pinning stolen tails & retries strictly to the owning worker's link
/// so per-link EWMA rates remain unpolluted.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SegmentLease {
    pub index: usize,
    pub start: u64,
    pub end: u64,
    pub pinned_link: String,
    pub is_tail_hedge: bool,
}

/// Open-ended right-neighbor absorption (`_emptyRightOf`) + `_splitSlowest`:
/// Scans for an unstarted right-hand neighbor or bisects a lagging open-ended lease.
pub fn absorb_empty_right_of(states: &mut [u8], from_index: usize) -> Option<usize> {
    for (idx, state) in states.iter_mut().enumerate().skip(from_index + 1) {
        if *state == 0 {
            *state = 1; // Leased
            return Some(idx);
        }
    }
    None
}

/// Bidirectional closed-loop autoSpeedUp controller (v3.0 + v7.1):
/// Scales +2 workers when goodput improves >= 0.8 * workerRatio (or > 6% gain) with deltaRTT < 15ms;
/// Scales -2 workers when deltaRTT > 35ms or server emits HTTP 429/503 throttling.
pub fn adjust_auto_speed_up(
    current_workers: usize,
    min_workers: usize,
    max_workers: usize,
    prev_goodput_bps: f64,
    new_goodput_bps: f64,
    delta_rtt_ms: f64,
    throttled: bool,
) -> usize {
    if throttled || delta_rtt_ms > 35.0 {
        return current_workers.saturating_sub(2).max(min_workers);
    }
    if prev_goodput_bps > 0.0 && delta_rtt_ms < 15.0 {
        let gain = (new_goodput_bps - prev_goodput_bps) / prev_goodput_bps;
        if gain > 0.06 {
            return (current_workers + 2).min(max_workers);
        }
    }
    current_workers.clamp(min_workers, max_workers)
}

/// Deterministic IoSubstrate trait for F10 host-side unit testing without physical hardware.
pub trait IoSubstrate {
    fn preallocate(&mut self, len: u64) -> std::io::Result<()>;
    fn pwrite_extent(&mut self, offset: u64, data: &[u8]) -> std::io::Result<usize>;
    fn sync(&mut self) -> std::io::Result<()>;
}
