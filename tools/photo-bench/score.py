#!/usr/bin/env python3
"""Scores the full-page photo benchmark (TES-131).

Usage: python3 -I score.py <manifest.tsv> <results.tsv> [experiment.tsv]

Per image, against the generator's ground truth:
  read   - the SDK decoded the document and its raw MRZ lines equal the generated ones exactly
  partly - the SDK found an MRZ and decoded it, but at least one character differs
  none   - no MRZ found (or a capture error)
Prints one table: variant x format, as read / partly / none counts, and the failures' details.
"""
import sys
from collections import defaultdict

manifest, results = sys.argv[1], sys.argv[2]
truth = {}
for row in open(manifest, encoding="utf-8").read().splitlines():
    ident, fmt, lines = row.split("\t")
    truth[ident] = (fmt, lines.split("|"))


def grade(expected, outcome, parse, recognized):
    if outcome != "Decoded":
        return "none"
    if "rawLines=[" + ", ".join(expected) + "]" in parse:
        return "read"
    return "partly"


counts = defaultdict(lambda: defaultdict(int))
details = []
for row in open(results, encoding="utf-8").read().splitlines():
    name, outcome, parse, millis, recognized = (row.split("\t") + [""] * 5)[:5]
    ident, variant = name.rsplit(".", 1)[0].split("__")
    fmt, expected = truth[ident]
    g = grade(expected, outcome, parse, recognized)
    counts[(variant, fmt)][g] += 1
    if g != "read":
        details.append(f"{name}\t{g}\t{outcome}\t{parse[:60]}\t{recognized[:200]}")

variants = sorted({v for v, _ in counts}, key=lambda v: (v != "mrzcrop", v))
print("| Variant | TD3 passport page | TD1 ID card |")
print("|---|---|---|")
for v in variants:
    cells = []
    for fmt in ("TD3", "TD1"):
        c = counts[(v, fmt)]
        cells.append(f"{c['read']} read / {c['partly']} partly / {c['none']} none")
    print(f"| {v} | {cells[0]} | {cells[1]} |")
if details:
    print("\nNot read correctly:")
    print("\n".join(details))


# The rescue experiment: how many expected MRZ lines appear exactly (a) among ML Kit's first-pass
# lines, (b) among the re-read rows (crop at 1x or 2x), and the images where every line is
# recovered, per variant. Raw text, no correction.
if len(sys.argv) > 3:
    seen = defaultdict(lambda: defaultdict(set))
    for row in open(sys.argv[3], encoding="utf-8").read().splitlines():
        name, kind, text = (row.split("\t") + [""] * 3)[:3]
        seen[name][kind].add(text.strip())
    stats = defaultdict(lambda: [0, 0, 0, 0, 0])  # images, p1 full, rescue full, p1 lines, rescue lines
    total_lines = defaultdict(int)
    for name, kinds in seen.items():
        ident, variant = name.rsplit(".", 1)[0].split("__")
        expected = truth[ident][1]
        p1 = kinds["p1"]
        rr = kinds["rr1"] | kinds["rr2"]
        s = stats[variant]
        s[0] += 1
        s[1] += all(e in p1 for e in expected)
        s[2] += all(e in p1 or e in rr for e in expected)
        s[3] += sum(e in p1 for e in expected)
        s[4] += sum(e in p1 or e in rr for e in expected)
        total_lines[variant] += len(expected)
    print("\nRescue experiment (exact MRZ lines present in the recognizer's text):")
    print("| Variant | images with every line, first pass | ... after crop and re-read | lines exact, first pass | ... after re-read |")
    print("|---|---|---|---|---|")
    for v in sorted(stats):
        s = stats[v]
        print(f"| {v} | {s[1]}/{s[0]} | {s[2]}/{s[0]} | {s[3]}/{total_lines[v]} | {s[4]}/{total_lines[v]} |")
