package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.cms.BerNormalizer
import io.lightine.tessera.emrtd.trust.TrustAnchorProblem
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Synthetic objects from FakePki. Signatures and keys inside are placeholders; nothing is verified.
@OptIn(ExperimentalTesseraChipApi::class)
class SecurityObjectViewTest {
    private val groups = listOf(1 to FakePki.fakeHash(1), 2 to FakePki.fakeHash(2), 14 to FakePki.fakeHash(14))

    private fun parsed(file: ByteArray): SecurityObjectView {
        val r = SecurityObjectView.parse(file)
        return assertIs<SecurityObjectParse.Parsed>(r).view
    }

    @Test
    fun v1ObjectParses() {
        val view =
            parsed(
                FakePki.sod(
                    FakePki.ldsSecurityObject(1, groups = groups),
                    certificates = listOf(FakePki.documentSignerCertificate()),
                ),
            )
        assertEquals(1, view.version)
        assertEquals(3, view.signedDataVersion)
        assertEquals("SHA-256", view.hashAlgorithmName)
        assertFalse(view.hashAlgorithmHasParameters)
        assertEquals(listOf(1, 2, 14), view.dataGroupHashes.map { it.number })
        assertContentEquals(FakePki.fakeHash(14), view.hashOf(14))
        assertContentEquals(FakePki.fakeHash(14), view.dataGroupHashes.last().hash)
        assertNull(view.hashOf(3))
        assertEquals("0108", view.ldsVersion)
        assertEquals("040000", view.unicodeVersion)
        assertFalse(view.wasBer)
        assertEquals(1, view.signers.size)
        val signer = view.signers.single()
        assertEquals(1, signer.version)
        assertEquals("1234", signer.issuerSerialNumberHex)
        assertTrue(signer.hasSignedAttributes)
        assertEquals(32, signer.messageDigest?.size)
        assertEquals(1, view.certificates.size)
        assertEquals(0, view.unreadableCertificateCount)
        val cert = view.certificates.single()
        assertEquals(setOf("digitalSignature"), cert.keyUsage)
        assertEquals(listOf("P"), cert.documentTypes)
        assertEquals("UT", cert.subjectCountry)
        assertTrue(cert.unrecognizedCriticalExtensions.isEmpty())
    }

    @Test
    fun v0ObjectHasNoVersionInfo() {
        val view = parsed(FakePki.sod(FakePki.ldsSecurityObject(0, groups = groups)))
        assertEquals(0, view.version)
        assertNull(view.ldsVersion)
        assertNull(view.unicodeVersion)
        assertTrue(view.certificates.isEmpty()) // Appendix D: the certificate is optional in V0
    }

    @Test
    fun digestParametersAbsentAndNullBothParse() {
        for (withNull in listOf(false, true)) {
            val view =
                parsed(
                    FakePki.sod(
                        FakePki.ldsSecurityObject(1, hashParametersNull = withNull, groups = groups),
                        digestParametersNull = withNull,
                    ),
                )
            assertEquals("SHA-256", view.hashAlgorithmName)
            assertEquals(withNull, view.hashAlgorithmHasParameters)
            assertEquals(FakePki.OID_SHA256, view.signers.single().digestAlgorithmOid)
        }
    }

    @Test
    fun otherHashAlgorithmKeepsItsOid() {
        val view = parsed(FakePki.sod(FakePki.ldsSecurityObject(0, hashOid = "1.2.3.4", groups = groups)))
        assertEquals("1.2.3.4", view.hashAlgorithmOid)
        assertNull(view.hashAlgorithmName)
        val sha1 =
            parsed(
                FakePki.sod(
                    FakePki.ldsSecurityObject(
                        0,
                        hashOid = FakePki.OID_SHA1,
                        groups =
                            groups.map {
                                it.first to
                                    FakePki.fakeHash(it.first, 20)
                            },
                    ),
                ),
            )
        assertEquals("SHA-1", sha1.hashAlgorithmName)
    }

    @Test
    fun signerNamedByKeyIdentifierParses() {
        val view = parsed(FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups), signerBySubjectKeyId = true))
        val signer = view.signers.single()
        assertEquals(3, signer.version)
        assertNull(signer.issuerSerialNumberHex)
        assertEquals(20, signer.subjectKeyIdentifier?.size)
    }

    @Test
    fun fileWithoutTheFileTagParses() {
        val view = parsed(FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups), fileTag = false))
        assertEquals(3, view.dataGroupHashes.size)
    }

    @Test
    fun berWithIndefiniteLengthsParsesToTheSameValues() {
        val lds = FakePki.ldsSecurityObject(1, groups = groups)
        val der = parsed(FakePki.sod(lds, certificates = listOf(FakePki.documentSignerCertificate())))
        val ber = parsed(FakePki.sod(lds, certificates = listOf(FakePki.documentSignerCertificate()), indefinite = true))
        assertFalse(der.wasBer)
        assertTrue(ber.wasBer)
        assertEquals(der.dataGroupHashes.map { it.number }, ber.dataGroupHashes.map { it.number })
        assertContentEquals(der.hashOf(2), ber.hashOf(2))
        // The two OCTET STRING segments are joined: the signed content is the original bytes.
        assertContentEquals(lds, ber.contentBytes())
        assertEquals(1, ber.certificates.size)
    }

    @Test
    fun normalizerLeavesDerUnchangedAndRewritesIndefinite() {
        val der = FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups))
        assertFalse(BerNormalizer.normalize(der).changed)
        val ber = DerWriter.indefinite(0x30, DerWriter.integer(5), DerWriter.indefinite(0xA0, DerWriter.nullValue()))
        val n = BerNormalizer.normalize(ber)
        assertTrue(n.changed)
        assertContentEquals(DerWriter.sequence(DerWriter.integer(5), DerWriter.explicit(0, DerWriter.nullValue())), n.der)
    }

    @Test
    fun unreadableCertificateIsCountedNotFatal() {
        val view =
            parsed(
                FakePki.sod(
                    FakePki.ldsSecurityObject(1, groups = groups),
                    certificates = listOf(DerWriter.sequence(DerWriter.integer(1)), FakePki.cscaCertificate()),
                ),
            )
        assertEquals(1, view.unreadableCertificateCount)
        assertEquals(1, view.certificates.size)
        assertEquals(true, view.certificates.single().isCa)
        val unreadable = view.unreadableCertificates.single()
        assertEquals(0, unreadable.position)
        assertEquals(DerWriter.sequence(DerWriter.integer(1)).size, unreadable.length)
    }

    @Test
    fun eachUnreadableCertificateNamesItsPositionLengthProblemAndOffset() {
        val badField = FakePki.certificate(notBefore = DerWriter.utcTime("249901000000Z"))
        // Well-formed DER that is not a certificate: a sequence holding one integer.
        val badEncoding = DerWriter.sequence(DerWriter.integer(1))
        val view =
            parsed(
                FakePki.sod(
                    FakePki.ldsSecurityObject(1, groups = groups),
                    certificates = listOf(FakePki.cscaCertificate(), badField, badEncoding),
                ),
            )
        assertEquals(1, view.certificates.size)
        val (field, encoding) = view.unreadableCertificates
        assertEquals(1, field.position)
        assertEquals(badField.size, field.length)
        assertEquals(TrustAnchorProblem.INVALID_CERTIFICATE_FIELD, field.problem)
        assertTrue(field.offset > 0)
        assertEquals(2, encoding.position)
        assertEquals(badEncoding.size, encoding.length)
        assertEquals(TrustAnchorProblem.MALFORMED_ENCODING, encoding.problem)
        assertEquals(
            "UnreadableSignerCertificate(position=1, length=${badField.size}, problem=INVALID_CERTIFICATE_FIELD, offset=${field.offset})",
            field.toString(),
        )
    }

    @Test
    fun unknownCriticalExtensionInStoredCertificateIsSurfaced() {
        val cert = FakePki.documentSignerCertificate(extra = listOf(FakePki.Ext("1.2.3.4.5.6", true, DerWriter.nullValue())))
        val view = parsed(FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups), certificates = listOf(cert)))
        assertEquals(listOf("1.2.3.4.5.6"), view.certificates.single().unrecognizedCriticalExtensions)
        val unknown =
            view.certificates
                .single()
                .extensions
                .single { it.oid == "1.2.3.4.5.6" }
        assertTrue(unknown.critical)
        assertFalse(unknown.recognized)
        assertTrue(
            view.certificates
                .single()
                .extensions
                .any { it.recognized },
        )
    }

    @Test
    fun notSignedDataAndWrongContentAreTyped() {
        val notSigned =
            DerWriter.tlv(
                0x77,
                DerWriter.sequence(DerWriter.oid("1.2.840.113549.1.7.1"), DerWriter.explicit(0, DerWriter.nullValue())),
            )
        assertEquals(
            SecurityObjectProblem.NOT_SIGNED_DATA,
            assertIs<SecurityObjectParse.Unreadable>(SecurityObjectView.parse(notSigned)).problem,
        )

        val sod = FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups))
        val wrong = sod.copyOf()
        // The content type OID ends ... 01 01 01 (2.23.136.1.1.1): turn its last arc into 2.
        val oidBytes = DerWriter.oid(FakePki.OID_LDS_SECURITY_OBJECT)
        val at = (0..wrong.size - oidBytes.size).first { i -> oidBytes.indices.all { wrong[i + it] == oidBytes[it] } }
        wrong[at + oidBytes.size - 1] = 2
        assertEquals(
            SecurityObjectProblem.NOT_A_SECURITY_OBJECT,
            assertIs<SecurityObjectParse.Unreadable>(SecurityObjectView.parse(wrong)).problem,
        )
    }

    @Test
    fun hostileBytesFailTypedNeverThrow() {
        val sod =
            FakePki.sod(
                FakePki.ldsSecurityObject(1, groups = groups),
                certificates = listOf(FakePki.documentSignerCertificate()),
                indefinite = true,
            )
        for (n in sod.indices) assertIs<SecurityObjectParse.Unreadable>(SecurityObjectView.parse(sod.copyOf(n)))
        assertIs<SecurityObjectParse.Unreadable>(SecurityObjectView.parse(ByteArray(0)))
        assertIs<SecurityObjectParse.Unreadable>(
            SecurityObjectView.parse(byteArrayOf(0x30, 0x84.toByte(), 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())),
        )
        // Deep nesting: a few hundred indefinite SEQUENCEs.
        val deep = ByteArray(600) { if (it % 2 == 0) 0x30 else 0x80.toByte() }
        assertIs<SecurityObjectParse.Unreadable>(SecurityObjectView.parse(deep))
    }

    @Test
    fun everySingleByteChangeEndsTypedOrParsed() {
        val sod = FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups), certificates = listOf(FakePki.documentSignerCertificate()))
        for (i in sod.indices) {
            val copy = sod.copyOf()
            copy[i] = (copy[i].toInt() xor 0x5A).toByte()
            SecurityObjectView.parse(copy) // must not throw
        }
    }
}
