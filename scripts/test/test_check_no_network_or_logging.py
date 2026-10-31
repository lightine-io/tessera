#!/usr/bin/env python3
"""Tests for scripts/check-no-network-or-logging.py. Hermetic: every case builds a throwaway folder.

Run: python3 -I scripts/test/test_check_no_network_or_logging.py
"""

import contextlib
import importlib.util
import io
import os
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location("check_nl", os.path.join(HERE, "..", "check-no-network-or-logging.py"))
nl = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(nl)

MAIN = "mod/src/commonMain/kotlin/p/A.kt"


def build(files):
    root = tempfile.mkdtemp()
    for rel, text in files.items():
        path = os.path.join(root, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(textwrap.dedent(text))
    return root


def run(files, *extra):
    root = build(files)
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = nl.main(["--root", root] + list(extra))
    return code, out.getvalue()


def kt(body):
    return {MAIN: "package p\n" + body}


class Kotlin(unittest.TestCase):
    def test_clean_file_passes(self):
        code, out = run(kt("fun f(sink: TelemetrySink) { sink.emit(1) }\n"))
        self.assertEqual(code, 0)
        self.assertIn("clean", out)

    def assert_flagged(self, line, fragment, source_set="commonMain"):
        path = "mod/src/%s/kotlin/p/A.kt" % source_set
        code, out = run({path: "package p\n" + line + "\n"})
        self.assertEqual(code, 1, line)
        self.assertIn("%s:2: " % path, out)
        self.assertIn(fragment, out)

    def test_each_forbidden_pattern_is_flagged_on_its_line(self):
        cases = [
            ("import java.net.URL", "java.net"),
            ("import javax.net.ssl.SSLContext", "java.net"),
            ("import okhttp3.OkHttpClient", "okhttp3"),
            ("import io.ktor.client.HttpClient", "ktor"),
            ("import retrofit2.Retrofit", "retrofit"),
            ("val c: HttpURLConnection? = null", "HttpURLConnection"),
            ("val c = u.openConnection()", "openConnection"),
            ("val s = Socket(h, 1)", "Socket"),
            ("val s = NSURLSession.sharedSession", "NSURLSession"),
            ("val r = NSMutableURLRequest()", "NSURLRequest"),
            ("import platform.Network.nw_connection_create", "platform.Network"),
            ("import android.util.Log", "android.util.Log"),
            ("Log.d(TAG, x)", "Log.v/d"),
            ("Log.wtf(TAG, x)", "Log.v/d"),
            ("println(x)", "println"),
            ("print(x)", "println"),
            ("System.out.println(x)", "System.out"),
            ("System.err.flush()", "System.out"),
            ("e.printStackTrace()", "printStackTrace"),
            ("NSLog(\"x\")", "NSLog"),
            ("import platform.Foundation.NSLog", "NSLog"),
            ("import java.util.logging.Logger", "java.util.logging"),
            ("import org.slf4j.LoggerFactory", "slf4j"),
            ("Timber.d(x)", "Timber"),
            ("val s = url.openStream()", "openStream"),
            ("val t = URL(u).readText()", "readText"),
            ("Kermit.d { x }", "Kermit"),
            ("Napier.d(x)", "Napier"),
            ("private val log = KotlinLogging.logger {}", "kotlin-logging"),
        ]
        for line, fragment in cases:
            with self.subTest(line=line):
                self.assert_flagged(line, fragment)

    def test_every_main_source_set_is_scanned(self):
        for sset in ("commonMain", "jvmMain", "androidMain", "iosMain", "appleMain", "nativeMain"):
            with self.subTest(sset=sset):
                self.assert_flagged("println(x)", "println", sset)

    def test_line_number_and_count(self):
        code, out = run(kt("fun a() {}\nfun b() { println(1) }\nfun c() { Log.i(T, m) }\n"))
        self.assertEqual(code, 1)
        self.assertIn("%s:3: logging (println" % MAIN, out)
        self.assertIn("%s:4: logging (Log.v" % MAIN, out)
        self.assertIn("2 finding(s)", out)

    def test_comments_and_kdoc_are_ignored(self):
        body = ("// println(x) and java.net.URL\n"
                "/** See [okhttp3.OkHttpClient] and Log.d(T, m). */\n"
                "/* System.out.println(1)\n   Timber.d(x) */\n"
                "fun f() {}\n")
        self.assertEqual(run(kt(body))[0], 0)

    def test_string_literals_are_ignored(self):
        self.assertEqual(run(kt('val m = "call println(x) or Log.d"\n'))[0], 0)

    def test_member_named_print_is_not_flagged(self):
        self.assertEqual(run(kt("fun f(w: W) { w.print(1); w.println(2) }\n"))[0], 0)

    def test_test_source_sets_build_dirs_and_samples_are_ignored(self):
        bad = "package p\nfun f() { println(1) }\n"
        files = {"mod/src/commonTest/kotlin/p/T.kt": bad,
                 "mod/src/androidHostTest/kotlin/p/T.kt": bad,
                 "mod/src/jvmTest/kotlin/p/T.kt": bad,
                 "mod/build/generated/src/commonMain/p/G.kt": bad,
                 "samples/demo/src/commonMain/kotlin/p/S.kt": bad,
                 "mod/other/A.kt": bad,
                 MAIN: "package p\n"}
        code, out = run(files)
        self.assertEqual(code, 0, out)
        self.assertIn("(1 main source files", out)


class Swift(unittest.TestCase):
    def _run(self, line):
        root = build({"Sources/Tessera/A.swift": "import Foundation\n" + line + "\n",
                      "Tests/TesseraTests/T.swift": "import Foundation\nprint(1)\n"})
        kroot = build({})
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = nl.main(["--root", kroot, "--swift", root])
        return code, out.getvalue()

    def test_swift_patterns_are_flagged(self):
        cases = ["let s = URLSession.shared", "var r = URLRequest(url: u)", "let c = NWConnection(host: h, port: p, using: .tcp)",
                 "import Network", "print(x)", "debugPrint(x)", "NSLog(\"x\")", "os_log(\"x\")", "let l = Logger()"]
        for line in cases:
            with self.subTest(line=line):
                code, out = self._run(line)
                self.assertEqual(code, 1)
                self.assertIn("swift:Sources/Tessera/A.swift:2: ", out)

    def test_swift_comments_and_tests_are_ignored(self):
        code, _ = self._run("/// Uses URLSession? No.\n// print(x)\nlet a = 1")
        self.assertEqual(code, 0)

    def test_swift_is_not_scanned_without_the_flag(self):
        self.assertEqual(run(kt("fun f() {}\n"))[0], 0)

    def test_missing_swift_folder_cannot_run(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(nl.main(["--root", build({}), "--swift", "/nonexistent-dir-x"]), 2)


if __name__ == "__main__":
    unittest.main(verbosity=1)
