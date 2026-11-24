package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.fakechip.FakePki
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS file helper of TrustAnchors: a local path only, typed failures, never an exception. */
class TrustAnchorsFileTest {
    private fun pem(cert: ByteArray): String = "-----BEGIN CERTIFICATE-----\r\n" + Base64.encode(cert) + "\r\n-----END CERTIFICATE-----\r\n"

    @OptIn(ExperimentalForeignApi::class)
    private fun write(
        name: String,
        text: String,
    ): String {
        val path = NSTemporaryDirectory() + name
        val bytes = text.encodeToByteArray()
        val data = bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, contents = data, attributes = null))
        return path
    }

    @Test
    fun aLocalFileIsRead() {
        val path = write("tessera-trust-test.pem", pem(FakePki.cscaCertificate()))
        val loaded = TrustAnchors.load(path)
        assertEquals(TrustAnchorLoadKind.PEM_CERTIFICATES, loaded.kind)
        assertEquals(1, loaded.anchors.size)
        assertNull(loaded.problem)
    }

    @Test
    fun aMissingFileIsNotFound() {
        val loaded = TrustAnchors.load(NSTemporaryDirectory() + "no-such-file.pem")
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_FOUND, loaded.problem)
    }

    @Test
    fun aWebAddressIsNotALocalFile() {
        val loaded = TrustAnchors.load("https://example.org/anchors.pem")
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_A_LOCAL_FILE, loaded.problem)
        assertEquals(0, loaded.anchors.size)
    }

    @Test
    fun aFileLargerThanTheCapIsTooLarge() {
        val path = write("tessera-trust-test-small.pem", pem(FakePki.cscaCertificate()))
        val loaded = TrustAnchors.load(path, 3)
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.TOO_LARGE, loaded.problem)
    }
}
