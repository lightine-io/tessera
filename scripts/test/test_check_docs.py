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

    def test_class_reference_in_an_annotation_is_not_the_class_keyword(self):
        # `@Throws(X::class)` between the class name and its constructor must not hide the class doc.
        source = textwrap.dedent('''\
            /**
             * A counter.
             *
             * @property limit documented by the tag.
             */
            public class Counter
                @Throws(IllegalArgumentException::class)
                constructor(
                    public val limit: Int,
                    public val missing: Int,
                )
            ''')
        code, found = self.findings(source)
        self.assertEqual(code, 1)
        self.assertEqual(len(found), 1)
        self.assertIn("constructor property missing", found[0])

    def test_test_sources_are_skipped(self):
        code, found = self.findings("public fun x() = 1\n", "core/src/commonTest/kotlin/T.kt")
        self.assertEqual((code, found), (0, []))

    def test_documentation_samples_are_skipped(self):
        code, found = self.findings("public fun x() = 1\n", "samples/kotlin/src/jvmMain/kotlin/S.kt")
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


def fake_page(kind, title, platforms=(), contributor=False, aid=None):
    return {"title": title, "kind": kind, "contributor": contributor,
            "platforms": set(platforms), "id": aid}


FAKE_LLMS = "- [Parse an MRZ in Kotlin](https://x.example/articles/TES-A-25)\n"


class ShelfRules(unittest.TestCase):
    """The shared rule function over an in-memory page set: no files, no network."""

    def rows(self, mark="\u2713", links=("TES-A-25",)):
        return [(7, "Parse", {"jvm": mark, "android": "n/a", "ios": "n/a"}, list(links))]

    def run_rules(self, pages, rows, llms=FAKE_LLMS):
        return cd.shelf_findings(pages, "matrix", rows, llms, "llms.txt")

    def pages(self):
        return {"TES-A-25": fake_page("how-to", "Parse an MRZ in Kotlin", ["jvm", "android"], aid="TES-A-25")}

    def test_clean_set_has_no_findings(self):
        self.assertEqual(self.run_rules(self.pages(), self.rows()), [])

    def test_check_mark_without_platform_page(self):
        pages = {"TES-A-25": fake_page("how-to", "Parse an MRZ in Kotlin", ["ios"], aid="TES-A-25")}
        self.assertIn("matrix:7: 'Parse' is \u2713 on jvm but links no jvm how-to page",
                      self.run_rules(pages, self.rows()))

    def test_link_to_missing_page(self):
        out = self.run_rules(self.pages(), self.rows(links=("TES-A-999",)))
        self.assertIn("matrix:7: links a page that does not exist: TES-A-999", out)

    def test_unlisted_page_and_wrong_url_and_duplicate_title(self):
        pages = self.pages()
        pages["TES-A-26"] = fake_page("how-to", "Generate an MRZ in Kotlin", ["jvm"], aid="TES-A-26")
        pages["TES-A-27"] = fake_page("tutorial", "Parse an MRZ in Kotlin", aid="TES-A-27")
        out = "\n".join(self.run_rules(pages, self.rows()))
        self.assertIn("'Generate an MRZ in Kotlin' not listed", out)
        self.assertIn("same title as TES-A-25", out)
        out = "\n".join(self.run_rules(self.pages(), self.rows(),
                                      "- [Parse an MRZ in Kotlin](https://x.example/articles/TES-A-250)\n"))
        self.assertIn("not with its article URL (.../articles/TES-A-25)", out)

    def test_contributor_and_section_pages_need_no_listing(self):
        pages = self.pages()
        pages["TES-A-7"] = fake_page("how-to", "How work flows", contributor=True, aid="TES-A-7")
        pages["TES-A-89"] = fake_page("", "How-to: Android", aid="TES-A-89")
        self.assertEqual(self.run_rules(pages, self.rows()), [])

    def test_missing_llms(self):
        self.assertIn("llms.txt: missing (it lives in the repo root)",
                      self.run_rules(self.pages(), self.rows(), None))


class ShelfLive(unittest.TestCase):
    def live(self, fetch):
        repo = tempfile.mkdtemp()
        write(repo, "llms.txt", FAKE_LLMS)
        return cd.cmd_shelf(repo, None, "unused", "llms.txt", live=True, fetch=fetch)

    def test_fetch_error_cannot_run(self):
        def boom(url):
            raise OSError("no route")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            with self.assertRaises(SystemExit) as ctx:
                self.live(boom)
        self.assertEqual(ctx.exception.code, 2)
        self.assertIn("cannot read the live KB", err.getvalue())

    def test_odd_answer_cannot_run(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as ctx:
                self.live(lambda url: {"error": "nope"})
        self.assertEqual(ctx.exception.code, 2)

    def test_tree_is_read_into_pages(self):
        arts = [("TES-A-3", None, "Public"), ("TES-A-16", "TES-A-3", "Start here"),
                ("TES-A-139", "TES-A-16", "Capability matrix"), ("TES-A-88", "TES-A-3", "How-to guides"),
                ("TES-A-127", "TES-A-88", "How-to: Kotlin"), ("TES-A-25", "TES-A-127", "Parse an MRZ in Kotlin"),
                ("TES-A-78", "TES-A-3", "Contribute"), ("TES-A-7", "TES-A-78", "How work flows")]
        listing = [{"idReadable": i, "summary": t, "parentArticle": {"idReadable": p} if p else None}
                   for i, p, t in arts]
        matrix = "| Capability | JVM | Android | iOS | How to |\n|---|---|---|---|---|\n" \
                 "| Parse | " + TICK + " | " + TICK + " | n/a | [P](https://k/articles/TES-A-25) |\n"

        def fetch(url):
            return {"content": matrix} if "fields=content" in url else listing
        pages, text = cd.fetch_live(fetch)
        self.assertEqual(pages["TES-A-25"]["platforms"], {"jvm", "android"})
        self.assertEqual(pages["TES-A-25"]["kind"], "how-to")
        self.assertEqual(pages["TES-A-127"]["kind"], "")
        self.assertTrue(pages["TES-A-7"]["contributor"])
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(self.live(fetch), 0)


FENCE = "`" * 3
SAMPLE_PAGE = (
    "Intro.\n\n1. Add the dependency.\n\n   `build.gradle.kts`\n\n"
    "   " + FENCE + "kotlin\n   dependencies {\n       implementation(\"x\")\n   }\n   " + FENCE + "\n\n"
    "2. Parse.\n\n   " + FENCE + "kotlin\n   import a.B\n\n   val x = B.parse()\n   println(x)\n   " + FENCE + "\n\n"
    "   " + FENCE + "\n   Success\n   " + FENCE + "\n\n"
    + FENCE + "swift\nlet y = 1\n" + FENCE + "\n"
)


def sample_file(blocks):
    """blocks: [(marker, code)] -> a sample file with each code between its region lines."""
    out = ["package samples", "", "fun main() {"]
    for marker, code in blocks:
        out += ["    // region " + marker] + ["    " + ln for ln in code.splitlines()] + ["    // endregion"]
    return "\n".join(out + ["}"]) + "\n"


class Samples(unittest.TestCase):
    def check(self, files, pages=None):
        regions, findings = cd.sample_regions(files)
        return findings + cd.samples_findings(pages or {"TES-A-25": ("Parse", SAMPLE_PAGE)}, regions)

    def test_blocks_are_read_with_labels(self):
        blocks = cd.kotlin_blocks(SAMPLE_PAGE)
        self.assertEqual([(n, label) for n, label, _ in blocks], [(1, "build.gradle.kts"), (2, "")])
        self.assertEqual(blocks[1][2], "import a.B\n\nval x = B.parse()\nprintln(x)")

    def test_matching_regions_pass_whitespace_aside(self):
        files = {"s/build.kts": sample_file([("TES-A-25 block 1", "dependencies {\nimplementation(\"x\")\n}")]),
                 "s/A.kt": sample_file([("TES-A-25 block 2", "  import a.B\nval x = B.parse()  \n\nprintln(x)")])}
        self.assertEqual(self.check(files), [])

    def test_changed_line_is_a_finding(self):
        files = {"s/build.kts": sample_file([("TES-A-25 block 1", "dependencies {\nimplementation(\"x\")\n}")]),
                 "s/A.kt": sample_file([("TES-A-25 block 2", "import a.B\nval x = B.parse(1)\nprintln(x)")])}
        found = self.check(files)
        self.assertEqual(len(found), 1)
        self.assertIn("TES-A-25 block 2 differs from the page at line 2", found[0])
        self.assertIn("page 'val x = B.parse()', sample 'val x = B.parse(1)'", found[0])

    def test_block_without_region_and_region_without_block(self):
        files = {"s/A.kt": sample_file([("TES-A-25 block 2", "import a.B\nval x = B.parse()\nprintln(x)"),
                                        ("TES-A-25 block 3", "println(1)"),
                                        ("TES-A-99 block 1", "println(2)")])}
        found = "\n".join(self.check(files))
        self.assertIn("TES-A-25 block 1 (build.gradle.kts): no sample region", found)
        self.assertIn("region for TES-A-25 block 3 has no such block", found)
        self.assertIn("region for TES-A-99 block 1 has no such block", found)

    def test_parts_join_in_order(self):
        text = ("// region TES-A-25 block 2 part 1\nimport a.B\n// endregion\n"
                "fun main() {\n    // region TES-A-25 block 2 part 2\n    val x = B.parse()\n    println(x)\n"
                "    // endregion\n}\n")
        files = {"s/A.kt": text,
                 "s/build.kts": sample_file([("TES-A-25 block 1", "dependencies {\nimplementation(\"x\")\n}")])}
        self.assertEqual(self.check(files), [])

    def test_duplicate_and_unclosed_regions(self):
        files = {"s/A.kt": sample_file([("TES-A-25 block 1", "x"), ("TES-A-25 block 1", "y")]),
                 "s/B.kt": "// region TES-A-25 block 2\nval x = 1\n"}
        found = "\n".join(cd.sample_regions(files)[1])
        self.assertIn("second region for TES-A-25 block 1", found)
        self.assertIn("s/B.kt:1: region has no endregion", found)

    def test_fetch_error_cannot_run(self):
        repo = tempfile.mkdtemp()
        write(repo, "samples/kotlin/A.kt", "x\n")

        def boom(url):
            raise OSError("no route")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            with self.assertRaises(SystemExit) as ctx:
                cd.cmd_samples(repo, fetch=boom)
        self.assertEqual(ctx.exception.code, 2)
        self.assertIn("cannot read the live KB", err.getvalue())

    def test_live_pages_are_compared(self):
        repo = tempfile.mkdtemp()
        write(repo, "samples/kotlin/build-scripts/p.gradle.kts",
              sample_file([("TES-A-25 block 1", "dependencies {\nimplementation(\"x\")\n}")]))
        write(repo, "samples/kotlin/src/jvmMain/kotlin/p.kt",
              sample_file([("TES-A-25 block 2", "import a.B\nval x = B.parse()\nprintln(y)")]))
        listing = [{"idReadable": "TES-A-3", "summary": "Public", "parentArticle": None},
                   {"idReadable": "TES-A-139", "summary": "Matrix", "parentArticle": {"idReadable": "TES-A-3"}},
                   {"idReadable": "TES-A-88", "summary": "How-to", "parentArticle": {"idReadable": "TES-A-3"}},
                   {"idReadable": "TES-A-25", "summary": "Parse", "parentArticle": {"idReadable": "TES-A-88"}}]

        def fetch(url):
            if "TES-A-25?" in url:
                return {"content": SAMPLE_PAGE}
            return {"content": ""} if "fields=content" in url else listing
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(cd.cmd_samples(repo, fetch=fetch), 1)
        self.assertIn("page 'println(x)', sample 'println(y)'", out.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=1)
