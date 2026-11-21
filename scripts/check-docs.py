#!/usr/bin/env python3
"""Documentation checks for Tessera. Python 3 stdlib only.

Reads files, writes nothing, needs no secret, and uses the network only in `shelf --live` and
`samples --live`. Run from the repo root
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
  shelf --live     Release gate. Reads the live public KB anonymously over HTTPS (the one
                   place in this repo's tooling that uses the network; CI never runs it).
                   Every check mark in the capability matrix links a how-to page for that
                   platform. The repo-root llms.txt lists every consumer tutorial and
                   how-to by its exact title, with the page's KB article URL.
  shelf DIR        The same rules over a local folder of exported pages. Offline: this is
                   how the rules are tested.
  samples --live   Release gate. Reads every public tutorial and how-to from the live KB
                   (anonymous HTTPS, like shelf --live). Each Kotlin code block of a page
                   matches, whitespace aside, its region `// region TES-A-<n> block <k>` in
                   the :samples module (samples/kotlin), which CI compiles. A block with no
                   region, or a region with no block, is a finding too.

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
        # `::class` (as in `@Throws(X::class)`) is a class reference, not the class keyword.
        cls = list(re.finditer(r"(?<!::)\bclass\b", hdr))
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
        # samples/ holds the documentation examples (the `samples` command checks them), not SDK API.
        if rel.endswith(".kt") and re.search(r"(^|/)src/[A-Za-z]*Main/", rel) and not rel.startswith("samples/"):
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


def parse_matrix(text):
    """Matrix rows: (line, capability, {platform: mark}, how-to cell). Same table rules for both modes."""
    rows, cols = [], None
    for no, line in enumerate(text.splitlines(), 1):
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
        marks = {p: cells[cols[p]] for p in ("jvm", "android", "ios")}
        rows.append((no, cells[0], marks, cells[cols["howto"]]))
    return rows


def shelf_findings(pages, matrix_loc, matrix_rows, llms_text, llms_rel):
    """The shared shelf rules. Both modes build the same inputs; nothing here reads a file or the network.

    pages: {key: {"title", "kind" ('tutorial'|'how-to'|''), "contributor" (bool),
                  "platforms" (set of jvm/android/ios this how-to serves), "id" (optional article id)}}
    matrix_rows: [(line, capability, {platform: mark}, [page keys linked in the how-to cell])]
    llms_text: the text of the repo-root llms.txt, or None when the file is missing.
    """
    findings = []
    for no, name, marks, links in matrix_rows:
        for link in links:
            if link not in pages:
                findings.append("%s:%d: links a page that does not exist: %s" % (matrix_loc, no, link))
        for plat in ("jvm", "android", "ios"):
            if marks[plat] != "✓":
                continue
            if not any(link in pages and plat in pages[link]["platforms"] for link in links):
                findings.append("%s:%d: '%s' is ✓ on %s but links no %s how-to page"
                                % (matrix_loc, no, name, plat, plat))

    # llms.txt lives in the repo root. Its links point at KB article URLs, so a page is
    # matched by its title: the link text must be the page title.
    consumer = {k: p for k, p in pages.items()
                if not p["contributor"] and p["kind"] in ("tutorial", "how-to")}
    seen = {}
    for key in sorted(consumer):
        tk = title_key(consumer[key]["title"])
        if not tk:
            findings.append("%s: no title (a top-level heading) to match in %s" % (key, llms_rel))
        elif tk in seen:
            findings.append("%s: same title as %s; a title must name one page" % (key, seen[tk]))
        else:
            seen[tk] = key
    if llms_text is None:
        findings.append("%s: missing (it lives in the repo root)" % llms_rel)
        return findings
    listed = {}
    for no, line in enumerate(llms_text.splitlines(), 1):
        for text, url in re.findall(r"\[([^\]]+)\]\(([^)\s]+)\)", line):
            if not re.match(r"^https?://", url):
                findings.append("%s:%d: link is not an absolute URL: %s" % (llms_rel, no, url))
                continue
            listed.setdefault(title_key(text), []).append(url)
    for key in sorted(consumer):
        p, tk = consumer[key], title_key(consumer[key]["title"])
        if not tk:
            continue
        if tk not in listed:
            findings.append("%s: %s page '%s' not listed in %s" % (key, p["kind"], p["title"], llms_rel))
        elif p.get("id") and not any(re.search(r"/articles/%s(?![\w-])" % re.escape(p["id"]), u)
                                     for u in listed[tk]):
            findings.append("%s: '%s' is listed in %s but not with its article URL (.../articles/%s)"
                            % (key, p["title"], llms_rel, p["id"]))
    return findings


def folder_pages(shelf):
    """Folder mode: the exported pages, with kind, audience and platform read from the folder."""
    pages, texts, findings = {}, {}, []
    for dirpath, _, names in os.walk(shelf):
        for name in names:
            if name.endswith(".md"):
                rel = os.path.relpath(os.path.join(dirpath, name), shelf).replace(os.sep, "/")
                text = read(os.path.join(dirpath, name))
                meta = frontmatter(text)
                texts[rel] = text
                pages[rel] = {"title": page_title(text), "kind": meta.get("kind", ""),
                              "contributor": meta.get("audience", "consumer") == "contributor"
                              or rel.startswith("contribute/"),
                              "platforms": {p for p, dirs in PLATFORM_DIRS.items()
                                            if rel.startswith(dirs)}}
                if not meta.get("kind"):
                    findings.append("%s: no kind in the page header" % rel)
    return pages, texts, findings


def folder_matrix_rows(texts, matrix_rel):
    rows = []
    for no, name, marks, cell in parse_matrix(texts[matrix_rel]):
        rows.append((no, name, marks, link_targets(cell, os.path.dirname(matrix_rel))))
    return rows


# Live mode: the public tree of the KB, read anonymously over HTTPS.
KB_BASE = "https://lightine.youtrack.cloud"
KB_USER_AGENT = "tessera-docs-check"
KB_MATRIX_ID = "TES-A-139"
KB_TUTORIALS_ID = "TES-A-141"       # tutorials tree
KB_HOWTO_ID = "TES-A-88"            # how-to tree
KB_PLATFORM_TREES = {"TES-A-127": {"jvm", "android"},  # Kotlin how-tos serve JVM and Android
                     "TES-A-89": {"android"},
                     "TES-A-103": {"ios"}}
KB_CONTRIBUTE_ID = "TES-A-78"


def http_get_json(url):
    """The one network call in this repo's tooling: an anonymous GET. Raises OSError or ValueError."""
    import json
    import urllib.request
    req = urllib.request.Request(url, headers={"User-Agent": KB_USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def fetch_live(fetch):
    """Read the public KB with `fetch(url) -> parsed JSON`. Returns (pages, matrix_text).
    Any failure raises; the caller turns it into 'cannot run'."""
    from urllib.parse import quote
    fields = quote("idReadable,summary,parentArticle(idReadable)", safe="(),")
    articles, skip = [], 0
    while True:
        batch = fetch("%s/api/articles?fields=%s&$top=100&$skip=%d" % (KB_BASE, fields, skip))
        if not isinstance(batch, list):
            raise ValueError("unexpected answer from the article list")
        articles.extend(batch)
        if len(batch) < 100:
            break
        skip += 100
    parent = {a["idReadable"]: (a.get("parentArticle") or {}).get("idReadable") for a in articles}
    title = {a["idReadable"]: a.get("summary", "") for a in articles}
    has_children = {p for p in parent.values() if p}
    if KB_MATRIX_ID not in parent:
        raise ValueError("%s (capability matrix) is not in the public tree" % KB_MATRIX_ID)

    def ancestors(aid):
        out, seen = [], set()
        while parent.get(aid) and aid not in seen:
            seen.add(aid)
            aid = parent[aid]
            out.append(aid)
        return out

    pages = {}
    for aid in parent:
        up = ancestors(aid)
        kind = ""
        if aid not in has_children:  # index pages (sections) are not tutorials or how-tos
            if KB_TUTORIALS_ID in up:
                kind = "tutorial"
            elif KB_HOWTO_ID in up:
                kind = "how-to"
        pages[aid] = {"id": aid, "title": title[aid], "kind": kind,
                      "contributor": KB_CONTRIBUTE_ID in up or aid == KB_CONTRIBUTE_ID,
                      "platforms": set().union(*[KB_PLATFORM_TREES.get(a, set()) for a in up])
                      if kind == "how-to" else set()}
    art = fetch("%s/api/articles/%s?fields=content" % (KB_BASE, KB_MATRIX_ID))
    content = art.get("content") if isinstance(art, dict) else None
    if not isinstance(content, str):
        raise ValueError("%s came back without content" % KB_MATRIX_ID)
    return pages, content


def live_matrix_rows(matrix_text):
    return [(no, name, marks, re.findall(r"/articles/(TES-A-\d+)", cell))
            for no, name, marks, cell in parse_matrix(matrix_text)]


def cmd_shelf(root, shelf, matrix_rel, llms_rel, live=False, fetch=http_get_json):
    llms_path = os.path.join(root, llms_rel)
    llms_text = read(llms_path) if os.path.isfile(llms_path) else None
    if live:
        try:
            pages, matrix_text = fetch_live(fetch)
        except Exception as exc:  # network, HTTP, JSON, or shape: the check cannot run
            fail("cannot read the live KB at %s: %s: %s" % (KB_BASE, type(exc).__name__, exc))
        findings = shelf_findings(pages, "KB %s" % KB_MATRIX_ID, live_matrix_rows(matrix_text),
                                  llms_text, llms_rel)
        return report(findings, "shelf")
    if not os.path.isdir(shelf):
        fail("no shelf folder at %s" % shelf)
    pages, texts, findings = folder_pages(shelf)
    if matrix_rel not in texts:
        fail("no capability matrix at %s" % os.path.join(shelf, matrix_rel))
    findings += shelf_findings(pages, matrix_rel, folder_matrix_rows(texts, matrix_rel), llms_text, llms_rel)
    return report(findings, "shelf")


# ------------------------------------------------------------------ samples

SAMPLES_DIR = "samples/kotlin"   # the :samples module: compiled sources plus build-scripts/
SAMPLE_LANGS = {"kotlin", "kt", "kts"}
FENCE_RE = re.compile(r"^(\s*)(`{3,}|~{3,})\s*([\w+-]*)")
LABEL_RE = re.compile(r"^\s*`([^`\s]+)`\s*$")
REGION_RE = re.compile(r"^\s*//\s*region\s+(TES-A-\d+)\s+block\s+(\d+)(?:\s+part\s+(\d+))?\s*$")
ENDREGION_RE = re.compile(r"^\s*//\s*endregion\b")


def kotlin_blocks(text):
    """The Kotlin code blocks of a page, in order: [(number, label, code)]. The label is the
    backticked file path on the line above the block (the page's own label), or ''."""
    blocks, lines, i = [], text.splitlines(), 0
    while i < len(lines):
        m = FENCE_RE.match(lines[i])
        if not m:
            i += 1
            continue
        indent, fence, lang = len(m.group(1)), m.group(2), m.group(3).lower()
        j, body = i + 1, []
        while j < len(lines) and not re.match(r"^\s*" + re.escape(fence[0]) + "{%d,}\\s*$" % len(fence), lines[j]):
            body.append(lines[j][indent:] if lines[j][:indent].strip() == "" else lines[j].lstrip())
            j += 1
        if lang in SAMPLE_LANGS:
            k = i - 1
            while k >= 0 and not lines[k].strip():
                k -= 1
            label = LABEL_RE.match(lines[k]) if k >= 0 else None
            blocks.append((len(blocks) + 1, label.group(1) if label else "", "\n".join(body)))
        i = j + 1
    return blocks


def sample_regions(files):
    """files: {rel path: text}. Returns ({(page id, block no): (rel, line, code)}, findings).
    A block whose imports must sit at the top of the file while its code sits in a function is
    split into parts (`// region TES-A-<n> block <k> part <p>`); the parts join in part order."""
    parts, findings = {}, []
    for rel in sorted(files):
        open_at = None
        for no, line in enumerate(files[rel].splitlines(), 1):
            m = REGION_RE.match(line)
            if m:
                if open_at:
                    findings.append("%s:%d: region inside the region opened on line %d" % (rel, no, open_at[2]))
                open_at = ((m.group(1), int(m.group(2)), int(m.group(3) or 0)), [], no)
            elif ENDREGION_RE.match(line) and open_at:
                (aid, block, part), body, start = open_at
                got = parts.setdefault((aid, block), {})
                name = "%s block %d%s" % (aid, block, " part %d" % part if part else "")
                if part in got:
                    findings.append("%s:%d: second region for %s (first in %s:%d)"
                                    % (rel, start, name, got[part][0], got[part][1]))
                elif got and (0 in got or part == 0):
                    findings.append("%s:%d: %s mixes a whole region with parts" % (rel, start, name))
                else:
                    got[part] = (rel, start, "\n".join(body))
                open_at = None
            elif open_at:
                open_at[1].append(line)
        if open_at:
            findings.append("%s:%d: region has no endregion" % (rel, open_at[2]))
    regions = {}
    for key, got in parts.items():
        first = got[min(got)]
        regions[key] = (first[0], first[1], "\n".join(got[p][2] for p in sorted(got)))
    return regions, findings


def code_key(code):
    """Whitespace-normalized code: each line trimmed, blank lines dropped."""
    return [ln.strip() for ln in code.splitlines() if ln.strip()]


def samples_findings(pages, regions):
    """pages: {page id: (title, page text)}. Compares every Kotlin block with its sample region."""
    findings, seen = [], set()
    for aid in sorted(pages, key=lambda a: int(a.rsplit("-", 1)[1])):
        title, text = pages[aid]
        for no, label, code in kotlin_blocks(text):
            seen.add((aid, no))
            where = "%s block %d%s" % (aid, no, " (%s)" % label if label else "")
            if (aid, no) not in regions:
                findings.append("%s: no sample region '// region %s block %d' (%s)" % (where, aid, no, title))
                continue
            rel, line, sample = regions[(aid, no)]
            page_lines, sample_lines = code_key(code), code_key(sample)
            if page_lines != sample_lines:
                k = next((i for i, (a, b) in enumerate(zip(page_lines, sample_lines)) if a != b),
                         min(len(page_lines), len(sample_lines)))
                findings.append("%s:%d: %s differs from the page at line %d of the block: page '%s', sample '%s'"
                                % (rel, line, where, k + 1,
                                   page_lines[k] if k < len(page_lines) else "(end)",
                                   sample_lines[k] if k < len(sample_lines) else "(end)"))
    for key in sorted(set(regions) - seen):
        rel, line, _ = regions[key]
        findings.append("%s:%d: region for %s block %d has no such block on a public page" % (rel, line, key[0], key[1]))
    return findings


def local_sample_files(root):
    base = os.path.join(root, SAMPLES_DIR)
    files = {}
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle")]
        for name in filenames:
            if name.endswith((".kt", ".kts")):
                path = os.path.join(dirpath, name)
                files[os.path.relpath(path, root)] = read(path)
    return files


def fetch_live_pages(fetch):
    """Every public tutorial and how-to: {page id: (title, page text)}. Any failure raises."""
    pages, _ = fetch_live(fetch)
    out = {}
    for aid, page in pages.items():
        if not page["kind"]:
            continue
        art = fetch("%s/api/articles/%s?fields=content" % (KB_BASE, aid))
        content = art.get("content") if isinstance(art, dict) else None
        if not isinstance(content, str):
            raise ValueError("%s came back without content" % aid)
        out[aid] = (page["title"], content)
    return out


def cmd_samples(root, fetch=http_get_json):
    if not os.path.isdir(os.path.join(root, SAMPLES_DIR)):
        fail("no samples module at %s" % os.path.join(root, SAMPLES_DIR))
    regions, findings = sample_regions(local_sample_files(root))
    try:
        pages = fetch_live_pages(fetch)
    except Exception as exc:  # network, HTTP, JSON, or shape: the check cannot run
        fail("cannot read the live KB at %s: %s: %s" % (KB_BASE, type(exc).__name__, exc))
    findings += samples_findings(pages, regions)
    return report(findings, "samples")


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
    sh.add_argument("shelf", nargs="?", help="local folder holding the public pages (offline mode)")
    sh.add_argument("--live", action="store_true",
                    help="read the live public KB anonymously over HTTPS (release gate only)")
    sh.add_argument("--matrix", default="start/capability-matrix.md", help="matrix page, relative to the folder")
    sh.add_argument("--llms", default="llms.txt", help="llms.txt, relative to the repo root")
    sa = sub.add_parser("samples")
    sa.add_argument("--live", action="store_true", required=True,
                    help="read the live public KB anonymously over HTTPS (release gate only)")
    args = ap.parse_args(argv)
    root = os.path.abspath(args.root)
    if args.cmd == "pointers":
        return cmd_pointers(root)
    if args.cmd == "style":
        return cmd_style(root, args.files, args.kdoc, args.swift)
    if args.cmd == "code-docs":
        return cmd_code_docs(root, args.swift)
    if args.cmd == "samples":
        return cmd_samples(root)
    if not args.live and not args.shelf:
        ap.error("shelf needs a folder, or --live")
    if args.live and args.shelf:
        ap.error("shelf takes a folder or --live, not both")
    return cmd_shelf(root, os.path.abspath(args.shelf) if args.shelf else None, args.matrix, args.llms, args.live)


if __name__ == "__main__":
    sys.exit(main())
