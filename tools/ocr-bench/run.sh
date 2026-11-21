#!/usr/bin/env bash
# Runs the OCR benchmark end to end (TES-149) on a Mac with Xcode and the Android SDK:
#   1. the generator writes synthetic MRZ text with Tessera's own MrzGenerator;
#   2. render.swift draws each MRZ in OCR-B, clean and with blur, glare, skew, and all three;
#   3. Apple Vision reads the images in a booted iOS Simulator (and on macOS for comparison);
#   4. ML Kit reads them on a running Android emulator;
#   5. an open line recognizer (PaddleOCR PP-OCRv5 English, ONNX Runtime) reads per-line crops
#      on the same emulator, when OCR_BENCH_ONNX_MODEL is set (see below);
#   6. score.py prints the result tables.
#
# Usage: tools/ocr-bench/run.sh [out-dir]   (run from the repo root)
# Needs: a booted iOS Simulator (SKIP_IOS=1 to skip) and one running Android emulator
# (SKIP_ANDROID=1 to skip). Images and results stay in out-dir; never commit them.
#
# The ONNX model is never committed. Set OCR_BENCH_ONNX_MODEL to the file en_PP-OCRv5_rec_mobile.onnx
# (RapidAI ONNX conversion of the PaddleOCR PP-OCRv5 English mobile recognizer, Apache-2.0):
#   https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/rec/en_PP-OCRv5_rec_mobile.onnx
#   7,872,351 bytes, SHA-256 c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8
# This engine reads single lines, so the renderer also cuts one crop per MRZ line from its known
# geometry; the other engines read the whole image. Its raw text and its text filtered to A-Z,
# 0-9 and `<` are both scored: the filter is one the vendor engines do not get.
#
# The OCR-B font is the one inside the iOS Simulator runtime. It is used in place and never
# copied: set OCRB_FONT to use another OCR-B font file whose licence you have checked.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
out="${1:-${TMPDIR:-/tmp}/tessera-ocr-bench}"
mkdir -p "$out"

echo "== 1. ground truth"
"$repo/gradlew" -q -p "$here/generator" run --args="$out/manifest.tsv 20 149"

echo "== 2. images"
shopt -s nullglob
runtime_fonts=(
  /private/var/run/com.apple.security.cryptexd/mnt/*SimulatorRuntime*/Library/Developer/CoreSimulator/Profiles/Runtimes/*.simruntime/Contents/Resources/RuntimeRoot/System/Library/PrivateFrameworks/CoreIDVUI.framework/OCRB.otf
  /Library/Developer/CoreSimulator/Volumes/*/Library/Developer/CoreSimulator/Profiles/Runtimes/*.simruntime/Contents/Resources/RuntimeRoot/System/Library/PrivateFrameworks/CoreIDVUI.framework/OCRB.otf
)
shopt -u nullglob
font="${OCRB_FONT:-${runtime_fonts[0]:-}}"
[ -n "$font" ] || { echo "no OCR-B font found; set OCRB_FONT" >&2; exit 1; }
rm -rf "$out/images"
rm -rf "$out/lines"
swift "$here/render.swift" "$out/manifest.tsv" "$font" "$out/images" 149 "$out/lines"

scores=("ML Kit=$out/mlkit.tsv")
if [ "${SKIP_IOS:-0}" != "1" ]; then
  echo "== 3. Apple Vision"
  swiftc -O "$here/vision.swift" -o "$out/vision-macos"
  "$out/vision-macos" "$out/images" > "$out/vision-macos.tsv"
  xcrun --sdk iphonesimulator swiftc -O -target arm64-apple-ios27.2-simulator "$here/vision.swift" -o "$out/vision-iossim"
  # simctl spawn is the only way to run a command-line binary inside the Simulator; the Xcode
  # MCP has no tool for it (an AI session adds the `# raw-ok` override, see platform-work.md).
  xcrun simctl spawn booted "$out/vision-iossim" "$out/images" > "$out/vision-ios.tsv"
  scores+=("Vision, iOS Simulator=$out/vision-ios.tsv" "Vision, macOS=$out/vision-macos.tsv")
fi

if [ "${SKIP_ANDROID:-0}" != "1" ]; then
  echo "== 4. ML Kit"
  pkg=io.lightine.tessera.ocrbench
  "$repo/gradlew" -q -p "$here/android" :app:assembleDebug
  android install --apks="$here/android/app/build/outputs/apk/debug/app-debug.apk"
  # Files pushed by the shell are not readable from the app's external folder, so the images go
  # through /data/local/tmp into the app's private folder (the app is debuggable: run-as works).
  adb shell rm -rf /data/local/tmp/ocrbench
  adb shell mkdir -p /data/local/tmp/ocrbench
  adb push "$out/images/." /data/local/tmp/ocrbench/ > /dev/null
  adb shell "run-as $pkg sh -c 'rm -rf files/images files/mlkit.tsv; mkdir -p files/images; cp /data/local/tmp/ocrbench/*.png files/images/'"
  adb shell am force-stop "$pkg"
  adb logcat -c
  adb shell am start -n "$pkg/.BenchActivity" > /dev/null
  for _ in $(seq 1 200); do
    done_line="$(adb logcat -d -s OCRBENCH:I | grep 'OCRBENCH DONE' || true)"
    [ -n "$done_line" ] && break
    sleep 3
  done
  [ -n "$done_line" ] || { echo "ML Kit run did not finish" >&2; exit 1; }
  echo "$done_line"
  adb exec-out run-as "$pkg" cat files/mlkit.tsv > "$out/mlkit.tsv"
else
  scores=("${scores[@]:1}")
fi

if [ -n "${OCR_BENCH_ONNX_MODEL:-}" ] && [ "${SKIP_ANDROID:-0}" != "1" ]; then
  echo "== 5. ONNX line recognizer"
  pkg=io.lightine.tessera.ocrbench
  [ "$(shasum -a 256 "$OCR_BENCH_ONNX_MODEL" | cut -d' ' -f1)" = c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8 ] \
    || { echo "OCR_BENCH_ONNX_MODEL does not match the expected SHA-256" >&2; exit 1; }
  adb shell rm -rf /data/local/tmp/ocrbench-lines
  adb shell mkdir -p /data/local/tmp/ocrbench-lines
  adb push "$out/lines/." /data/local/tmp/ocrbench-lines/ > /dev/null
  adb push "$OCR_BENCH_ONNX_MODEL" /data/local/tmp/ocrbench-model.onnx > /dev/null
  adb shell "run-as $pkg sh -c 'rm -rf files/lines files/onnx.tsv; mkdir -p files/lines; cp /data/local/tmp/ocrbench-lines/*.png files/lines/; cp /data/local/tmp/ocrbench-model.onnx files/model.onnx'"
  adb shell am force-stop "$pkg"
  adb logcat -c
  adb shell am start -n "$pkg/.OnnxBenchActivity" > /dev/null
  for _ in $(seq 1 200); do
    done_line="$(adb logcat -d -s OCRBENCH:I | grep 'OCRBENCH DONE' || true)"
    [ -n "$done_line" ] && break
    sleep 3
  done
  [ -n "$done_line" ] || { echo "ONNX run did not finish" >&2; exit 1; }
  echo "$done_line"
  adb exec-out run-as "$pkg" cat files/onnx.tsv > "$out/onnx.tsv"
  scores+=("ONNX PP-OCRv5, per-line crops=$out/onnx.tsv")
fi

echo "== 6. scores"
python3 -I "$here/score.py" "$out/manifest.tsv" "${scores[@]}"
