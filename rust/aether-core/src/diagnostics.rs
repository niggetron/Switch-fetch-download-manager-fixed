//! Rotating redacted local diagnostics log + runtime feature-flag kill switches (v9.0).

use std::collections::VecDeque;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

pub const FLAG_MULTI_LINK: u32 = 1 << 0;
pub const FLAG_HTTP3_QUIC: u32 = 1 << 1;
pub const FLAG_WREQ_EMULATION: u32 = 1 << 2;
pub const FLAG_IO_URING_PROBE: u32 = 1 << 3;
pub const FLAG_ARIA2_RPC: u32 = 1 << 4;
pub const FLAG_TAIL_HEDGE: u32 = 1 << 5;
pub const FLAG_AUTO_SPEED_UP: u32 = 1 << 6;
pub const FLAG_TURBO_MULTI_SOCKET: u32 = 1 << 7;
pub const FLAG_ANTI_DIP_WATCHDOG: u32 = 1 << 8;

const DEFAULT_FLAGS: u32 = FLAG_MULTI_LINK
    | FLAG_HTTP3_QUIC
    | FLAG_WREQ_EMULATION
    | FLAG_TAIL_HEDGE
    | FLAG_AUTO_SPEED_UP
    | FLAG_TURBO_MULTI_SOCKET
    | FLAG_ANTI_DIP_WATCHDOG;

const MAX_LOG_ENTRIES: usize = 256;

static FEATURE_FLAGS: AtomicU32 = AtomicU32::new(DEFAULT_FLAGS);
static LOG_RING: std::sync::OnceLock<Mutex<VecDeque<String>>> = std::sync::OnceLock::new();

fn log_ring() -> &'static Mutex<VecDeque<String>> {
    LOG_RING.get_or_init(|| Mutex::new(VecDeque::with_capacity(MAX_LOG_ENTRIES)))
}

pub fn set_feature_flags(mask: u32) {
    FEATURE_FLAGS.store(mask, Ordering::Release);
    record_diag("FLAGS", &format!("Updated runtime feature mask=0x{mask:03X}"));
}

pub fn feature_flags() -> u32 {
    FEATURE_FLAGS.load(Ordering::Acquire)
}

pub fn is_flag_enabled(flag: u32) -> bool {
    (feature_flags() & flag) != 0
}

/// Scrubs bearer tokens, cookies, RPC secrets, and signed URL query params before logging.
pub fn redact_sensitive(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for (idx, token) in input.split_whitespace().enumerate() {
        if idx > 0 {
            out.push(' ');
        }
        let lower = token.to_ascii_lowercase();
        if lower.starts_with("token:")
            || lower.starts_with("bearer=")
            || lower.starts_with("cookie:")
            || lower.starts_with("authorization:")
        {
            out.push_str("[REDACTED]");
        } else if let Some(q_pos) = token.find('?') {
            let (base, query) = token.split_at(q_pos);
            let q_lower = query.to_ascii_lowercase();
            if q_lower.contains("sig=")
                || q_lower.contains("signature=")
                || q_lower.contains("token=")
                || q_lower.contains("expires=")
                || q_lower.contains("x-amz-")
            {
                out.push_str(base);
                out.push_str("?[REDACTED_SIGNED_PARAMS]");
            } else {
                out.push_str(token);
            }
        } else {
            out.push_str(token);
        }
    }
    out
}

pub fn record_diag(tag: &str, message: &str) {
    let ts_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let clean = redact_sensitive(message);
    let line = format!("[{ts_ms}] [{tag}] {clean}");
    let mut guard = log_ring().lock().unwrap_or_else(|p| p.into_inner());
    if guard.len() >= MAX_LOG_ENTRIES {
        guard.pop_front();
    }
    guard.push_back(line);
}

pub fn export_diagnostics_text() -> String {
    let guard = log_ring().lock().unwrap_or_else(|p| p.into_inner());
    if guard.is_empty() {
        return format!(
            "AetherIO Rust v9.0 · feature_mask=0x{:03X} · ring empty",
            feature_flags()
        );
    }
    guard.iter().cloned().collect::<Vec<_>>().join("\n")
}
