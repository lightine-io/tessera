#!/usr/bin/env bash
# Runs the OCR benchmark end to end (TES-149) on a Mac with Xcode and the Android SDK:
#   1. the generator writes synthetic MRZ text with Tessera's own MrzGenerator;
#   2. render.swift draws each MRZ in OCR-B, clean and with blur, glare, skew, and all three;
#   3. Apple Vision reads the images in a booted iOS Simulator (and on macOS for comparison);
#   4. ML Kit reads them on a running Android emulator;
#   5. score.py prints the result tables.
#
# Usage: tools/bench/ocr/run.sh [out-dir]   (run from the repo root)
# Needs: a booted iOS Simulator (SKIP_IOS=1 to skip) and one running Android emulator
# (SKIP_ANDROID=1 to skip). Images and results stay in out-dir; never commit them.
#
# The OCR-B font is the one inside the iOS Simulator runtime. It is used in place and never
# copied: set OCRB_FONT to use another OCR-B font file whose licence you have checked.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
out="${1:-${TMPDIR:-/tmp}/tessera-ocr-bench}"
mkdir -p "$out"

echo "== 1. ground truth"
"$repo/gradlew" -q -p "$here/../generator" run --args="ocr $out/manifest.tsv 20 149"

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
swift "$here/render.swift" "$out/manifest.tsv" "$font" "$out/images" 149

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

echo "== 5. scores"
python3 -I "$here/score.py" "$out/manifest.tsv" "${scores[@]}"
