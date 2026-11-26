#!/usr/bin/env bash
# Link check: the device slice of an iOS XCFramework needs no Objective-C class at link time that
# is newer than the framework's minimum, iOS 15.
#
# Usage: check-ios-framework-symbols.sh <TesseraCore.xcframework>
#
# A class the framework names in code shows as an undefined `_OBJC_CLASS_$_<name>` symbol. When that
# reference is required (not weak), an app that links the framework needs the class in its SDK and
# on the device: a class newer than iOS 15 breaks the link against an older SDK and the load on an
# older system. Code that needs a newer class looks it up by name at run time instead (TES-366,
# `NFCTagReaderSessionConfiguration`, iOS 26.4).
#
# The check fails, naming the symbols, when a required class reference is not on ALLOWED below.
# Every class on ALLOWED exists on iOS 15 (its `API_AVAILABLE` in the iPhoneOS SDK headers, or a
# base Foundation or runtime class with no mark). Add a class only after checking its header; a
# class introduced after iOS 15 never goes on the list. Weak references (`weak external`) pass.

set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <framework.xcframework>" >&2
  exit 2
fi
XCFRAMEWORK="$1"

# The device slice; the binary inside a framework bears the framework's name.
FRAMEWORK="$(find "$XCFRAMEWORK/ios-arm64" -maxdepth 1 -name '*.framework' -type d | head -n 1)"
if [ -z "$FRAMEWORK" ]; then
  echo "no ios-arm64 framework in $XCFRAMEWORK" >&2
  exit 2
fi
BIN="$FRAMEWORK/$(basename "$FRAMEWORK" .framework)"
if [ ! -f "$BIN" ]; then
  echo "no binary at $BIN" >&2
  exit 2
fi

# One class per line: its name, then the iOS version its header gives ("base": a Foundation or
# runtime class with no availability mark, there since the first iOS SDK).
ALLOWED="
AVCaptureDevice 4.0
AVCaptureDeviceDiscoverySession 10.0
AVCaptureDeviceInput 4.0
AVCaptureSession 4.0
AVCaptureVideoDataOutput 4.0
CIImage 5.0
NFCISO7816APDU 13.0
NFCTagReaderSession 13.0
NSArray base
NSCalendar base
NSData base
NSDate base
NSDateComponents base
NSDecimalNumber base
NSDictionary base
NSEnumerator base
NSError base
NSException base
NSFileManager base
NSLock base
NSMethodSignature base
NSMutableArray base
NSMutableDictionary base
NSMutableSet base
NSNotificationCenter base
NSNull base
NSNumber base
NSObject base
NSOperationQueue 2.0
NSProcessInfo base
NSRecursiveLock base
NSSet base
NSString base
NSTimeZone base
NSValue base
VNImageRequestHandler 11.0
VNRecognizeTextRequest 13.0
VNRecognizedText 13.0
"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "$ALLOWED" | awk 'NF { print $1 }' | sort -u > "$WORK/allowed.txt"

# `nm -m -u` prints "(undefined) external _OBJC_CLASS_$_Name" for a required reference and
# "(undefined) weak external ..." for a weak one. A static archive lists a symbol once per object.
nm -m -u "$BIN" \
  | grep -E '\(undefined\) external _OBJC_CLASS_\$_' \
  | sed -E 's/.*_OBJC_CLASS_\$_//' \
  | sort -u > "$WORK/required.txt"

if [ ! -s "$WORK/required.txt" ]; then
  echo "no required Objective-C class reference found in $BIN; the check cannot be trusted" >&2
  exit 2
fi

UNLISTED="$(comm -23 "$WORK/required.txt" "$WORK/allowed.txt")"
if [ -n "$UNLISTED" ]; then
  echo "the framework needs Objective-C classes that are not known to exist on iOS 15:" >&2
  echo "$UNLISTED" | sed 's/^/  _OBJC_CLASS_$_/' >&2
  echo "look the class up by name at run time, or, if its header says iOS 15 or older, add it to ALLOWED" >&2
  exit 1
fi

echo "ok: $(wc -l < "$WORK/required.txt" | tr -d ' ') required Objective-C class references, all on the iOS 15 list"
