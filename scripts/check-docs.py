#!/usr/bin/env python3
"""Documentation checks for Tessera. Python 3 stdlib only.

Reads files, writes nothing, needs no network and no secret. Run from the repo root
with `python3 -I scripts/check-docs.py <command>`. Rule: .claude/rules/documentation.md.

Commands and where they run:

  pointers         PR CI (`repo · rules`). No path into the retired docs folder, no
                   backticked *.md name that git does not track, no quoted CLAUDE.md
                   section name that is not a CLAUDE.md heading.
  style [FILE..]   PR CI on repo Markdown (no FILE: the default set below). No em-dash,
                   no banned word. With --kdoc: doc comments in main sources instead
                   (release gate until the code-docs pass reaches zero).
  code-docs        Release gate. Every public Kotlin declaration in a main source set
                   has a doc comment. --swift DIR adds a Swift package's Sources/.
  shelf DIR        Release gate. DIR is a local folder holding the public pages (the
                   vault's kb-public/new/ at release time, or an export of the public KB
                   arranged the same way). Every check mark in the capability matrix links
                   a how-to page for that platform. The repo-root llms.txt lists every
                   consumer tutorial and how-to by its exact title, with a KB article URL.
                   Reads local files only: no network, so CI never depends on YouTrack.

Exit 0: clean. Exit 1: findings (listed as path:line: message). Exit 2: could not run.
"""

import argparse
import os
import re
import subprocess
import sys

EM_DASH = chr(0x2014)
BANNED = ["simply", "easy", "just", "quickly", "note that", "please", "let's", "let" + chr(0x2019) + "s"]
BANNED_RE = re.compile(r"(?<![\w'])(" + "|".join(re.escape(w) for w in BANNED) + r")(?![\w'])", re.I)

# Built from pieces so this file never matches its own pattern.
RETIRED = "do" + "cs/"
RETIRED_RE = re.compile(r"(?<![\w.-])" + re.escape(RETIRED) + r"(?:[\w./-]*?\.md\b|(?:features|decisions|mobile)/)")

TEXT_EXT = {".md", ".kt", ".kts", ".sh", ".py", ".yml", ".yaml", ".json", ".txt", ".swift", ".toml"}
DEFAULT_STYLE_GLOBS = (".md", "llms.txt")
IGNORED_BACKTICK = re.compile(r"^(SESSION-HANDOFF-.*|MEMORY)\.md$")
SECTION_REFS = [
    re.compile(r"CLAUDE\.md[^\"“\n]{0,30}?[\"“]([^\"”\n]{2,80})[\"”]"),
    re.compile(r"CLAUDE\.md\s*'([^'\n]{2,80})'"),
    re.compile(r"CLAUDE\.md`?\)?\s*(?:→|->)\s*([A-Z][^.,;:()\"\n]{2,60})"),
    re.compile(r"\(CLAUDE\.md\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)+)\)"),
]


# ------------------------------------------------------------------ shared helpers

def fail(msg):
    sys.stderr.write("check-docs: %s\n" % msg)
    sys.exit(2)


def tracked_files(root):
    """Paths git tracks under root; falls back to a walk when root is not a git checkout."""
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "-z"], capture_output=True, check=True)
        files = [p for p in out.stdout.decode("utf-8", "replace").split("\0") if p]
        if files:
            return files
    except (OSError, subprocess.CalledProcessError):
        pass
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "build", ".gradle")]
        for name in filenames:
            found.append(os.path.relpath(os.path.join(dirpath, name), root))
    return sorted(found)


def read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def unreleased_only(text):
    """Keep only the [Unreleased] section of a Keep a Changelog file; older sections are history."""
    out, keep = [], False
    for line in text.splitlines(True):
        if line.startswith("## ["):
            keep = line.startswith("## [Unreleased]")
        out.append(line if keep else "\n")
    return "".join(out)


def prose_lines(text):
    """Yield (lineno, line) of Markdown prose: fenced code blocks and inline code are blanked."""
    fence = False
    for no, line in enumerate(text.splitlines(), 1):
        if re.match(r"^\s*(```|~~~)", line):
            fence = not fence
            continue
        if fence:
            continue
        yield no, re.sub(r"`[^`]*`", "", line)


def style_findings(label, text, first_line=1):
    found = []
    for no, line in prose_lines(text):
        if EM_DASH in line:
            found.append("%s:%d: em-dash (use a hyphen or two sentences)" % (label, no + first_line - 1))
        for m in BANNED_RE.finditer(line):
            found.append("%s:%d: banned word '%s'" % (label, no + first_line - 1, m.group(1)))
    return found


def report(findings, what):
    for f in findings:
        print(f)
    if findings:
        print("%d %s finding(s)." % (len(findings), what))
        return 1
    print("%s: clean." % what)
    return 0


# ------------------------------------------------------------------ pointers

def claude_headings(root):
    path = os.path.join(root, "CLAUDE.md")
    if not os.path.exists(path):
        return []
    names = []
    for line in read(path).splitlines():
        if line.startswith("#"):
            names.append(line.lstrip("#").strip())
        names.extend(re.findall(r"\*\*([^*]{2,60}?)\.?\*\*", line))
    return [norm(n) for n in names]


def norm(s):
    return re.sub(r"\s+", " ", re.sub(r"[^\w ]", " ", s.lower())).strip()


def cmd_pointers(root):
    files = tracked_files(root)
    basenames = {os.path.basename(p) for p in files}
    headings = claude_headings(root)
    findings = []
    for rel in files:
        if os.path.splitext(rel)[1] not in TEXT_EXT and os.path.basename(rel) != "llms.txt":
            continue
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            continue
        text = read(path)
        if os.path.basename(rel) == "CHANGELOG.md":
            text = unreleased_only(text)
        for no, line in enumerate(text.splitlines(), 1):
            for m in RETIRED_RE.finditer(line):
                findings.append("%s:%d: path into the retired docs folder: %s" % (rel, no, m.group(0)))
            if rel.endswith(".md") or rel.endswith("llms.txt"):
                for ref in re.findall(r"`([A-Za-z0-9.][\w./-]*\.md)`", line):
                    if IGNORED_BACKTICK.match(os.path.basename(ref)) or ref.startswith(".handoffs/"):
                        continue
                    if RETIRED_RE.search(ref):
                        continue
                    if "/" in ref:
                        tail = re.sub(r"^(\./|\.\./)+", "", ref)
                        if not any(p == tail or p.endswith("/" + tail) for p in files):
                            findings.append("%s:%d: names a file git does not track: %s" % (rel, no, ref))
                    elif ref not in basenames:
                        findings.append("%s:%d: names a file git does not track: %s" % (rel, no, ref))
            if rel == "CLAUDE.md":
                continue
            for pattern in SECTION_REFS:
                for m in pattern.finditer(line):
                    name = norm(m.group(1))
                    if name and not any(name in h for h in headings):
                        findings.append("%s:%d: CLAUDE.md has no section '%s'" % (rel, no, m.group(1).strip()))
    return report(findings, "pointers")


# ------------------------------------------------------------------ style

def default_style_files(root):
    picked = []
    for rel in tracked_files(root):
        top = rel.split("/", 1)[0]
        in_module = os.path.isdir(os.path.join(root, top, "src"))
        if in_module:
            continue
        if rel.endswith(DEFAULT_STYLE_GLOBS) or rel.startswith(".github/ISSUE_TEMPLATE/"):
            picked.append(rel)
    return picked


def cmd_style(root, files, kdoc, swift):
    findings = []
    if kdoc:
        for label, start_line, body in doc_comments(root, swift):
            findings.extend(style_findings(label, body, start_line))
        return report(findings, "doc-comment style")
    for rel in files or default_style_files(root):
        path = rel if os.path.isabs(rel) else os.path.join(root, rel)
        if not os.path.isfile(path):
            findings.append("%s: no such file" % rel)
            continue
        text = read(path)
        if os.path.basename(path) == "CHANGELOG.md":
            text = unreleased_only(text)
        findings.extend(style_findings(rel, text))
    return report(findings, "style")


# ------------------------------------------------------------------ source scanning

def blank(chars, i, j):
    for k in range(i, min(j, len(chars))):
        if chars[k] != "\n":
            chars[k] = " "


def mask_source(text, swift=False):
    """Blank comments and string literals (newlines kept). Returns (masked, doc_spans)."""
    chars, docs, i, n = list(text), [], 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            if swift and text.startswith("///", i) and not text.startswith("////", i):
                if docs and docs[-1][2] == "///" and text[docs[-1][1]:i].strip() == "":
                    docs[-1] = (docs[-1][0], j, "///")
                else:
                    docs.append((i, j, "///"))
            blank(chars, i, j)
            i = j
            continue
        if text.startswith("/*", i):
            depth, j = 0, i
            while j < n:
                if text.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif text.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                    if depth == 0:
                        break
                else:
                    j += 1
            if text.startswith("/**", i) and not text.startswith("/**/", i):
                docs.append((i, j, "/**"))
            blank(chars, i, j)
            i = j
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            while j < n and text[j] == '"':
                j += 1
            blank(chars, i, j)
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            while j < n and text[j] not in '"\n':
                if text[j] == "\\":
                    j += 2
                    continue
                if text.startswith("${", j) or (swift and text.startswith("\\(", j)):
                    depth = 0
                    while j < n:
                        if text[j] in "{(":
                            depth += 1
                        elif text[j] in "})":
                            depth -= 1
                            if depth == 0:
                                break
                        j += 1
                j += 1
            blank(chars, i, j + 1)
            i = j + 1
            continue
        if text[i] == "'" and not swift:
            m = re.match(r"'(?:\\u[0-9a-fA-F]{4}|\\.|[^'\\\n])'", text[i:i + 8])
            if m:
                blank(chars, i, i + m.end())
                i += m.end()
                continue
        i += 1
    return "".join(chars), docs


def bracket_pairs(masked):
    pairs, stack = {}, []
    for i, c in enumerate(masked):
        if c in "({[":
            stack.append(i)
        elif c in ")}]" and stack:
            pairs[stack.pop()] = i
    return pairs


def innermost(pairs, pos):
    best = None
    for o, c in pairs.items():
        if o < pos < c and (best is None or o > best):
            best = o
    return best


def line_start(text, pos):
    return text.rfind("\n", 0, pos) + 1


def header_before(masked, pos):
    """Text of the declaration that owns the bracket at pos: back to the previous ; { or }."""
    k = max(masked.rfind(ch, 0, pos) for ch in ";{}")
    return masked[k + 1:pos]


def decl_start(masked, pairs, pos):
    """Start of the declaration at pos, including annotation or attribute lines above it."""
    closers = {c: o for o, c in pairs.items()}
    start = line_start(masked, pos)
    while start > 0:
        prev = line_start(masked, start - 1)
        line = masked[prev:start - 1].strip()
        if line.startswith("@"):
            start = prev
            continue
        if line.endswith(")"):
            close = masked.rfind(")", prev, start)
            opener = closers.get(close)
            if opener is not None and masked[line_start(masked, opener):opener].strip().startswith("@"):
                start = line_start(masked, opener)
                continue
        break
    return start


def has_doc(text, masked, docs, pairs, pos):
    start = decl_start(masked, pairs, pos)
    for d0, d1, _ in docs:
        if d1 <= start and text[d1:start].strip() == "":
            return True
        if start <= d1 <= pos and re.match(r"^(\s|@\w+(\([^)]*\))?)*$", masked[d1:pos]):
            return True
    return False


def doc_text_before(text, masked, docs, pairs, pos):
    start = decl_start(masked, pairs, pos)
    for d0, d1, _ in docs:
        if d1 <= start and text[d1:start].strip() == "":
            return text[d0:d1]
    return ""


KOTLIN_MODS = r"(?:(?:public|override|open|abstract|final|sealed|data|value|enum|inner|inline|suspend|operator|infix|const|lateinit|actual|expect|external|tailrec|annotation|companion|fun)\s+)*"


def split_top(masked, start, end):
    """Segments (s, e) of masked[start:end] split at commas outside brackets."""
    segs, depth, k = [], 0, start
    for i in range(start, end):
        ch = masked[i]
        if ch in "({[":
            depth += 1
        elif ch in ")}]":
            depth -= 1
        elif ch == "," and depth == 0:
            segs.append((k, i))
            k = i + 1
    segs.append((k, end))
    return segs


ANNOTATION = re.compile(r"@[\w.]+(\([^)]*\))?")


def kotlin_findings(rel, text):
    masked, docs = mask_source(text)
    pairs = bracket_pairs(masked)
    out = []

    def where(pos):
        return "%s:%d" % (rel, masked.count("\n", 0, pos) + 1)

    def snippet(pos):
        end = masked.find("\n", pos)
        return re.sub(r"\s+", " ", masked[pos:end if end > 0 else None]).strip()[:80]

    # Declarations that carry the `public` keyword (explicit API mode makes every public
    # declaration say it, except overrides, enum entries and data-class properties).
    for m in re.finditer(r"\bpublic\b", masked):
        pos = m.start()
        mods = re.match(r"public\s+" + KOTLIN_MODS, masked[pos:pos + 200])
        if mods and re.search(r"\b(override|actual)\b", mods.group(0)):
            continue
        box = innermost(pairs, pos)
        if box is not None and masked[box] == "(":
            continue  # a constructor property: checked below
        if enclosed_by_hidden(masked, pairs, pos):
            continue
        if not has_doc(text, masked, docs, pairs, pos):
            out.append("%s: no doc comment: %s" % (where(pos), snippet(pos)))

    # Constructor properties: val/var directly inside a class header's parentheses.
    for o, c in pairs.items():
        if masked[o] != "(":
            continue
        hdr = header_before(masked, o)
        cls = list(re.finditer(r"\bclass\b", hdr))
        if not cls or re.search(r"\b(private|internal)\b", hdr) or enclosed_by_hidden(masked, pairs, o):
            continue
        cls_pos = o - len(hdr) + cls[-1].start()
        type_doc = doc_text_before(text, masked, docs, pairs, cls_pos)
        for s0, s1 in split_top(masked, o + 1, c):
            seg = ANNOTATION.sub(lambda x: " " * len(x.group(0)), masked[s0:s1])
            pm = re.match(r"\s*((?:\w+\s+)*?)(val|var)\s+(\w+)", seg)
            if not pm or re.search(r"\b(private|internal|protected|override)\b", pm.group(1)):
                continue
            p = s0 + pm.start(2)
            name = pm.group(3)
            if has_doc(text, masked, docs, pairs, p) or doc_ends_right_before(text, docs, s0, p):
                continue
            if re.search(r"@(property|param)\s+" + re.escape(name) + r"\b", type_doc):
                continue
            out.append("%s: no doc comment and no @property tag: constructor property %s" % (where(p), name))

    # Enum entries of public enum classes: the body up to the first top-level semicolon.
    for o, c in pairs.items():
        if masked[o] != "{":
            continue
        hdr = header_before(masked, o)
        if not re.search(r"\benum\s+class\b", hdr) or not re.search(r"\bpublic\b", hdr):
            continue
        if enclosed_by_hidden(masked, pairs, o):
            continue
        body_end, depth = c, 0
        for k in range(o + 1, c):
            ch = masked[k]
            if ch in "({[":
                depth += 1
            elif ch in ")}]":
                depth -= 1
            elif ch == ";" and depth == 0:
                body_end = k
                break
        for s0, s1 in split_top(masked, o + 1, body_end):
            seg = ANNOTATION.sub(lambda x: " " * len(x.group(0)), masked[s0:s1])
            em = re.search(r"[A-Za-z_]\w*", seg)
            if not em:
                continue
            p = s0 + em.start()
            if not has_doc(text, masked, docs, pairs, p) and not doc_ends_right_before(text, docs, s0, p):
                out.append("%s: no doc comment: enum entry %s" % (where(p), em.group(0)))
    return out


def doc_ends_right_before(text, docs, seg_start, pos):
    for d0, d1, _ in docs:
        if seg_start <= d1 <= pos and text[d1:pos].strip() == "":
            return True
    return False


def enclosed_by_hidden(masked, pairs, pos):
    """True when a private or internal class or object body, or a function body, encloses pos."""
    for o, c in pairs.items():
        if masked[o] == "{" and o < pos < c:
            hdr = header_before(masked, o)
            if re.search(r"\b(private|internal)\b", hdr) and re.search(r"\b(class|object|interface)\b", hdr):
                return True
            if re.search(r"\bfun\b", hdr) and not re.search(r"\b(class|object|interface)\b", hdr):
                return True
    return False


def swift_findings(rel, text):
    masked, docs = mask_source(text, swift=True)
    pairs = bracket_pairs(masked)
    out = []
    pattern = r"\b(public|open)\s+((?:(?:static|class|final|override|private\(set\)|internal\(set\)|mutating|nonisolated|convenience|required|indirect|lazy)\s+)*)(func|var|let|class|struct|enum|protocol|init|subscript|typealias|actor|case)\b"
    for m in re.finditer(pattern, masked):
        if "override" in m.group(2):
            continue
        pos = m.start()
        if not has_doc(text, masked, docs, pairs, pos):
            end = masked.find("\n", pos)
            out.append("%s:%d: no doc comment: %s" % (rel, masked.count("\n", 0, pos) + 1,
                                                       re.sub(r"\s+", " ", masked[pos:end]).strip()[:80]))
    return out


def main_sources(root):
    for rel in tracked_files(root):
        if rel.endswith(".kt") and re.search(r"(^|/)src/[A-Za-z]*Main/", rel):
            yield rel


def swift_sources(swift_root):
    for rel in tracked_files(swift_root):
        if rel.endswith(".swift") and rel.startswith("Sources/"):
            yield rel


def cmd_code_docs(root, swift_root):
    findings = []
    for rel in main_sources(root):
        findings.extend(kotlin_findings(rel, read(os.path.join(root, rel))))
    if swift_root:
        for rel in swift_sources(swift_root):
            findings.extend(swift_findings(rel, read(os.path.join(swift_root, rel))))
    return report(findings, "code-docs")


def doc_comments(root, swift_root):
    sets = [(root, list(main_sources(root)), False)]
    if swift_root:
        sets.append((swift_root, list(swift_sources(swift_root)), True))
    for base, rels, swift in sets:
        for rel in rels:
            text = read(os.path.join(base, rel))
            _, docs = mask_source(text, swift)
            for d0, d1, _ in docs:
                yield rel, text.count("\n", 0, d0) + 1, text[d0:d1]


# ------------------------------------------------------------------ shelf

PLATFORM_DIRS = {"jvm": ("how-to/kotlin/", "how-to/jvm/"),
                 "android": ("how-to/android/", "how-to/kotlin/"),
                 "ios": ("how-to/ios/",)}


def frontmatter(text):
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    meta = {}
    for line in text[3:end if end > 0 else 0].splitlines():
        m = re.match(r"^(\w[\w-]*):\s*(.*)$", line)
        if m:
            meta[m.group(1)] = m.group(2).strip()
    return meta


def link_targets(cell, base_dir):
    out = []
    for t in re.findall(r"\[\[([^\]|#]+)", cell):
        out.append(t.strip() if t.strip().endswith(".md") else t.strip() + ".md")
    for t in re.findall(r"\]\(([^)#\s]+)", cell):
        if re.match(r"^[a-z]+:", t):
            continue
        out.append(os.path.normpath(os.path.join(base_dir, t)))
    return [p.replace(os.sep, "/") for p in out]


def page_title(text):
    """The page title: the first top-level heading after the page header, else a title: header."""
    body = text
    if text.startswith("---"):
        end = text.find("\n---", 3)
        if end > 0:
            body = text[end + 4:]
    for line in body.splitlines():
        m = re.match(r"^#\s+(.+?)\s*#*\s*$", line)
        if m:
            return m.group(1).strip()
    return frontmatter(text).get("title", "")


def title_key(s):
    return re.sub(r"\s+", " ", s.strip()).casefold()


def cmd_shelf(root, shelf, matrix_rel, llms_rel):
    if not os.path.isdir(shelf):
        fail("no shelf folder at %s" % shelf)
    pages, titles = {}, {}
    for dirpath, _, names in os.walk(shelf):
        for name in names:
            if name.endswith(".md"):
                rel = os.path.relpath(os.path.join(dirpath, name), shelf).replace(os.sep, "/")
                text = read(os.path.join(dirpath, name))
                pages[rel] = frontmatter(text)
                titles[rel] = page_title(text)
    findings = []
    for rel, meta in sorted(pages.items()):
        if not meta.get("kind"):
            findings.append("%s: no kind in the page header" % rel)

    matrix_path = os.path.join(shelf, matrix_rel)
    if not os.path.isfile(matrix_path):
        fail("no capability matrix at %s" % matrix_path)
    cols = None
    for no, line in enumerate(read(matrix_path).splitlines(), 1):
        if not line.startswith("|"):
            cols = None
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        lowered = [c.lower() for c in cells]
        if "jvm" in lowered and "android" in lowered and "ios" in lowered:
            cols = {k: lowered.index(k) for k in ("jvm", "android", "ios")}
            cols["howto"] = next((i for i, c in enumerate(lowered) if c.startswith("how")), None)
            continue
        if cols is None or cols["howto"] is None or set(line.replace("|", "").strip()) <= set("-: "):
            continue
        links = link_targets(cells[cols["howto"]], os.path.dirname(matrix_rel))
        for link in links:
            if link not in pages:
                findings.append("%s:%d: links a page that does not exist: %s" % (matrix_rel, no, link))
        for plat in ("jvm", "android", "ios"):
            if cells[cols[plat]] != "\u2713":
                continue
            if not any(link.startswith(PLATFORM_DIRS[plat]) and link in pages for link in links):
                findings.append("%s:%d: '%s' is \u2713 on %s but links no %s how-to page"
                                % (matrix_rel, no, cells[0], plat, plat))

    # llms.txt lives in the repo root. Its links point at KB article URLs, so a page is
    # matched by its title: the link text must be the page title.
    consumer = {rel: meta for rel, meta in pages.items()
                if meta.get("audience", "consumer") != "contributor"
                and not rel.startswith("contribute/")
                and meta.get("kind") in ("tutorial", "how-to")}
    seen = {}
    for rel in sorted(consumer):
        key = title_key(titles[rel])
        if not key:
            findings.append("%s: no title (a top-level heading) to match in %s" % (rel, llms_rel))
        elif key in seen:
            findings.append("%s: same title as %s; a title must name one page" % (rel, seen[key]))
        else:
            seen[key] = rel
    llms_path = os.path.join(root, llms_rel)
    if not os.path.isfile(llms_path):
        findings.append("%s: missing (it lives in the repo root)" % llms_rel)
    else:
        listed = set()
        for no, line in enumerate(read(llms_path).splitlines(), 1):
            for text, url in re.findall(r"\[([^\]]+)\]\(([^)\s]+)\)", line):
                if not re.match(r"^https?://", url):
                    findings.append("%s:%d: link is not an absolute URL: %s" % (llms_rel, no, url))
                    continue
                listed.add(title_key(text))
        for rel in sorted(consumer):
            if title_key(titles[rel]) and title_key(titles[rel]) not in listed:
                findings.append("%s: %s page '%s' not listed in %s"
                                % (rel, consumer[rel].get("kind"), titles[rel], llms_rel))
    return report(findings, "shelf")


# ------------------------------------------------------------------ entry point

def main(argv=None):
    ap = argparse.ArgumentParser(description="Tessera documentation checks (see module docstring).")
    ap.add_argument("--root", default=".", help="repo root (default: current directory)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("pointers")
    st = sub.add_parser("style")
    st.add_argument("files", nargs="*")
    st.add_argument("--kdoc", action="store_true", help="check doc comments in main sources instead")
    st.add_argument("--swift", help="also a Swift package root (with --kdoc)")
    cd = sub.add_parser("code-docs")
    cd.add_argument("--swift", help="also check a Swift package root (its Sources/)")
    sh = sub.add_parser("shelf")
    sh.add_argument("shelf", help="local folder holding the public pages (a vault folder or a KB export)")
    sh.add_argument("--matrix", default="start/capability-matrix.md", help="matrix page, relative to the folder")
    sh.add_argument("--llms", default="llms.txt", help="llms.txt, relative to the repo root")
    args = ap.parse_args(argv)
    root = os.path.abspath(args.root)
    if args.cmd == "pointers":
        return cmd_pointers(root)
    if args.cmd == "style":
        return cmd_style(root, args.files, args.kdoc, args.swift)
    if args.cmd == "code-docs":
        return cmd_code_docs(root, args.swift)
    return cmd_shelf(root, os.path.abspath(args.shelf), args.matrix, args.llms)


if __name__ == "__main__":
    sys.exit(main())
