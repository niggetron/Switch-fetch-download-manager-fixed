//! Rust download orchestrator: task lifecycle, Stream-0 probe, worker fan-out,
//! live range bisection, and the decoupled disk writer. Does NOT parse manifests
//! (HLS/DASH parsing is in `hls_dash.rs`, and the parsed segments are currently
//! only logged) and does NOT do raw socket I/O (that is `transport.rs`).
//!
//! Invariants:
//! - `max_workers` is clamped to `MAX_WORKERS_CAP` (128); thermal/governor decide activity.
//! - Network chunks are accumulated into 256 KiB slabs; slabs return to the pool
//!   BEFORE fdatasync so slow disks do not stall network workers.
//! - Data `fdatasync` always precedes ledger `fdatasync` (crash consistency).
//! - Range workers use `ActiveRangeState` CAS to split a slow tail; the stolen
//!   half is `[mid, old_end]`. See `ARCHITECTURE.md` for the full flow.

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::os::fd::FromRawFd;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use futures_util::StreamExt;
use tokio::runtime::Runtime;
use tokio::sync::{Notify, mpsc};

use crate::cas::{ContiguousSha256Verifier, blake3_digest_bytes};
use crate::diagnostics::{
    FLAG_ANTI_DIP_WATCHDOG, FLAG_AUTO_SPEED_UP, FLAG_FORCE_ALL_LINKS, FLAG_HTTP3_QUIC,
    FLAG_MULTI_LINK, FLAG_TAIL_HEDGE, FLAG_TURBO_MULTI_SOCKET, is_flag_enabled, record_diag,
};
use crate::hls_dash::{is_adaptive_manifest_url, parse_dash_mpd, parse_hls_m3u8};
use crate::ledger::{AetherLedger, SegmentStatus};
use crate::scheduler::{
    ActiveConnectionSnapshot, LinkCircuitBreaker, LinkDispatchStats, MAX_WORKERS_CAP,
    absorb_empty_right_of, adaptive_bdp_segment_bytes, adjust_auto_speed_up,
    effective_worker_count, select_link_with_force_all, select_lowest_rtt_link,
    select_mirror_for_link,
};
use crate::storage_and_telemetry::{
    BoundedSlabPool, SLAB_BYTES, SlabLeaseGuard, flush_fdatasync, live_telemetry_page,
    pack_quad_worker_counts, preallocate_file, pwrite_all,
};
use crate::transport::{
    BoundLinkState, TransportContext, fetch_h3_range_bytes, parse_content_disposition_filename,
    probe_bound_link_blocked_reason, send_wreq_range_request, sniff_mime_from_magic,
};

const DISK_BATCH_FLUSH_BYTES: u64 = 16 * 1024 * 1024; // Batch fdatasync every 16 MiB
const DISK_BATCH_FLUSH_INTERVAL: Duration = Duration::from_secs(2);
const TELEMETRY_PUSH_INTERVAL: Duration = Duration::from_millis(120);
const MIN_BISECT_REMAINING_BYTES: u64 = 2 * 1024 * 1024; // 2 MiB min remaining for live split
const STALL_BISECT_REMAINING_BYTES: u64 = 1024 * 1024; // 1 MiB if active worker stalled

#[derive(Debug, Clone)]
pub struct NativeDownloadTaskConfig {
    pub task_id: String,
    pub urls: Vec<String>,
    pub output_path: String,
    pub ledger_path: String,
    pub direct_fd: i32, // >= 0 when writing straight to MediaStore IS_PENDING=1 FD (<1 GiB)
    pub max_workers: usize,
    pub max_cellular_bytes: u64, // u64::MAX = unlimited
    pub browser_profile: String,
    pub protocol_mode: String, // "auto", "wreq_h2", "h3_quic"
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct TaskOutcomeEvent {
    pub task_id: String,
    pub state: String, // "STREAMING" | "COMPLETED" | "TOKEN_EXPIRED" | "ENOSPC" | "FAILED" | "PAUSED"
    pub file_name: String,
    pub mime_type: String,
    pub downloaded_bytes: u64,
    pub total_bytes: u64,
    pub wifi_bytes: u64,
    pub wifi2_bytes: u64,
    pub cell_bytes: u64,
    pub usb_bytes: u64,
    pub sha256_hex: String,
    pub ttfb_ms: u64,
    pub error_message: String,
}

pub struct TaskRuntimeHandle {
    pub cancelled: AtomicBool,
    pub paused: AtomicBool,
    pub updated_urls: RwLock<Vec<String>>,
    pub hotplug_notify: Arc<Notify>,
}

/// Decoupled disk-write job processed asynchronously by the blocking writer task so
/// HTTP/2 & HTTP/3 network workers never block on `pwrite`/`fdatasync`/SHA-256 locks.
struct DiskWriteJob {
    slab: SlabLeaseGuard,
    offset: u64,
    len: usize,
    seg_idx: usize,
    mark_seg_done: bool,
}

/// Shared atomic range cursor enabling lock-free live mid-flight range bisection (Anti-Dip).
struct ActiveRangeState {
    seg_idx: usize,
    current_offset: AtomicU64,
    end_offset: AtomicU64,
    ewma_bps: AtomicU64,
    last_progress_ms: AtomicU64,
}

/// Tracks the parallel writers of a single fixed ledger segment after anti-dip
/// bisection. A segment can only be marked `Done` when ALL of its sub-ranges
/// finish; if any one fails, the whole segment is rolled back to `Pending` and
/// re-downloaded from scratch (no orphaned halves, no overlapping writes).
#[derive(Debug, Clone, Copy)]
struct SplitState {
    parts: usize,
    full_end: u64,
}

/// Work unit claimed either from the ledger or stolen via live range bisection.
#[derive(Debug, Clone, Copy)]
struct WorkUnit {
    seg_idx: usize,
    start: u64,
    end: u64,
    is_bisected_steal: bool,
}

/// RAII guard tracking live in-flight bytes on a BoundLinkState so earliest-arrival scheduling
/// distributes workers simultaneously across Wi-Fi 1, Wi-Fi 2, 5G, and USB without starving any link.
struct InFlightGuard {
    link: Option<Arc<BoundLinkState>>,
    remaining: u64,
}

impl InFlightGuard {
    fn new(link: Option<Arc<BoundLinkState>>, bytes: u64) -> Self {
        if let Some(ref l) = link {
            l.in_flight_bytes.fetch_add(bytes, Ordering::Relaxed);
            l.active_workers.fetch_add(1, Ordering::Relaxed);
        }
        Self {
            link,
            remaining: bytes,
        }
    }

    fn consume(&mut self, written: u64) {
        let dec = written.min(self.remaining);
        if dec > 0 {
            self.remaining -= dec;
            if let Some(ref l) = self.link {
                sub_saturating_atomic(&l.in_flight_bytes, dec);
            }
        }
    }
}

impl Drop for InFlightGuard {
    fn drop(&mut self) {
        if let Some(ref l) = self.link {
            sub_saturating_atomic(&l.active_workers, 1);
            if self.remaining > 0 {
                sub_saturating_atomic(&l.in_flight_bytes, self.remaining);
            }
        }
        self.remaining = 0;
    }
}

fn sub_saturating_atomic(atom: &AtomicU64, delta: u64) {
    let mut cur = atom.load(Ordering::Relaxed);
    loop {
        let next = cur.saturating_sub(delta);
        match atom.compare_exchange_weak(cur, next, Ordering::Relaxed, Ordering::Relaxed) {
            Ok(_) => break,
            Err(actual) => cur = actual,
        }
    }
}

pub struct NativeEngine {
    pub runtime: Runtime,
    pub pool: Arc<BoundedSlabPool>,
    pub transport: Arc<TransportContext>,
    pub tasks: Mutex<HashMap<String, Arc<TaskRuntimeHandle>>>,
    pub connections: RwLock<HashMap<String, ActiveConnectionSnapshot>>,
    pub outcomes: RwLock<HashMap<String, TaskOutcomeEvent>>,
    pub thermal_worker_cap: AtomicI64,
}

impl NativeEngine {
    pub fn new() -> Self {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .thread_name("aether-rust-io")
            .build()
            .expect("Failed to start Tokio runtime");
        Self {
            runtime,
            pool: Arc::new(BoundedSlabPool::new()),
            transport: Arc::new(TransportContext::new()),
            tasks: Mutex::new(HashMap::new()),
            connections: RwLock::new(HashMap::new()),
            outcomes: RwLock::new(HashMap::new()),
            thermal_worker_cap: AtomicI64::new(MAX_WORKERS_CAP as i64),
        }
    }

    pub fn notify_hotplug(&self) {
        let handles: Vec<Arc<TaskRuntimeHandle>> = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .values()
            .cloned()
            .collect();
        for h in handles {
            h.hotplug_notify.notify_waiters();
        }
    }

    pub fn start_task(self: &Arc<Self>, cfg: NativeDownloadTaskConfig) {
        // P2 (C2/C3/C7 audits): `start_task` must be idempotent per task id. A
        // pause/resume or quota handoff could otherwise spawn a SECOND writer for
        // the same output file + ledger (two coroutines both writing the same
        // offsets), and a stale run's cleanup would delete the new run's handle.
        // Cancel any live generation before replacing it.
        if let Some(old) = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(cfg.task_id.clone(), Arc::new(TaskRuntimeHandle {
                cancelled: AtomicBool::new(false),
                paused: AtomicBool::new(false),
                updated_urls: RwLock::new(cfg.urls.clone()),
                hotplug_notify: Arc::new(Notify::new()),
            }))
        {
            // Signal the previous generation to stop; the new handle is already
            // installed so the old run's `Arc::ptr_eq` cleanup guard (below) is a no-op.
            old.cancelled.store(true, Ordering::Release);
            old.hotplug_notify.notify_waiters();
        }
        let handle = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&cfg.task_id)
            .cloned()
            .expect("just inserted");

        let engine = self.clone();
        self.runtime.spawn(async move {
            engine.run_download_task(cfg, handle).await;
        });
    }

    pub fn pause_task(&self, task_id: &str) {
        if let Some(h) = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(task_id)
        {
            h.paused.store(true, Ordering::Release);
            h.hotplug_notify.notify_waiters();
        }
    }

    pub fn resume_task(&self, task_id: &str) {
        if let Some(h) = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(task_id)
        {
            h.paused.store(false, Ordering::Release);
            h.hotplug_notify.notify_waiters();
        }
    }

    pub fn cancel_task(&self, task_id: &str) {
        if let Some(h) = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(task_id)
        {
            h.cancelled.store(true, Ordering::Release);
            h.hotplug_notify.notify_waiters();
        }
    }

    pub fn update_task_urls(&self, task_id: &str, new_urls: Vec<String>) {
        if let Some(h) = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(task_id)
        {
            *h.updated_urls.write().unwrap_or_else(|p| p.into_inner()) = new_urls;
            h.hotplug_notify.notify_waiters();
        }
    }

    pub fn connections_json(&self) -> String {
        let list: Vec<ActiveConnectionSnapshot> = self
            .connections
            .read()
            .unwrap_or_else(|p| p.into_inner())
            .values()
            .cloned()
            .collect();
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".to_string())
    }

    pub fn outcome_json(&self, task_id: &str) -> String {
        let guard = self.outcomes.read().unwrap_or_else(|p| p.into_inner());
        match guard.get(task_id) {
            Some(ev) => serde_json::to_string(ev).unwrap_or_default(),
            None => "{}".to_string(),
        }
    }

    fn set_outcome(&self, ev: TaskOutcomeEvent) {
        self.outcomes
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .insert(ev.task_id.clone(), ev);
    }

    pub fn upsert_conn(&self, snap: ActiveConnectionSnapshot) {
        self.connections
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .insert(snap.conn_id.clone(), snap);
        self.sync_telemetry_from_connections();
    }

    pub fn remove_conn(&self, conn_id: &str) {
        self.connections
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .remove(conn_id);
        self.sync_telemetry_from_connections();
    }

    pub fn sync_telemetry_from_connections(&self) {
        let guard = self.connections.read().unwrap_or_else(|p| p.into_inner());
        let mut wifi1_bps_bits = 0u64;
        let mut wifi2_bps_bits = 0u64;
        let mut cell_bps_bits = 0u64;
        let mut eth_bps_bits = 0u64;
        let mut wifi1_w = 0u64;
        let mut wifi2_w = 0u64;
        let mut cell_w = 0u64;
        let mut eth_w = 0u64;

        for conn in guard.values() {
            let bits_per_sec = conn.speed_bps.saturating_mul(8);
            if conn.link_key.starts_with("cell:") {
                cell_bps_bits = cell_bps_bits.saturating_add(bits_per_sec);
                cell_w += 1;
            } else if conn.link_key.starts_with("wifi2:") {
                wifi2_bps_bits = wifi2_bps_bits.saturating_add(bits_per_sec);
                wifi2_w += 1;
            } else if conn.link_key.starts_with("eth:") || conn.link_key.starts_with("usb:") {
                eth_bps_bits = eth_bps_bits.saturating_add(bits_per_sec);
                eth_w += 1;
            } else {
                wifi1_bps_bits = wifi1_bps_bits.saturating_add(bits_per_sec);
                wifi1_w += 1;
            }
        }
        let total_w = wifi1_w + wifi2_w + cell_w + eth_w;
        let packed_workers = pack_quad_worker_counts(total_w, wifi1_w, wifi2_w, cell_w, eth_w);

        let page = live_telemetry_page();
        page.wifi_goodput_bps
            .store(wifi1_bps_bits, Ordering::Release);
        page.wifi2_goodput_bps
            .store(wifi2_bps_bits, Ordering::Release);
        page.cell_goodput_bps
            .store(cell_bps_bits, Ordering::Release);
        page.eth_goodput_bps.store(eth_bps_bits, Ordering::Release);
        page.goodput_bps.store(
            wifi1_bps_bits
                .saturating_add(wifi2_bps_bits)
                .saturating_add(cell_bps_bits)
                .saturating_add(eth_bps_bits),
            Ordering::Release,
        );
        page.worker_counts_packed
            .store(packed_workers, Ordering::Release);
    }

    async fn run_download_task(
        self: Arc<Self>,
        cfg: NativeDownloadTaskConfig,
        handle: Arc<TaskRuntimeHandle>,
    ) {
        let task_id = cfg.task_id.clone();
        let task_epoch = Instant::now();
        // Take immediate RAII ownership of direct_fd so any early return closes it cleanly (zero FD leak).
        let direct_file_opt: Option<File> = if cfg.direct_fd >= 0 {
            Some(unsafe { File::from_raw_fd(cfg.direct_fd) })
        } else {
            None
        };
        let page = live_telemetry_page();
        page.total_written.store(0, Ordering::Release);
        page.wifi_bytes_total.store(0, Ordering::Release);
        page.wifi2_bytes_total.store(0, Ordering::Release);
        page.cell_bytes_total.store(0, Ordering::Release);
        page.eth_bytes_total.store(0, Ordering::Release);
        page.anti_dip_rescues.store(0, Ordering::Release);
        page.cellular_quota_remaining
            .store(cfg.max_cellular_bytes, Ordering::Release);

        let urls = handle
            .updated_urls
            .read()
            .unwrap_or_else(|p| p.into_inner())
            .clone();
        let primary_url = urls.first().cloned().unwrap_or_default();
        if primary_url.is_empty() {
            self.set_outcome(empty_outcome(task_id, "FAILED", "Empty URL"));
            return;
        }

        record_diag(
            "RUST_TASK",
            &format!("v9.0 Stream 0 probe starting: {primary_url}"),
        );

        // Step 1: Single-Connection Stream 0 Probe
        let links_snap = self.transport.snapshot_links();
        let probe_stats: Vec<LinkDispatchStats> = links_snap
            .iter()
            .map(|l| LinkDispatchStats {
                link_id: l.link_key.clone(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: (l.measured_ewma_bps.load(Ordering::Relaxed) as f64)
                    .max(250_000.0),
                rtt_min_secs: (l.rtt_ms.load(Ordering::Relaxed) as f64 / 1000.0).max(0.005),
                active_workers_on_link: 0,
            })
            .collect();
        let mut breakers = HashMap::new();
        let lowest_rtt_key = select_lowest_rtt_link(&probe_stats, &mut breakers, Instant::now())
            .map(str::to_string)
            .unwrap_or_else(|| "default:auto".to_string());
        let probe_link = links_snap
            .iter()
            .find(|l| l.link_key == lowest_rtt_key)
            .cloned();

        let t0 = Instant::now();
        let (probe_status, probe_headers, probe_first_bytes) = match self
            .execute_single_probe(
                &primary_url,
                probe_link.as_deref(),
                &cfg.browser_profile,
                &cfg.protocol_mode,
            )
            .await
        {
            Ok(res) => res,
            Err(e) => {
                record_diag("PROBE_ERR", &format!("Stream 0 probe error: {e}"));
                self.set_outcome(empty_outcome(task_id, "FAILED", &e.to_string()));
                return;
            }
        };
        let ttfb_ms = t0.elapsed().as_millis().max(1) as u64;
        page.ttfb_ms.store(ttfb_ms, Ordering::Release);

        if probe_status == 403 || probe_status == 410 {
            let mut ev = empty_outcome(
                task_id,
                "TOKEN_EXPIRED",
                &format!("HTTP {probe_status} signed token expired"),
            );
            ev.ttfb_ms = ttfb_ms;
            self.set_outcome(ev);
            return;
        }

        let content_disp = probe_headers
            .get("content-disposition")
            .and_then(|v| v.to_str().ok())
            .and_then(parse_content_disposition_filename);
        let url_name = primary_url
            .split('?')
            .next()
            .unwrap_or("")
            .rsplit('/')
            .next()
            .filter(|s| !s.is_empty())
            .unwrap_or("aether_download.bin")
            .to_string();
        let file_name = content_disp.unwrap_or(url_name);
        let mime_type = sniff_mime_from_magic(&probe_first_bytes, &file_name).to_string();

        if is_adaptive_manifest_url(&primary_url) {
            if let Ok(manifest_text) = String::from_utf8(probe_first_bytes.clone()) {
                let seg_list = if primary_url.to_ascii_lowercase().contains(".m3u8") {
                    parse_hls_m3u8(&primary_url, &manifest_text).and_then(|r| match r {
                        crate::hls_dash::ManifestParseResult::MediaSegments(s) => Some(s),
                        crate::hls_dash::ManifestParseResult::MasterVariant { .. } => None,
                    })
                } else {
                    parse_dash_mpd(&primary_url, &manifest_text)
                };
                if let Some(segs) = seg_list {
                    record_diag(
                        "HLS_DASH",
                        &format!("Adaptive manifest resolved {} segments", segs.len()),
                    );
                }
            }
        }

        let supports_range = probe_status == 206
            || probe_headers
                .get("accept-ranges")
                .and_then(|v| v.to_str().ok())
                .map(|v| v.eq_ignore_ascii_case("bytes"))
                .unwrap_or(false);

        // Unknown length must stay unknown. Using the 16 KiB probe snippet as the
        // total previously caused silent truncation AND a false COMPLETED outcome.
        // 0 means "stream to EOF" in the worker path.
        let total_bytes = parse_total_length(&probe_headers, probe_status).unwrap_or(0);
        let etag = probe_headers
            .get("etag")
            .or_else(|| probe_headers.get("last-modified"))
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();

        page.total_expected.store(total_bytes, Ordering::Release);

        // Open backing file
        let data_file: File = if let Some(f) = direct_file_opt {
            f
        } else {
            let p = PathBuf::from(&cfg.output_path);
            if let Some(parent) = p.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            match OpenOptions::new().create(true).read(true).write(true).open(&p) {
                Ok(f) => f,
                Err(e) => {
                    let mut ev = empty_outcome(task_id, "FAILED", &format!("Open output failed: {e}"));
                    ev.file_name = file_name;
                    ev.mime_type = mime_type;
                    ev.total_bytes = total_bytes;
                    ev.ttfb_ms = ttfb_ms;
                    self.set_outcome(ev);
                    return;
                }
            }
        };

        if let Err(e) = preallocate_file(&data_file, total_bytes) {
            let err_state = if e.kind() == std::io::ErrorKind::StorageFull {
                "ENOSPC"
            } else {
                "FAILED"
            };
            let mut ev = empty_outcome(task_id, err_state, &e.to_string());
            ev.file_name = file_name;
            ev.mime_type = mime_type;
            ev.total_bytes = total_bytes;
            ev.ttfb_ms = ttfb_ms;
            self.set_outcome(ev);
            return;
        }

        let fastest_kbps = links_snap
            .iter()
            .map(|l| l.downstream_kbps.load(Ordering::Relaxed))
            .max()
            .unwrap_or(60_000);
        let seg_bytes = adaptive_bdp_segment_bytes(fastest_kbps);
        let ledger_path = PathBuf::from(&cfg.ledger_path);
        let ledger = AetherLedger::load_or_create(&ledger_path, total_bytes, seg_bytes, &etag)
            .unwrap_or_else(|_| AetherLedger::new(total_bytes, seg_bytes, &etag));

        let resumed_bytes: u64 = ledger
            .states
            .iter()
            .enumerate()
            .filter(|&(_, &s)| s == SegmentStatus::Done as u8)
            .map(|(idx, _)| {
                let s = idx as u64 * ledger.segment_bytes;
                let e = (s + ledger.segment_bytes - 1).min(total_bytes.saturating_sub(1));
                if total_bytes > 0 && e >= s {
                    e - s + 1
                } else {
                    0
                }
            })
            .sum();

        page.total_written.store(resumed_bytes, Ordering::Release);
        page.contiguous_verified_offset
            .store(ledger.contiguous_verified_offset, Ordering::Release);

        let data_file = Arc::new(data_file);
        // Seed the SHA-256 verifier for resumed transfers: bytes before the ledger's
        // contiguous frontier are already on disk and must be part of the final digest.
        let resume_frontier = ledger.contiguous_verified_offset.min(total_bytes);
        let mut seeded_verifier = ContiguousSha256Verifier::with_offset(total_bytes, resume_frontier);
        if resume_frontier > 0 {
            #[cfg(unix)]
            {
                use std::os::fd::AsFd;
                let mut prefix = vec![0u8; 256 * 1024];
                let mut pos = 0u64;
                while pos < resume_frontier {
                    let n = (resume_frontier - pos).min(prefix.len() as u64) as usize;
                    match rustix::io::pread(data_file.as_fd(), &mut prefix[..n], pos) {
                        Ok(0) | Err(_) => break,
                        Ok(read) => {
                            seeded_verifier.seed_prefix(&prefix[..read]);
                            pos += read as u64;
                        }
                    }
                }
            }
        }
        let verifier = Arc::new(Mutex::new(seeded_verifier));
        let written_total = Arc::new(AtomicU64::new(resumed_bytes));
        let wifi_bytes_total = Arc::new(AtomicU64::new(0));
        let wifi2_bytes_total = Arc::new(AtomicU64::new(0));
        let cell_bytes_total = Arc::new(AtomicU64::new(0));
        let usb_bytes_total = Arc::new(AtomicU64::new(0));
        let token_expired_flag = Arc::new(AtomicBool::new(false));
        let disk_io_error = Arc::new(Mutex::new(None::<String>));
        let last_error_msg = Arc::new(Mutex::new(None::<String>));
        let seg_retries = Arc::new(Mutex::new(HashMap::<usize, u32>::new()));

        self.set_outcome(TaskOutcomeEvent {
            task_id: task_id.clone(),
            state: "STREAMING".into(),
            file_name: file_name.clone(),
            mime_type: mime_type.clone(),
            downloaded_bytes: resumed_bytes,
            total_bytes,
            wifi_bytes: 0,
            wifi2_bytes: 0,
            cell_bytes: 0,
            usb_bytes: 0,
            sha256_hex: String::new(),
            ttfb_ms,
            error_message: String::new(),
        });

        let ledger_arc = Arc::new(Mutex::new(ledger));
        let breakers_arc = Arc::new(Mutex::new(HashMap::<String, LinkCircuitBreaker>::new()));
        let active_ranges = Arc::new(Mutex::new(HashMap::<String, Arc<ActiveRangeState>>::new()));
        let splits = Arc::new(Mutex::new(HashMap::<usize, SplitState>::new()));

        // Spawn Decoupled Async Disk-Writer Task (batched fdatasync every 16 MiB or 2s!)
        let (disk_tx, mut disk_rx) = mpsc::channel::<DiskWriteJob>(16);
        let writer_file = data_file.clone();
        let writer_verifier = verifier.clone();
        let writer_ledger = ledger_arc.clone();
        let writer_ldg_path = ledger_path.clone();
        let writer_err = disk_io_error.clone();
        let writer_handle = tokio::task::spawn_blocking(move || {
            let mut bytes_since_sync = 0u64;
            let mut last_sync = Instant::now();
            while let Some(job) = disk_rx.blocking_recv() {
                if job.len > 0 {
                    let slice = &job.slab.as_slice()[..job.len];
                    let slab_b3 = blake3_digest_bytes(slice);
                    if let Err(e) = pwrite_all(&writer_file, slice, job.offset) {
                        *writer_err.lock().unwrap_or_else(|p| p.into_inner()) = Some(e.to_string());
                        break;
                    }
                    let mut local_verified_off = None;
                    if let Ok(verified_off) = writer_verifier
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .record_and_advance(job.offset, slice, Some(&writer_file))
                    {
                        local_verified_off = Some(verified_off);
                        live_telemetry_page()
                            .contiguous_verified_offset
                            .store(verified_off, Ordering::Release);
                    }
                    {
                        let mut lg = writer_ledger.lock().unwrap_or_else(|p| p.into_inner());
                        let slab_idx = (job.offset / SLAB_BYTES as u64) as usize;
                        // P3 (C2 audit): only record full, aligned slab digests. A bisected
                        // steal begins at a 4 KiB-aligned `mid`, so hashing its partial slab
                        // would overwrite the victim's aligned digest in the same bucket.
                        if job.offset % SLAB_BYTES as u64 == 0 && job.len as usize == SLAB_BYTES {
                            lg.record_slab_digest(slab_idx, slab_b3);
                        }
                        // P2 (C2 audit): use this writer's LOCAL frontier directly. Reading
                        // it back from the global telemetry page lets a concurrent task's
                        // writer overwrite the slot between the store above and this load,
                        // persisting the wrong frontier into our ledger on resume.
                        if let Some(v) = local_verified_off {
                            lg.contiguous_verified_offset = v;
                        }
                        if job.mark_seg_done && job.seg_idx < lg.states.len() {
                            lg.states[job.seg_idx] = SegmentStatus::Done as u8;
                        }
                    }
                    bytes_since_sync = bytes_since_sync.saturating_add(job.len as u64);
                } else if job.mark_seg_done {
                    let mut lg = writer_ledger.lock().unwrap_or_else(|p| p.into_inner());
                    if job.seg_idx < lg.states.len() {
                        lg.states[job.seg_idx] = SegmentStatus::Done as u8;
                    }
                }
                // Release slab immediately back to Semaphore pool BEFORE optional fdatasync!
                drop(job.slab);

                if bytes_since_sync >= DISK_BATCH_FLUSH_BYTES
                    || last_sync.elapsed() >= DISK_BATCH_FLUSH_INTERVAL
                {
                    // P2 (C2 audit): surface durability failures. Previously these errors
                    // were discarded while segments were already recorded Done, so after a
                    // crash the ledger claimed bytes were durable when they were not.
                    if let Err(e) = flush_fdatasync(&writer_file) {
                        *writer_err.lock().unwrap_or_else(|p| p.into_inner()) =
                            Some(format!("fdatasync failed: {e}"));
                        break;
                    }
                    let lg = writer_ledger.lock().unwrap_or_else(|p| p.into_inner());
                    if let Err(e) = lg.persist(&writer_ldg_path, Some(&writer_file)) {
                        *writer_err.lock().unwrap_or_else(|p| p.into_inner()) =
                            Some(format!("ledger persist failed: {e}"));
                        break;
                    }
                    live_telemetry_page()
                        .consumer_seq
                        .fetch_add(1, Ordering::Relaxed);
                    bytes_since_sync = 0;
                    last_sync = Instant::now();
                }
            }
            let _ = flush_fdatasync(&writer_file);
            let lg = writer_ledger.lock().unwrap_or_else(|p| p.into_inner());
            let _ = lg.persist(&writer_ldg_path, Some(&writer_file));
        });

        let thermal_cap = self
            .thermal_worker_cap
            .load(Ordering::Acquire)
            .clamp(1, MAX_WORKERS_CAP as i64) as usize;
        let links_now = self.transport.snapshot_links();
        let multi_link_on = is_flag_enabled(FLAG_MULTI_LINK);
        let turbo_on = is_flag_enabled(FLAG_TURBO_MULTI_SOCKET);
        let auto_speed_on = is_flag_enabled(FLAG_AUTO_SPEED_UP);
        let max_pool_workers = if supports_range && total_bytes > SLAB_BYTES as u64 {
            let link_count = if multi_link_on {
                links_now.len().max(1)
            } else {
                1
            };
            let desired = effective_worker_count(cfg.max_workers, link_count);
            // Do NOT jump straight to the hard cap. The old `boosted.max(thermal_cap)`
            // pinned every turbo download to 64 workers, which collapsed average
            // throughput under congestion and thermal throttling.
            let boosted = if turbo_on || auto_speed_on {
                desired.max((link_count * 4).min(MAX_WORKERS_CAP))
            } else {
                desired
            };
            boosted.min(thermal_cap).min(MAX_WORKERS_CAP).max(1)
        } else {
            1
        };
        let initial_target = cfg
            .max_workers
            .max(links_now.len().saturating_mul(4))
            .clamp(1, max_pool_workers);
        let target_workers = Arc::new(std::sync::atomic::AtomicUsize::new(initial_target));
        let scaling_done = Arc::new(AtomicBool::new(false));

        // Ghost-style closed-loop autoSpeedUp controller ramping live up to 64 parallel sockets!
        let scaler_handle = if auto_speed_on && max_pool_workers > 1 {
            let tw = target_workers.clone();
            let done_flag = scaling_done.clone();
            let w_tot_s = written_total.clone();
            let h_s = handle.clone();
            let min_w = (links_now.len().max(1) * 3).clamp(2, max_pool_workers);
            Some(tokio::spawn(async move {
                let mut prev_bytes = w_tot_s.load(Ordering::Relaxed);
                let mut prev_bps = 0.0f64;
                // C19/W3: rolling raw-goodput window for the stability gate (CV).
                let mut bps_window: std::collections::VecDeque<f64> =
                    std::collections::VecDeque::with_capacity(5);
                let mut prev_rtt = live_telemetry_page().ttfb_ms.load(Ordering::Relaxed) as f64;
                while !done_flag.load(Ordering::Acquire)
                    && !h_s.cancelled.load(Ordering::Acquire)
                    && !h_s.paused.load(Ordering::Acquire)
                {
                    tokio::time::sleep(Duration::from_millis(600)).await;
                    let cur_bytes = w_tot_s.load(Ordering::Relaxed);
                    let cur_bps = (cur_bytes.saturating_sub(prev_bytes) as f64) / 0.6;
                    let raw_rtt = live_telemetry_page().ttfb_ms.load(Ordering::Relaxed) as f64;
                    // A stale 30 s TTFB from a dead probe must not be treated as a
                    // fresh RTT spike (or as a 0 ms improvement on the next tick).
                    let cur_rtt = if raw_rtt > 5_000.0 { 0.0 } else { raw_rtt };
                    let delta_rtt = if prev_rtt > 0.0 && cur_rtt > 0.0 {
                        (cur_rtt - prev_rtt).max(0.0)
                    } else {
                        0.0
                    };
                    let cur_w = tw.load(Ordering::Acquire);
                    // Smooth the sample: raw 600 ms windows swing wildly on 5G and
                    // caused the governor to shed lanes after every normal dip.
                    let smooth_bps = if prev_bps <= 0.0 {
                        cur_bps
                    } else {
                        0.6 * prev_bps + 0.4 * cur_bps
                    };
                    // C19/W3: CV over the last 5 RAW goodput samples gates
                    // gain-driven steps so a noisy link does not cause the
                    // add-lanes -> goodput drop -> shed-lanes oscillation.
                    bps_window.push_back(cur_bps);
                    if bps_window.len() > 5 {
                        bps_window.pop_front();
                    }
                    let sample_cv = if bps_window.len() >= 3 {
                        let mean = bps_window.iter().sum::<f64>() / bps_window.len() as f64;
                        if mean > 0.0 {
                            let var = bps_window.iter().map(|v| (v - mean) * (v - mean)).sum::<f64>()
                                / bps_window.len() as f64;
                            var.sqrt() / mean
                        } else {
                            0.0
                        }
                    } else {
                        0.0
                    };
                    let next_w = adjust_auto_speed_up_with_cv(
                        cur_w,
                        min_w,
                        max_pool_workers,
                        prev_bps,
                        smooth_bps,
                        delta_rtt,
                        false,
                        sample_cv,
                    );
                    tw.store(next_w, Ordering::Release);
                    prev_bytes = cur_bytes;
                    prev_bps = smooth_bps;
                    prev_rtt = cur_rtt;
                }
            }))
        } else {
            None
        };

        let mut worker_handles = Vec::with_capacity(max_pool_workers);
        for worker_idx in 0..max_pool_workers {
            let eng = self.clone();
            let h = handle.clone();
            let ledger_ref = ledger_arc.clone();
            let brk_ref = breakers_arc.clone();
            let rng_ref = active_ranges.clone();
            let splits_ref = splits.clone();
            let tw_ref = target_workers.clone();
            let w_tot = written_total.clone();
            let wifi_tot = wifi_bytes_total.clone();
            let wifi2_tot = wifi2_bytes_total.clone();
            let cell_tot = cell_bytes_total.clone();
            let usb_tot = usb_bytes_total.clone();
            let tok_exp_ref = token_expired_flag.clone();
            let d_err_ref = disk_io_error.clone();
            let err_msg_ref = last_error_msg.clone();
            let retries_ref = seg_retries.clone();
            let tx = disk_tx.clone();
            let tid = task_id.clone();
            let fname_ref = file_name.clone();
            let mime_ref = mime_type.clone();
            let profile = cfg.browser_profile.clone();
            let max_cell = cfg.max_cellular_bytes;

            worker_handles.push(tokio::spawn(async move {
                let conn_id = format!("{tid}-w{worker_idx}");
                loop {
                    if h.cancelled.load(Ordering::Acquire)
                        || h.paused.load(Ordering::Acquire)
                        || tok_exp_ref.load(Ordering::Acquire)
                        || d_err_ref
                            .lock()
                            .unwrap_or_else(|p| p.into_inner())
                            .is_some()
                    {
                        break;
                    }

                    if worker_idx >= tw_ref.load(Ordering::Acquire) {
                        let any_work_left = {
                            let lg = ledger_ref.lock().unwrap_or_else(|p| p.into_inner());
                            lg.states.iter().any(|&s| s == SegmentStatus::Pending as u8)
                        } || !rng_ref.lock().unwrap_or_else(|p| p.into_inner()).is_empty();
                        if !any_work_left {
                            break;
                        }
                        tokio::time::sleep(Duration::from_millis(120)).await;
                        continue;
                    }

                    // 1. Claim next Pending segment from ledger, OR absorb right neighbor,
                    //    OR atomically bisect the slowest/largest active range (Anti-Dip!)
                    let seg_size = ledger_ref
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .segment_bytes;

                    let work_unit: Option<WorkUnit> = {
                        let claimed_seg = {
                            let mut lg = ledger_ref.lock().unwrap_or_else(|p| p.into_inner());
                            if let Some(pos) = lg
                                .states
                                .iter()
                                .position(|&s| s == SegmentStatus::Pending as u8)
                            {
                                lg.states[pos] = SegmentStatus::Leased as u8;
                                Some(pos)
                            } else if is_flag_enabled(FLAG_TAIL_HEDGE) {
                                absorb_empty_right_of(&mut lg.states, 0)
                            } else {
                                None
                            }
                        };

                        if let Some(idx) = claimed_seg {
                            let start = idx as u64 * seg_size;
                            let end = if total_bytes > 0 {
                                (start + seg_size - 1).min(total_bytes - 1)
                            } else {
                                0
                            };
                            if total_bytes > 0 && start > end {
                                None
                            } else {
                                Some(WorkUnit {
                                    seg_idx: idx,
                                    start,
                                    end,
                                    is_bisected_steal: false,
                                })
                            }
                        } else if supports_range
                            && total_bytes > 0
                            && (is_flag_enabled(FLAG_TAIL_HEDGE)
                                || is_flag_enabled(FLAG_ANTI_DIP_WATCHDOG))
                        {
                            try_steal_bisected_range(&rng_ref, &splits_ref, task_epoch)
                        } else {
                            None
                        }
                    };

                    let unit = match work_unit {
                        Some(u) => u,
                        None => {
                            let any_active = !rng_ref
                                .lock()
                                .unwrap_or_else(|p| p.into_inner())
                                .is_empty();
                            if !any_active {
                                break;
                            }
                            tokio::select! {
                                _ = tokio::time::sleep(Duration::from_millis(80)) => {}
                                _ = h.hotplug_notify.notified() => {}
                            }
                            continue;
                        }
                    };

                    let range_len = if total_bytes > 0 {
                        unit.end.saturating_sub(unit.start).saturating_add(1)
                    } else {
                        seg_size
                    };

                    let active_state = Arc::new(ActiveRangeState {
                        seg_idx: unit.seg_idx,
                        current_offset: AtomicU64::new(unit.start),
                        end_offset: AtomicU64::new(unit.end),
                        ewma_bps: AtomicU64::new(0),
                        last_progress_ms: AtomicU64::new(task_epoch.elapsed().as_millis() as u64),
                    });
                    rng_ref
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .insert(conn_id.clone(), active_state.clone());

                    // 2. Select link via real in-flight Earliest-Arrival F_k = (Q_k + S)/R_k + r_k/2
                    let links = eng.transport.snapshot_links();
                    let cell_used = cell_tot.load(Ordering::Relaxed);
                    let eligible_links: Vec<Arc<BoundLinkState>> = links
                        .into_iter()
                        .filter(|l| {
                            if l.is_cellular && max_cell > 0 && cell_used >= max_cell {
                                return false;
                            }
                            is_flag_enabled(FLAG_MULTI_LINK) || !l.is_cellular
                        })
                        .collect();

                    let stats: Vec<LinkDispatchStats> = eligible_links
                        .iter()
                        .map(|l| {
                            let real_ewma = l.measured_ewma_bps.load(Ordering::Relaxed) as f64;
                            let in_flight = l.in_flight_bytes.load(Ordering::Relaxed) as f64;
                            let active_w = l.active_workers.load(Ordering::Relaxed) as usize;
                            LinkDispatchStats {
                                link_id: l.link_key.clone(),
                                queued_bytes: in_flight,
                                ewma_bytes_per_sec: real_ewma.max(250_000.0),
                                rtt_min_secs: (l.rtt_ms.load(Ordering::Relaxed) as f64 / 1000.0)
                                    .max(0.005),
                                active_workers_on_link: active_w,
                            }
                        })
                        .collect();

                    let force_all = is_flag_enabled(FLAG_FORCE_ALL_LINKS);
                    let min_per_link = if force_all && stats.len() > 1 {
                        (tw_ref.load(Ordering::Relaxed) / (stats.len() * 2)).clamp(2, 8)
                    } else {
                        1
                    };
                    let picked_key = {
                        let mut brk = brk_ref.lock().unwrap_or_else(|p| p.into_inner());
                        select_link_with_force_all(
                            &stats,
                            &mut brk,
                            range_len as f64,
                            force_all,
                            worker_idx,
                            min_per_link,
                            Instant::now(),
                        )
                        .map(str::to_string)
                    }
                    .unwrap_or_else(|| "default:auto".to_string());

                    let picked_link = eligible_links
                        .iter()
                        .find(|l| l.link_key == picked_key)
                        .cloned();
                    let mut in_flight_guard = InFlightGuard::new(picked_link.clone(), range_len);

                    let mirror_url = {
                        let cur_urls = h.updated_urls.read().unwrap_or_else(|p| p.into_inner());
                        select_mirror_for_link(&cur_urls, &picked_key, unit.seg_idx).to_string()
                    };

                    eng.upsert_conn(ActiveConnectionSnapshot {
                        conn_id: conn_id.clone(),
                        task_id: tid.clone(),
                        mirror_url: mirror_url.clone(),
                        link_key: picked_key.clone(),
                        protocol: if worker_idx % 16 > 0 {
                            "H2 Shard".into()
                        } else if is_flag_enabled(FLAG_HTTP3_QUIC) {
                            "H2/H3".into()
                        } else {
                            "H2 wreq".into()
                        },
                        state: if unit.is_bisected_steal {
                            "Anti-Dip Split".into()
                        } else {
                            "Streaming".into()
                        },
                        range_start: unit.start,
                        range_end: unit.end,
                        bytes_pulled: 0,
                        speed_bps: 0,
                        ttfb_ms,
                        blocked_reason: "NONE".into(),
                    });

                    let range_hdr = if supports_range && total_bytes > 0 {
                        Some(format!("bytes={}-{}", unit.start, unit.end))
                    } else {
                        None
                    };

                    let proxy_ov = picked_link.as_ref().and_then(|l| {
                        l.proxy_override
                            .read()
                            .unwrap_or_else(|p| p.into_inner())
                            .clone()
                    });
                    let local_ip = picked_link
                        .as_ref()
                        .and_then(|l| *l.local_ip.read().unwrap_or_else(|p| p.into_inner()));
                    let picked_handle = picked_link
                        .as_ref()
                        .map(|l| l.net_handle.load(Ordering::Relaxed))
                        .unwrap_or(0);
                    let picked_iface = picked_link.as_ref().and_then(|l| {
                        l.interface_name
                            .read()
                            .unwrap_or_else(|p| p.into_inner())
                            .clone()
                    });

                    // 3. Use sharded persistent warm wreq::Client (independent TCP sockets!)
                    let res = match eng.transport.get_or_build_wreq_client(
                        &picked_key,
                        local_ip,
                        picked_iface.as_deref(),
                        proxy_ov.as_ref(),
                        &profile,
                        worker_idx,
                    ) {
                        Ok(client) => {
                            let seg_t0 = Instant::now();
                            match send_wreq_range_request(&client, &mirror_url, range_hdr.as_deref())
                                .await
                            {
                                Ok(resp) => {
                                    let status = resp.status().as_u16();
                                    if status == 403 || status == 410 {
                                        Err(format!("TOKEN_EXPIRED:{status}"))
                                    } else if status == 200 && range_hdr.is_some() {
                                        // Range was requested but the server replied with the
                                        // whole entity: writing it at unit.start corrupts the file.
                                        Err("SERVER_IGNORED_RANGE".to_string())
                                    } else if !(status == 200 || status == 206) {
                                        Err(format!("HTTP {status}"))
                                    } else {
                                        let mut cursor = unit.start;
                                        let mut seg_bytes_written = 0u64;
                                        let mut io_err: Option<String> = None;
                                        let mut last_ui_push = Instant::now();
                                        // Accumulate locally; publishing atomics per network
                                        // chunk made 128 workers fight over one cache line.
                                        let mut pending_bytes: u64 = 0;
                                        let mut current_slab: Option<SlabLeaseGuard> = None;
                                        let mut slab_fill = 0usize;
                                        let mut slab_start_offset = cursor;

                                        let mut stream = resp.bytes_stream();
                                        while let Some(chunk_res) = stream.next().await {
                                            let chunk = match chunk_res {
                                                Ok(c) => c,
                                                Err(e) => {
                                                    io_err = Some(e.to_string());
                                                    break;
                                                }
                                            };
                                            if h.cancelled.load(Ordering::Acquire)
                                                || h.paused.load(Ordering::Acquire)
                                            {
                                                break;
                                            }
                                            // Respect live bisection shrinking our end_offset!
                                            let dynamic_end =
                                                active_state.end_offset.load(Ordering::Acquire);
                                            if total_bytes > 0 && cursor > dynamic_end {
                                                break;
                                            }
                                            let allowed_len = if total_bytes > 0 {
                                                let rem = (dynamic_end - cursor + 1) as usize;
                                                chunk.len().min(rem)
                                            } else {
                                                chunk.len()
                                            };
                                            let mut pos = 0usize;
                                            while pos < allowed_len {
                                                if current_slab.is_none() {
                                                    match eng.pool.acquire_guard_async().await {
                                                        Ok(g) => {
                                                            slab_start_offset = cursor;
                                                            slab_fill = 0;
                                                            current_slab = Some(g);
                                                        }
                                                        Err(e) => {
                                                            io_err = Some(e.to_string());
                                                            break;
                                                        }
                                                    }
                                                }
                                                let space = SLAB_BYTES - slab_fill;
                                                let take = (allowed_len - pos).min(space);
                                                if let Some(ref mut g) = current_slab {
                                                    g.as_mut_slice()[slab_fill..slab_fill + take]
                                                        .copy_from_slice(&chunk[pos..pos + take]);
                                                }
                                                slab_fill += take;
                                                pos += take;
                                                cursor += take as u64;
                                                seg_bytes_written += take as u64;
                                                in_flight_guard.consume(take as u64);
                                                active_state
                                                    .current_offset
                                                    .store(cursor, Ordering::Release);
                                                active_state.last_progress_ms.store(
                                                    task_epoch.elapsed().as_millis() as u64,
                                                    Ordering::Relaxed,
                                                );

                                                // Flush full 512 KiB slab asynchronously to disk writer!
                                                if slab_fill == SLAB_BYTES {
                                                    if let Some(full_slab) = current_slab.take() {
                                                        if tx
                                                            .send(DiskWriteJob {
                                                                slab: full_slab,
                                                                offset: slab_start_offset,
                                                                len: SLAB_BYTES,
                                                                seg_idx: unit.seg_idx,
                                                                mark_seg_done: false,
                                                            })
                                                            .await
                                                            .is_err()
                                                        {
                                                            io_err = Some("Disk writer closed".into());
                                                            break;
                                                        }
                                                    }
                                                    slab_fill = 0;
                                                }

                                                // Local accumulation only. Shared atomics are
                                                // published once per TELEMETRY_PUSH_INTERVAL below.
                                                pending_bytes += take as u64;
                                            }
                                            if io_err.is_some() {
                                                break;
                                            }

                                            // Throttled telemetry & EWMA update (~120 ms)
                                            if last_ui_push.elapsed() >= TELEMETRY_PUSH_INTERVAL {
                                                if pending_bytes > 0 {
                                                    let tot = w_tot
                                                        .fetch_add(pending_bytes, Ordering::Relaxed)
                                                        + pending_bytes;
                                                    page.total_written.store(tot, Ordering::Release);
                                                    page.producer_seq.fetch_add(1, Ordering::Relaxed);
                                                    if picked_key.starts_with("cell:") {
                                                        let cb = cell_tot
                                                            .fetch_add(pending_bytes, Ordering::Relaxed)
                                                            + pending_bytes;
                                                        page.cell_bytes_total.store(cb, Ordering::Release);
                                                        page.cellular_quota_remaining.store(
                                                            max_cell.saturating_sub(cb),
                                                            Ordering::Release,
                                                        );
                                                    } else if picked_key.starts_with("wifi2:") {
                                                        let w2b = wifi2_tot
                                                            .fetch_add(pending_bytes, Ordering::Relaxed)
                                                            + pending_bytes;
                                                        page.wifi2_bytes_total.store(w2b, Ordering::Release);
                                                    } else if picked_key.starts_with("eth:")
                                                        || picked_key.starts_with("usb:")
                                                    {
                                                        let eb = usb_tot
                                                            .fetch_add(pending_bytes, Ordering::Relaxed)
                                                            + pending_bytes;
                                                        page.eth_bytes_total.store(eb, Ordering::Release);
                                                    } else {
                                                        let wb = wifi_tot
                                                            .fetch_add(pending_bytes, Ordering::Relaxed)
                                                            + pending_bytes;
                                                        page.wifi_bytes_total.store(wb, Ordering::Release);
                                                    }
                                                    pending_bytes = 0;
                                                }
                                                let secs = seg_t0.elapsed().as_secs_f64().max(0.05);
                                                let sample_bps =
                                                    (seg_bytes_written as f64 / secs) as u64;
                                                // True EWMA (0.7 old / 0.3 new) so a speed
                                                // collapse is visible to bisection within a
                                                // few ticks. A lifetime average hid laggards.
                                                let prev_bps = active_state
                                                    .ewma_bps
                                                    .load(Ordering::Relaxed);
                                                let ewma = if prev_bps == 0 {
                                                    sample_bps
                                                } else {
                                                    ((prev_bps as f64 * 0.7)
                                                        + (sample_bps as f64 * 0.3)) as u64
                                                };
                                                active_state.ewma_bps.store(ewma, Ordering::Relaxed);
                                                if let Some(ref l) = picked_link {
                                                    l.record_measured_bytes_per_sec(sample_bps);
                                                }
                                                eng.upsert_conn(ActiveConnectionSnapshot {
                                                    conn_id: conn_id.clone(),
                                                    task_id: tid.clone(),
                                                    mirror_url: mirror_url.clone(),
                                                    link_key: picked_key.clone(),
                                                    protocol: if is_flag_enabled(FLAG_HTTP3_QUIC) {
                                                        "H2/H3".into()
                                                    } else {
                                                        "H2 wreq".into()
                                                    },
                                                    state: if unit.is_bisected_steal {
                                                        "Anti-Dip Split".into()
                                                    } else {
                                                        "Streaming".into()
                                                    },
                                                    range_start: unit.start,
                                                    range_end: active_state
                                                        .end_offset
                                                        .load(Ordering::Relaxed),
                                                    bytes_pulled: seg_bytes_written,
                                                    speed_bps: ewma,
                                                    ttfb_ms,
                                                    blocked_reason: "NONE".into(),
                                                });
                                                eng.set_outcome(TaskOutcomeEvent {
                                                    task_id: tid.clone(),
                                                    state: "STREAMING".into(),
                                                    file_name: fname_ref.clone(),
                                                    mime_type: mime_ref.clone(),
                                                    downloaded_bytes: w_tot
                                                        .load(Ordering::Relaxed),
                                                    total_bytes,
                                                    wifi_bytes: wifi_tot.load(Ordering::Relaxed),
                                                    wifi2_bytes: wifi2_tot.load(Ordering::Relaxed),
                                                    cell_bytes: cell_tot.load(Ordering::Relaxed),
                                                    usb_bytes: usb_tot.load(Ordering::Relaxed),
                                                    sha256_hex: String::new(),
                                                    ttfb_ms,
                                                    error_message: String::new(),
                                                });
                                                last_ui_push = Instant::now();
                                            }

                                            if total_bytes > 0
                                                && cursor
                                                    > active_state
                                                        .end_offset
                                                        .load(Ordering::Acquire)
                                            {
                                                break;
                                            }
                                        }

                                        // C6 audit: final flush of the sub-interval byte tail.
                                        // `pending_bytes` is only published by the throttled
                                        // ~120 ms telemetry block above. Short adaptive segments
                                        // often complete faster than that, so their bytes were
                                        // silently dropped from `w_tot`/per-link counters and
                                        // the cellular quota was never enforced for them.
                                        if pending_bytes > 0 {
                                            let tot = w_tot
                                                .fetch_add(pending_bytes, Ordering::Relaxed)
                                                + pending_bytes;
                                            page.total_written.store(tot, Ordering::Release);
                                            page.producer_seq.fetch_add(1, Ordering::Relaxed);
                                            if picked_key.starts_with("cell:") {
                                                let cb = cell_tot
                                                    .fetch_add(pending_bytes, Ordering::Relaxed)
                                                    + pending_bytes;
                                                page.cell_bytes_total.store(cb, Ordering::Release);
                                                page.cellular_quota_remaining.store(
                                                    max_cell.saturating_sub(cb),
                                                    Ordering::Release,
                                                );
                                            } else if picked_key.starts_with("wifi2:") {
                                                let w2b = wifi2_tot
                                                    .fetch_add(pending_bytes, Ordering::Relaxed)
                                                    + pending_bytes;
                                                page.wifi2_bytes_total.store(w2b, Ordering::Release);
                                            } else if picked_key.starts_with("eth:")
                                                || picked_key.starts_with("usb:")
                                            {
                                                let eb = usb_tot
                                                    .fetch_add(pending_bytes, Ordering::Relaxed)
                                                    + pending_bytes;
                                                page.eth_bytes_total.store(eb, Ordering::Release);
                                            } else {
                                                let wb = wifi_tot
                                                    .fetch_add(pending_bytes, Ordering::Relaxed)
                                                    + pending_bytes;
                                                page.wifi_bytes_total.store(wb, Ordering::Release);
                                            }
                                            // No reset needed: this is the final flush and the
                                            // variable goes out of scope at the end of the range.
                                        }

                                        // A bisected segment has multiple writers. Only the
                                        // LAST one to finish may mark the fixed ledger segment
                                        // Done; otherwise a crash/resume would skip the other
                                        // half's unwritten bytes.
                                        // P1 (C2 audit): never mark a segment Done on a short
                                        // read. A server that closes the body early, or a 206
                                        // with a bogus Content-Range, previously produced a
                                        // zero-filled file reported as COMPLETED. Compare the
                                        // bytes this writer actually stored against its FINAL
                                        // dynamic range end (bisection shrinks `end_offset`
                                        // for the victim, so `unit.end` is NOT authoritative).
                                        let final_end =
                                            active_state.end_offset.load(Ordering::Acquire);
                                        let expected_len = if total_bytes > 0 {
                                            final_end.saturating_sub(unit.start).saturating_add(1)
                                        } else {
                                            0 // unknown-length stream-to-EOF has no fixed count
                                        };
                                        let short_read =
                                            expected_len > 0 && seg_bytes_written < expected_len;
                                        if short_read && io_err.is_none() {
                                            io_err = Some(format!(
                                                "SHORT_READ: wrote {} of {} bytes for segment {}",
                                                seg_bytes_written, expected_len, unit.seg_idx
                                            ));
                                        }
                                        let ok = io_err.is_none()
                                            && !h.cancelled.load(Ordering::Acquire)
                                            && !h.paused.load(Ordering::Acquire);
                                        let last_part = if ok {
                                            let mut sg =
                                                splits_ref.lock().unwrap_or_else(|p| p.into_inner());
                                            if let Some(state) = sg.get_mut(&unit.seg_idx) {
                                                state.parts -= 1;
                                                if state.parts == 0 {
                                                    sg.remove(&unit.seg_idx);
                                                    true
                                                } else {
                                                    false
                                                }
                                            } else {
                                                !unit.is_bisected_steal
                                            }
                                        } else {
                                            false
                                        };
                                        if let Some(rem_slab) = current_slab.take() {
                                            if tx
                                                .send(DiskWriteJob {
                                                    slab: rem_slab,
                                                    offset: slab_start_offset,
                                                    len: slab_fill,
                                                    seg_idx: unit.seg_idx,
                                                    mark_seg_done: last_part,
                                                })
                                                .await
                                                .is_err()
                                            {
                                                io_err = Some("Disk writer closed".into());
                                            }
                                        } else if last_part {
                                            let mut lg = ledger_ref
                                                .lock()
                                                .unwrap_or_else(|p| p.into_inner());
                                            if unit.seg_idx < lg.states.len() {
                                                lg.states[unit.seg_idx] = SegmentStatus::Done as u8;
                                            }
                                        }

                                        if let Some(err) = io_err {
                                            Err(err)
                                        } else {
                                            let secs = seg_t0.elapsed().as_secs_f64().max(0.05);
                                            if let Some(ref l) = picked_link {
                                                l.record_measured_bytes_per_sec(
                                                    (seg_bytes_written as f64 / secs) as u64,
                                                );
                                            }
                                            Ok(seg_bytes_written)
                                        }
                                    }
                                }
                                Err(e) => Err(e.to_string()),
                            }
                        }
                        Err(e) => Err(e.to_string()),
                    };

                    drop(in_flight_guard);
                    rng_ref
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .remove(&conn_id);

                    match res {
                        Ok(_) => {
                            if h.cancelled.load(Ordering::Acquire)
                                || h.paused.load(Ordering::Acquire)
                            {
                                unwind_failed_segment(
                                    unit.seg_idx,
                                    &splits_ref,
                                    &rng_ref,
                                    &ledger_ref,
                                );
                                break;
                            }
                            brk_ref
                                .lock()
                                .unwrap_or_else(|p| p.into_inner())
                                .entry(picked_key.clone())
                                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(6)))
                                .record_success();
                        }
                        Err(err) => {
                            if picked_handle > 0 && !err.starts_with("TOKEN_EXPIRED") {
                                probe_bound_link_blocked_reason(
                                    &mirror_url,
                                    picked_handle,
                                    proxy_ov.as_ref(),
                                )
                                .await;
                            }
                            record_diag(
                                "SEG_ERR",
                                &format!("Segment {} on {picked_key}: {err}", unit.seg_idx),
                            );
                            unwind_failed_segment(
                                unit.seg_idx,
                                &splits_ref,
                                &rng_ref,
                                &ledger_ref,
                            );
                            brk_ref
                                .lock()
                                .unwrap_or_else(|p| p.into_inner())
                                .entry(picked_key.clone())
                                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(6)))
                                .record_error_or_stall(Instant::now(), false);
                            if err.starts_with("TOKEN_EXPIRED") {
                                tok_exp_ref.store(true, Ordering::Release);
                                *err_msg_ref.lock().unwrap_or_else(|p| p.into_inner()) = Some(err);
                                break;
                            }
                            let attempts = {
                                let mut rmap =
                                    retries_ref.lock().unwrap_or_else(|p| p.into_inner());
                                let c = rmap.entry(unit.seg_idx).or_insert(0);
                                *c += 1;
                                *c
                            };
                            *err_msg_ref.lock().unwrap_or_else(|p| p.into_inner()) = Some(err);
                            if attempts >= 5 {
                                break;
                            }
                            // Exponential backoff: a hammering 120 ms retry loop on
                            // Connect failures kept the modem/CDN saturated and made
                            // recovery impossible (seen as hundreds of SEG_ERR lines).
                            let backoff_ms = (120u64 << (attempts - 1).min(4)).min(2_000);
                            tokio::time::sleep(Duration::from_millis(backoff_ms)).await;
                        }
                    }
                }
                eng.remove_conn(&conn_id);
            }));
        }

        // Drop original disk_tx so writer task terminates cleanly once all workers finish
        drop(disk_tx);

        for j in worker_handles {
            let _ = j.await;
        }
        scaling_done.store(true, Ordering::Release);
        if let Some(sh) = scaler_handle {
            sh.abort();
        }
        let _ = writer_handle.await;

        // P2 (C2 audit): surface the final durability failure too, so a truncated
        // file cannot be reported COMPLETED with a hash of bytes never flushed.
        if let Err(e) = flush_fdatasync(&data_file) {
            *disk_io_error.lock().unwrap_or_else(|p| p.into_inner()) =
                Some(format!("final fdatasync failed: {e}"));
        }
        // P1 (C2 audit): finalize from the backing file so a resume frontier that
        // trails the Done set still yields a SHA-256 over the entire declared file.
        let finalize_res = match Arc::try_unwrap(verifier) {
            Ok(m) => m
                .into_inner()
                .unwrap_or_else(|p| p.into_inner())
                .finalize_hex_from_file(&data_file),
            Err(v) => Ok(v
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .finalize_hex_ref()),
        };
        let final_sha = match finalize_res {
            Ok(h) => h,
            Err(e) => {
                *disk_io_error.lock().unwrap_or_else(|p| p.into_inner()) =
                    Some(format!("SHA-256 finalize failed: {e}"));
                String::new()
            }
        };

        let paused = handle.paused.load(Ordering::Acquire);
        let cancelled = handle.cancelled.load(Ordering::Acquire);
        let token_expired = token_expired_flag.load(Ordering::Acquire);
        let disk_err = disk_io_error
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .clone();
        let all_segments_done = {
            let lg = ledger_arc.lock().unwrap_or_else(|p| p.into_inner());
            lg.states.is_empty() || lg.states.iter().all(|&s| s == SegmentStatus::Done as u8)
        };
        // P1 defense-in-depth (C2 audit): a segment can now only be marked Done after its
        // writer stored the full expected byte count, but also confirm the on-disk file
        // actually reaches the declared total before reporting COMPLETED. Uses cheap
        // metadata, not the throttled `written_total` counter (which flushes only every
        // ~120 ms and would false-negative at completion).
        let file_len_ok = total_bytes == 0
            || data_file
                .metadata()
                .map(|m| m.len() >= total_bytes)
                .unwrap_or(false);
        let last_err = disk_err
            .or_else(|| {
                last_error_msg
                    .lock()
                    .unwrap_or_else(|p| p.into_inner())
                    .clone()
            })
            .unwrap_or_default();

        let (final_state, final_err) = if cancelled {
            ("CANCELLED", String::new())
        } else if paused {
            ("PAUSED", String::new())
        } else if token_expired {
            (
                "TOKEN_EXPIRED",
                if last_err.is_empty() {
                    "HTTP 403/410 signed token expired".into()
                } else {
                    last_err
                },
            )
        } else if all_segments_done && file_len_ok && last_err.is_empty() {
            let _ = std::fs::remove_file(&ledger_path);
            ("COMPLETED", String::new())
        } else {
            (
                "FAILED",
                if last_err.is_empty() {
                    "Transfer incomplete — tap to resume from AETHLDG2 checkpoint".into()
                } else {
                    last_err
                },
            )
        };

        // P2 (C2 audit): only the generation still installed in `tasks` may publish
        // an outcome or remove the handle. A superseded run (cancel-then-restart under
        // the same id) must not delete the new run's handle or overwrite its STREAMING
        // outcome with a stale terminal one.
        let is_current = self
            .tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&task_id)
            .map(|h| Arc::ptr_eq(h, &handle))
            .unwrap_or(false);
        if is_current {
            self.set_outcome(TaskOutcomeEvent {
                task_id: task_id.clone(),
                state: final_state.into(),
                file_name,
                mime_type,
                downloaded_bytes: written_total.load(Ordering::Acquire),
                total_bytes,
                wifi_bytes: wifi_bytes_total.load(Ordering::Acquire),
                wifi2_bytes: wifi2_bytes_total.load(Ordering::Acquire),
                cell_bytes: cell_bytes_total.load(Ordering::Acquire),
                usb_bytes: usb_bytes_total.load(Ordering::Acquire),
                sha256_hex: final_sha,
                ttfb_ms,
                error_message: final_err,
            });
            self.tasks
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .remove(&task_id);
        }
    }

    async fn execute_single_probe(
        &self,
        url: &str,
        link: Option<&BoundLinkState>,
        profile: &str,
        protocol_mode: &str,
    ) -> std::io::Result<(u16, http::HeaderMap, Vec<u8>)> {
        if (protocol_mode == "h3_quic" || is_flag_enabled(FLAG_HTTP3_QUIC))
            && url.starts_with("https://")
        {
            let handle = link
                .map(|l| l.net_handle.load(Ordering::Relaxed))
                .unwrap_or(0);
            let lkey = link.map(|l| l.link_key.as_str()).unwrap_or("default:auto");
            if let Ok((st, hdrs, bytes)) = fetch_h3_range_bytes(
                &self.transport,
                lkey,
                handle,
                url,
                Some("bytes=0-16383"),
                16384,
            )
            .await
            {
                live_telemetry_page()
                    .state_flags
                    .fetch_or(1 << 10, Ordering::AcqRel);
                return Ok((st.as_u16(), hdrs, bytes));
            }
        }

        let lkey = link.map(|l| l.link_key.as_str()).unwrap_or("default:auto");
        let proxy_ov = link.and_then(|l| {
            l.proxy_override
                .read()
                .unwrap_or_else(|p| p.into_inner())
                .clone()
        });
        let local_ip = link.and_then(|l| *l.local_ip.read().unwrap_or_else(|p| p.into_inner()));
        let iface = link.and_then(|l| {
            l.interface_name
                .read()
                .unwrap_or_else(|p| p.into_inner())
                .clone()
        });
        let client = self
            .transport
            .get_or_build_wreq_client(lkey, local_ip, iface.as_deref(), proxy_ov.as_ref(), profile, 0)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

        let resp = send_wreq_range_request(&client, url, Some("bytes=0-16383"))
            .await
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

        live_telemetry_page()
            .state_flags
            .fetch_or(1 << 11, Ordering::AcqRel);
        let status = resp.status().as_u16();
        let headers = resp.headers().clone();
        let mut snippet = Vec::new();
        let mut stream = resp.bytes_stream();
        while let Some(chunk_res) = stream.next().await {
            let chunk = chunk_res
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
            let take = chunk.len().min(16384usize.saturating_sub(snippet.len()));
            snippet.extend_from_slice(&chunk[..take]);
            if snippet.len() >= 16384 {
                break;
            }
        }
        Ok((status, headers, snippet))
    }
}

/// Roll back a bisected segment after any part fails or is cancelled:
/// cancel every sibling range, restore the ledger state to Pending so the
/// whole fixed segment is re-downloaded from scratch. This is the key safety
/// net that prevents orphaned unwritten halves and overlapping pwrites.
fn unwind_failed_segment(
    seg_idx: usize,
    splits: &Mutex<HashMap<usize, SplitState>>,
    active_ranges: &Mutex<HashMap<String, Arc<ActiveRangeState>>>,
    ledger: &Mutex<AetherLedger>,
) {
    splits
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .remove(&seg_idx);
    active_ranges
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .retain(|_, st| st.seg_idx != seg_idx);
    let mut lg = ledger.lock().unwrap_or_else(|p| p.into_inner());
    if seg_idx < lg.states.len() {
        lg.states[seg_idx] = SegmentStatus::Pending as u8;
    }
}

/// Atomically bisects the slowest or largest remaining active worker range so idle workers
/// immediately steal its trailing half instead of letting throughput dip from 400 Mbps to 3 Mbps!
fn try_steal_bisected_range(
    rng_ref: &Arc<Mutex<HashMap<String, Arc<ActiveRangeState>>>>,
    splits: &Arc<Mutex<HashMap<usize, SplitState>>>,
    task_epoch: Instant,
) -> Option<WorkUnit> {
    let now_ms = task_epoch.elapsed().as_millis() as u64;
    let candidates: Vec<Arc<ActiveRangeState>> = rng_ref
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .values()
        .cloned()
        .collect();

    let mut best: Option<(f64, Arc<ActiveRangeState>)> = None;
    for st in &candidates {
        let cur = st.current_offset.load(Ordering::Acquire);
        let end = st.end_offset.load(Ordering::Acquire);
        if end <= cur {
            continue;
        }
        let rem = end - cur + 1;
        let idle_ms = now_ms.saturating_sub(st.last_progress_ms.load(Ordering::Relaxed));
        let is_stalled = idle_ms >= 900;
        let min_req = if is_stalled {
            STALL_BISECT_REMAINING_BYTES
        } else {
            MIN_BISECT_REMAINING_BYTES
        };
        if rem < min_req {
            continue;
        }
        let bps = st.ewma_bps.load(Ordering::Relaxed).max(65_536) as f64;
        let est_eta_secs = (rem as f64 / bps) + if is_stalled { 10.0 } else { 0.0 };
        if best
            .as_ref()
            .map(|(score, _)| est_eta_secs > *score)
            .unwrap_or(true)
        {
            best = Some((est_eta_secs, st.clone()));
        }
    }

    let (_, victim) = best?;
    // Speed-weighted cut (aria2-next style): give the faster link more bytes.
    // `typical` is the median measured speed of the healthy active ranges, so
    // the victim keeps `share = own / (own + typical)` of the remainder. Both
    // halves are clamped to at least MIN_BISECT_REMAINING_BYTES and the split
    // point is rounded down to 4096 to keep slab/digest boundaries aligned.
    let measured: Vec<f64> = candidates
        .iter()
        .map(|s| s.ewma_bps.load(Ordering::Relaxed) as f64)
        .filter(|&v| v > 65_536.0)
        .collect();
    let typical = if measured.is_empty() {
        1_000_000.0
    } else {
        let mut m = measured.clone();
        m.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
        m[m.len() / 2]
    };
    let own = victim.ewma_bps.load(Ordering::Relaxed) as f64;
    let share = if own > 65_536.0 {
        (own / (own + typical)).clamp(0.25, 0.75)
    } else {
        0.5 // no measurement yet - split evenly
    };
    loop {
        let cur = victim.current_offset.load(Ordering::Acquire);
        let old_end = victim.end_offset.load(Ordering::Acquire);
        if old_end <= cur {
            return None;
        }
        let rem = old_end - cur + 1;
        if rem < STALL_BISECT_REMAINING_BYTES {
            return None;
        }
        let min_half = MIN_BISECT_REMAINING_BYTES.min(rem / 2);
        let mut mid = cur + (rem as f64 * share) as u64;
        mid = mid.clamp(cur + min_half, old_end - min_half + 1);
        mid -= mid % 4096; // 4 KiB alignment keeps slabs and digests aligned
        if mid <= cur || mid > old_end {
            mid = cur + rem / 2;
            mid -= mid % 4096;
        }
        let new_victim_end = mid.saturating_sub(1);
        if new_victim_end <= cur || mid > old_end {
            return None;
        }
        if victim
            .end_offset
            .compare_exchange(old_end, new_victim_end, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            // Register the second writer so Done is gated on BOTH halves.
            {
                let mut sg = splits.lock().unwrap_or_else(|p| p.into_inner());
                let entry = sg.entry(victim.seg_idx).or_insert(SplitState {
                    parts: 1,
                    full_end: old_end,
                });
                entry.parts += 1;
                entry.full_end = entry.full_end.max(old_end);
            }
            live_telemetry_page()
                .anti_dip_rescues
                .fetch_add(1, Ordering::Relaxed);
            return Some(WorkUnit {
                seg_idx: victim.seg_idx,
                start: mid,
                end: old_end,
                is_bisected_steal: true,
            });
        }
    }
}

fn empty_outcome(task_id: String, state: &str, err: &str) -> TaskOutcomeEvent {
    TaskOutcomeEvent {
        task_id,
        state: state.into(),
        file_name: String::new(),
        mime_type: "application/octet-stream".into(),
        downloaded_bytes: 0,
        total_bytes: 0,
        wifi_bytes: 0,
        wifi2_bytes: 0,
        cell_bytes: 0,
        usb_bytes: 0,
        sha256_hex: String::new(),
        ttfb_ms: 0,
        error_message: err.into(),
    }
}

fn parse_total_length(headers: &http::HeaderMap, status: u16) -> Option<u64> {
    if status == 206 {
        // P2 (C2 audit): a 206 MUST get its entity size from Content-Range. If the
        // total is unknown (`bytes 0-16383/*`) or the header is missing, fall through
        // to the unknown-length path (`total_bytes = 0`, stream to EOF). Previously
        // this fell back to Content-Length — which on a 206 is only the partial body
        // length — producing a tiny bogus "total" and a silent truncated COMPLETED.
        if let Some(cr) = headers.get("content-range").and_then(|v| v.to_str().ok()) {
            if let Some(slash) = cr.rsplit('/').next() {
                if let Ok(len) = slash.trim().parse::<u64>() {
                    return Some(len);
                }
            }
        }
        return None;
    }
    headers
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.trim().parse::<u64>().ok())
}
