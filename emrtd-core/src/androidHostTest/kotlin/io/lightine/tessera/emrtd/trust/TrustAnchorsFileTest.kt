package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.fakechip.FakePki
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The Android copy of the file and stream helpers (TrustAnchors.android.kt mirrors the JVM file), so
// the same cases as jvmTest TrustAnchorsFileTest: a local file only, typed failures, never an exception.
class TrustAnchorsFileTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "tessera-trust-" + System.nanoTime())

    @BeforeTest
    fun makeDir() {
        assertTrue(dir.mkdir())
    }

    @AfterTest
    fun removeDir() {
        dir.listFiles()?.forEach { it.delete() }
        assertTrue(dir.delete())
    }

    @Test
    fun aLocalFileIsRead() {
        val der = FakePki.cscaCertificate()
        val file = File(dir, "root.der")
        file.writeBytes(der)
        val loaded = TrustAnchors.load(file)
        assertEquals(TrustAnchorLoadKind.CERTIFICATE, loaded.kind)
        assertEquals(1, loaded.anchors.size)
        assertEquals("UT", loaded.anchors[0].subjectCountry)
        assertEquals(null, loaded.problem)
    }

    @Test
    fun aMissingFileIsNotFound() {
        val loaded = TrustAnchors.load(File(dir, "no-such-file.pem"))
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_FOUND, loaded.problem)
        assertEquals(0, loaded.anchors.size)
    }

    @Test
    fun aFileLargerThanTheCapIsTooLarge() {
        val file = File(dir, "root.der")
        file.writeBytes(FakePki.cscaCertificate())
        val loaded = TrustAnchors.load(file, file.length() - 1)
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.TOO_LARGE, loaded.problem)
        assertEquals(0, loaded.anchors.size)
    }

    @Test
    fun aFolderIsNotReadable() {
        val loaded = TrustAnchors.load(dir)
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_READABLE, loaded.problem)
    }

    @Test
    fun aWebAddressIsNotALocalFile() {
        for (path in listOf("https://example.org/anchors.pem", "http://example.org/anchors.cer", "HTTPS://example.org/anchors.pem")) {
            val loaded = TrustAnchors.load(File(path))
            assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
            assertEquals(TrustAnchorLoadProblem.NOT_A_LOCAL_FILE, loaded.problem)
            assertEquals(0, loaded.anchors.size)
        }
    }

    @Test
    fun aStreamIsReadAndNotClosed() {
        val der = FakePki.cscaCertificate()
        val stream = Recording(java.io.ByteArrayInputStream(der))
        val loaded = TrustAnchors.load(stream)
        assertEquals(TrustAnchorLoadKind.CERTIFICATE, loaded.kind)
        assertEquals(1, loaded.anchors.size)
        assertEquals(0, stream.closes)
    }

    @Test
    fun aStreamLongerThanTheCapIsTooLarge() {
        val stream = java.io.ByteArrayInputStream(ByteArray(200) { it.toByte() })
        val loaded = TrustAnchors.load(stream, 100)
        assertEquals(TrustAnchorLoadProblem.TOO_LARGE, loaded.problem)
        assertEquals(0, loaded.anchors.size)
    }

    @Test
    fun aBrokenStreamIsNotReadable() {
        val loaded = TrustAnchors.load(Broken())
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_READABLE, loaded.problem)
    }

    @Test
    fun anEmptyStreamIsNotRecognized() {
        val loaded = TrustAnchors.load(java.io.ByteArrayInputStream(ByteArray(0)))
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, loaded.problem)
    }

    private class Recording(
        private val inner: InputStream,
    ) : InputStream() {
        var closes = 0

        override fun read(): Int = inner.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = inner.read(b, off, len)

        override fun close() {
            closes++
            inner.close()
        }
    }

    private class Broken : InputStream() {
        override fun read(): Int = throw IOException("broken")

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = throw IOException("broken")
    }
}
