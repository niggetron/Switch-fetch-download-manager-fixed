use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong};

use crate::cas::blake3_digest_hex;
use crate::telemetry::TelemetryPage;
use crate::{F2FS_SECTION_BYTES, SlabArenaAlloc};

static TELEMETRY_PAGE: TelemetryPage = TelemetryPage::new();
static GLOBAL_ARENA: SlabArenaAlloc = SlabArenaAlloc::new_zeroed();

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_engine_AetherNativeBridge_nativeEngineVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    env.new_string("libaether_core.so (Rust 1.100.0 · F2FS 2MB + BLAKE3 + GF256 RLNC)")
        .expect("Unable to allocate JNI string")
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
    env.new_string(hex).expect("Unable to allocate BLAKE3 JNI string")
}
