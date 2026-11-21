#!/usr/bin/env bash
# Builds OpenSSL's libcrypto as a static library for one iOS slice: the cryptography behind chip
# reading on iOS (emrtd-core's OpenSslChipCrypto, through cinterop). emrtd-core's Gradle build runs
# this script before its cinterop step; nothing it makes is committed (output goes under build/).
#
# Usage: build-openssl-ios.sh <ios-arm64|ios-simulator-arm64> <output-dir>
#
# The source tarball is checked against the pinned SHA-256 before it is extracted. By default it is
# downloaded from OpenSSL's official GitHub release; set TESSERA_OPENSSL_TARBALL to the path of a
# tarball already on disk to build offline (it is checked the same way).
#
# Output: <output-dir>/include/openssl/*.h, <output-dir>/lib/libcrypto.a, and
# <output-dir>/openssl-symbols.txt (every global symbol libcrypto defines, one per line, for the
# XCFramework's symbol hiding). A stamp file makes a rerun with the same inputs a no-op, so a CI
# cache of the output directory skips the build.

set -euo pipefail

# --- The pin. The dependency cadence checks it (.claude/skills/dependency-upgrade-cadence). ---
# OpenSSL 3.5 is a long-term-support series, supported until 2030-04-08
# (https://openssl-library.org/policies/releasestrat/).
OPENSSL_VERSION="3.5.9"
OPENSSL_SHA256="603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
OPENSSL_LTS_END="2030-04-08"
OPENSSL_URL="https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz"

# iOS 18 is the SDK's minimum (the XCFramework and the Swift package require it too).
IOS_MIN="18.0"

# Only what chip reading needs: EVP digests, ciphers and CMAC, BIGNUM, prime-field EC, RSA, DSA, DH,
# and the default provider built in. No TLS, no networking, no config file loading, no legacy
# provider (single DES for the retail MAC is three-key 3DES with three equal keys), no engines, no
# dynamic loading. Assembly stays on: OpenSSL's arm64 code uses the CPU's AES and SHA instructions,
# which are faster and constant time.
OPENSSL_OPTIONS=(
  no-shared no-apps no-tests no-docs no-module no-legacy no-engine no-dso no-ui-console
  no-ssl no-tls no-dtls no-quic no-sock no-http no-ocsp no-ct no-ts no-cmp no-cms no-srp no-srtp
  no-comp no-ec2m no-gost no-sm2 no-sm3 no-sm4 no-chacha no-poly1305 no-idea no-seed no-camellia
  no-aria no-bf no-cast no-rc2 no-rc4 no-rc5 no-md4 no-mdc2 no-rmd160 no-whirlpool no-blake2
  no-siphash no-ocb no-scrypt no-argon2 no-ecx no-ml-dsa no-ml-kem no-slh-dsa no-dgram
  no-autoload-config
)

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <ios-arm64|ios-simulator-arm64> <output-dir>" >&2
  exit 2
fi
SLICE="$1"
OUT="$2"

case "$SLICE" in
  ios-arm64)
    CONFIG_TARGET="ios64-xcrun"; SDK="iphoneos"; MIN_FLAG="-mios-version-min=${IOS_MIN}" ;;
  ios-simulator-arm64)
    CONFIG_TARGET="iossimulator-arm64-xcrun"; SDK="iphonesimulator"; MIN_FLAG="-mios-simulator-version-min=${IOS_MIN}" ;;
  *)
    echo "unknown slice: $SLICE" >&2; exit 2 ;;
esac

# Everything that changes the output goes into the stamp, the SDK version included.
SDK_VERSION="$(xcrun --sdk "$SDK" --show-sdk-version)"
STAMP_TEXT="openssl=${OPENSSL_VERSION} sha256=${OPENSSL_SHA256} slice=${SLICE} min=${IOS_MIN} sdk=${SDK_VERSION} options=${OPENSSL_OPTIONS[*]} script=2"
if [ -f "$OUT/stamp.txt" ] && [ -f "$OUT/lib/libcrypto.a" ] && [ "$(cat "$OUT/stamp.txt")" = "$STAMP_TEXT" ]; then
  echo "OpenSSL ${OPENSSL_VERSION} for ${SLICE}: up to date in ${OUT}"
  exit 0
fi

rm -rf "$OUT"
WORK="$OUT/work"
mkdir -p "$WORK/download" "$WORK/src" "$WORK/build"
TARBALL="$WORK/download/openssl-${OPENSSL_VERSION}.tar.gz"

if [ -n "${TESSERA_OPENSSL_TARBALL:-}" ]; then
  cp "$TESSERA_OPENSSL_TARBALL" "$TARBALL"
else
  echo "Downloading ${OPENSSL_URL}"
  curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
    --retry 3 --output "$TARBALL" "$OPENSSL_URL"
fi

ACTUAL_SHA256="$(shasum -a 256 "$TARBALL" | cut -d' ' -f1)"
if [ "$ACTUAL_SHA256" != "$OPENSSL_SHA256" ]; then
  echo "OpenSSL tarball SHA-256 mismatch: expected ${OPENSSL_SHA256}, got ${ACTUAL_SHA256}" >&2
  exit 1
fi

tar -xzf "$TARBALL" -C "$WORK/src"
SRC="$WORK/src/openssl-${OPENSSL_VERSION}"

start=$(date +%s)
(
  cd "$WORK/build"
  perl "$SRC/Configure" "$CONFIG_TARGET" --prefix="$WORK/prefix" --openssldir="$WORK/prefix/ssl" \
    "${OPENSSL_OPTIONS[@]}" "$MIN_FLAG" > configure.log 2>&1 || { tail -40 configure.log >&2; exit 1; }
  JOBS="$(sysctl -n hw.ncpu)"
  { make -j"$JOBS" build_generated && make -j"$JOBS" libcrypto.a; } > make.log 2>&1 || { tail -60 make.log >&2; exit 1; }
)

mkdir -p "$OUT/include/openssl" "$OUT/lib"
cp "$SRC"/include/openssl/*.h "$OUT/include/openssl/"
cp "$WORK"/build/include/openssl/*.h "$OUT/include/openssl/"
cp "$WORK/build/libcrypto.a" "$OUT/lib/libcrypto.a"
# Global symbols libcrypto defines (Mach-O names, leading underscore), for symbol hiding.
nm -gUj "$OUT/lib/libcrypto.a" | grep -E '^_' | sort -u > "$OUT/openssl-symbols.txt"
rm -rf "$WORK"

echo "OpenSSL ${OPENSSL_VERSION} (LTS until ${OPENSSL_LTS_END}) for ${SLICE} built in $(( $(date +%s) - start )) s"
printf '%s' "$STAMP_TEXT" > "$OUT/stamp.txt"
