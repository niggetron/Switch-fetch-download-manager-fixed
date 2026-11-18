use std::sync::atomic::{AtomicU64, Ordering};

/// 4 KB anonymous mmap telemetry ledger with 64-byte cache-line isolation
/// to prevent false-sharing invalidations between the Rust data plane and Kotlin UI.
#[repr(C, align(4096))]
pub struct TelemetryPage {
    /// Cache Line 0 (0..63 B): UI 2-4 Hz read line
    pub line0: UiTelemetryLine,
    /// Cache Line 1 (64..127 B): Producer write cursor + 56 B pad guard
    pub head_line: ProducerLine,
    /// Cache Line 2 (128..191 B): Consumer read cursor + 56 B pad guard
    pub tail_line: ConsumerLine,
    /// Crash-safe 2 MB segment bitfield (up to 3904 bytes -> 15,616 extents = 31 GB)
    pub segment_bitfield: [u8; 4096 - 192],
}

#[repr(C, align(64))]
pub struct UiTelemetryLine {
    pub total_bytes: AtomicU64,
    pub instant_bps: AtomicU64,
    pub active_workers: AtomicU64,
    pub syscall_enters: AtomicU64,
    pub _pad: [u8; 32],
}

#[repr(C, align(64))]
pub struct ProducerLine {
    pub head: AtomicU64,
    pub _pad: [u8; 56],
}

#[repr(C, align(64))]
pub struct ConsumerLine {
    pub tail: AtomicU64,
    pub _pad: [u8; 56],
}

impl TelemetryPage {
    pub const fn new() -> Self {
        Self {
            line0: UiTelemetryLine {
                total_bytes: AtomicU64::new(0),
                instant_bps: AtomicU64::new(0),
                active_workers: AtomicU64::new(0),
                syscall_enters: AtomicU64::new(0),
                _pad: [0u8; 32],
            },
            head_line: ProducerLine {
                head: AtomicU64::new(0),
                _pad: [0u8; 56],
            },
            tail_line: ConsumerLine {
                tail: AtomicU64::new(0),
                _pad: [0u8; 56],
            },
            segment_bitfield: [0u8; 4096 - 192],
        }
    }

    pub fn record_commit(&self, bytes: u64) {
        self.line0.total_bytes.fetch_add(bytes, Ordering::Relaxed);
        self.line0.syscall_enters.fetch_add(1, Ordering::Relaxed);
        self.head_line.head.fetch_add(1, Ordering::Release);
    }
}
