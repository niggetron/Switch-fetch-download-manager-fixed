#!/usr/bin/env bash
# AetherIO v8.0 — Reproducible Device Benchmark & Thermal/Memory Audit Harness
# Usage: ./scripts/bench_device.sh [IPERF3_SERVER_HOST] [TEST_DOWNLOAD_URL]
set -euo pipefail

PKG="com.aistudio.aetherio.xkqmzp"
IPERF_HOST="${1:-iperf.he.net}"
TEST_URL="${2:-https://speed.cloudflare.com/__down?bytes=524288000}"
OUT_DIR="bench_results_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_DIR"

echo "=== AetherIO v8.0 Hardware Benchmark Harness ==="
echo "Device: $(adb shell getprop ro.product.model) (Android $(adb shell getprop ro.build.version.release), SDK $(adb shell getprop ro.build.version.sdk))"
echo "Output: $OUT_DIR"

echo "[1/5] Resetting batterystats & meminfo baseline..."
adb shell dumpsys batterystats --reset >/dev/null || true
adb shell dumpsys meminfo "$PKG" > "$OUT_DIR/meminfo_before.txt" || true

echo "[2/5] Running iperf3 single-stream vs multi-stream baseline (if installed on device)..."
if adb shell "command -v iperf3 >/dev/null 2>&1"; then
  adb shell "iperf3 -c $IPERF_HOST -P 4 -t 10 -J" > "$OUT_DIR/iperf3_baseline.json" || true
else
  echo "iperf3 not present on device; skipping baseline." > "$OUT_DIR/iperf3_baseline.txt"
fi

echo "[3/5] Launching AetherIO 500 MB dual-link transfer..."
adb shell am start -n "$PKG/com.example.MainActivity" \
  --es "aether_bench_url" "$TEST_URL" \
  --ez "aether_bench_multipath" true

echo "[4/5] Sampling dumpsys meminfo & thermalservice during transfer (20s window)..."
for i in $(seq 1 10); do
  sleep 2
  adb shell dumpsys meminfo "$PKG" | grep -E "TOTAL|Native Heap|Dalvik Heap" >> "$OUT_DIR/meminfo_samples.txt" || true
  adb shell dumpsys thermalservice | head -n 15 >> "$OUT_DIR/thermal_samples.txt" || true
done

echo "[5/5] Capturing batterystats & final meminfo snapshot..."
adb shell dumpsys meminfo "$PKG" > "$OUT_DIR/meminfo_after.txt" || true
adb shell dumpsys batterystats "$PKG" > "$OUT_DIR/batterystats.txt" || true
echo "Benchmark artifacts saved in $OUT_DIR/"
