use std::collections::HashMap;
use std::time::{Duration, Instant};

use serde::Serialize;

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

/// F5: Route HEAD / Range 0- Stream-0 probes strictly to the lowest-RTT healthy link.
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

/// F6: Immutable SegmentLease pinning stolen tails & retries strictly to the owning worker's link.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SegmentLease {
    pub index: usize,
    pub start: u64,
    pub end: u64,
    pub pinned_link: String,
    pub is_tail_hedge: bool,
}

/// Open-ended right-neighbor absorption (`_emptyRightOf`) on warm TLS sockets.
pub fn absorb_empty_right_of(states: &mut [u8], from_index: usize) -> Option<usize> {
    for (idx, state) in states.iter_mut().enumerate().skip(from_index + 1) {
        if *state == 0 {
            *state = 1; // Leased
            return Some(idx);
        }
    }
    None
}

/// `_splitSlowest`: Halves a lagging worker's active byte range `[current_pos .. end]` so a fast
/// link takes `[mid + 1 .. end]` cleanly without dropping any downloaded prefix.
pub fn bisect_slowest_range(current_pos: u64, end_inclusive: u64, min_split_bytes: u64) -> Option<(u64, u64)> {
    if end_inclusive <= current_pos {
        return None;
    }
    let remaining = end_inclusive - current_pos + 1;
    if remaining < min_split_bytes.saturating_mul(2) {
        return None;
    }
    let mid = current_pos + (remaining / 2);
    Some((mid, end_inclusive))
}

/// Multi-Mirror URL selector: pairs Wi-Fi with Mirror 0 and Cellular/Ethernet with distinct mirrors
/// to bypass per-IP CDN rate caps when bonding multiple links.
pub fn select_mirror_for_link<'a>(urls: &'a [String], link_key: &str, seg_index: usize) -> &'a str {
    if urls.is_empty() {
        return "";
    }
    if urls.len() == 1 {
        return urls[0].as_str();
    }
    let idx = if link_key.starts_with("cell:") {
        1 % urls.len()
    } else if link_key.starts_with("eth:") {
        2 % urls.len()
    } else {
        seg_index % urls.len()
    };
    urls[idx].as_str()
}

/// Bidirectional closed-loop autoSpeedUp controller (v3.0 + v8.0).
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

/// Live Ketch-style per-connection descriptor serialized for the Connections Dashboard.
#[derive(Debug, Clone, Serialize)]
pub struct ActiveConnectionSnapshot {
    pub conn_id: String,
    pub task_id: String,
    pub mirror_url: String,
    pub link_key: String,
    pub protocol: String,
    pub state: String,
    pub range_start: u64,
    pub range_end: u64,
    pub bytes_pulled: u64,
    pub speed_bps: u64,
    pub ttfb_ms: u64,
    pub blocked_reason: String,
}

pub trait IoSubstrate {
    fn preallocate(&mut self, len: u64) -> std::io::Result<()>;
    fn pwrite_extent(&mut self, offset: u64, data: &[u8]) -> std::io::Result<usize>;
    fn sync(&mut self) -> std::io::Result<()>;
}
