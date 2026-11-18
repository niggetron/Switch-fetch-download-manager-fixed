//! AetherIO v8.0 Async Rust Download Orchestrator:
//! - Single-connection Stream 0 probe first (206 + ETag + RFC 6266 + magic MIME + ENOSPC)
//! - Multi-mirror URL bonding across Wi-Fi / 5G / hot-plugged USB-C Ethernet
//! - Open-ended ranges + `_emptyRightOf` warm socket absorption + `_splitSlowest` bisection
//! - Lock-free Semaphore BoundedSlabPool -> in-cache BLAKE3 -> pwrite -> fdatasync -> AETHLDG2 msync
//! - Page-cache sequential SHA-256 advancement prior to POSIX_FADV_DONTNEED
//! - Real-time per-link memfd TelemetryPage updates & Ketch-style ActiveConnectionSnapshot registry

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::os::fd::FromRawFd;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use tokio::runtime::Runtime;
use tokio::sync::Notify;

use crate::cas::{ContiguousSha256Verifier, blake3_digest_bytes};
use crate::diagnostics::{
    FLAG_AUTO_SPEED_UP, FLAG_HTTP3_QUIC, FLAG_MULTI_LINK, FLAG_TAIL_HEDGE, is_flag_enabled,
    record_diag,
};
use crate::hls_dash::{is_adaptive_manifest_url, parse_dash_mpd, parse_hls_m3u8};
use crate::ledger::{AetherLedger, SegmentStatus};
use crate::scheduler::{
    ActiveConnectionSnapshot, BreakerState, LinkCircuitBreaker, LinkDispatchStats,
    absorb_empty_right_of, adaptive_bdp_segment_bytes, adjust_auto_speed_up,
    bisect_slowest_range, effective_worker_count, select_earliest_arrival_link,
    select_lowest_rtt_link, select_mirror_for_link,
};
use crate::storage_and_telemetry::{
    BoundedSlabPool, SLAB_BYTES, flush_fdatasync, live_telemetry_page, preallocate_file,
    pwrite_all,
};
use crate::transport::{
    ProxyOverride, TransportContext, build_wreq_link_client, fetch_h3_range_bytes,
    parse_content_disposition_filename, send_wreq_range_request, sniff_mime_from_magic,
};

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
    pub cell_bytes: u64,
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
            thermal_worker_cap: AtomicI64::new(8),
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
        let handle = Arc::new(TaskRuntimeHandle {
            cancelled: AtomicBool::new(false),
            paused: AtomicBool::new(false),
            updated_urls: RwLock::new(cfg.urls.clone()),
            hotplug_notify: Arc::new(Notify::new()),
        });
        self.tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(cfg.task_id.clone(), handle.clone());

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

    fn upsert_conn(&self, snap: ActiveConnectionSnapshot) {
        self.connections
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .insert(snap.conn_id.clone(), snap);
    }

    fn remove_conn(&self, conn_id: &str) {
        self.connections
            .write()
            .unwrap_or_else(|p| p.into_inner())
            .remove(conn_id);
    }

    async fn run_download_task(
        self: Arc<Self>,
        cfg: NativeDownloadTaskConfig,
        handle: Arc<TaskRuntimeHandle>,
    ) {
        let task_id = cfg.task_id.clone();
        let page = live_telemetry_page();
        page.total_written.store(0, Ordering::Release);
        page.wifi_bytes_total.store(0, Ordering::Release);
        page.cell_bytes_total.store(0, Ordering::Release);
        page.cellular_quota_remaining
            .store(cfg.max_cellular_bytes, Ordering::Release);

        let urls = handle
            .updated_urls
            .read()
            .unwrap_or_else(|p| p.into_inner())
            .clone();
        let primary_url = urls.first().cloned().unwrap_or_default();
        if primary_url.is_empty() {
            self.set_outcome(TaskOutcomeEvent {
                task_id,
                state: "FAILED".into(),
                file_name: String::new(),
                mime_type: "application/octet-stream".into(),
                downloaded_bytes: 0,
                total_bytes: 0,
                wifi_bytes: 0,
                cell_bytes: 0,
                sha256_hex: String::new(),
                ttfb_ms: 0,
                error_message: "Empty URL".into(),
            });
            return;
        }

        record_diag("RUST_TASK", &format!("Stream 0 probe starting: {primary_url}"));

        // Step 1: Mandatory Single-Connection Stream 0 Probe (no blind parallel burst at t=0)
        let links_snap = self.transport.snapshot_links();
        let probe_stats: Vec<LinkDispatchStats> = links_snap
            .iter()
            .map(|l| LinkDispatchStats {
                link_id: l.link_key.clone(),
                queued_bytes: 0.0,
                ewma_bytes_per_sec: (l.downstream_kbps.load(Ordering::Relaxed) as f64 * 125.0)
                    .max(125_000.0),
                rtt_min_secs: (l.rtt_ms.load(Ordering::Relaxed) as f64 / 1000.0).max(0.005),
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
            .execute_single_probe(&primary_url, probe_link.as_deref(), &cfg.browser_profile, &cfg.protocol_mode)
            .await
        {
            Ok(res) => res,
            Err(e) => {
                record_diag("PROBE_ERR", &format!("Stream 0 probe error: {e}"));
                self.set_outcome(TaskOutcomeEvent {
                    task_id,
                    state: "FAILED".into(),
                    file_name: String::new(),
                    mime_type: "application/octet-stream".into(),
                    downloaded_bytes: 0,
                    total_bytes: 0,
                    wifi_bytes: 0,
                    cell_bytes: 0,
                    sha256_hex: String::new(),
                    ttfb_ms: 0,
                    error_message: e.to_string(),
                });
                return;
            }
        };
        let ttfb_ms = t0.elapsed().as_millis().max(1) as u64;
        page.ttfb_ms.store(ttfb_ms, Ordering::Release);

        if probe_status == 403 || probe_status == 410 {
            self.set_outcome(TaskOutcomeEvent {
                task_id,
                state: "TOKEN_EXPIRED".into(),
                file_name: String::new(),
                mime_type: "application/octet-stream".into(),
                downloaded_bytes: 0,
                total_bytes: 0,
                wifi_bytes: 0,
                cell_bytes: 0,
                sha256_hex: String::new(),
                ttfb_ms,
                error_message: format!("HTTP {probe_status} signed token expired"),
            });
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

        // Adaptive HLS/DASH detection
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
                    record_diag("HLS_DASH", &format!("Adaptive manifest resolved {} segments", segs.len()));
                }
            }
        }

        let supports_range = probe_status == 206
            || probe_headers
                .get("accept-ranges")
                .and_then(|v| v.to_str().ok())
                .map(|v| v.eq_ignore_ascii_case("bytes"))
                .unwrap_or(false);

        let total_bytes = parse_total_length(&probe_headers, probe_status)
            .unwrap_or(probe_first_bytes.len() as u64);
        let etag = probe_headers
            .get("etag")
            .or_else(|| probe_headers.get("last-modified"))
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_string();

        page.total_expected.store(total_bytes, Ordering::Release);

        // Open backing file (direct MediaStore FD if passed < 1 GiB, else raw F2FS app-private path)
        let data_file: File = if cfg.direct_fd >= 0 {
            unsafe { File::from_raw_fd(cfg.direct_fd) }
        } else {
            let p = PathBuf::from(&cfg.output_path);
            if let Some(parent) = p.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            match OpenOptions::new().create(true).read(true).write(true).open(&p) {
                Ok(f) => f,
                Err(e) => {
                    self.set_outcome(TaskOutcomeEvent {
                        task_id,
                        state: "FAILED".into(),
                        file_name,
                        mime_type,
                        downloaded_bytes: 0,
                        total_bytes,
                        wifi_bytes: 0,
                        cell_bytes: 0,
                        sha256_hex: String::new(),
                        ttfb_ms,
                        error_message: format!("Open output failed: {e}"),
                    });
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
            self.set_outcome(TaskOutcomeEvent {
                task_id,
                state: err_state.into(),
                file_name,
                mime_type,
                downloaded_bytes: 0,
                total_bytes,
                wifi_bytes: 0,
                cell_bytes: 0,
                sha256_hex: String::new(),
                ttfb_ms,
                error_message: e.to_string(),
            });
            if cfg.direct_fd >= 0 {
                std::mem::forget(data_file);
            }
            return;
        }

        let fastest_kbps = links_snap
            .iter()
            .map(|l| l.downstream_kbps.load(Ordering::Relaxed))
            .max()
            .unwrap_or(60_000);
        let seg_bytes = adaptive_bdp_segment_bytes(fastest_kbps);
        let ledger_path = PathBuf::from(&cfg.ledger_path);
        let mut ledger = AetherLedger::load_or_create(&ledger_path, total_bytes, seg_bytes, &etag)
            .unwrap_or_else(|_| AetherLedger::new(total_bytes, seg_bytes, &etag));

        let data_file = Arc::new(data_file);
        let verifier = Arc::new(Mutex::new(ContiguousSha256Verifier::new(total_bytes)));
        let written_total = Arc::new(AtomicU64::new(0));
        let wifi_bytes_total = Arc::new(AtomicU64::new(0));
        let cell_bytes_total = Arc::new(AtomicU64::new(0));

        // Notify Kotlin Stream 0 metadata resolved
        self.set_outcome(TaskOutcomeEvent {
            task_id: task_id.clone(),
            state: "STREAMING".into(),
            file_name: file_name.clone(),
            mime_type: mime_type.clone(),
            downloaded_bytes: 0,
            total_bytes,
            wifi_bytes: 0,
            cell_bytes: 0,
            sha256_hex: String::new(),
            ttfb_ms,
            error_message: String::new(),
        });

        let seg_count = ledger.states.len().max(1);
        let ledger_arc = Arc::new(Mutex::new(ledger));
        let breakers_arc = Arc::new(Mutex::new(HashMap::<String, LinkCircuitBreaker>::new()));
        let active_ranges = Arc::new(Mutex::new(HashMap::<String, (u64, u64)>::new()));

        let thermal_cap = self.thermal_worker_cap.load(Ordering::Acquire).clamp(1, 8) as usize;
        let links_now = self.transport.snapshot_links();
        let multi_link_on = is_flag_enabled(FLAG_MULTI_LINK);
        let base_workers = if supports_range && total_bytes > SLAB_BYTES as u64 {
            let desired = effective_worker_count(cfg.max_workers, if multi_link_on { links_now.len() } else { 1 });
            desired.min(thermal_cap).min(seg_count).max(1)
        } else {
            1
        };

        let mut worker_handles = Vec::with_capacity(base_workers);
        for worker_idx in 0..base_workers {
            let eng = self.clone();
            let h = handle.clone();
            let ledger_ref = ledger_arc.clone();
            let file_ref = data_file.clone();
            let ver_ref = verifier.clone();
            let brk_ref = breakers_arc.clone();
            let rng_ref = active_ranges.clone();
            let w_tot = written_total.clone();
            let wifi_tot = wifi_bytes_total.clone();
            let cell_tot = cell_bytes_total.clone();
            let tid = task_id.clone();
            let profile = cfg.browser_profile.clone();
            let max_cell = cfg.max_cellular_bytes;

            worker_handles.push(tokio::spawn(async move {
                let conn_id = format!("{tid}-w{worker_idx}");
                loop {
                    if h.cancelled.load(Ordering::Acquire) || h.paused.load(Ordering::Acquire) {
                        break;
                    }

                    // Claim next Pending segment, or absorb empty right neighbor, or bisect slowest
                    let claimed_idx = {
                        let mut lg = ledger_ref.lock().unwrap_or_else(|p| p.into_inner());
                        if let Some(pos) = lg.states.iter().position(|&s| s == SegmentStatus::Pending as u8) {
                            lg.states[pos] = SegmentStatus::Leased as u8;
                            Some(pos)
                        } else if is_flag_enabled(FLAG_TAIL_HEDGE) {
                            absorb_empty_right_of(&mut lg.states, 0)
                        } else {
                            None
                        }
                    };

                    let seg_idx = match claimed_idx {
                        Some(i) => i,
                        None => {
                            // Try _splitSlowest check cleanly
                            let can_split = {
                                let rngs = rng_ref.lock().unwrap_or_else(|p| p.into_inner());
                                rngs.values().any(|&(s, e)| bisect_slowest_range(s, e, SLAB_BYTES as u64).is_some())
                            };
                            if !can_split {
                                break;
                            }
                            tokio::time::sleep(Duration::from_millis(120)).await;
                            continue;
                        }
                    };

                    let seg_size = {
                        ledger_ref.lock().unwrap_or_else(|p| p.into_inner()).segment_bytes
                    };
                    let start = seg_idx as u64 * seg_size;
                    let end = if total_bytes > 0 {
                        (start + seg_size - 1).min(total_bytes - 1)
                    } else {
                        0
                    };
                    if total_bytes > 0 && start > end {
                        break;
                    }

                    rng_ref
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .insert(conn_id.clone(), (start, end));

                    // Select link via Earliest-Arrival F_k = (Q_k + S)/R_k + r_k/2 + cellular quota check
                    let links = eng.transport.snapshot_links();
                    let cell_used = cell_tot.load(Ordering::Relaxed);
                    let stats: Vec<LinkDispatchStats> = links
                        .iter()
                        .filter(|l| {
                            if l.is_cellular && cell_used >= max_cell {
                                return false;
                            }
                            is_flag_enabled(FLAG_MULTI_LINK) || !l.is_cellular
                        })
                        .map(|l| LinkDispatchStats {
                            link_id: l.link_key.clone(),
                            queued_bytes: seg_size as f64,
                            ewma_bytes_per_sec: (l.downstream_kbps.load(Ordering::Relaxed) as f64 * 125.0)
                                .max(125_000.0),
                            rtt_min_secs: (l.rtt_ms.load(Ordering::Relaxed) as f64 / 1000.0).max(0.005),
                        })
                        .collect();

                    let picked_key = {
                        let mut brk = brk_ref.lock().unwrap_or_else(|p| p.into_inner());
                        select_earliest_arrival_link(&stats, &mut brk, seg_size as f64, Instant::now())
                            .map(str::to_string)
                    }
                    .unwrap_or_else(|| "default:auto".to_string());

                    let picked_link = links.iter().find(|l| l.link_key == picked_key).cloned();
                    let mirror_url = {
                        let cur_urls = h.updated_urls.read().unwrap_or_else(|p| p.into_inner());
                        select_mirror_for_link(&cur_urls, &picked_key, seg_idx).to_string()
                    };

                    eng.upsert_conn(ActiveConnectionSnapshot {
                        conn_id: conn_id.clone(),
                        task_id: tid.clone(),
                        mirror_url: mirror_url.clone(),
                        link_key: picked_key.clone(),
                        protocol: if is_flag_enabled(FLAG_HTTP3_QUIC) { "H2/H3".into() } else { "H2 wreq".into() },
                        state: "Streaming".into(),
                        range_start: start,
                        range_end: end,
                        bytes_pulled: 0,
                        speed_bps: 0,
                        ttfb_ms,
                        blocked_reason: "NONE".into(),
                    });

                    let range_hdr = if supports_range && total_bytes > 0 {
                        Some(format!("bytes={start}-{end}"))
                    } else {
                        None
                    };

                    let proxy_ov = picked_link.as_ref().and_then(|l| {
                        l.proxy_override.read().unwrap_or_else(|p| p.into_inner()).clone()
                    });
                    let local_ip = picked_link.as_ref().and_then(|l| {
                        *l.local_ip.read().unwrap_or_else(|p| p.into_inner())
                    });

                    let res = match build_wreq_link_client(local_ip, proxy_ov.as_ref(), &profile) {
                        Ok(client) => {
                            let seg_t0 = Instant::now();
                            match send_wreq_range_request(&client, &mirror_url, range_hdr.as_deref()).await {
                                Ok(mut resp) => {
                                    let status = resp.status().as_u16();
                                    if status == 403 || status == 410 {
                                        Err(format!("TOKEN_EXPIRED:{status}"))
                                    } else if !(status == 200 || status == 206) {
                                        Err(format!("HTTP {status}"))
                                    } else {
                                        let mut cursor = start;
                                        let mut seg_bytes_written = 0u64;
                                        let mut io_err: Option<String> = None;

                                        // Acquire 512 KiB slab under Semaphore(16) backpressure
                                        while let Ok(Some(chunk)) = resp.chunk().await {
                                            if h.cancelled.load(Ordering::Acquire) || h.paused.load(Ordering::Acquire) {
                                                break;
                                            }
                                            let mut pos_in_chunk = 0usize;
                                            while pos_in_chunk < chunk.len() {
                                                let mut slab = match eng.pool.acquire_guard_async().await {
                                                    Ok(g) => g,
                                                    Err(e) => {
                                                        io_err = Some(e.to_string());
                                                        break;
                                                    }
                                                };
                                                let take = (chunk.len() - pos_in_chunk).min(SLAB_BYTES);
                                                slab.as_mut_slice()[..take]
                                                    .copy_from_slice(&chunk[pos_in_chunk..pos_in_chunk + take]);

                                                // Fix #3: L1/L2 in-cache BLAKE3 before pwrite!
                                                let slab_b3 = blake3_digest_bytes(&slab.as_slice()[..take]);
                                                if let Err(e) = pwrite_all(&file_ref, &slab.as_slice()[..take], cursor) {
                                                    io_err = Some(e.to_string());
                                                    break;
                                                }
                                                let _ = ver_ref.lock().unwrap_or_else(|p| p.into_inner()).record_and_advance(
                                                    cursor,
                                                    &slab.as_slice()[..take],
                                                    Some(&file_ref),
                                                );

                                                {
                                                    let mut lg = ledger_ref.lock().unwrap_or_else(|p| p.into_inner());
                                                    let slab_idx = (cursor / SLAB_BYTES as u64) as usize;
                                                    lg.record_slab_digest(slab_idx, slab_b3);
                                                }

                                                cursor += take as u64;
                                                seg_bytes_written += take as u64;
                                                pos_in_chunk += take;

                                                let tot = w_tot.fetch_add(take as u64, Ordering::Relaxed) + take as u64;
                                                page.total_written.store(tot, Ordering::Release);
                                                page.producer_seq.fetch_add(1, Ordering::Relaxed);
                                                if picked_key.starts_with("cell:") {
                                                    let cb = cell_tot.fetch_add(take as u64, Ordering::Relaxed) + take as u64;
                                                    page.cell_bytes_total.store(cb, Ordering::Release);
                                                    let rem = max_cell.saturating_sub(cb);
                                                    page.cellular_quota_remaining.store(rem, Ordering::Release);
                                                } else {
                                                    let wb = wifi_tot.fetch_add(take as u64, Ordering::Relaxed) + take as u64;
                                                    page.wifi_bytes_total.store(wb, Ordering::Release);
                                                }

                                                let secs = seg_t0.elapsed().as_secs_f64().max(0.05);
                                                let link_bps = ((seg_bytes_written as f64 * 8.0) / secs) as u64;
                                                if picked_key.starts_with("cell:") {
                                                    page.cell_goodput_bps.store(link_bps, Ordering::Release);
                                                } else if picked_key.starts_with("eth:") {
                                                    page.eth_goodput_bps.store(link_bps, Ordering::Release);
                                                } else {
                                                    page.wifi_goodput_bps.store(link_bps, Ordering::Release);
                                                }
                                                let agg = page.wifi_goodput_bps.load(Ordering::Relaxed)
                                                    + page.cell_goodput_bps.load(Ordering::Relaxed)
                                                    + page.eth_goodput_bps.load(Ordering::Relaxed);
                                                page.goodput_bps.store(agg, Ordering::Release);
                                            }
                                            if io_err.is_some() {
                                                break;
                                            }
                                        }
                                        if let Some(err) = io_err {
                                            Err(err)
                                        } else {
                                            Ok(seg_bytes_written)
                                        }
                                    }
                                }
                                Err(e) => Err(e.to_string()),
                            }
                        }
                        Err(e) => Err(e.to_string()),
                    };

                    rng_ref.lock().unwrap_or_else(|p| p.into_inner()).remove(&conn_id);

                    match res {
                        Ok(_) => {
                            // Fix #6: Strict fdatasync(data_fd) BEFORE marking Done & msyncing ledger
                            let _ = flush_fdatasync(&file_ref);
                            let mut lg = ledger_ref.lock().unwrap_or_else(|p| p.into_inner());
                            lg.states[seg_idx] = SegmentStatus::Done as u8;
                            let _ = lg.persist(&ledger_path, Some(&file_ref));
                            page.consumer_seq.fetch_add(1, Ordering::Relaxed);
                            let _ = adjust_auto_speed_up(base_workers, 1, 8, 1.0, 1.1, 4.0, false);
                            brk_ref
                                .lock()
                                .unwrap_or_else(|p| p.into_inner())
                                .entry(picked_key.clone())
                                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(10)))
                                .record_success();
                        }
                        Err(err) => {
                            record_diag("SEG_ERR", &format!("Segment {seg_idx} on {picked_key}: {err}"));
                            let mut lg = ledger_ref.lock().unwrap_or_else(|p| p.into_inner());
                            lg.states[seg_idx] = SegmentStatus::Pending as u8;
                            brk_ref
                                .lock()
                                .unwrap_or_else(|p| p.into_inner())
                                .entry(picked_key.clone())
                                .or_insert_with(|| LinkCircuitBreaker::new(Duration::from_secs(10)))
                                .record_error_or_stall(Instant::now(), true);
                            if err.starts_with("TOKEN_EXPIRED") {
                                break;
                            }
                        }
                    }
                }
                eng.remove_conn(&conn_id);
            }));
        }

        for j in worker_handles {
            let _ = j.await;
        }

        let _ = flush_fdatasync(&data_file);
        let final_sha = match Arc::try_unwrap(verifier) {
            Ok(m) => m.into_inner().unwrap().finalize_hex(),
            Err(_) => String::new(),
        };

        let paused = handle.paused.load(Ordering::Acquire);
        let cancelled = handle.cancelled.load(Ordering::Acquire);
        let final_state = if cancelled {
            "CANCELLED"
        } else if paused {
            "PAUSED"
        } else {
            let _ = std::fs::remove_file(&ledger_path);
            "COMPLETED"
        };

        if cfg.direct_fd >= 0 {
            if let Ok(raw_f) = Arc::try_unwrap(data_file) {
                std::mem::forget(raw_f);
            }
        }

        self.set_outcome(TaskOutcomeEvent {
            task_id: task_id.clone(),
            state: final_state.into(),
            file_name,
            mime_type,
            downloaded_bytes: written_total.load(Ordering::Acquire),
            total_bytes,
            wifi_bytes: wifi_bytes_total.load(Ordering::Acquire),
            cell_bytes: cell_bytes_total.load(Ordering::Acquire),
            sha256_hex: final_sha,
            ttfb_ms,
            error_message: String::new(),
        });
        self.tasks
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .remove(&task_id);
    }

    async fn execute_single_probe(
        &self,
        url: &str,
        link: Option<&crate::transport::BoundLinkState>,
        profile: &str,
        protocol_mode: &str,
    ) -> std::io::Result<(u16, http::HeaderMap, Vec<u8>)> {
        // Race HTTP/3 QUIC if requested/enabled and bound link available
        if (protocol_mode == "h3_quic" || is_flag_enabled(FLAG_HTTP3_QUIC)) && url.starts_with("https://") {
            let handle = link.map(|l| l.net_handle.load(Ordering::Relaxed)).unwrap_or(0);
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

        let proxy_ov = link.and_then(|l| l.proxy_override.read().unwrap_or_else(|p| p.into_inner()).clone());
        let local_ip = link.and_then(|l| *l.local_ip.read().unwrap_or_else(|p| p.into_inner()));
        let client = build_wreq_link_client(local_ip, proxy_ov.as_ref(), profile)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

        let mut resp = send_wreq_range_request(&client, url, Some("bytes=0-16383"))
            .await
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

        live_telemetry_page()
            .state_flags
            .fetch_or(1 << 11, Ordering::AcqRel);
        let status = resp.status().as_u16();
        let headers = resp.headers().clone();
        let mut snippet = Vec::new();
        while let Ok(Some(chunk)) = resp.chunk().await {
            let take = chunk.len().min(16384usize.saturating_sub(snippet.len()));
            snippet.extend_from_slice(&chunk[..take]);
            if snippet.len() >= 16384 {
                break;
            }
        }
        Ok((status, headers, snippet))
    }
}

fn parse_total_length(headers: &http::HeaderMap, status: u16) -> Option<u64> {
    if status == 206 {
        if let Some(cr) = headers.get("content-range").and_then(|v| v.to_str().ok()) {
            if let Some(slash) = cr.rsplit('/').next() {
                if let Ok(len) = slash.trim().parse::<u64>() {
                    return Some(len);
                }
            }
        }
    }
    headers
        .get("content-length")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.trim().parse::<u64>().ok())
}
