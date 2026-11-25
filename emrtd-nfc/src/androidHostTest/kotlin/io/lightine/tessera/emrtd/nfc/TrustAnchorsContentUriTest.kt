package io.lightine.tessera.emrtd.nfc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.trust.TrustAnchorLoadKind
import io.lightine.tessera.emrtd.trust.TrustAnchorLoadProblem
import io.lightine.tessera.emrtd.trust.TrustAnchors
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A provider that fails the way a document provider can: no file, no permission, no descriptor, a crash. */
class FailingDocumentsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? =
        when (uri.lastPathSegment) {
            "missing" -> throw FileNotFoundException("no such document")
            "denied" -> throw SecurityException("no permission grant for the address")
            "died" -> throw IllegalStateException("the provider failed")
            else -> null // no descriptor: the platform answers with no stream
        }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0
}

/** TES-346: root certificates from a Storage Access Framework content address, every failure a typed problem. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrustAnchorsContentUriTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://com.example.documents/document/roots")

    private class Tracked(
        bytes: ByteArray,
    ) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        var closed = 0
        var reads = 0

        override fun read(): Int {
            reads++
            return inner.read()
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            reads++
            return inner.read(b, off, len)
        }

        override fun close() {
            closed++
        }
    }

    private fun serve(
        address: Uri,
        stream: InputStream,
    ) = shadowOf(context.contentResolver).registerInputStream(address, stream)

    @Test
    fun `a certificate behind a content address is read through the resolver and the stream is closed`() {
        val stream = Tracked(FakePki.cscaCertificate())
        serve(uri, stream)
        val loaded = TrustAnchors.load(context, uri)
        assertEquals(TrustAnchorLoadKind.CERTIFICATE, loaded.kind)
        assertEquals(1, loaded.anchors.size)
        assertNull(loaded.problem)
        assertEquals(1, stream.closed, "the stream is opened and closed here")
    }

    @Test
    fun `PEM text behind a content address is recognised from the bytes alone, with held roots allowed`() {
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.Mime.encode(FakePki.cscaCertificate()) + "\n-----END CERTIFICATE-----\n"
        serve(uri, Tracked(pem.encodeToByteArray()))
        val loaded = TrustAnchors.load(context, uri, emptyList())
        assertEquals(TrustAnchorLoadKind.PEM_CERTIFICATES, loaded.kind)
        assertEquals(1, loaded.anchors.size)
    }

    @Test
    fun `content that is not a certificate is not recognised, and the stream is still closed`() {
        val stream = Tracked("not a certificate".encodeToByteArray())
        serve(uri, stream)
        val loaded = TrustAnchors.load(context, uri)
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, loaded.problem)
        assertTrue(loaded.anchors.isEmpty())
        assertEquals(1, stream.closed)
    }

    @Test
    fun `a web address is not a local file and is never opened`() {
        for (address in listOf("http://example.org/roots.pem", "https://example.org/roots.pem", "HTTPS://example.org/roots.pem")) {
            val web = Uri.parse(address)
            val stream = Tracked(FakePki.cscaCertificate())
            serve(web, stream)
            val loaded = TrustAnchors.load(context, web)
            assertEquals(TrustAnchorLoadProblem.NOT_A_LOCAL_FILE, loaded.problem, address)
            assertEquals(0, stream.reads + stream.closed, "nothing was opened or read for $address")
        }
    }

    @Test
    fun `every way the platform can fail is a typed problem and never an exception`() {
        Robolectric.setupContentProvider(FailingDocumentsProvider::class.java, "com.example.failing")

        fun at(name: String) = Uri.parse("content://com.example.failing/$name")
        assertEquals(TrustAnchorLoadProblem.NOT_FOUND, TrustAnchors.load(context, at("missing")).problem)
        assertEquals(TrustAnchorLoadProblem.NOT_READABLE, TrustAnchors.load(context, at("denied")).problem, "no permission grant")
        assertEquals(TrustAnchorLoadProblem.NOT_READABLE, TrustAnchors.load(context, at("died")).problem, "a crashing provider")
        assertEquals(
            TrustAnchorLoadProblem.NOT_READABLE,
            TrustAnchors.load(context, at("nothing")).problem,
            "a provider with no descriptor",
        )
        val failing =
            object : InputStream() {
                var closed = false

                override fun read(): Int = throw IOException("the read failed")

                override fun close() {
                    closed = true
                }
            }
        serve(uri, failing)
        assertEquals(TrustAnchorLoadProblem.NOT_READABLE, TrustAnchors.load(context, uri).problem)
        assertTrue(failing.closed, "a failing stream is closed too")
    }

    @Test
    fun `content larger than the cap is too large`() {
        val stream = Tracked(ByteArray(100) { 1 })
        serve(uri, stream)
        val loaded = TrustAnchors.load(context, uri, 50L, emptyList())
        assertEquals(TrustAnchorLoadProblem.TOO_LARGE, loaded.problem)
        assertEquals(1, stream.closed)
    }
}
