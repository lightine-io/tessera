#!/usr/bin/env bash
# Runs the full-page photo benchmark end to end (TES-131) on a Mac with the Android SDK and Swift:
#   1. the generator writes synthetic MRZ text (TD3 and TD1) with Tessera's own MrzGenerator;
#   2. render.swift draws each document as a whole page on a desk, at phone-photo size, in ten
#      variants (flat, clutter, tilt, perspective, blur, lowlight, glare, far, landscape, combined)
#      plus a tight MRZ crop as the control;
#   3. a small Android app reads each photo with the SDK's saved-image path (SavedImageMrzReader over
#      mlKitSavedImageRecognizer) on a running emulator;
#   4. the same app also runs a rescue experiment (find the MRZ rows, crop, read again), and
#      score.py prints the per-variant table and the experiment's gain.
#
# Usage: tools/bench/photo/run.sh [out-dir]   (run from anywhere; needs one running Android emulator)
# Images and results stay in out-dir; never commit them.
#
# The OCR-B font is the one inside the iOS Simulator runtime (the same one the TES-149 bench uses).
# It is used in place and never copied: set OCRB_FONT to use another OCR-B font file whose licence
# you have checked.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
out="${1:-${TMPDIR:-/tmp}/tessera-photo-bench}"
mkdir -p "$out"

echo "== 1. ground truth"
"$repo/gradlew" -q -p "$here/../generator" run --args="photo $out/manifest.tsv ${PER_FORMAT:-8} 131"

echo "== 2. photos"
font="${OCRB_FONT:-$(find /private/var/run/com.apple.security.cryptexd/mnt /Library/Developer/CoreSimulator \
  -path '*CoreIDVUI.framework/OCRB.otf' 2>/dev/null | head -1 || true)}"
[ -n "$font" ] || { echo "no OCR-B font found; set OCRB_FONT" >&2; exit 1; }
rm -rf "$out/images"
swift "$here/render.swift" "$out/manifest.tsv" "$font" "$out/images" 131

echo "== 3. Android saved-image reading"
pkg=io.lightine.tessera.photobench
"$repo/gradlew" -q -p "$here/android" :app:assembleDebug
android install --apks="$here/android/app/build/outputs/apk/debug/app-debug.apk"
# The images go through /data/local/tmp into the app's private folder (the app is debuggable).
adb shell rm -rf /data/local/tmp/photobench
adb shell mkdir -p /data/local/tmp/photobench
adb push "$out/images/." /data/local/tmp/photobench/ > /dev/null
adb shell "run-as $pkg sh -c 'rm -rf files/images files/results.tsv files/experiment.tsv; mkdir -p files/images; cp /data/local/tmp/photobench/*.jpg files/images/'"
adb shell am force-stop "$pkg"
adb logcat -c
adb shell am start -n "$pkg/.BenchActivity" > /dev/null
done_line=""
for _ in $(seq 1 200); do
  done_line="$(adb logcat -d -s PHOTOBENCH:I | grep 'PHOTOBENCH DONE' || true)"
  [ -n "$done_line" ] && break
  sleep 3
done
[ -n "$done_line" ] || { echo "the read did not finish" >&2; exit 1; }
echo "$done_line"
adb exec-out run-as "$pkg" cat files/results.tsv > "$out/results.tsv"
adb exec-out run-as "$pkg" cat files/experiment.tsv > "$out/experiment.tsv"

echo "== 4. scores"
python3 -I "$here/score.py" "$out/manifest.tsv" "$out/results.tsv" "$out/experiment.tsv"
