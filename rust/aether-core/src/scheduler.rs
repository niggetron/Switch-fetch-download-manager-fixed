use std::collections::HashMap;
use std::time::{Duration, Instant};

use serde::Serialize;

// 64 concurrent sockets to one CDN caused congestion collapse (70 Mbps spikes,
// 25 Mbps average). 24 is enough to fill a 5G/Wi-Fi link while staying stable,
// especially under the thermal throttling seen on real devices.
pub const MAX_WORKERS_CAP: usize = 24;

/// Adaptive BDP extent sizing for high-speed Wi-Fi 6 / 5G / Quad-Link pipes:
/// - < 50 Mbps -> 1 MiB
/// - 50..120 Mbps -> 4 MiB
/// - 120..250 Mbps -> 8 MiB
/// - 250..600 Mbps -> 16 MiB
/// - > 600 Mbps -> 32 MiB
pub fn adaptive_bdp_segment_bytes(downstream_kbps: u64) -> u64 {
    let mbps = downstream_kbps as f64 / 1000.0;
    if mbps < 50.0 {
        1024 * 1024 // 1 MiB
    } else if mbps <= 120.0 {
        4 * 1024 * 1024 // 4 MiB
    } else if mbps <= 250.0 {
        8 * 1024 * 1024 // 8 MiB
    } else if mbps <= 600.0 {
        16 * 1024 * 1024 // 16 MiB
    } else {
        32 * 1024 * 1024 // 32 MiB
    }
}

/// Guarantees at least one active worker per validated active link (and up to 64 total).
pub fn effective_worker_count(user_max_connections: usize, validated_active_links: usize) -> usize {
    user_max_connections
        .max(validated_active_links)
        .clamp(1, MAX_WORKERS_CAP)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BreakerState {
    Closed,
    Open,
    HalfOpen,
}

/// 3-State Per-Link Circuit Breaker (Closed -> Open -> HalfOpen -> Closed).
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
    pub active_workers_on_link: usize,
}

/// Force-All-Links + Earliest-Arrival dispatcher (v9.1):
/// When `force_all_links` is true, every healthy link (Wi-Fi 1, Wi-Fi 2, 5G/LTE, USB)
/// is guaranteed at least `min_reserved_per_link` active streams before Earliest-Arrival
/// weighting applies — guaranteeing Mobile Data & Wi-Fi 2 are never starved by Wi-Fi 1!
pub fn select_link_with_force_all<'a>(
    candidates: &'a [LinkDispatchStats],
    breakers: &mut HashMap<String, LinkCircuitBreaker>,
    segment_bytes: f64,
    force_all_links: bool,
    worker_idx: usize,
    min_reserved_per_link: usize,
    now: Instant,
) -> Option<&'a str> {
    let healthy: Vec<&LinkDispatchStats> = candidates
        .iter()
        .filter(|c| {
            let b = breakers
                .entry(c.link_id.clone())
                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(6)));
            b.allow_request(now) && c.ewma_bytes_per_sec > 0.0
        })
        .collect();

    if healthy.is_empty() {
        return None;
    }
    if healthy.len() == 1 {
        return Some(healthy[0].link_id.as_str());
    }

    if force_all_links {
        let starved: Vec<&LinkDispatchStats> = healthy
            .iter()
            .copied()
            .filter(|c| c.active_workers_on_link < min_reserved_per_link)
            .collect();
        if !starved.is_empty() {
            let min_w = starved
                .iter()
                .map(|c| c.active_workers_on_link)
                .min()
                .unwrap_or(0);
            let least_loaded: Vec<&LinkDispatchStats> = starved
                .into_iter()
                .filter(|c| c.active_workers_on_link == min_w)
                .collect();
            return Some(least_loaded[worker_idx % least_loaded.len()].link_id.as_str());
        }
    }

    healthy
        .into_iter()
        .min_by(|a, b| {
            let fa = (a.queued_bytes + segment_bytes) / a.ewma_bytes_per_sec + (a.rtt_min_secs * 0.5);
            let fb = (b.queued_bytes + segment_bytes) / b.ewma_bytes_per_sec + (b.rtt_min_secs * 0.5);
            fa.total_cmp(&fb)
        })
        .map(|c| c.link_id.as_str())
}

/// Earliest-Arrival scheduler minimizing F_k = (Q_k + S) / R_k + r_k / 2.
pub fn select_earliest_arrival_link<'a>(
    candidates: &'a [LinkDispatchStats],
    breakers: &mut HashMap<String, LinkCircuitBreaker>,
    segment_bytes: f64,
    now: Instant,
) -> Option<&'a str> {
    select_link_with_force_all(candidates, breakers, segment_bytes, false, 0, 1, now)
}

/// Route Stream-0 probes strictly to the lowest-RTT healthy link.
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
                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(6)));
            b.allow_request(now)
        })
        .min_by(|a, b| a.rtt_min_secs.total_cmp(&b.rtt_min_secs))
        .map(|c| c.link_id.as_str())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SegmentLease {
    pub index: usize,
    pub start: u64,
    pub end: u64,
    pub pinned_link: String,
    pub is_tail_hedge: bool,
}

/// Open-ended right-neighbor absorption on warm pooled sockets.
pub fn absorb_empty_right_of(states: &mut [u8], from_index: usize) -> Option<usize> {
    for (idx, state) in states.iter_mut().enumerate().skip(from_index + 1) {
        if *state == 0 {
            *state = 1; // Leased
            return Some(idx);
        }
    }
    None
}

/// Halves a lagging worker's remaining byte range `[current_pos .. end_inclusive]` so an idle
/// fast worker takes `[mid .. end_inclusive]` atomically with zero duplicate download bytes.
pub fn bisect_slowest_range(
    current_pos: u64,
    end_inclusive: u64,
    min_split_bytes: u64,
) -> Option<(u64, u64)> {
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

/// Quad-link multi-mirror URL selector (`wifi:` -> 0, `cell:` -> 1, `wifi2:` -> 2, `eth:`/`usb:` -> 3).
pub fn select_mirror_for_link<'a>(urls: &'a [String], link_key: &str, seg_index: usize) -> &'a str {
    if urls.is_empty() {
        return "";
    }
    if urls.len() == 1 {
        return urls[0].as_str();
    }
    let base = if link_key.starts_with("cell:") {
        1
    } else if link_key.starts_with("wifi2:") {
        2
    } else if link_key.starts_with("eth:") || link_key.starts_with("usb:") {
        3
    } else {
        0
    };
    urls[(base + seg_index) % urls.len()].as_str()
}

/// Ghost-style bidirectional closed-loop autoSpeedUp controller scaling cleanly up to 64 workers.
pub fn adjust_auto_speed_up(
    current_workers: usize,
    min_workers: usize,
    max_workers: usize,
    prev_goodput_bps: f64,
    new_goodput_bps: f64,
    delta_rtt_ms: f64,
    throttled: bool,
) -> usize {
    let clamped_max = max_workers.clamp(1, MAX_WORKERS_CAP);
    let clamped_min = min_workers.clamp(1, clamped_max);
    if throttled || delta_rtt_ms > 40.0 {
        return current_workers.saturating_sub(2).max(clamped_min);
    }
    if prev_goodput_bps <= 0.0 && new_goodput_bps > 0.0 && delta_rtt_ms < 20.0 {
        return (current_workers + 4).min(clamped_max);
    }
    if prev_goodput_bps > 0.0 {
        let gain = (new_goodput_bps - prev_goodput_bps) / prev_goodput_bps;
        // Back off aggressively when adding sockets made things WORSE. The old
        // governor only ever increased workers, so a congested 64-worker pool
        // stayed at 64 forever and average throughput collapsed.
        if gain < -0.08 {
            let step = (current_workers / 4).max(2);
            return current_workers.saturating_sub(step).max(clamped_min);
        }
        if gain > 0.05 && delta_rtt_ms < 18.0 {
            let step = if current_workers < 12 { 4 } else { 2 };
            return (current_workers + step).min(clamped_max);
        }
    }
    current_workers.clamp(clamped_min, clamped_max)
}

#[derive(Debug, Clone, Serialize)]
pub struct ActiveConnectionSnapshot {
    pub conn_id: String,
    pub task_id: String,
    #[serde(rename = "url")]
    pub mirror_url: String,
    #[serde(rename = "link_key_id")]
    pub link_key: String,
    pub protocol: String,
    #[serde(rename = "socket_state")]
    pub state: String,
    pub range_start: u64,
    pub range_end: u64,
    #[serde(rename = "bytes_received")]
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
