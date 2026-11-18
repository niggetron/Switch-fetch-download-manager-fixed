#!/usr/bin/env bash
set -euo pipefail

# Builds libaether_core.so for Android arm64-v8a and places it in app/src/main/jniLibs/
# Requires Rust toolchain + cargo-ndk (or cargo-zigbuild) + Android NDK on host machine.

OUT_DIR="../../app/src/main/jniLibs"
mkdir -p "$OUT_DIR/arm64-v8a"

if command -v cargo-ndk >/dev/null 2>&1; then
  cargo ndk -t arm64-v8a -p 24 -o "$OUT_DIR" build --release
elif command -v cargo-zigbuild >/dev/null 2>&1; then
  cargo zigbuild --target aarch64-linux-android.29 --release
  cp target/aarch64-linux-android/release/libaether_core.so "$OUT_DIR/arm64-v8a/libaether_core.so"
else
  echo "Error: Install cargo-ndk or cargo-zigbuild + Android NDK to cross-compile libaether_core.so." >&2
  exit 1
fi

echo "Built $OUT_DIR/arm64-v8a/libaether_core.so"
