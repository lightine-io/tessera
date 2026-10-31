#!/usr/bin/env python3
"""Fitness check for Tessera: the SDK's own main sources make no network calls and write
no logs. Python 3 stdlib only. Reads files, writes nothing, needs no network and no secret.

Run from the repo root: `python3 -I scripts/check-no-network-or-logging.py [--root DIR] [--swift DIR]`.
Rule home: CLAUDE.md "Safety invariants". Runs in PR CI (`repo · rules`).

Scans Kotlin main source sets only: every `*/src/*Main/**/*.kt` (commonMain, jvmMain, androidMain,
iosMain, appleMain, nativeMain). Test source sets, build
folders and samples/ are skipped. Comments and string literals are blanked first, so KDoc
links and message text never trip it. --swift DIR also scans DIR/Sources/**/*.swift (the Swift
package lives in a sibling repo; CI runs the Kotlin part only).

Telemetry goes through the SDK's own sink (ADR-015), which is not a log call and is not flagged.
There is no per-line escape: a real need changes this file, in review.

Exit 0: clean. Exit 1: findings (listed as path:line: reason). Exit 2: could not run.
"""

import argparse
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("check_docs", os.path.join(HERE, "check-docs.py"))
_cd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_cd)
mask_source = _cd.mask_source  # blanks comments and string literals, keeps newlines

SKIP_DIRS = {".git", "build", ".gradle", "samples", "node_modules"}

# (regex, reason). Both lists apply to Kotlin and Swift; the *_SWIFT lists add Swift-only names.
NETWORK = [
    (r"\bjavax?\.net\b", "network API (java.net / javax.net)"),
    (r"\bokhttp3\b", "network API (okhttp3)"),
    (r"\bio\.ktor\b", "network API (io.ktor)"),
    (r"\bretrofit2?\b", "network API (retrofit)"),
    (r"\bHttps?URLConnection\b", "network API (HttpURLConnection)"),
    (r"\.openConnection\s*\(", "network API (URL.openConnection)"),
    (r"\.openStream\s*\(", "network API (URL.openStream)"),
    (r"\bURL\s*\([^)]*\)\s*\.read(?:Text|Bytes)\s*\(", "network API (URL.readText / readBytes)"),
    (r"\b(?:Server|Datagram|SSL)?Socket\b", "network API (Socket)"),
    (r"\bNS(?:Mutable)?URL(?:Session|Connection|Request)\w*", "network API (NSURLSession / NSURLConnection / NSURLRequest)"),
    (r"\bplatform\.Network\b", "network API (platform.Network)"),
]
NETWORK_SWIFT = [
    (r"\bURLSession\b", "network API (URLSession)"),
    (r"\b(?:NS)?(?:Mutable)?URLRequest\b", "network API (URLRequest)"),
    (r"\bNWConnection\b", "network API (NWConnection)"),
    (r"^\s*(?:@\w+\s+)*import\s+Network\b", "network API (Network framework)"),
]
LOGGING = [
    (r"\bandroid\.util\.Log\b", "logging (android.util.Log)"),
    (r"\bLog\.(?:v|d|i|w|e|wtf)\s*\(", "logging (Log.v/d/i/w/e/wtf)"),
    (r"(?<![\w.])(?:println|print)\s*\(", "logging (println / print)"),
    (r"\bSystem\.(?:out|err)\b", "logging (System.out / System.err)"),
    (r"\.printStackTrace\b", "logging (printStackTrace)"),
    (r"\bNSLog\b", "logging (NSLog)"),
    (r"\bjava\.util\.logging\b", "logging (java.util.logging)"),
    (r"\borg\.slf4j\b", "logging (org.slf4j)"),
    (r"\bTimber\b", "logging (Timber)"),
    (r"\bco\.touchlab\.kermit\b|\bKermit\b", "logging (Kermit)"),
    (r"\bio\.github\.aakira\.napier\b|\bNapier\b", "logging (Napier)"),
    (r"\bKotlinLogging\b|\bio\.github\.oshai\b|\bmu\.KotlinLogging\b", "logging (kotlin-logging)"),
]
LOGGING_SWIFT = [
    (r"\bos_log\b", "logging (os_log)"),
    (r"\bLogger\b", "logging (os Logger)"),
    (r"(?<![\w.])(?:debugPrint|dump)\s*\(", "logging (debugPrint / dump)"),
]
KOTLIN_RULES = [(re.compile(p, re.M), r) for p, r in NETWORK + LOGGING]
SWIFT_RULES = [(re.compile(p, re.M), r) for p, r in NETWORK + NETWORK_SWIFT + LOGGING + LOGGING_SWIFT]


def is_main_kotlin(parts):
    """True for .../src/<x>Main/... (parts is the split path)."""
    for i, part in enumerate(parts[:-2]):
        if part == "src" and parts[i + 1].endswith("Main"):
            return True
    return False


def walk(root):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            yield os.path.relpath(os.path.join(dirpath, name), root)


def scan(path, label, rules):
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    masked, _ = mask_source(text, swift=label.endswith(".swift"))
    out = []
    for lineno, line in enumerate(masked.split("\n"), 1):
        for rx, reason in rules:
            if rx.search(line):
                out.append("%s:%d: %s" % (label, lineno, reason))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", default=".")
    ap.add_argument("--swift", metavar="DIR", help="also scan DIR/Sources/**/*.swift")
    args = ap.parse_args(argv)
    if not os.path.isdir(args.root) or (args.swift and not os.path.isdir(args.swift)):
        sys.stderr.write("check-no-network-or-logging: folder not found\n")
        return 2
    findings, count = [], 0
    for rel in walk(args.root):
        parts = rel.split(os.sep)
        if rel.endswith(".kt") and is_main_kotlin(parts):
            count += 1
            findings += scan(os.path.join(args.root, rel), rel.replace(os.sep, "/"), KOTLIN_RULES)
    if args.swift:
        for rel in walk(args.swift):
            if rel.endswith(".swift") and rel.split(os.sep)[0] == "Sources":
                count += 1
                findings += scan(os.path.join(args.swift, rel), "swift:" + rel.replace(os.sep, "/"), SWIFT_RULES)
    if findings:
        print("\n".join(findings))
        print("check-no-network-or-logging: %d finding(s) in %d main source files." % (len(findings), count))
        return 1
    print("check-no-network-or-logging: clean (%d main source files, no network or logging calls)." % count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
