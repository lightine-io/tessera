package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.pa.Pki
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What TrustAnchors.load reads from content the app hands it, on synthetic Utopia certificates. */
class TrustAnchorsTest {
    private fun masterList(
        keys: io.lightine.tessera.emrtd.internal.pa.TestKeys,
        listed: List<ByteArray>,
        signer: Pki,
    ): ByteArray {
        val content = DerWriter.sequence(DerWriter.integer(0), DerWriter.set(*listed.toTypedArray()))
        return FakePki.signedData(
            FakePki.Signing(keys.signer, signer.dsKey, signer.dsAlgorithm),
            HashAlgorithm.SHA256,
            FakePki.OID_CSCA_MASTER_LIST,
            content,
            listOf(signer.ds, signer.csca),
            signer.dsSid,
        )
    }

    /** PEM text for [cert], with [newLine] inside the block; well formed for any line ending. */
    private fun pem(
        cert: ByteArray,
        newLine: String = "\r\n",
    ): String = "-----BEGIN CERTIFICATE-----$newLine" + Base64.encode(cert) + "$newLine-----END CERTIFICATE-----$newLine"

    @Test
    fun aMasterListIsLoadedWithItsChecksRidingAlong() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val other = FakePki.cscaCertificate()
            val notACertificate = DerWriter.sequence(DerWriter.integer(1))
            val listed = listOf(pki.csca, pki.csca, other, notACertificate)
            val loaded = TrustAnchors.load(masterList(keys, listed, pki))

            assertEquals(TrustAnchorLoadKind.MASTER_LIST, loaded.kind)
            assertNull(loaded.problem)
            // The three readable certificates, with the duplicate listed twice kept once.
            assertEquals(2, loaded.anchors.size)
            // The reader's own list keeps every readable certificate, duplicates included.
            assertEquals(3, loaded.masterList?.anchors?.size)
            // The list's own report of its unreadable entry, with no per-item entries here.
            assertEquals(1, loaded.masterList?.unreadableCertificateCount)
            assertEquals(0, loaded.unreadable.size)
            assertEquals(0, loaded.masterList?.version)
            assertTrue(loaded.anchors.any { it.encodedBytes().contentEquals(pki.csca) })
            assertTrue(loaded.anchors.any { it.encodedBytes().contentEquals(other) })
            assertEquals(CheckOutcome.Passed, loaded.masterList?.outcomeOf(MasterListCheckKind.SIGNATURE_VERIFIES))
        }

    @Test
    fun aDerCertificateIsLoaded() {
        val loaded = TrustAnchors.load(FakePki.cscaCertificate())
        assertEquals(TrustAnchorLoadKind.CERTIFICATE, loaded.kind)
        assertEquals(1, loaded.anchors.size)
        assertEquals("UT", loaded.anchors[0].subjectCountry)
        assertEquals(0, loaded.unreadable.size)
        assertNull(loaded.masterList)
        assertNull(loaded.problem)
    }

    @Test
    fun pemHoldingOneCertificateIsLoaded() {
        val loaded = TrustAnchors.load(pem(FakePki.cscaCertificate()).encodeToByteArray())
        assertEquals(TrustAnchorLoadKind.PEM_CERTIFICATES, loaded.kind)
        assertEquals(1, loaded.anchors.size)
        assertTrue(loaded.anchors[0].encodedBytes().contentEquals(FakePki.cscaCertificate()))
        assertNull(loaded.problem)
    }

    @Test
    fun pemHoldingThreeCertificatesKeepsTheOriginalOrder() {
        val a = FakePki.cscaCertificate()
        val b = FakePki.documentSignerCertificate()
        val c = FakePki.certificate(serial = 0x7777)
        val text =
            "A bundle of roots.\n" + pem(a) + "kept from an old file\r\n" +
                pem(b, "\n") + pem(c) + "nothing below"
        val loaded = TrustAnchors.load(text.encodeToByteArray())
        assertEquals(TrustAnchorLoadKind.PEM_CERTIFICATES, loaded.kind)
        assertEquals(3, loaded.anchors.size)
        assertTrue(loaded.anchors[0].encodedBytes().contentEquals(a))
        assertTrue(loaded.anchors[1].encodedBytes().contentEquals(b))
        assertTrue(loaded.anchors[2].encodedBytes().contentEquals(c))
        assertEquals(0, loaded.unreadable.size)
    }

    @Test
    fun repeatedCertificatesAreKeptOnce() {
        val a = FakePki.cscaCertificate()
        val b = FakePki.documentSignerCertificate()
        val fromPem = TrustAnchors.load((pem(a) + pem(b) + pem(a)).encodeToByteArray())
        assertEquals(2, fromPem.anchors.size)
        val fromDer = TrustAnchors.load(a + b + a)
        assertEquals(TrustAnchorLoadKind.CERTIFICATE, fromDer.kind)
        assertEquals(2, fromDer.anchors.size)
        assertTrue(fromDer.anchors[0].encodedBytes().contentEquals(a))
        assertTrue(fromDer.anchors[1].encodedBytes().contentEquals(b))
    }

    @Test
    fun oneUnreadableCertificateAmongGoodOnesIsReportedByPosition() {
        val a = FakePki.cscaCertificate()
        val c = FakePki.documentSignerCertificate()
        val notACertificate = DerWriter.sequence(DerWriter.integer(1))
        val fromPem =
            TrustAnchors.load((pem(a) + pem(notACertificate) + pem(c)).encodeToByteArray())
        assertEquals(TrustAnchorLoadKind.PEM_CERTIFICATES, fromPem.kind)
        assertEquals(2, fromPem.anchors.size)
        assertEquals(1, fromPem.unreadable.size)
        assertEquals(1, fromPem.unreadable[0].position)
        assertEquals(TrustAnchorProblem.MALFORMED_ENCODING, fromPem.unreadable[0].problem)
        val fromDer = TrustAnchors.load(a + notACertificate + c)
        assertEquals(TrustAnchorLoadKind.CERTIFICATE, fromDer.kind)
        assertEquals(2, fromDer.anchors.size)
        assertEquals(1, fromDer.unreadable.size)
        assertEquals(1, fromDer.unreadable[0].position)
        assertEquals(TrustAnchorProblem.MALFORMED_ENCODING, fromDer.unreadable[0].problem)
    }

    @Test
    fun aPemBlockThatCannotBeDecodedIsUnreadable() {
        val a = FakePki.cscaCertificate()
        val brokenBase64 = "-----BEGIN CERTIFICATE-----\r\n@@@@\r\n-----END CERTIFICATE-----\r\n"
        val withNoEnd = "-----BEGIN CERTIFICATE-----\n" + Base64.encode(a)
        val loaded = TrustAnchors.load((pem(a) + brokenBase64 + withNoEnd).encodeToByteArray())
        assertEquals(TrustAnchorLoadKind.PEM_CERTIFICATES, loaded.kind)
        assertEquals(1, loaded.anchors.size)
        assertEquals(2, loaded.unreadable.size)
        assertEquals(1, loaded.unreadable[0].position)
        assertEquals(TrustAnchorProblem.MALFORMED_ENCODING, loaded.unreadable[0].problem)
        assertEquals(0, loaded.unreadable[0].offset)
        assertEquals(2, loaded.unreadable[1].position)
    }

    @Test
    fun emptyContentIsNotRecognized() {
        val loaded = TrustAnchors.load(ByteArray(0))
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, loaded.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, loaded.problem)
        assertEquals(0, loaded.anchors.size)
        assertEquals(0, loaded.unreadable.size)
        assertNull(loaded.masterList)
    }

    @Test
    fun randomBytesAreNotRecognized() {
        val filled = TrustAnchors.load(ByteArray(48) { (it * 13 + 5).toByte() })
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, filled.problem)
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, filled.kind)
        val cutShort = TrustAnchors.load(byteArrayOf(0x30, 0x7F))
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, cutShort.problem)
        // A CMS SignedData that is not a master list is not recognized either.
        val sod = FakePki.sod(FakePki.ldsSecurityObject(version = 0, groups = listOf(1 to FakePki.fakeHash(1))))
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, TrustAnchors.load(sod).problem)
    }

    @Test
    fun toStringPrintsTheKindAndCountsOnly() {
        val a = FakePki.cscaCertificate()
        val b = FakePki.documentSignerCertificate()
        val text = TrustAnchors.load((pem(a) + pem(b)).encodeToByteArray()).toString()
        assertTrue(text.contains("kind=PEM_CERTIFICATES"))
        assertTrue(text.contains("anchors=2"))
        assertTrue(text.contains("unreadable=0"))
        assertFalse(text.contains("UT"))
        assertFalse(text.contains("Utopia"))
        val failed = TrustAnchors.load(ByteArray(0)).toString()
        assertTrue(failed.contains("kind=UNRECOGNIZED"))
        assertTrue(failed.contains("problem=NOT_RECOGNIZED"))
        assertFalse(failed.contains("Utopia"))
    }
}
