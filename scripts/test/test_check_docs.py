#!/usr/bin/env python3
"""Tests for scripts/check-docs.py. Hermetic: every case builds a throwaway folder.

Run: python3 -I scripts/test/test_check_docs.py
Fixture text that the repo-wide pointer check would flag is built from pieces.
"""

import contextlib
import importlib.util
import io
import os
import subprocess
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location("check_docs", os.path.join(HERE, "..", "check-docs.py"))
cd = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cd)

CLAUDE = "CLAUDE" + ".md"
RETIRED = "do" + "cs/"
DASH = chr(0x2014)
TICK = chr(0x2713)


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(textwrap.dedent(text))


def git_repo(files):
    root = tempfile.mkdtemp()
    for rel, text in files.items():
        write(root, rel, text)
    subprocess.run(["git", "init", "-q", root], check=True)
    subprocess.run(["git", "-C", root, "add", "-A"], check=True)
    return root


def run(argv):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = cd.main(argv)
    return code, out.getvalue()


class Pointers(unittest.TestCase):
    def test_clean_repo_passes(self):
        root = git_repo({CLAUDE: "# C\n## The Work Loop\n",
                         "a.md": "See `b.md`, `.claude/rules/r.md`, and %s \"The Work Loop\".\n" % CLAUDE,
                         "b.md": "x\n", ".claude/rules/r.md": "x\n"})
        self.assertEqual(run(["--root", root, "pointers"])[0], 0)

    def test_retired_folder_path_is_flagged(self):
        root = git_repo({CLAUDE: "# C\n", "a.md": "Read %sfeatures/parsing.md first.\n" % RETIRED,
                         "s.sh": "# see ../../%stesting.md\n" % RETIRED})
        code, out = run(["--root", root, "pointers"])
        self.assertEqual(code, 1)
        self.assertIn("a.md:1", out)
        self.assertIn("s.sh:1", out)

    def test_branch_prefix_is_not_a_path(self):
        root = git_repo({CLAUDE: "# C\n", "a.md": "Branch names: `feature/x`, `%salign-shapes`.\n" % RETIRED})
        self.assertEqual(run(["--root", root, "pointers"])[0], 0)

    def test_untracked_backticked_name_is_flagged(self):
        root = git_repo({CLAUDE: "# C\n", "a.md": "See `gone.md` and `.claude/rules/gone.md`.\n"})
        code, out = run(["--root", root, "pointers"])
        self.assertEqual(code, 1)
        self.assertEqual(out.count("does not track"), 2)

    def test_missing_claude_section_is_flagged(self):
        root = git_repo({CLAUDE: "# C\n## Safety invariants\n",
                         "w.yml": "msg: (%s 'Required Discipline')\n" % CLAUDE,
                         "h.sh": "echo \"(%s Session Discipline)\"\n" % CLAUDE})
        code, out = run(["--root", root, "pointers"])
        self.assertEqual(code, 1)
        self.assertIn("Required Discipline", out)
        self.assertIn("Session Discipline", out)

    def test_changelog_history_is_ignored(self):
        changelog = "# Changelog\n## [Unreleased]\n- ok\n## [0.1.0]\n- moved `old.md` out of %sx.md\n" % RETIRED
        root = git_repo({CLAUDE: "# C\n", "CHANGELOG.md": changelog})
        self.assertEqual(run(["--root", root, "pointers"])[0], 0)


class Style(unittest.TestCase):
    def test_dash_and_banned_words_are_flagged(self):
        root = git_repo({"README.md": "Tessera %s a reader.\nJust call it.\n" % DASH})
        code, out = run(["--root", root, "style"])
        self.assertEqual(code, 1)
        self.assertIn("README.md:1: em-dash", out)
        self.assertIn("README.md:2: banned word 'Just'", out)

    def test_code_is_ignored_and_words_need_boundaries(self):
        text = "Adjust the `just` flag.\n```\nsimply %s here\n```\n" % DASH
        root = git_repo({"README.md": text})
        self.assertEqual(run(["--root", root, "style"])[0], 0)

    def test_only_unreleased_changelog_counts(self):
        text = "## [Unreleased]\n- fine\n## [0.1.0]\n- old %s text, please\n" % DASH
        root = git_repo({"CHANGELOG.md": text})
        self.assertEqual(run(["--root", root, "style"])[0], 0)

    def test_module_markdown_is_not_in_the_default_set(self):
        root = git_repo({"core/src/commonMain/x.kt": "", "core/notes.md": "%s\n" % DASH})
        self.assertEqual(run(["--root", root, "style"])[0], 0)

    def test_kdoc_mode_reads_doc_comments(self):
        src = "/** A reader %s nothing more. */\npublic class A\n// plain %s comment\n" % (DASH, DASH)
        root = git_repo({"core/src/commonMain/kotlin/A.kt": src})
        code, out = run(["--root", root, "style", "--kdoc"])
        self.assertEqual(code, 1)
        self.assertEqual(out.count("em-dash"), 1)


KOTLIN = '''\
/** Documented type. */
public class Good {
    /** Documented member. */
    public fun a(): Int = 1

    /** Doc above a multi-line annotation. */
    @Deprecated(
        "old",
    )
    public fun moved(): Int = 2

    public override fun toString(): String = "x"

    public fun bare(): Int = 3
}

/**
 * Data with tags.
 * @property tagged documented by the tag.
 */
public data class Data(
    val tagged: Int,
    /** Own doc. */
    val own: Int,
    val missing: Int,
    private val hidden: Int,
)

/** Kinds. */
public enum class Kind {
    /** Documented entry. */
    ONE,
    TWO,
    ;
    /** Member after the entries. */
    public fun f(): Int = 0
}

private class Hidden {
    public fun notReally(): Int = 0
}

public fun undocumentedTopLevel(text: String = "public fun fake()"): Int = 0
'''


class CodeDocs(unittest.TestCase):
    def findings(self, source, name="core/src/commonMain/kotlin/A.kt"):
        root = git_repo({name: source})
        code, out = run(["--root", root, "code-docs"])
        return code, [line for line in out.splitlines() if ": no doc comment" in line]

    def test_kotlin_rules(self):
        code, found = self.findings(KOTLIN)
        text = "\n".join(found)
        self.assertEqual(code, 1)
        self.assertIn("public fun bare", text)
        self.assertIn("constructor property missing", text)
        self.assertIn("enum entry TWO", text)
        self.assertIn("public fun undocumentedTopLevel", text)
        for absent in ("public fun a()", "moved", "toString", "tagged", "property own", "hidden",
                       "enum entry ONE", "notReally", "fake"):
            self.assertNotIn(absent, text)
        self.assertEqual(len(found), 4)

    def test_test_sources_are_skipped(self):
        code, found = self.findings("public fun x() = 1\n", "core/src/commonTest/kotlin/T.kt")
        self.assertEqual((code, found), (0, []))

    def test_swift(self):
        swift = git_repo({"Sources/UI/V.swift": textwrap.dedent('''\
            /// A view.
            public struct V {
                /// Documented.
                public init() {}
                public var bare: Int { 1 }
                public override func f() {}
            }
            ''')})
        kotlin = git_repo({"README.md": "x\n"})
        code, out = run(["--root", kotlin, "code-docs", "--swift", swift])
        self.assertEqual(code, 1)
        self.assertIn("public var bare", out)
        self.assertEqual(out.count("no doc comment"), 1)


MATRIX = '''\
---
kind: reference
---
| Capability | JVM | Android | iOS | How to |
|---|---|---|---|---|
| Parse | {t} | {t} | {t} | Kotlin: [[how-to/kotlin/parse]]. Swift: [[how-to/ios/parse]] |
| Torch | n/a | {t} | ✗ | Android: [[how-to/android/torch]] |
'''.replace("{t}", TICK)

KB = "https://lightine.youtrack.cloud/articles/"

LLMS = '''\
# Tessera
- [Read your first MRZ on the JVM](%s1): first run
- [Parse an MRZ in Kotlin](%s2): parse
- [Parse an MRZ string on iOS](%s3): parse
- [Turn the torch on and off on Android](%s4): torch
''' % ((KB,) * 4)


def page(kind, title, extra=""):
    return "---\nkind: %s\n%s---\n\n# %s\n\nBody.\n" % (kind, extra, title)


def shelf(files=None, llms=LLMS):
    """Returns (repo_root, shelf_dir): llms.txt sits in the repo root, the pages in the shelf folder."""
    repo = tempfile.mkdtemp()
    if llms is not None:
        write(repo, "llms.txt", llms)
    folder = tempfile.mkdtemp()
    base = {"start/capability-matrix.md": MATRIX,
            "tutorials/jvm-parse.md": page("tutorial", "Read your first MRZ on the JVM"),
            "how-to/kotlin/parse.md": page("how-to", "Parse an MRZ in Kotlin"),
            "how-to/ios/parse.md": page("how-to", "Parse an MRZ string on iOS"),
            "how-to/android/torch.md": page("how-to", "Turn the torch on and off on Android"),
            "contribute/set-up.md": page("how-to", "Set up to contribute", "audience: contributor\n")}
    base.update(files or {})
    for rel, text in base.items():
        if text is not None:
            write(folder, rel, text)
    return repo, folder


def run_shelf(files=None, llms=LLMS):
    repo, folder = shelf(files, llms)
    return run(["--root", repo, "shelf", folder])


class Shelf(unittest.TestCase):
    def test_consistent_shelf_passes(self):
        self.assertEqual(run_shelf()[0], 0)

    def test_tick_without_page_is_flagged(self):
        code, out = run_shelf({"how-to/android/torch.md": None})
        self.assertEqual(code, 1)
        self.assertIn("'Torch' is %s on android" % TICK, out)
        self.assertIn("does not exist: how-to/android/torch.md", out)

    def test_page_missing_from_llms_is_flagged_by_title(self):
        code, out = run_shelf({"how-to/ios/torch.md": page("how-to", "Turn the torch on on iOS")})
        self.assertEqual(code, 1)
        self.assertIn("how-to page 'Turn the torch on on iOS' not listed in llms.txt", out)

    def test_link_text_must_be_the_exact_title(self):
        code, out = run_shelf(llms=LLMS.replace("Parse an MRZ in Kotlin", "Kotlin parse"))
        self.assertEqual(code, 1)
        self.assertIn("how-to/kotlin/parse.md: how-to page 'Parse an MRZ in Kotlin' not listed", out)

    def test_title_match_ignores_case_and_spacing(self):
        llms = LLMS.replace("Parse an MRZ in Kotlin", "parse  an MRZ   in kotlin")
        self.assertEqual(run_shelf(llms=llms)[0], 0)

    def test_relative_link_is_flagged(self):
        code, out = run_shelf(llms=LLMS.replace(KB + "2", "how-to/kotlin/parse.md"))
        self.assertEqual(code, 1)
        self.assertIn("link is not an absolute URL: how-to/kotlin/parse.md", out)

    def test_contributor_page_need_not_be_listed(self):
        self.assertEqual(run_shelf({"contribute/more.md": page("how-to", "More setup")})[0], 0)

    def test_missing_llms_in_repo_root_is_flagged(self):
        code, out = run_shelf(llms=None)
        self.assertEqual(code, 1)
        self.assertIn("llms.txt: missing", out)

    def test_llms_inside_the_shelf_folder_does_not_count(self):
        code, out = run_shelf({"llms.txt": LLMS}, llms=None)
        self.assertEqual(code, 1)
        self.assertIn("llms.txt: missing", out)

    def test_two_pages_with_one_title_are_flagged(self):
        code, out = run_shelf({"how-to/kotlin/parse-again.md": page("how-to", "Parse an MRZ in Kotlin")})
        self.assertEqual(code, 1)
        self.assertIn("same title as", out)

    def test_missing_shelf_folder_cannot_run(self):
        repo = tempfile.mkdtemp()
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as ctx:
                run(["--root", repo, "shelf", os.path.join(repo, "nope")])
        self.assertEqual(ctx.exception.code, 2)


if __name__ == "__main__":
    unittest.main(verbosity=1)
