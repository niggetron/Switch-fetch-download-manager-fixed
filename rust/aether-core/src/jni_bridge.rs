//! JNI surface consumed by `com.example.engine.AetherNativeBridge`.
//!
//! Every entry point is `#[unsafe(no_mangle)]` + `extern "system"` so the
//! symbols match the Kotlin `external fun` declarations exactly.

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jdouble, jint, jlong};

use crate::ENGINE_VERSION;
use crate::cas::{blake3_digest_hex, verify_extent_blake3};
use crate::rlnc::gf256_mul_acc;
use crate::scheduler::{LinkMetrics, build_f2fs_extents, select_earliest_arrival_link};
use crate::telemetry::TelemetryPage;
use crate::{F2FS_SECTION_BYTES, SlabArenaAlloc};

static TELEMETRY_PAGE: TelemetryPage = TelemetryPage::new();
static GLOBAL_ARENA: std::sync::LazyLock<SlabArenaAlloc> =
    std::sync::LazyLock::new(SlabArenaAlloc::new_zeroed);

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeEngineVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    env.new_string(ENGINE_VERSION)
        .unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeArenaUsedBytes(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    GLOBAL_ARENA.used_bytes() as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeF2fsExtentBytes(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    F2FS_SECTION_BYTES as jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeRecordTelemetryCommit(
    _env: JNIEnv,
    _class: JClass,
    bytes: jlong,
) -> jlong {
    if bytes > 0 {
        TELEMETRY_PAGE.record_commit(bytes as u64);
    }
    TELEMETRY_PAGE
        .line0
        .total_bytes
        .load(std::sync::atomic::Ordering::Relaxed) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeBlake3Hex<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    data: JByteArray<'local>,
) -> JString<'local> {
    let bytes = env.convert_byte_array(data).unwrap_or_default();
    let hex = blake3_digest_hex(&bytes);
    env.new_string(hex)
        .unwrap_or_else(|_| unsafe { JString::from_raw(std::ptr::null_mut()) })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeVerifyBlake3(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    expected_hex: JString,
) -> jboolean {
    let bytes = env.convert_byte_array(data).unwrap_or_default();
    let expected: String = env
        .get_string(&expected_hex)
        .map(|s| s.into())
        .unwrap_or_default();
    if expected.is_empty() {
        return 0;
    }
    verify_extent_blake3(&bytes, &expected) as jint
}

/// XOR-accumulates `src` scaled by `coeff` into `dst` over GF(2^8).
/// Returns 0 on success, -1 when lengths differ or JNI access fails.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeGf256MulAcc(
    env: JNIEnv,
    _class: JClass,
    dst: JByteArray,
    src: JByteArray,
    coeff: jint,
) -> jint {
    let mut dst_bytes = match env.convert_byte_array(&dst) {
        Ok(v) => v,
        Err(_) => return -1,
    };
    let src_bytes = match env.convert_byte_array(&src) {
        Ok(v) => v,
        Err(_) => return -1,
    };
    if dst_bytes.len() != src_bytes.len() {
        return -1;
    }
    gf256_mul_acc(&mut dst_bytes, &src_bytes, coeff as u8);
    let signed: Vec<i8> = dst_bytes.iter().map(|&b| b as i8).collect();
    match env.set_byte_array_region(dst, 0, &signed) {
        Ok(()) => 0,
        Err(_) => -1,
    }
}

/// Earliest-arrival multipath decision. Link 0 = Wi-Fi, link 1 = cellular.
/// A rate <= 0 marks the link as unavailable. Returns 0, 1, or -1 (no link).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeSelectLink(
    _env: JNIEnv,
    _class: JClass,
    wifi_rate: jdouble,
    wifi_queue: jdouble,
    wifi_rtt: jdouble,
    cell_rate: jdouble,
    cell_queue: jdouble,
    cell_rtt: jdouble,
    block_bytes: jdouble,
) -> jint {
    let mut links = Vec::with_capacity(2);
    if wifi_rate > 0.0 {
        links.push(LinkMetrics {
            id: 0,
            queue_bytes: wifi_queue,
            rate_bytes_per_sec: wifi_rate,
            rtt_min_secs: wifi_rtt,
        });
    }
    if cell_rate > 0.0 {
        links.push(LinkMetrics {
            id: 1,
            queue_bytes: cell_queue,
            rate_bytes_per_sec: cell_rate,
            rtt_min_secs: cell_rtt,
        });
    }
    select_earliest_arrival_link(&links, block_bytes)
        .map(|id| id as jint)
        .unwrap_or(-1)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeF2fsExtentCount(
    _env: JNIEnv,
    _class: JClass,
    total_bytes: jlong,
) -> jint {
    if total_bytes <= 0 {
        return 0;
    }
    build_f2fs_extents(total_bytes as u64).len() as jint
}
