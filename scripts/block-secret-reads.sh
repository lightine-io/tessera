#!/usr/bin/env bash
# PreToolUse hook: block an assistant tool call that would read (or write) a file that
# typically holds secrets: private keys, key stores, credential and token files, keychains.
#
# Why: secrets read into a session stay in its transcript. It happened once (2026-05-29:
# a whole local Gradle properties file was read to check one line, and a signing key, its
# passphrase and an upload token entered the transcript). Only the assistant's tool calls
# reach this hook; a human's terminal is never affected. There is no override for the
# assistant: the owner reads the file, or runs the command, in their own terminal.
#
# What it checks (wired in .claude/settings.json):
#   - Read, Edit, Write, NotebookEdit: the file path.
#   - Grep: the search path; a folder is blocked when it holds a denied home location
#     (searching all of $HOME, for example). The glob filter is not checked.
#   - Bash: a conservative look at the command text. Arguments of reading or copying
#     commands (cat, head, grep, cp, scp ...) and input redirection (< file) that name an
#     existing denied file, or a folder holding a denied home location. `~`, $HOME,
#     ${HOME}, quotes, wildcards, relative paths (against the hook's cwd, following `cd`)
#     and symlinks are resolved.
#   Not checked: Glob and `ls` (they list names, never contents).
# Matching is on whole path parts and file names, so a name that only contains a denied
# word (.envrc, notes-on-.netrc.md, id_rsa.txt outside ~/.ssh) passes. A file matched by
# NAME (key suffixes, .env, gradle.properties) passes when git tracks it: its content is
# already in the repository. The deny list lives in ONE place: DENY below.
#
# Exit: 0 allow; 2 block (reason on stderr). Fails open: a missing or odd payload, or any
# error, never produces exit 2.
# Regression cases: scripts/test/block-secret-reads.test.sh.

set -euo pipefail

payload="$(cat 2>/dev/null || true)"
[ -n "$payload" ] || exit 0

root="${CLAUDE_PROJECT_DIR:-}"

hit=$(printf '%s' "$payload" | HOOK_ROOT="$root" python3 -I -c '
import fnmatch, glob, json, os, re, shlex, subprocess, sys

# ---- DENY: the one list. Paths are relative to $HOME unless the group says otherwise. ----
DENY = {
    # Whole folders of private keys, key rings, cloud keys and keychains.
    "home_trees": {
        ".ssh": "SSH private keys",
        ".gnupg": "GnuPG key ring",
        ".aws": "AWS credentials",
        "Library/Keychains": "macOS keychains",
    },
    # Public halves and host lists inside ~/.ssh stay readable.
    "ssh_public": ["*.pub", "known_hosts", "known_hosts.old", "config", "authorized_keys"],
    # Single credential and token files.
    "home_files": {
        ".gradle/gradle.properties": "Gradle credentials (signing key, publishing tokens)",
        ".m2/settings.xml": "Maven credentials",
        ".netrc": "login tokens",
        ".npmrc": "npm token",
        ".pypirc": "PyPI token",
        ".config/gh/hosts.yml": "GitHub CLI token",
        ".docker/config.json": "Docker registry credentials",
        ".claude.json": "Claude Code account data",
        ".claude/.credentials.json": "Claude Code credentials",
    },
    # Private keys and key stores, by suffix, anywhere under $HOME or the project.
    "key_suffixes": [".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".asc", ".gpg"],
    # Environment files, by name, anywhere. Example files stay readable.
    "env_names": [".env", ".env.*"],
    "env_allowed": [".env.example", ".env.sample"],
    # Gradle properties outside a git-tracked copy may hold signing keys and tokens.
    "gradle_props": "gradle.properties",
    # The private terms list: the scan scripts read it; a session never prints it.
    "terms_file": ".claude/private-content-terms.local",
}

READERS = {"cat", "head", "tail", "less", "more", "bat", "grep", "egrep", "fgrep", "rg",
           "sed", "awk", "gawk", "strings", "xxd", "od", "hexdump", "base64", "open", "cp",
           "scp", "rsync", "tar", "zip", "nl", "tac", "cut", "sort", "diff", "cmp", "jq",
           "yq", "plutil", "iconv", "gpg", "view", "vim", "vi", "nano"}
WRAPPERS = {"sudo", "command", "exec", "env", "nohup", "time", "nice"}
PATTERN_FIRST = {"grep", "egrep", "fgrep", "rg"}

def homes():
    h = os.environ.get("HOME", "")
    return {p for p in (os.path.normpath(h), os.path.realpath(h)) if h and p != "/"}

def projects(root):
    return {p for p in (os.path.normpath(root), os.path.realpath(root)) if root and p != "/"}

def under(p, base):
    return p == base or p.startswith(base.rstrip("/") + "/")

def tracked(p):
    try:
        r = subprocess.run(["git", "-C", os.path.dirname(p), "ls-files", "--error-unmatch",
                            "--", os.path.basename(p)], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=5)
        return r.returncode == 0
    except Exception:
        return False

def file_reason(path, root):
    """Why this file path is denied, or None."""
    cands = {os.path.normpath(path), os.path.realpath(path)}
    for p in cands:
        name = os.path.basename(p)
        for h in homes():
            if not under(p, h):
                continue
            rel = os.path.relpath(p, h)
            for tree, why in DENY["home_trees"].items():
                if under(rel, tree) and rel != tree:
                    if tree == ".ssh" and any(fnmatch.fnmatch(name, g) for g in DENY["ssh_public"]):
                        continue
                    return why
            if rel in DENY["home_files"]:
                return DENY["home_files"][rel]
        if p.endswith("/" + DENY["terms_file"]):
            return "the private terms list"
        by_name = None
        if any(fnmatch.fnmatch(name, g) for g in DENY["env_names"]) and name not in DENY["env_allowed"]:
            by_name = "environment file"
        elif name == DENY["gradle_props"]:
            by_name = "Gradle properties outside a tracked copy"
        elif name.lower().endswith(tuple(DENY["key_suffixes"])) and \
                any(under(p, b) for b in homes() | projects(root)):
            by_name = "private key or key store"
        if by_name and not (os.path.isfile(p) and tracked(p)):
            return by_name
    return None

def dir_reason(d):
    """Why a recursive read of folder d is denied, or None."""
    for p in {os.path.normpath(d), os.path.realpath(d)}:
        for h in homes():
            for loc, why in list(DENY["home_trees"].items()) + list(DENY["home_files"].items()):
                full = os.path.join(h, loc)
                if os.path.lexists(full) and (under(full, p) or under(p, full)):
                    return "folder holds " + why
    return None

def resolve(tok, cwd):
    home = os.environ.get("HOME", "")
    tok = tok.replace("${HOME}", home).replace("$HOME", home)
    if tok == "~" or tok.startswith("~/"):
        tok = home + tok[1:]
    return os.path.normpath(os.path.join(cwd, tok))

def strip_heredocs(cmd):
    out, lines, i = [], cmd.split("\n"), 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        tags = re.findall(r"<<-?\s*[\"\x27]?([A-Za-z_][A-Za-z0-9_]*)[\"\x27]?", line)
        i += 1
        for tag in tags:
            while i < len(lines) and lines[i].strip() != tag:
                i += 1
            i += 1
    return "\n".join(out)

def tokens(cmd):
    cmd = strip_heredocs(cmd).replace("\n", " ; ")
    try:
        lex = shlex.shlex(cmd, posix=True, punctuation_chars=True)
        lex.whitespace_split = True
        return list(lex)
    except ValueError:
        return re.findall(r"[;&|()<>]+|[^\s;&|()<>]+", cmd)

def check_bash(cmd, cwd, root):
    toks = tokens(cmd)
    seg_start, name, positional, redirect = True, None, 0, False
    for t in toks:
        if ("$(" in t or "`" in t) and t not in ("$(", "`"):
            inner = t.replace("$(", " ; ").replace("`", " ; ").replace(")", " ; ")
            why = check_bash(inner, cwd, root)
            if why:
                return why
        if t and all(c in ";&|()<>" for c in t):
            redirect = t.endswith("<") and not t.endswith("<<")
            if not redirect:
                seg_start, name, positional = True, None, 0
            continue
        if redirect:
            redirect = False
            why = check_arg(t, cwd, root)
            if why:
                return why
            continue
        if seg_start:
            if re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", t):
                continue
            base = os.path.basename(t.lstrip("$(`"))
            if base in WRAPPERS:
                continue
            name, seg_start = base, False
            continue
        if name == "cd" and positional == 0:
            cwd = resolve(t, cwd)
            positional += 1
            continue
        if name not in READERS:
            continue
        if t.startswith("-"):
            if "=" in t:
                why = check_arg(t.split("=", 1)[1], cwd, root)
                if why:
                    return why
            continue
        positional += 1
        if name in PATTERN_FIRST and positional == 1:
            continue
        why = check_arg(t, cwd, root)
        if why:
            return why
    return None

def check_arg(tok, cwd, root):
    p = resolve(tok.rstrip(")`"), cwd)
    paths = glob.glob(p) if any(c in p for c in "*?[") else [p]
    for q in paths:
        if os.path.isdir(q):
            why = dir_reason(q)
        elif os.path.lexists(q):
            why = file_reason(q, root)
        else:
            why = None
        if why:
            return q + "\t" + why
    return None

try:
    data = json.load(sys.stdin)
    tool = data.get("tool_name", "")
    inp = data.get("tool_input") or {}
    cwd = data.get("cwd") or os.getcwd()
    root = os.environ.get("HOOK_ROOT") or cwd
    hit = None
    if tool == "Bash":
        hit = check_bash(inp.get("command", "") or "", cwd, root)
    elif tool in ("Read", "Edit", "Write", "MultiEdit", "NotebookEdit"):
        path = inp.get("file_path") or inp.get("notebook_path") or ""
        if path:
            p = resolve(path, cwd)
            why = file_reason(p, root)
            hit = p + "\t" + why if why else None
    elif tool == "Grep":
        p = resolve(inp.get("path") or cwd, cwd)
        why = dir_reason(p) if os.path.isdir(p) else file_reason(p, root)
        hit = p + "\t" + why if why else None
    if hit:
        print(tool + "\t" + hit)
except Exception:
    pass
' 2>/dev/null || true)
[ -n "$hit" ] || exit 0

IFS=$'\t' read -r tool path why <<EOF
$hit
EOF
{
    echo "block-secret-reads: BLOCKED - this would read a file that typically holds secrets."
    echo ""
    echo "  Tool: $tool"
    echo "  Path: $path"
    echo "  Why:  $why"
    echo ""
    echo "  Secrets read into a session stay in its transcript. Ask the owner to read the"
    echo "  file, or to run the command in their own terminal, and to share only what you need."
} >&2
exit 2
