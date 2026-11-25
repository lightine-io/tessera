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
import kotlin.test.assertIs
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
    fun heldRootsLetAMasterListsOwnSignatureChainToThem() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val bytes = masterList(keys, listOf(pki.csca), pki)
            val without = TrustAnchors.load(bytes)
            assertTrue(without.masterList?.outcomeOf(MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT) is CheckOutcome.NotRun)
            val held = (TrustAnchors.load(pki.csca).anchors)
            val with = TrustAnchors.load(bytes, held)
            assertEquals(CheckOutcome.Passed, with.masterList?.outcomeOf(MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT))
            // Held roots are not added to what is loaded.
            assertEquals(1, with.anchors.size)
            // Other content has no signature: held roots change nothing.
            assertEquals(1, TrustAnchors.load(pki.csca, held).anchors.size)
        }

    @Test
    fun mergeJoinsLoadsWithoutRepeatsInOrder() {
        val a = FakePki.cscaCertificate()
        val b = FakePki.documentSignerCertificate()
        val notACertificate = DerWriter.sequence(DerWriter.integer(1))
        val one = TrustAnchors.load(a + b)
        val two = TrustAnchors.load((pem(b) + pem(a)).encodeToByteArray())
        val three = TrustAnchors.load(a + notACertificate)
        val merged = TrustAnchors.merge(one, two, three)
        assertEquals(2, merged.anchors.size)
        assertTrue(merged.anchors[0].encodedBytes().contentEquals(a))
        assertTrue(merged.anchors[1].encodedBytes().contentEquals(b))
        assertEquals(TrustAnchorLoadKind.CERTIFICATE, merged.kind)
        assertEquals(1, merged.unreadable.size)
        assertEquals(2, (one + two).anchors.size)
        // One load alone is returned as it is.
        assertTrue(one === TrustAnchors.merge(one))
    }

    @Test
    fun mergeOfNothingUsableReportsAProblem() {
        val bad = TrustAnchors.load(ByteArray(0))
        val merged = TrustAnchors.merge(bad, bad)
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, merged.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, merged.problem)
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, TrustAnchors.merge().problem)
        // A good load joined with a bad one keeps the good anchors.
        assertEquals(1, (bad + TrustAnchors.load(FakePki.cscaCertificate())).anchors.size)
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
    fun aCertificateWithAnInvalidFieldIsUnreadableWithItsProblemAndOffset() {
        val a = FakePki.cscaCertificate()
        val badTime = FakePki.certificate(notBefore = DerWriter.utcTime("249901000000Z"))
        val expected = assertIs<TrustAnchorParse.Unreadable>(TrustAnchor.fromDer(badTime))
        for (loaded in listOf(TrustAnchors.load((pem(a) + pem(badTime)).encodeToByteArray()), TrustAnchors.load(a + badTime))) {
            assertEquals(1, loaded.anchors.size)
            val bad = loaded.unreadable.single()
            assertEquals(1, bad.position)
            assertEquals(TrustAnchorProblem.INVALID_CERTIFICATE_FIELD, bad.problem)
            assertEquals(expected.offset, bad.offset)
            assertTrue(bad.offset > 0)
            assertEquals("UnreadableTrustAnchor(position=1, problem=INVALID_CERTIFICATE_FIELD, offset=${bad.offset})", bad.toString())
        }
    }

    @Test
    fun mergeOfFailedLoadsKeepsTheFirstProblem() {
        val missing = trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_FOUND)
        val merged = TrustAnchors.merge(missing, TrustAnchors.load(ByteArray(0)))
        assertEquals(TrustAnchorLoadKind.UNRECOGNIZED, merged.kind)
        assertEquals(TrustAnchorLoadProblem.NOT_FOUND, merged.problem)
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
    fun contentWithMoreItemsThanAnyMasterListIsTooLarge() {
        // Tiny DER items (ASN.1 NULL) and empty PEM blocks: one past the cap each.
        val nulls = ByteArray((MAX_ITEMS + 1) * 2) { if (it % 2 == 0) 0x05 else 0x00 }
        assertEquals(TrustAnchorLoadProblem.TOO_LARGE, TrustAnchors.load(nulls).problem)
        val blocks = "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n".repeat(MAX_ITEMS + 1)
        assertEquals(TrustAnchorLoadProblem.TOO_LARGE, TrustAnchors.load(blocks.encodeToByteArray()).problem)
        // At the cap, the items are still read one by one.
        val atCap = TrustAnchors.load(ByteArray(MAX_ITEMS * 2) { if (it % 2 == 0) 0x05 else 0x00 })
        assertEquals(TrustAnchorLoadProblem.NOT_RECOGNIZED, atCap.problem)
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
