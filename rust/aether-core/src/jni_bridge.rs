use std::net::IpAddr;
use std::os::fd::IntoRawFd;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::{Arc, OnceLock};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jlong, jlongArray};
use tokio::sync::mpsc;

use crate::aria2_rpc::{RpcCommand, is_rpc_running, start_rpc_server, stop_rpc_server};
use crate::cas::{blake3_digest_hex, sha256_digest_hex};
use crate::diagnostics::{export_diagnostics_text, record_diag, set_feature_flags};
use crate::engine::{NativeDownloadTaskConfig, NativeEngine};
use crate::storage_and_telemetry::{
    create_telemetry_memfd, live_telemetry_page, probe_io_uring_supported,
    stream_copy_staging_to_fd,
};
use crate::transport::ProxyOverride;

static ENGINE: OnceLock<Arc<NativeEngine>> = OnceLock::new();

fn engine() -> &'static Arc<NativeEngine> {
    ENGINE.get_or_init(|| Arc::new(NativeEngine::new()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeEngineVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let label = catch_unwind(AssertUnwindSafe(|| {
        let uring = if probe_io_uring_supported() {
            "io_uring+pwrite"
        } else {
            "fallocate+pwrite+fdatasync"
        };
        format!(
            "libaether_core.so v9.1 Hyperion (64-Stream Sharded Multi-Socket · Force-All-Links · Pooled wreq+H3 · Async Disk Writer 12 MiB · Anti-Dip Bisection · memfd · {uring})"
        )
    }))
    .unwrap_or_else(|_| "libaether_core.so v9.1 (recovered panic)".to_string());

    env.new_string(label)
        .expect("Failed to allocate JNI version string")
}

/// Pre-build the Tokio runtime / TLS pools / slab arena so the first real
/// download does not pay the initialization cost and appear to "queue".
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeWarmup() {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let _ = engine();
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeCreateTelemetryMemfd(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        #[cfg(unix)]
        {
            match create_telemetry_memfd() {
                Ok(fd) => fd.into_raw_fd() as jint,
                Err(_) => -1,
            }
        }
        #[cfg(not(unix))]
        {
            -1
        }
    }))
    .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeUpdateLinkHandle(
    mut env: JNIEnv,
    _class: JClass,
    link_key: JString,
    net_handle: jlong,
    downstream_kbps: jlong,
    rtt_ms: jlong,
    is_cellular: jboolean,
    local_ip: JString,
    proxy_host: JString,
    proxy_port: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let key: String = env.get_string(&link_key).map(Into::into).unwrap_or_default();
        if key.is_empty() {
            return;
        }
        let ip_str: String = env.get_string(&local_ip).map(Into::into).unwrap_or_default();
        let parsed_ip: Option<IpAddr> = ip_str.trim().parse().ok();
        let px_host_raw: String = env.get_string(&proxy_host).map(Into::into).unwrap_or_default();
        let px_trimmed = px_host_raw.trim();
        let proxy = if !px_trimmed.is_empty() && proxy_port > 0 {
            if let Some(stripped) = px_trimmed.strip_prefix("http://") {
                Some(ProxyOverride {
                    host: stripped.to_string(),
                    port: proxy_port as u16,
                    is_socks5h: false,
                    is_http: true,
                })
            } else {
                let clean = px_trimmed
                    .strip_prefix("socks5h://")
                    .or_else(|| px_trimmed.strip_prefix("socks5://"))
                    .unwrap_or(px_trimmed);
                Some(ProxyOverride {
                    host: clean.to_string(),
                    port: proxy_port as u16,
                    is_socks5h: true,
                    is_http: false,
                })
            }
        } else {
            None
        };
        let is_new = engine().transport.upsert_link(
            key.clone(),
            net_handle.max(0) as u64,
            downstream_kbps.max(1000) as u64,
            rtt_ms.max(10) as u64,
            is_cellular == JNI_TRUE,
            parsed_ip,
            proxy,
        );
        if is_new {
            record_diag("HOTPLUG", &format!("Mid-download interface registered: {key}"));
            engine().notify_hotplug();
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeRemoveLink(
    mut env: JNIEnv,
    _class: JClass,
    link_key: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(k) = env.get_string(&link_key) {
            let s: String = k.into();
            engine().transport.remove_link(&s);
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeSetFeatureFlags(
    _env: JNIEnv,
    _class: JClass,
    mask: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        set_feature_flags(mask as u32);
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeSetThermalWorkerCap(
    _env: JNIEnv,
    _class: JClass,
    max_workers: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        engine()
            .thermal_worker_cap
            .store((max_workers as i64).clamp(1, 64), std::sync::atomic::Ordering::Release);
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeStartDownload(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    urls_newline_sep: JString,
    output_path: JString,
    ledger_path: JString,
    direct_fd: jint,
    max_workers: jint,
    max_cellular_bytes: jlong,
    browser_profile: JString,
    protocol_mode: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let tid: String = env.get_string(&task_id).map(Into::into).unwrap_or_default();
        let raw_urls: String = env
            .get_string(&urls_newline_sep)
            .map(Into::into)
            .unwrap_or_default();
        let urls: Vec<String> = raw_urls
            .lines()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
        let out_path: String = env
            .get_string(&output_path)
            .map(Into::into)
            .unwrap_or_default();
        let ldg_path: String = env
            .get_string(&ledger_path)
            .map(Into::into)
            .unwrap_or_default();
        let profile: String = env
            .get_string(&browser_profile)
            .map(Into::into)
            .unwrap_or_else(|_| "chrome134".into());
        let proto: String = env
            .get_string(&protocol_mode)
            .map(Into::into)
            .unwrap_or_else(|_| "auto".into());

        let cell_cap = if max_cellular_bytes <= 0 {
            u64::MAX
        } else {
            max_cellular_bytes as u64
        };

        engine().start_task(NativeDownloadTaskConfig {
            task_id: tid,
            urls,
            output_path: out_path,
            ledger_path: ldg_path,
            direct_fd: direct_fd as i32,
            max_workers: (max_workers as usize).clamp(1, 64),
            max_cellular_bytes: cell_cap,
            browser_profile: profile,
            protocol_mode: proto,
        });
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativePauseDownload(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(tid) = env.get_string(&task_id) {
            let s: String = tid.into();
            engine().pause_task(&s);
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeCancelDownload(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if let Ok(tid) = env.get_string(&task_id) {
            let s: String = tid.into();
            engine().cancel_task(&s);
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeUpdateTaskUrls(
    mut env: JNIEnv,
    _class: JClass,
    task_id: JString,
    urls_newline_sep: JString,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let tid: String = env.get_string(&task_id).map(Into::into).unwrap_or_default();
        let raw_urls: String = env
            .get_string(&urls_newline_sep)
            .map(Into::into)
            .unwrap_or_default();
        let urls: Vec<String> = raw_urls
            .lines()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect();
        if !tid.is_empty() && !urls.is_empty() {
            engine().update_task_urls(&tid, urls);
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeTaskOutcomeJson<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    task_id: JString<'local>,
) -> JString<'local> {
    let json = catch_unwind(AssertUnwindSafe(|| {
        let tid: String = env.get_string(&task_id).map(Into::into).unwrap_or_default();
        engine().outcome_json(&tid)
    }))
    .unwrap_or_else(|_| "{}".to_string());
    env.new_string(json)
        .expect("Failed to allocate outcome JSON string")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeConnectionsJson<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let json = catch_unwind(AssertUnwindSafe(|| engine().connections_json()))
        .unwrap_or_else(|_| "[]".to_string());
    env.new_string(json)
        .expect("Failed to allocate connections JSON string")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeDiagnosticsLog<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let log = catch_unwind(AssertUnwindSafe(export_diagnostics_text)).unwrap_or_default();
    env.new_string(log)
        .expect("Failed to allocate diagnostics log string")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeStartAria2Rpc<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let secret = catch_unwind(AssertUnwindSafe(|| {
        let eng = engine();
        let (tx, mut rx) = mpsc::unbounded_channel::<RpcCommand>();
        eng.runtime.spawn(async move {
            while let Some(cmd) = rx.recv().await {
                if let RpcCommand::AddUri { gid, urls } = cmd {
                    record_diag("ARIA2_ADD", &format!("Queued via RPC gid={gid} mirrors={}", urls.len()));
                }
            }
        });
        eng.runtime
            .block_on(start_rpc_server(tx))
            .unwrap_or_default()
    }))
    .unwrap_or_default();
    env.new_string(secret)
        .expect("Failed to allocate rpc secret string")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeStopAria2Rpc(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        if is_rpc_running() {
            stop_rpc_server();
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeSlabStats<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlongArray {
    let stats = catch_unwind(AssertUnwindSafe(|| {
        let eng = engine();
        let page = live_telemetry_page();
        [
            eng.pool.allocated_bytes() as jlong,
            eng.pool.in_use_bytes() as jlong,
            page.total_written.load(std::sync::atomic::Ordering::Relaxed) as jlong,
        ]
    }))
    .unwrap_or([0, 0, 0]);

    let arr = env
        .new_long_array(3)
        .expect("Failed to allocate JNI long array");
    let _ = env.set_long_array_region(&arr, 0, &stats);
    arr.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeCopyStagingToFd(
    mut env: JNIEnv,
    _class: JClass,
    staging_path: JString,
    dest_fd: jint,
) -> jboolean {
    let ok = catch_unwind(AssertUnwindSafe(|| {
        #[cfg(unix)]
        {
            let path_str: String = match env.get_string(&staging_path) {
                Ok(s) => s.into(),
                Err(_) => return false,
            };
            stream_copy_staging_to_fd(Path::new(&path_str), dest_fd, &engine().pool).is_ok()
        }
        #[cfg(not(unix))]
        {
            false
        }
    }))
    .unwrap_or(false);

    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativePauseQuotaGuard(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        live_telemetry_page()
            .state_flags
            .fetch_or(1 << 8, std::sync::atomic::Ordering::Release);
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeBlake3Hex<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> JString<'local> {
    let hex = catch_unwind(AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(&data).unwrap_or_default();
        blake3_digest_hex(&bytes)
    }))
    .unwrap_or_default();
    env.new_string(hex)
        .expect("Failed to allocate BLAKE3 JNI string")
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeSha256Hex<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> JString<'local> {
    let hex = catch_unwind(AssertUnwindSafe(|| {
        let bytes = env.convert_byte_array(&data).unwrap_or_default();
        sha256_digest_hex(&bytes)
    }))
    .unwrap_or_default();
    env.new_string(hex)
        .expect("Failed to allocate SHA-256 JNI string")
}
