#!/usr/bin/env bash
set -euo pipefail

# Cross-compiles libaether_core.so for Android arm64-v8a (minSdk 24) and drops it
# into app/src/main/jniLibs/. Requires a stable Rust toolchain plus cargo-ndk
# (or cargo-zigbuild) and an Android NDK. GitHub Actions does all of this in CI;
# this script is only for local development.

OUT_DIR="../../app/src/main/jniLibs"
mkdir -p "$OUT_DIR/arm64-v8a"

if command -v cargo-ndk >/dev/null 2>&1; then
  cargo ndk --target arm64-v8a --platform 24 -o "$OUT_DIR" build --release
elif command -v cargo-zigbuild >/dev/null 2>&1; then
  cargo zigbuild --target aarch64-linux-android.24 --release
  cp target/aarch64-linux-android/release/libaether_core.so "$OUT_DIR/arm64-v8a/libaether_core.so"
else
  echo "Error: Install cargo-ndk (recommended) or cargo-zigbuild + Android NDK to cross-compile libaether_core.so." >&2
  exit 1
fi

test -s "$OUT_DIR/arm64-v8a/libaether_core.so" || {
  echo "Error: build did not produce $OUT_DIR/arm64-v8a/libaether_core.so" >&2
  exit 1
}
echo "Built $OUT_DIR/arm64-v8a/libaether_core.so"
