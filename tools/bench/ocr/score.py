"""Scores OCR benchmark results against the generator's manifest (TES-149).

Usage: python3 -I score.py <manifest.tsv> <label>=<fragments.tsv> [<label>=<fragments.tsv> ...]

A fragments file has one row per recognized text fragment:
image <TAB> variant <TAB> centre-x <TAB> centre-y <TAB> width <TAB> height <TAB> angle <TAB> text
(pixels, y growing downwards, baseline angle in radians). Fragments are joined into lines by
their position along the image's median baseline angle: a fragment joins the nearest line that
it does not overlap horizontally. The same grouping runs for every engine, so the score measures
the recognizer and not its line grouping. Spaces are dropped (an MRZ has none); nothing else is corrected.

Prints a Markdown table: character error rate (edit distance over ground-truth characters) and
the share of images whose every MRZ line was read exactly, per engine and condition. The first
table counts every difference; the second counts the letter O and the number 0 as one character.
That confusion dominates every engine here, and the field layout narrows it (a country code holds
letters, a date holds numbers), so the second table shows what is left once it is set aside.
"""

import collections
import math
import sys

CONDITIONS = ["clean", "blur", "glare", "skew", "combined"]


def levenshtein(a, b):
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def assemble(fragments):
    """Groups (x, y, w, h, angle, text) fragments into lines, top to bottom, left to right."""
    if not fragments:
        return []
    angles = sorted(f[4] for f in fragments)
    a = angles[len(angles) // 2]
    cos, sin = math.cos(a), math.sin(a)
    # Rotate the centres so the text baseline is horizontal.
    upright = [(x * cos + y * sin, -x * sin + y * cos, w, h, t) for x, y, w, h, _, t in fragments]
    lines = []
    for x, y, w, h, text in sorted(upright, key=lambda f: (-f[2], f[1])):
        left, right = x - w / 2, x + w / 2
        candidates = [
            line
            for line in lines
            if abs(y - line["y"]) < 0.5 * max(h, line["h"])
            and all(right <= l + 0.1 * w or left >= r - 0.1 * w for l, r, _ in line["parts"])
        ]
        if candidates:
            min(candidates, key=lambda line: abs(y - line["y"]))["parts"].append((left, right, text))
        else:
            lines.append({"y": y, "h": h, "parts": [(left, right, text)]})
    out = []
    for line in sorted(lines, key=lambda line: line["y"]):
        text = "".join(t for _, _, t in sorted(line["parts"])).replace(" ", "").upper()
        if text:
            out.append(text)
    return out


def main():
    truth = {}
    for row in open(sys.argv[1], encoding="utf-8"):
        cols = row.rstrip("\n").split("\t")
        if len(cols) == 3:
            truth[cols[0]] = cols[2].split("|")

    results = collections.OrderedDict()
    for spec in sys.argv[2:]:
        label, path = spec.split("=", 1)
        fragments = collections.defaultdict(list)
        variants = []
        for row in open(path, encoding="utf-8"):
            cols = row.rstrip("\n").split("\t")
            if len(cols) < 8:
                continue
            image, variant = cols[0], cols[1]
            x, y, w, h, angle = map(float, cols[2:7])
            text = "\t".join(cols[7:])
            if variant not in variants:
                variants.append(variant)
            fragments[(variant, image)].append((x, y, w, h, angle, text))
        for variant in variants:
            name = f"{label} ({variant})" if len(variants) > 1 else label
            for fold in (False, True):
                stats = {c: [0, 0, 0, 0] for c in CONDITIONS}  # errors, chars, exact images, images
                for doc_id, lines in truth.items():
                    for condition in CONDITIONS:
                        hyp = assemble(fragments.get((variant, f"{doc_id}__{condition}.png"), []))
                        ref = lines
                        if fold:
                            hyp = [line.replace("O", "0") for line in hyp]
                            ref = [line.replace("O", "0") for line in ref]
                        s = stats[condition]
                        s[0] += levenshtein("\n".join(hyp), "\n".join(ref))
                        s[1] += len("\n".join(ref))
                        s[2] += int(hyp == ref)
                        s[3] += 1
                results.setdefault(fold, collections.OrderedDict())[name] = stats

    for fold, title in ((False, "Every difference counts"), (True, "Letter O and digit 0 counted as one")):
        print(f"{title}:")
        print()
        print("| Engine | " + " | ".join(CONDITIONS) + " | all |")
        print("|---|" + "---|" * (len(CONDITIONS) + 1))
        for name, stats in results[fold].items():
            cells = []
            for c in CONDITIONS:
                e, n, x, k = stats[c]
                cells.append(f"{100 * e / n:.1f}% / {100 * x / k:.0f}%")
            e = sum(s[0] for s in stats.values())
            n = sum(s[1] for s in stats.values())
            x = sum(s[2] for s in stats.values())
            k = sum(s[3] for s in stats.values())
            cells.append(f"{100 * e / n:.1f}% / {100 * x / k:.0f}%")
            print(f"| {name} | " + " | ".join(cells) + " |")
        print()
    print("Each cell: character error rate / share of images with every MRZ line exact.")


if __name__ == "__main__":
    main()
