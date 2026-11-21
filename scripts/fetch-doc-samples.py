#!/usr/bin/env python3
"""Fetch the Kotlin code blocks of the public tutorials and how-tos, for writing samples.

Python 3 stdlib only. Reads the live public KB anonymously over HTTPS (the same reader as
`check-docs.py samples --live`) and writes one file per page into OUT_DIR, outside the repo:
`<page id>.kt.txt`, with each block between the `// region <page id> block <n>` and
`// endregion` lines a sample uses. Copy a region into the page's sample file under
samples/kotlin and wrap it until it compiles; the wrapper stays outside the region.

Run: python3 -I scripts/fetch-doc-samples.py OUT_DIR
Exit 0: written. Exit 2: could not read the KB.
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("check_docs", os.path.join(HERE, "check-docs.py"))
_cd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_cd)


def main(argv):
    if len(argv) != 1:
        sys.stderr.write("usage: fetch-doc-samples.py OUT_DIR\n")
        return 2
    out = argv[0]
    try:
        pages = _cd.fetch_live_pages(_cd.http_get_json)
    except Exception as exc:  # network, HTTP, JSON, or shape
        _cd.fail("cannot read the live KB at %s: %s: %s" % (_cd.KB_BASE, type(exc).__name__, exc))
    os.makedirs(out, exist_ok=True)
    for aid in sorted(pages):
        title, text = pages[aid]
        blocks = _cd.kotlin_blocks(text)
        if not blocks:
            continue
        parts = ["// %s %s" % (aid, title)]
        for no, label, code in blocks:
            parts += ["", "// label: %s" % (label or "(none)"), "// region %s block %d" % (aid, no), code,
                      "// endregion"]
        with open(os.path.join(out, aid + ".kt.txt"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(parts) + "\n")
        print("%s: %d block(s), %s" % (aid, len(blocks), title))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
