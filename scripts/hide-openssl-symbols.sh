#!/usr/bin/env bash
# Hides OpenSSL's symbols inside the static TesseraCore framework binary, so an app that links its own
# OpenSSL (or another library that bundles one) gets no duplicate symbols and never mixes the two.
#
# Usage: hide-openssl-symbols.sh <framework-binary> <openssl-symbols.txt> <ios|ios-simulator> <min-ios>
#
# <framework-binary> is the static archive inside TesseraCore.framework (one architecture), as
# Kotlin/Native links it: the Kotlin code plus the libcrypto objects embedded from emrtd-core's
# klib. <openssl-symbols.txt> lists every global symbol libcrypto defines
# (scripts/build-openssl-ios.sh writes it). The platform and minimum iOS version go into the merged
# object's build-version load command, which `xcodebuild -create-xcframework` reads.
#
# How: a single-object prelink, the technique behind Xcode's "Perform Single-Object Prelink".
# `ld -r` merges every member into one object, and -unexported_symbols_list makes the OpenSSL
# symbols private; `ld -r` then turns private symbols into local ones. The Kotlin code's calls into
# OpenSSL are resolved inside that object, so nothing outside the framework can see or replace
# them. The archive is then rebuilt around the one object. The script fails if any OpenSSL symbol
# is still global afterwards.

set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo "usage: $0 <framework-binary> <openssl-symbols.txt> <ios|ios-simulator> <min-ios>" >&2
  exit 2
fi
BIN="$1"
SYMS="$2"
PLATFORM="$3"
MIN_IOS="$4"
case "$PLATFORM" in
  ios) SDK="iphoneos" ;;
  ios-simulator) SDK="iphonesimulator" ;;
  *) echo "unknown platform: $PLATFORM" >&2; exit 2 ;;
esac
SDK_VERSION="$(xcrun --sdk "$SDK" --show-sdk-version)"

ARCHS="$(xcrun lipo -archs "$BIN")"
case "$ARCHS" in
  *" "*) echo "expected one architecture in $BIN, found: $ARCHS" >&2; exit 1 ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# -all_load: every member goes in (an archive member is otherwise loaded only on demand, and an
# `ld -r` has no demand of its own). The app's final link still dead-strips what nothing uses.
xcrun ld -r -arch "$ARCHS" -platform_version "$PLATFORM" "$MIN_IOS" "$SDK_VERSION" \
  -all_load -unexported_symbols_list "$SYMS" "$BIN" -o "$WORK/Tessera.o"
xcrun libtool -static -no_warning_for_no_symbols -o "$WORK/Tessera.a" "$WORK/Tessera.o"

LEAKED="$(nm -gUj "$WORK/Tessera.a" | grep -E '^_' | sort -u | comm -12 - <(sort -u "$SYMS") || true)"
if [ -n "$LEAKED" ]; then
  echo "OpenSSL symbols are still global in $BIN:" >&2
  echo "$LEAKED" | head -20 >&2
  exit 1
fi

mv "$WORK/Tessera.a" "$BIN"
echo "OpenSSL symbols hidden in $BIN"
