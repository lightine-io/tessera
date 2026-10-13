#!/usr/bin/env python3
"""Link check for the public YouTrack KB plus repo markdown (read-only, stdlib only, anonymous, no credentials).
Usage: python3 -I scripts/kb-linkcheck.py [--quiet] [--allow-internal] [FILE.md ...]  (default files: README, CONTRIBUTING, SECURITY, CHANGELOG).
It walks the public KB from TES-A-3 down childArticles; if a child list hits the API cap of 50, use --ids TES-A-3,TES-A-16 or --range 1-120 (they replace the walk).
Output is a Markdown table (source, link text, target, result) and a summary line; --quiet prints only failures and the summary.
Exit 1 on a dead or wrong-anchor link, and on a "not public" link unless --allow-internal; exit 2 if the check could not run.
"""
import argparse
import concurrent.futures as cf
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import json

KB_HOST = "lightine.youtrack.cloud"
KB_API = "https://%s/api/" % KB_HOST
START = "TES-A-3"
CHILD_CAP = 50  # the API returns at most this many childArticles per article
RETRIES = 3
TIMEOUT = 30
WORKERS = 8
UA = {"User-Agent": "tessera-kb-linkcheck/1.0 (+read-only)"}
DEFAULT_FILES = ["README.md", "CONTRIBUTING.md", "SECURITY.md", "CHANGELOG.md"]
REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLACEHOLDER_HOSTS = ("example.com", "example.org", "example.net", "localhost", "127.0.0.1", "0.0.0.0")

OK, DEAD, NOT_PUBLIC, REDIRECT, WRONG_ANCHOR, SKIPPED = (
    "ok", "dead", "not public", "redirected", "wrong anchor", "skipped")
ORDER = [OK, DEAD, NOT_PUBLIC, REDIRECT, WRONG_ANCHOR, SKIPPED]

MD_LINK = re.compile(r"(!?)\[((?:[^\[\]]|\[[^\]]*\])*)\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
BARE_URL = re.compile(r"https?://[^\s)<>\]\"'`]+")
FENCE = re.compile(r"^(```|~~~)")


# ---------------------------------------------------------------- HTTP

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


_opener = urllib.request.build_opener(NoRedirect)


def _once(url, method):
    """One request, no redirect following. Returns (status, location_or_None); raises on network errors."""
    req = urllib.request.Request(url, method=method, headers=UA)
    try:
        with _opener.open(req, timeout=TIMEOUT) as r:
            return r.status, None
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Location")


def request(url, method="HEAD"):
    """Follow up to 8 redirects, retrying each hop up to RETRIES times on network errors / 5xx / 429.

    Returns (status, final_url, first_redirect_target_or_None, error_text_or_None).
    """
    cur, first = url, None
    for _ in range(9):
        status, loc, err = None, None, None
        for attempt in range(RETRIES):
            try:
                status, loc = _once(cur, method)
                err = None
                if status in (429, 500, 502, 503, 504) and attempt < RETRIES - 1:
                    time.sleep(1 + attempt)
                    continue
                break
            except Exception as e:  # DNS, TLS, timeout, reset
                err = "%s: %s" % (type(e).__name__, e)
                if attempt < RETRIES - 1:
                    time.sleep(1 + attempt)
        if err:
            return None, cur, first, err
        if status in (301, 302, 303, 307, 308) and loc:
            cur = urllib.parse.urljoin(cur, loc)
            first = first or cur
            continue
        return status, cur, first, None
    return None, cur, first, "too many redirects"


def probe(url):
    """HEAD, falling back to GET when the server dislikes HEAD."""
    res = request(url, "HEAD")
    if res[0] is not None and ((400 <= res[0] < 500 and res[0] != 429) or res[0] == 501):
        res = request(url, "GET")
    return res


def get_text(url):
    last = None
    for attempt in range(RETRIES):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=TIMEOUT) as r:
                return r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:
                raise
            last = e
        except Exception as e:
            last = e
        time.sleep(1 + attempt)
    raise last


def api_get(path):
    """Anonymous GET of the KB REST API. Returns (status, parsed_json_or_None, error_or_None)."""
    url = KB_API + path
    last_err = None
    for attempt in range(RETRIES):
        try:
            req = urllib.request.Request(url, headers=dict(UA, Accept="application/json"))
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return r.status, json.loads(r.read().decode("utf-8")), None
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 404):
                return e.code, None, None
            last_err = "HTTP %d" % e.code
        except Exception as e:
            last_err = "%s: %s" % (type(e).__name__, e)
        if attempt < RETRIES - 1:
            time.sleep(1 + attempt)
    return None, None, last_err


# ---------------------------------------------------------------- extraction

def extract(text):
    """Return a de-duplicated list of (link_text, target) for every Markdown link and bare URL."""
    prose_lines, code_lines, in_fence = [], [], False
    for line in text.splitlines():
        if FENCE.match(line.strip()):
            in_fence = not in_fence
            continue
        (code_lines if in_fence else prose_lines).append(line)
    prose, code = "\n".join(prose_lines), "\n".join(code_lines)
    out, covered = [], []
    for m in MD_LINK.finditer(prose):
        txt = " ".join(m.group(2).split())
        out.append((("(image) " if m.group(1) else "") + txt, m.group(3)))
        covered.append(m.span())
    for m in BARE_URL.finditer(prose):
        if not any(a <= m.start() < b for a, b in covered):
            out.append(("(bare URL)", m.group(0).rstrip(".,;:!?*")))
    for m in BARE_URL.finditer(code):  # URLs in code samples count only when they point at this project
        url = m.group(0).rstrip(".,;:!?*")
        if "github.com/lightine-io" in url or urllib.parse.urlparse(url).netloc == KB_HOST:
            out.append(("(URL in code sample)", url))
    seen, uniq = set(), []
    for item in out:
        if item not in seen:
            seen.add(item)
            uniq.append(item)
    return uniq


def slugify(heading):
    h = re.sub(r"[`*_]", "", heading.strip().lower())
    return re.sub(r"[^\w\- ]", "", h, flags=re.UNICODE).replace(" ", "-")


_anchor_cache = {}


def anchors_of(path):
    """GitHub-style heading slugs of a markdown file (with -1, -2 suffixes for repeats)."""
    if path in _anchor_cache:
        return _anchor_cache[path]
    res, counts, in_fence = set(), {}, False
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
    except OSError:
        lines = []
    for line in lines:
        if FENCE.match(line.strip()):
            in_fence = not in_fence
        if in_fence:
            continue
        m = re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if m:
            slug = slugify(m.group(1))
            n = counts.get(slug, 0)
            counts[slug] = n + 1
            res.add(slug if n == 0 else "%s-%d" % (slug, n))
    _anchor_cache[path] = res
    return res


# ---------------------------------------------------------------- resolvers

class Context:
    def __init__(self, public_ids):
        self.public_ids = public_ids  # TES-A ids already fetched anonymously (known public)


def check_kb(ctx, kind, ident):
    """kind = 'articles' | 'issues'. Anonymous API read: 200 public, 401/403/404 not public."""
    if kind == "articles" and ident in ctx.public_ids:
        return OK, "public"
    status, _, err = api_get("%s/%s?fields=id" % (kind, urllib.parse.quote(ident)))
    if err:
        return DEAD, "KB API unreachable after %d tries (%s)" % (RETRIES, err)
    if status == 200:
        return OK, "public"
    if status in (401, 403, 404):
        return NOT_PUBLIC, "anonymous HTTP %d: internal or missing" % status
    return DEAD, "unexpected HTTP %s" % status


def check_github(target):
    p = urllib.parse.urlparse(target)
    parts = [x for x in p.path.split("/") if x]
    base = "https://github.com" + p.path
    status, final, first, err = probe(base)
    if err:
        return DEAD, err
    if status != 200:
        return DEAD, "HTTP %s" % status
    note = ""
    if first and first.rstrip("/") != base.rstrip("/"):
        note = "-> %s" % first
    m = re.match(r"^L(\d+)(?:-L(\d+))?$", p.fragment or "")
    if len(parts) >= 5 and parts[2] == "blob" and m:
        owner, repo, _, ref = parts[:4]
        raw = "https://raw.githubusercontent.com/%s/%s/%s/%s" % (
            owner, repo, urllib.parse.quote(ref), urllib.parse.quote("/".join(parts[4:])))
        try:
            n = len(get_text(raw).splitlines())
        except Exception as e:
            return SKIPPED, "raw fetch failed, line anchor not verified (%s)" % e
        last = int(m.group(2) or m.group(1))
        if last > n:
            return WRONG_ANCHOR, "#%s but the file has %d lines at %s" % (p.fragment, n, ref)
        if not note:
            return OK, "line anchor valid (%d lines)" % n
    return (REDIRECT, note) if note else (OK, "")


def check_external(target):
    status, final, first, err = probe(target)
    if err:
        return DEAD, err
    if status < 400:
        if first and urllib.parse.urldefrag(first)[0].rstrip("/") != urllib.parse.urldefrag(target)[0].rstrip("/"):
            return REDIRECT, "-> %s (HTTP %s)" % (final, status)
        return OK, ""
    if status in (401, 403, 429):
        return SKIPPED, "HTTP %s: site blocks automated checks" % status
    return DEAD, "HTTP %s" % status


def check_relative(src_kind, src_path, target):
    path_part, _, frag = target.partition("#")
    path_part = path_part.split("?")[0]
    if src_kind == "kb":
        # A KB page resolves relative links from the site root; only a bare article id can ever work.
        if re.fullmatch(r"TES-A-\d+", path_part):
            return DEAD, "bare article id; write the full %s URL" % ("https://%s/articles/%s" % (KB_HOST, path_part))
        return DEAD, "relative link in a KB page does not resolve to an article"
    if path_part.startswith("/"):
        full = os.path.normpath(os.path.join(REPO_ROOT, path_part.lstrip("/")))
    else:
        full = os.path.normpath(os.path.join(os.path.dirname(src_path), urllib.parse.unquote(path_part)))
    if not os.path.exists(full):
        return DEAD, "file not found: %s" % os.path.relpath(full, REPO_ROOT)
    if frag and full.endswith(".md") and slugify(urllib.parse.unquote(frag)) not in anchors_of(full):
        return WRONG_ANCHOR, "#%s is not a heading in %s" % (frag, os.path.basename(full))
    return OK, "file exists"


def classify(ctx, src_kind, src_path, target):
    """Return (category, detail) for one link target."""
    target = target.strip()
    if target.startswith("#"):
        return SKIPPED, "in-page anchor, not verified"
    if re.match(r"^(mailto|tel):", target):
        return SKIPPED, "mailto/tel"
    if re.match(r"^https?://", target):
        u = urllib.parse.urlparse(target)
        host = (u.hostname or "").lower()
        if host in PLACEHOLDER_HOSTS or "<" in target or "{" in target:
            return SKIPPED, "placeholder or local host"
        if host == KB_HOST:
            m = re.match(r"^/articles/(TES-A-\d+)", u.path)
            if m:
                return check_kb(ctx, "articles", m.group(1))
            m = re.match(r"^/issue/(TES-\d+)", u.path)
            if m:
                return check_kb(ctx, "issues", m.group(1))
            return check_external(target)
        if host in ("github.com", "www.github.com"):
            return check_github(target)
        return check_external(target)
    if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.I):
        return SKIPPED, "non-http scheme"
    return check_relative(src_kind, src_path, target)


# ---------------------------------------------------------------- KB walk

ARTICLE_FIELDS = "id,idReadable,summary,content,childArticles(idReadable)"


def fetch_article(ident):
    status, data, err = api_get("articles/%s?fields=%s" % (urllib.parse.quote(ident), ARTICLE_FIELDS))
    return ident, status, data, err


def walk(start, warnings):
    """Breadth-first walk of the public tree. Returns an ordered list of article dicts."""
    pages, seen, level = [], set(), [start]
    while level:
        level = [i for i in level if i not in seen]
        seen.update(level)
        nxt = []
        with cf.ThreadPoolExecutor(max_workers=WORKERS) as ex:
            for ident, status, data, err in ex.map(fetch_article, level):
                if err or status != 200:
                    if ident == start:
                        raise RuntimeError("cannot read %s anonymously (%s)" % (ident, err or "HTTP %s" % status))
                    warnings.append("child %s not readable anonymously (%s)" % (ident, err or "HTTP %s" % status))
                    continue
                pages.append(data)
                kids = [c["idReadable"] for c in data.get("childArticles", []) if "idReadable" in c]
                if len(kids) >= CHILD_CAP:
                    warnings.append("%s: child list hit the %d cap, children may be missing; "
                                    "re-run with --ids or --range" % (data["idReadable"], CHILD_CAP))
                nxt.extend(kids)
        level = nxt
    return pages


def fetch_list(idents, warnings):
    pages = []
    with cf.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        for ident, status, data, err in ex.map(fetch_article, idents):
            if err:
                raise RuntimeError("cannot read %s (%s)" % (ident, err))
            if status == 200:
                pages.append(data)
            else:
                warnings.append("%s not readable anonymously (HTTP %s), skipped" % (ident, status))
    return pages


# ---------------------------------------------------------------- output

def cell(s, n):
    s = s.replace("|", "\\|").replace("\n", " ")
    return s if len(s) <= n else s[: n - 3] + "..."


def parse_args(argv):
    ap = argparse.ArgumentParser(description="Link check for the public KB and repo markdown.")
    ap.add_argument("files", nargs="*", help="repo markdown files (default: %s)" % ", ".join(DEFAULT_FILES))
    ap.add_argument("--ids", help="comma-separated KB article ids to check instead of walking, e.g. TES-A-3,TES-A-16")
    ap.add_argument("--range", dest="rng", help="KB article number range to check instead of walking, e.g. 1-120")
    ap.add_argument("--quiet", action="store_true", help="print only failures and the summary")
    ap.add_argument("--allow-internal", action="store_true", help="do not count 'not public' links as failures")
    return ap.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    warnings = []
    if args.files:
        files = args.files
        missing = [f for f in files if not os.path.isfile(f)]
        if missing:
            print("error: file not found: %s" % ", ".join(missing), file=sys.stderr)
            return 2
    else:
        files = [os.path.join(REPO_ROOT, f) for f in DEFAULT_FILES]
        for f in [f for f in files if not os.path.isfile(f)]:
            warnings.append("default file missing, skipped: %s" % os.path.relpath(f, REPO_ROOT))
        files = [f for f in files if os.path.isfile(f)]

    try:
        if args.ids or args.rng:
            idents = []
            if args.ids:
                idents += [i.strip() for i in args.ids.split(",") if i.strip()]
            if args.rng:
                m = re.fullmatch(r"(\d+)-(\d+)", args.rng.strip())
                if not m or int(m.group(1)) > int(m.group(2)):
                    print("error: --range must look like 1-120", file=sys.stderr)
                    return 2
                idents += ["TES-A-%d" % n for n in range(int(m.group(1)), int(m.group(2)) + 1)]
            pages = fetch_list(list(dict.fromkeys(idents)), warnings)
        else:
            pages = walk(START, warnings)
    except RuntimeError as e:
        print("error: %s" % e, file=sys.stderr)
        return 2

    ctx = Context({p["idReadable"] for p in pages})
    items = []  # (label, kind, path, text, target)
    for p in pages:
        label = "KB %s %s" % (p["idReadable"], p.get("summary", ""))
        for txt, tgt in extract(p.get("content") or ""):
            items.append((label, "kb", "", txt, tgt))
    for f in files:
        with open(f, encoding="utf-8") as fh:
            content = fh.read()
        label = os.path.relpath(os.path.abspath(f), REPO_ROOT)
        for txt, tgt in extract(content):
            items.append((label, "repo", os.path.abspath(f), txt, tgt))

    keys = sorted({(k, pth, t) for _, k, pth, _, t in items})
    results = {}
    with cf.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        futs = {ex.submit(classify, ctx, k, pth, t): (k, pth, t) for k, pth, t in keys}
        for fut in cf.as_completed(futs):
            try:
                results[futs[fut]] = fut.result()
            except Exception as e:  # a checker bug must not look like a pass
                results[futs[fut]] = (DEAD, "checker error: %s: %s" % (type(e).__name__, e))

    counts = dict.fromkeys(ORDER, 0)
    rows = []
    for label, kind, pth, txt, tgt in items:
        cat, detail = results[(kind, pth, tgt)]
        counts[cat] += 1
        failing = cat in (DEAD, WRONG_ANCHOR) or (cat == NOT_PUBLIC and not args.allow_internal)
        rows.append((failing, label, txt, tgt, "%s%s" % (cat, (": " + detail) if detail else "")))

    shown = [r for r in rows if r[0]] if args.quiet else rows
    if shown:
        print("| Source | Link text | Target | Result |")
        print("|---|---|---|---|")
        for _, label, txt, tgt, res in shown:
            print("| %s | %s | %s | %s |" % (cell(label, 50), cell(txt, 60), cell(tgt, 100), cell(res, 140)))
        print()
    for w in warnings:
        print("warning: %s" % w, file=sys.stderr)
    capped = sum(1 for w in warnings if "hit the %d cap" % CHILD_CAP in w)
    summary = "%d links (%d unique targets) in %d KB pages and %d files: %s" % (
        len(items), len(keys), len(pages), len(files), ", ".join("%s=%d" % (c, counts[c]) for c in ORDER))
    if capped:
        summary += ", capped-child-lists=%d" % capped
    print(summary)
    failed = counts[DEAD] + counts[WRONG_ANCHOR] + (0 if args.allow_internal else counts[NOT_PUBLIC])
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
