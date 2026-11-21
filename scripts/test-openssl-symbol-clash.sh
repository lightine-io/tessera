#!/usr/bin/env bash
# Link test: an iOS simulator app that links its own OpenSSL next to the Tessera framework links
# with no duplicate symbols, and neither copy of OpenSSL is visible to the other. Proves
# scripts/hide-openssl-symbols.sh did its job.
#
# Usage: test-openssl-symbol-clash.sh <Tessera.framework> <app-libcrypto.a> <openssl-include-dir>
#
# Two checks:
# 1. The framework defines no global symbol that the app's libcrypto defines. Without hiding, a
#    plain Kotlin/Native framework links without an error but silently mixes the two copies: the
#    linker takes each OpenSSL function from whichever copy it loads first.
# 2. An app that calls into its own OpenSSL and loads all of it (-force_load, as an app or a vendor
#    library that bundles OpenSSL as objects does) links next to the framework. With the OpenSSL
#    symbols merged into the framework object but left global, this link fails with 3,862 duplicate
#    symbols (checked by hand when the test was written).
#
# The framework must be the iosSimulatorArm64 slice; the app's libcrypto stands in for any other
# OpenSSL (the test uses the same build, the worst case: every symbol name matches).

set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <Tessera.framework> <app-libcrypto.a> <openssl-include-dir>" >&2
  exit 2
fi
FRAMEWORK="$1"
APP_LIBCRYPTO="$2"
INCLUDE="$3"
BIN="$FRAMEWORK/Tessera"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# 1. No OpenSSL symbol is global in the framework.
nm -gUj "$APP_LIBCRYPTO" | grep -E '^_' | sort -u > "$WORK/openssl.txt"
nm -gUj "$BIN" | grep -E '^_' | sort -u > "$WORK/framework.txt"
SHARED="$(comm -12 "$WORK/openssl.txt" "$WORK/framework.txt")"
if [ -n "$SHARED" ]; then
  echo "the framework exports $(echo "$SHARED" | wc -l | tr -d ' ') OpenSSL symbols, for example:" >&2
  echo "$SHARED" | head -10 >&2
  exit 1
fi

# 2. The app with its own OpenSSL links.
cat > "$WORK/main.c" <<'EOF'
#include <openssl/evp.h>

// The app's own use of OpenSSL: one SHA-256 through EVP.
int main(void) {
    unsigned char out[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    return EVP_Digest("abc", 3, out, &len, EVP_sha256(), NULL) == 1 ? 0 : 1;
}
EOF

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
# -ObjC loads the framework's object (it holds Objective-C classes).
if ! xcrun --sdk iphonesimulator clang -target arm64-apple-ios18.0-simulator -isysroot "$SDK" \
  -I "$INCLUDE" "$WORK/main.c" -Wl,-force_load,"$APP_LIBCRYPTO" "$BIN" -ObjC -Wl,-dead_strip \
  -framework Foundation -framework UIKit -framework CoreGraphics -framework CoreMedia \
  -framework CoreVideo -framework AVFoundation -framework Vision -framework ImageIO \
  -lc++ -lz -o "$WORK/app" > "$WORK/link.log" 2>&1; then
  echo "$(grep -c 'duplicate symbol' "$WORK/link.log" || true) duplicate symbols; first lines of the link log:" >&2
  head -20 "$WORK/link.log" >&2
  echo "link test FAILED: the app's OpenSSL and the Tessera framework do not link together" >&2
  exit 1
fi
echo "link test passed: an app with its own OpenSSL links the Tessera framework with no shared or duplicate symbols"
