use std::os::fd::IntoRawFd;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jlongArray, JNI_FALSE, JNI_TRUE};

use crate::cas::{blake3_digest_hex, sha256_digest_hex};
use crate::storage_and_telemetry::{
    BoundedSlabPool, TelemetryPage, create_telemetry_memfd, probe_io_uring_supported,
    stream_copy_staging_to_fd,
};
use crate::transport::{ProxyOverride, TransportContext};

static SLAB_POOL: OnceLock<BoundedSlabPool> = OnceLock::new();
static TRANSPORT_CTX: OnceLock<TransportContext> = OnceLock::new();
static TELEMETRY_PAGE: TelemetryPage = TelemetryPage::new();

fn pool() -> &'static BoundedSlabPool {
    SLAB_POOL.get_or_init(BoundedSlabPool::new)
}

fn transport() -> &'static TransportContext {
    TRANSPORT_CTX.get_or_init(TransportContext::new)
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
            "libaether_core.so v7.1 (panic=unwind · BoundedSlabPool 8.0 MiB · memfd · {uring} · AETHLDG1)"
        )
    }))
    .unwrap_or_else(|_| "libaether_core.so (recovered panic)".to_string());

    env.new_string(label)
        .expect("Failed to allocate JNI version string")
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
    proxy_host: JString,
    proxy_port: jint,
) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let key: String = env.get_string(&link_key).map(Into::into).unwrap_or_default();
        if key.is_empty() {
            return;
        }
        let px_host: String = env.get_string(&proxy_host).map(Into::into).unwrap_or_default();
        let proxy = if !px_host.trim().is_empty() && proxy_port > 0 {
            Some(ProxyOverride {
                host: px_host.trim().to_string(),
                port: proxy_port as u16,
                is_socks5h: true,
            })
        } else {
            None
        };
        transport().upsert_link(
            key,
            net_handle.max(0) as u64,
            downstream_kbps.max(1000) as u64,
            rtt_ms.max(10) as u64,
            is_cellular == JNI_TRUE,
            proxy,
        );
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
            transport().remove_link(&s);
        }
    }));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeSlabStats<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jlongArray<'local> {
    let stats = catch_unwind(AssertUnwindSafe(|| {
        let p = pool();
        [
            p.allocated_bytes() as jlong,
            p.in_use_bytes() as jlong,
            TELEMETRY_PAGE
                .total_written
                .load(std::sync::atomic::Ordering::Relaxed) as jlong,
        ]
    }))
    .unwrap_or([0, 0, 0]);

    let arr = env
        .new_long_array(3)
        .expect("Failed to allocate JNI long array");
    let _ = env.set_long_array_region(&arr, 0, &stats);
    arr
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
            stream_copy_staging_to_fd(Path::new(&path_str), dest_fd, pool()).is_ok()
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
        TELEMETRY_PAGE
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
