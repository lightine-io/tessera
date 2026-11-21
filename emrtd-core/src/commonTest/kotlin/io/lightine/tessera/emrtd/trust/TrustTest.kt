package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoUnavailableException
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.pa.Pki
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The public trust package: roots from DER and master lists the app obtained, on synthetic Utopia certificates. */
class TrustTest {
    private fun anchor(der: ByteArray): TrustAnchor = assertIs<TrustAnchorParse.Parsed>(TrustAnchor.fromDer(der)).anchor

    private fun masterList(
        keys: TestKeys,
        listed: List<ByteArray>,
        signer: Pki,
        extraContent: ByteArray? = null,
        contentType: String = FakePki.OID_CSCA_MASTER_LIST,
    ): ByteArray {
        val content = extraContent ?: DerWriter.sequence(DerWriter.integer(0), DerWriter.set(*listed.toTypedArray()))
        return FakePki.signedData(
            FakePki.Signing(keys.signer, signer.dsKey, signer.dsAlgorithm),
            HashAlgorithm.SHA256,
            contentType,
            content,
            listOf(signer.ds, signer.csca),
            signer.dsSid,
        )
    }

    @Test
    fun aRootIsReadFromDer() {
        val a = anchor(FakePki.cscaCertificate())
        assertEquals("UT", a.subjectCountry)
        assertEquals("Utopia CSCA", a.subjectCommonName)
        assertEquals("2024-01-01T00:00:00Z", a.notBefore)
        assertEquals("2034-01-01T00:00:00Z", a.notAfter)
        assertEquals(20, a.subjectKeyIdentifier?.size)
        assertEquals("1.2.840.10045.2.1", a.publicKeyAlgorithmOid)
        assertEquals("1234", a.serialNumberHex)
        assertTrue(a.encodedBytes().contentEquals(FakePki.cscaCertificate()))
    }

    @Test
    fun bytesThatAreNotACertificateAreUnreadable() {
        val bad = assertIs<TrustAnchorParse.Unreadable>(TrustAnchor.fromDer(byteArrayOf(0x30, 0x05, 0x01)))
        assertEquals(TrustAnchorProblem.MALFORMED_ENCODING, bad.problem)
        val badTime = FakePki.certificate(notBefore = DerWriter.utcTime("249901000000Z"))
        assertEquals(
            TrustAnchorProblem.INVALID_CERTIFICATE_FIELD,
            assertIs<TrustAnchorParse.Unreadable>(TrustAnchor.fromDer(badTime)).problem,
        )
    }

    @Test
    fun aMasterListIsReadAndItsSignatureReportedAsFacts() =
        withKeys { keys ->
            // The list's signer is a document-signer-shaped certificate issued by the Utopia CSCA.
            val pki = Pki.rsa(keys)
            val other = FakePki.cscaCertificate()
            val file = masterList(keys, listOf(pki.csca, other, DerWriter.sequence(DerWriter.integer(1))), pki)

            val read = assertIs<MasterListRead.Read>(MasterListReader.read(file, listOf(anchor(pki.csca)))).masterList
            assertEquals(0, read.version)
            assertEquals(2, read.anchors.size)
            assertEquals(1, read.unreadableCertificateCount)
            assertEquals(MasterListCheckOutcome.Passed, read.outcomeOf(MasterListCheckKind.SIGNATURE_VERIFIES))
            assertEquals(MasterListCheckOutcome.Passed, read.outcomeOf(MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT))
            assertEquals(MasterListCheckOutcome.Passed, read.outcomeOf(MasterListCheckKind.SIGNER_CRITICAL_EXTENSIONS_RECOGNIZED))

            // The anchors read from the list work as roots for a chip's signer.
            assertTrue(read.anchors.any { it.encodedBytes().contentEquals(pki.csca) })
        }

    @Test
    fun aTamperedMasterListFailsItsSignatureAndNoRootsMeansNotRun() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val file = masterList(keys, listOf(pki.csca), pki)
            // Flip a byte inside the listed certificate's signature, which the message digest covers.
            val tampered = file.copyOf()
            val at = indexOf(tampered, pki.csca) + pki.csca.size - 2
            tampered[at] = (tampered[at] + 1).toByte()
            val read = assertIs<MasterListRead.Read>(MasterListReader.read(tampered)).masterList
            assertEquals(
                MasterListCheckOutcome.Failed(MasterListCheckReason.MESSAGE_DIGEST_DIFFERS),
                read.outcomeOf(MasterListCheckKind.SIGNATURE_VERIFIES),
            )
            assertEquals(
                MasterListCheckOutcome.NotRun(MasterListCheckReason.NO_ROOTS_SUPPLIED),
                read.outcomeOf(MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT),
            )
            // A root that does not match the signer.
            val stranger =
                Pki.csca(
                    keys.signer,
                    keys.rsaOther,
                    FakePki.rsaPkcs1(
                        HashAlgorithm.SHA256,
                    ),
                    FakePki.name("UU", "Elsewhere CSCA"),
                    ByteArray(20) {
                        0x33
                    },
                )
            val unmatched = assertIs<MasterListRead.Read>(MasterListReader.read(file, listOf(anchor(stranger)))).masterList
            assertEquals(
                MasterListCheckOutcome.Failed(MasterListCheckReason.NO_SUPPLIED_ROOT_MATCHES),
                unmatched.outcomeOf(MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT),
            )
        }

    @Test
    fun aSecurityObjectIsNotAMasterList() =
        withKeys { keys ->
            val pki = Pki.rsa(keys)
            val sod = pki.sod(mapOf(1 to DerWriter.tlv(0x61, byteArrayOf(1, 2, 3))))
            assertEquals(MasterListProblem.NOT_A_MASTER_LIST, assertIs<MasterListRead.Unreadable>(MasterListReader.read(sod)).problem)
            val badContent = masterList(keys, emptyList(), pki, extraContent = DerWriter.octetString(byteArrayOf(1)))
            assertEquals(
                MasterListProblem.MALFORMED_CONTENT,
                assertIs<MasterListRead.Unreadable>(MasterListReader.read(badContent)).problem,
            )
        }

    @Test
    fun garbageIsUnreadableNeverAnException() {
        assertEquals(
            MasterListProblem.MALFORMED_ENCODING,
            assertIs<MasterListRead.Unreadable>(MasterListReader.read(byteArrayOf(0x30, 0x7F))).problem,
        )
        val notSigned =
            DerWriter.sequence(
                DerWriter.oid("1.2.840.113549.1.7.1"),
                DerWriter.explicit(0, DerWriter.octetString(byteArrayOf(1))),
            )
        assertEquals(MasterListProblem.NOT_SIGNED_DATA, assertIs<MasterListRead.Unreadable>(MasterListReader.read(notSigned)).problem)
    }

    @Test
    fun withoutCryptoTheListIsStillReadAndItsChecksDoNotRun() {
        // A placeholder-signed list: runs on every platform, iOS included.
        val content = DerWriter.sequence(DerWriter.integer(0), DerWriter.set(FakePki.cscaCertificate()))
        val list =
            DerWriter.sequence(
                DerWriter.oid(FakePki.OID_SIGNED_DATA),
                DerWriter.explicit(
                    0,
                    DerWriter.sequence(
                        DerWriter.integer(3),
                        DerWriter.set(DerWriter.sequence(DerWriter.oid(FakePki.OID_SHA256))),
                        DerWriter.sequence(
                            DerWriter.oid(FakePki.OID_CSCA_MASTER_LIST),
                            DerWriter.explicit(0, DerWriter.octetString(content)),
                        ),
                        DerWriter.implicitConstructed(0, FakePki.documentSignerCertificate()),
                        DerWriter.set(
                            DerWriter.sequence(
                                DerWriter.integer(1),
                                FakePki.sidIssuerAndSerial(FakePki.name("UT", "Utopia CSCA"), 0x1234),
                                DerWriter.sequence(DerWriter.oid(FakePki.OID_SHA256)),
                                DerWriter.sequence(DerWriter.oid(FakePki.OID_ECDSA_SHA256)),
                                DerWriter.octetString(ByteArray(64) { 1 }),
                            ),
                        ),
                    ),
                ),
            )
        val read =
            assertIs<MasterListRead.Read>(
                MasterListReader.read(list, emptyList()) { throw ChipCryptoUnavailableException() },
            ).masterList
        assertEquals(1, read.anchors.size)
        assertEquals(
            MasterListCheckOutcome.NotRun(MasterListCheckReason.CRYPTO_UNAVAILABLE),
            read.outcomeOf(MasterListCheckKind.SIGNATURE_VERIFIES),
        )
        assertEquals(MasterListCheckOutcome.Passed, read.outcomeOf(MasterListCheckKind.SIGNER_CRITICAL_EXTENSIONS_RECOGNIZED))
    }

    @Test
    fun everyReasonASignedObjectCheckCanGiveHasAPublicName() {
        // MasterListReader maps internal reasons to public ones by name; these are the ones its checks can produce.
        val reachable =
            listOf(
                "SIGNATURE_DOES_NOT_VERIFY",
                "MESSAGE_DIGEST_DIFFERS",
                "MESSAGE_DIGEST_ATTRIBUTE_MISSING",
                "CONTENT_TYPE_ATTRIBUTE_DIFFERS",
                "KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM",
                "NO_SUPPLIED_ROOT_MATCHES",
                "ISSUER_NAME_DIFFERS",
                "UNRECOGNIZED_CRITICAL_EXTENSION",
                "NO_SIGNER_CERTIFICATE",
                "UNREADABLE_SIGNER_CERTIFICATE",
                "NO_ROOTS_SUPPLIED",
                "UNSUPPORTED_ALGORITHM",
                "UNREADABLE_KEY",
                "CRYPTO_UNAVAILABLE",
            )
        val names = MasterListCheckReason.entries.map { it.name }
        for (r in reachable) {
            assertTrue(r in names, r)
            assertTrue(
                r in io.lightine.tessera.emrtd.internal.pa.PaFailure.entries
                    .map { it.name } +
                    io.lightine.tessera.emrtd.internal.pa.PaNotRun.entries
                        .map { it.name },
                r,
            )
        }
    }

    private fun indexOf(
        hay: ByteArray,
        needle: ByteArray,
    ): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        error("not found")
    }
}
