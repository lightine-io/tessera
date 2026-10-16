#!/usr/bin/env bash
# Pre-tag gate (release-gate item 10, TES-218): build the XCFramework from this checkout and run
# tessera-swift's tests against it, before the tag exists. Catches a framework change that breaks
# the Swift package while the fix is still cheap.
#
#   scripts/test-swift-against-local-framework.sh [TESSERA_SWIFT_DIR] [SIMULATOR_NAME]
#
# Defaults: ../tessera-swift and "iPhone 17". Exit 0 only when the tests pass.
# Slow (a full XCFramework build plus a simulator test run); touches only
# <tessera-swift>/.local-xcframework, and restores whatever was there on exit.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SWIFT_DIR="${1:-${ROOT}/../tessera-swift}"
SIMULATOR="${2:-iPhone 17}"

[ -f "${SWIFT_DIR}/Package.swift" ] || { echo "error: no Package.swift in ${SWIFT_DIR}" >&2; exit 2; }
SWIFT_DIR="$(cd "${SWIFT_DIR}" && pwd)"

if ! xcrun simctl list devices available | grep -qF "    ${SIMULATOR} ("; then
    echo "error: no available simulator named '${SIMULATOR}'. Available iPhones:" >&2
    xcrun simctl list devices available | grep iPhone >&2 || true   # raw-ok
    exit 2
fi

echo "==> Building the XCFramework"
(cd "${ROOT}" && ./gradlew :mrz-camera-ios:packTesseraXCFramework)

# The pack task zips the release XCFramework from this directory; test the same bits.
BUILT="${ROOT}/mrz-camera-ios/build/XCFrameworks/release/Tessera.xcframework"
[ -d "${BUILT}" ] || { echo "error: ${BUILT} not found after the build" >&2; exit 2; }

LOCAL_DIR="${SWIFT_DIR}/.local-xcframework"
TARGET="${LOCAL_DIR}/Tessera.xcframework"
BACKUP=""

restore() {
    rm -rf "${TARGET}"
    if [ -n "${BACKUP}" ] && [ -d "${BACKUP}" ]; then
        mv "${BACKUP}" "${TARGET}"
    fi
}
trap restore EXIT

mkdir -p "${LOCAL_DIR}"
if [ -e "${TARGET}" ]; then
    BACKUP="${LOCAL_DIR}/Tessera.xcframework.backup.$$"
    mv "${TARGET}" "${BACKUP}"
fi
cp -R "${BUILT}" "${TARGET}"

echo "==> Testing tessera-swift against ${BUILT}"
cd "${SWIFT_DIR}"
TESSERA_LOCAL_XCFRAMEWORK=1 xcodebuild test \
    -scheme Tessera-Package \
    -destination "platform=iOS Simulator,name=${SIMULATOR}" \
    -skipPackagePluginValidation   # raw-ok
echo "==> tessera-swift tests passed against the local XCFramework"
