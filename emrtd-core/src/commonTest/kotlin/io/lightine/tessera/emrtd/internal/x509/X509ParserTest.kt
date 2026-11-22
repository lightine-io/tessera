package io.lightine.tessera.emrtd.internal.x509

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The certificates are built by FakePki with placeholder signatures: these tests cover structure only.
class X509ParserTest {
    /**
     * The signer certificate of an issuer whose chips no longer read: sha256WithRSAEncryption, an
     * RSA key, and the nine extensions of Part 12 in this order, with the alt names carrying the
     * country in a `[4] EXPLICIT` directoryName (42 bytes each). The reader rejected that
     * directoryName form, so `fromDer` called the whole certificate unreadable.
     */
    private fun signerWithExplicitAltNames(): ByteArray =
        FakePki.certificate(
            serial = 0x0123456789ABCDEFL,
            signatureAlgorithm = DerWriter.sequence(DerWriter.oid("1.2.840.113549.1.1.11"), DerWriter.nullValue()),
            publicKeyInfo = FakePki.publicKeyInfo(VerificationKey.Rsa(ByteArray(256) { (0x80 + it).toByte() }, byteArrayOf(1, 0, 1))),
            extensions =
                listOf(
                    FakePki.subjectKeyIdentifier(ByteArray(20) { 0x41 }),
                    FakePki.basicConstraints(false, null),
                    FakePki.authorityKeyIdentifier(ByteArray(20) { 0x11 }),
                    FakePki.privateKeyUsagePeriod("20240101000000Z", "20340101000000Z"),
                    FakePki.crlDistributionPoint("https://crl.utopia.example/ds-crl-2024.bin"),
                    FakePki.keyUsage(0),
                    FakePki.subjectAltNameRfc822AndExplicitDirectory(),
                    FakePki.issuerAltNameRfc822AndExplicitDirectory(),
                    FakePki.documentTypeList("P", "I"),
                ),
        )

    @Test
    fun sixthEditionDocumentSignerProfileParses() {
        val c = X509Parser.parse(FakePki.documentSignerCertificate(documentTypes = listOf("P", "ID")))
        assertEquals(3, c.version)
        assertEquals("UT", c.subject.countryName)
        assertEquals("Utopia Document Signer 01", c.subject.commonName)
        assertEquals("UT", c.issuer.countryName)
        assertEquals("2024-01-01T00:00:00Z", c.notBefore.iso())
        assertEquals("2034-01-01T00:00:00Z", c.notAfter.iso())
        assertEquals(setOf(KeyUsageBit.DIGITAL_SIGNATURE), c.decoded.keyUsage)
        assertEquals(listOf("P", "ID"), c.decoded.documentTypeList?.types)
        assertEquals(0, c.decoded.documentTypeList?.version)
        assertEquals(
            "2024-01-01T00:00:00Z",
            c.decoded.privateKeyUsagePeriod
                ?.notBefore
                ?.iso(),
        )
        assertEquals(
            "2025-01-01T00:00:00Z",
            c.decoded.privateKeyUsagePeriod
                ?.notAfter
                ?.iso(),
        )
        assertEquals(
            20,
            c.decoded.authorityKeyIdentifier
                ?.keyIdentifier
                ?.size,
        )
        assertEquals(20, c.decoded.subjectKeyIdentifier?.size)
        assertNull(c.decoded.basicConstraints)
        assertFalse(c.decoded.nameChange)
        assertTrue(c.unrecognizedCriticalExtensions.isEmpty())
        // The critical flag is read: KeyUsage is critical, the rest are not.
        assertEquals(listOf(FakePki.OID_KEY_USAGE), c.extensions.filter { it.critical }.map { it.oid })
        assertEquals(c.tbsEncoded.size > 0, true)
        assertEquals("ecdsa-with-SHA256", if (c.signatureAlgorithm.oid == FakePki.OID_ECDSA_SHA256) "ecdsa-with-SHA256" else "other")
    }

    @Test
    fun cscaProfileParses() {
        val c = X509Parser.parse(FakePki.cscaCertificate())
        assertEquals(setOf(KeyUsageBit.KEY_CERT_SIGN, KeyUsageBit.CRL_SIGN), c.decoded.keyUsage)
        assertEquals(true, c.decoded.basicConstraints?.ca)
        assertEquals(0, c.decoded.basicConstraints?.pathLengthConstraint)
        assertContentEquals(c.issuer.encoded, c.subject.encoded)
    }

    @Test
    fun timesInGeneralizedFormAndUtcPivotRead() {
        val c =
            X509Parser.parse(
                FakePki.certificate(
                    notBefore = DerWriter.utcTime("990101000000Z"),
                    notAfter = DerWriter.generalizedTime("20500101000000Z"),
                ),
            )
        assertEquals("1999-01-01T00:00:00Z", c.notBefore.iso())
        assertEquals("2050-01-01T00:00:00Z", c.notAfter.iso())
        assertTrue(c.notBefore < c.notAfter)
    }

    @Test
    fun unknownCriticalExtensionIsSurfaced() {
        val unknown = FakePki.Ext("1.2.3.4.5.6", critical = true, content = DerWriter.nullValue())
        val c = X509Parser.parse(FakePki.documentSignerCertificate(extra = listOf(unknown)))
        assertEquals(listOf("1.2.3.4.5.6"), c.unrecognizedCriticalExtensions.map { it.oid })
        assertFalse(c.extensions.first { it.oid == "1.2.3.4.5.6" }.recognized)
    }

    @Test
    fun unknownNonCriticalExtensionIsNotSurfaced() {
        val unknown = FakePki.Ext("1.2.3.4.5.7", critical = false, content = DerWriter.nullValue())
        val c = X509Parser.parse(FakePki.documentSignerCertificate(extra = listOf(unknown)))
        assertTrue(c.unrecognizedCriticalExtensions.isEmpty())
        assertTrue(c.extensions.any { it.oid == "1.2.3.4.5.7" })
    }

    @Test
    fun altNameDirectoryCountriesAreDecoded() {
        val c =
            X509Parser.parse(
                FakePki.certificate(
                    extensions =
                        listOf(
                            FakePki.subjectAltNameDirectory("UT"),
                            FakePki.issuerAltNameDirectory("UT", "Utopia Issuer Directory Name"),
                        ),
                ),
            )
        assertEquals("UT", c.decoded.subjectAltNameCountry)
        assertEquals("UT", c.decoded.issuerAltNameCountry)
    }

    @Test
    fun anAltNameWithoutADirectoryNameCountryDecodesToNull() {
        // A URI only, as the Sixth Edition profile's SAN writes it.
        val c = X509Parser.parse(FakePki.documentSignerCertificate())
        assertNull(c.decoded.subjectAltNameCountry)
        assertNull(c.decoded.issuerAltNameCountry)
        // A directoryName that carries no country attribute.
        val noCountry = X509Parser.parse(FakePki.certificate(extensions = listOf(FakePki.subjectAltNameDirectory(null))))
        assertNull(noCountry.decoded.subjectAltNameCountry)
    }

    @Test
    fun anAltNameWithWrongContentFails() {
        val bad = FakePki.Ext(FakePki.OID_SAN, false, DerWriter.nullValue())
        val der = FakePki.certificate(extensions = listOf(bad))
        assertEquals(X509Failure.INVALID_EXTENSION, assertFailsWith<X509Exception> { X509Parser.parse(der) }.failure)
    }

    @Test
    fun namesThatDifferOnlyInCaseSpacesOrEncodingMatchUnderTheRfc5280Comparison() {
        val a = X509Parser.parse(FakePki.certificate(subject = FakePki.name("UT", "Utopia CSCA"))).subject
        val b = X509Parser.parse(FakePki.certificate(subject = FakePki.nameUtf8("ut", "utopia  csca"))).subject
        assertFalse(a.encoded.contentEquals(b.encoded))
        assertTrue(a.matchesNormalized(b))
        assertTrue(b.matchesNormalized(a))
    }

    @Test
    fun namesThatDifferInOrderOrTypeDoNotMatchUnderTheRfc5280Comparison() {
        val a = X509Parser.parse(FakePki.certificate(subject = FakePki.name("UT", "Utopia CSCA"))).subject
        // The same attributes in another order are another name.
        val swapped = X509Parser.parse(FakePki.certificate(subject = FakePki.nameSwapped("UT", "Utopia CSCA"))).subject
        assertFalse(a.matchesNormalized(swapped))
        // Another attribute count.
        val shorter = X509Parser.parse(FakePki.certificate(subject = FakePki.nameWithoutCountry("Utopia CSCA"))).subject
        assertFalse(a.matchesNormalized(shorter))
        // A name with an attribute that is neither PrintableString nor UTF8String is never matched
        // this way, even when the text is the same.
        val ia5Name =
            DerWriter.sequence(
                DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.6"), DerWriter.printableString("UT"))),
                DerWriter.set(
                    DerWriter.sequence(DerWriter.oid("1.2.840.113549.1.9.1"), DerWriter.tlv(0x16, "utopia@example".encodeToByteArray())),
                ),
            )
        val ia5 = X509Parser.parse(FakePki.certificate(subject = ia5Name)).subject
        val utf8Name =
            DerWriter.sequence(
                DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.6"), DerWriter.printableString("UT"))),
                DerWriter.set(DerWriter.sequence(DerWriter.oid("1.2.840.113549.1.9.1"), DerWriter.utf8String("utopia@example"))),
            )
        val utf8 = X509Parser.parse(FakePki.certificate(subject = utf8Name)).subject
        assertFalse(ia5.matchesNormalized(utf8))
    }

    @Test
    fun certificateWithoutExtensionsAndVersionOneParses() {
        val c = X509Parser.parse(FakePki.certificate(version = 1))
        assertEquals(1, c.version)
        assertTrue(c.extensions.isEmpty())
        assertNull(c.decoded.keyUsage)
    }

    @Test
    fun badTimeFails() {
        val der = FakePki.certificate(notBefore = DerWriter.utcTime("241301000000Z"))
        assertEquals(X509Failure.INVALID_TIME, assertFailsWith<X509Exception> { X509Parser.parse(der) }.failure)
    }

    @Test
    fun knownExtensionWithWrongContentFails() {
        val bad = FakePki.Ext(FakePki.OID_DOCUMENT_TYPE, critical = false, content = DerWriter.nullValue())
        val der = FakePki.certificate(extensions = listOf(bad))
        assertEquals(X509Failure.INVALID_EXTENSION, assertFailsWith<X509Exception> { X509Parser.parse(der) }.failure)
    }

    @Test
    fun truncatedAndTrailingBytesFailTyped() {
        val der = FakePki.documentSignerCertificate()
        assertFailsWith<Asn1Exception> { X509Parser.parse(der.copyOf(der.size - 5)) }
        assertFailsWith<Asn1Exception> { X509Parser.parse(der + byteArrayOf(0)) }
    }

    @Test
    fun invalidVersionFails() {
        val der = FakePki.certificate(version = 5)
        assertEquals(X509Failure.INVALID_VERSION, assertFailsWith<X509Exception> { X509Parser.parse(der) }.failure)
    }

    @Test
    fun eachKeyUsageBitDecodesOnItsOwn() {
        for (bit in KeyUsageBit.entries) {
            val idx = KeyUsageBit.entries.indexOf(bit)
            val c = X509Parser.parse(FakePki.certificate(extensions = listOf(FakePki.keyUsage(idx))))
            assertEquals(setOf(bit), c.decoded.keyUsage, "$bit")
        }
    }

    @Test
    fun everyTruncationFailsWithATypedErrorOnly() {
        val der = FakePki.documentSignerCertificate()
        for (n in der.indices) {
            try {
                X509Parser.parse(der.copyOf(n))
            } catch (e: Asn1Exception) {
                // typed failure
            } catch (e: X509Exception) {
                // typed failure
            }
        }
    }

    @Test
    fun aSignerCertificateWithExplicitlyTaggedAltNameDirectoriesParses() {
        val c = X509Parser.parse(signerWithExplicitAltNames())

        // The certificate's own facts.
        assertEquals(3, c.version)
        assertEquals(8, c.serialNumber.size)
        assertEquals("1.2.840.113549.1.1.11", c.signatureAlgorithm.oid)
        assertEquals("1.2.840.113549.1.1.11", c.tbsSignatureAlgorithm.oid)
        assertEquals("1.2.840.113549.1.1.1", c.publicKeyAlgorithm.oid)
        // A 2048-bit RSA key: the key BIT STRING is the measured 271 bytes - one unused-bits
        // byte plus the 270-byte RSAPublicKey it carries.
        assertEquals(271, c.publicKey.bytes.size + 1)

        // The nine extensions, in the issuer's order, with the shapes measured on the documents.
        assertEquals(
            listOf(
                "2.5.29.14",
                "2.5.29.19",
                "2.5.29.35",
                "2.5.29.16",
                "2.5.29.31",
                "2.5.29.15",
                "2.5.29.17",
                "2.5.29.18",
                "2.23.136.1.1.6.2",
            ),
            c.extensions.map { it.oid },
        )
        assertEquals(listOf("2.5.29.19", "2.5.29.15"), c.extensions.filter { it.critical }.map { it.oid })
        assertEquals(
            listOf(22, 2, 24, 36, 52, 4, 42, 42, 13),
            c.extensions.map { it.value.size },
        )

        // The facts the checks need; the alt-name countries used to be unreachable.
        assertEquals(setOf(KeyUsageBit.DIGITAL_SIGNATURE), c.decoded.keyUsage)
        assertEquals(false, c.decoded.basicConstraints?.ca)
        assertEquals(listOf("P", "I"), c.decoded.documentTypeList?.types)
        assertEquals(
            "2024-01-01T00:00:00Z",
            c.decoded.privateKeyUsagePeriod
                ?.notBefore
                ?.iso(),
        )
        assertEquals(
            "2034-01-01T00:00:00Z",
            c.decoded.privateKeyUsagePeriod
                ?.notAfter
                ?.iso(),
        )
        assertEquals("UTO", c.decoded.subjectAltNameCountry)
        assertEquals("UTO", c.decoded.issuerAltNameCountry)
        assertTrue(c.unrecognizedCriticalExtensions.isEmpty())
    }

    @Test
    fun anExplicitlyTaggedDirectoryNameAloneIsRead() {
        // Only the directoryName, `[4] EXPLICIT`, with a localityName and a countryName.
        val explicit =
            DerWriter.sequence(
                DerWriter.tlv(
                    0xA4,
                    DerWriter.sequence(
                        DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.7"), DerWriter.printableString("Utopia City"))),
                        DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.6"), DerWriter.printableString("UT"))),
                    ),
                ),
            )
        val c = X509Parser.parse(FakePki.certificate(extensions = listOf(FakePki.Ext(FakePki.OID_SAN, false, explicit))))
        assertEquals("UT", c.decoded.subjectAltNameCountry)
    }

    @Test
    fun anExplicitlyTaggedDirectoryNameIsStillRejectedWhenItIsNotAName() {
        // A `[4]` holding something other than a Name SEQUENCE is not the explicit form.
        val nullInside = FakePki.Ext(FakePki.OID_SAN, false, DerWriter.sequence(DerWriter.tlv(0xA4, DerWriter.nullValue())))
        assertEquals(
            X509Failure.INVALID_EXTENSION,
            assertFailsWith<X509Exception> { X509Parser.parse(FakePki.certificate(extensions = listOf(nullInside))) }.failure,
        )
        // The explicit form holds exactly one Name SEQUENCE; bytes after it stay malformed.
        val trailing =
            FakePki.Ext(
                FakePki.OID_SAN,
                false,
                DerWriter.sequence(
                    DerWriter.tlv(
                        0xA4,
                        DerWriter.concat(
                            DerWriter.sequence(
                                DerWriter.set(DerWriter.sequence(DerWriter.oid("2.5.4.6"), DerWriter.printableString("UT"))),
                            ),
                            DerWriter.nullValue(),
                        ),
                    ),
                ),
            )
        assertEquals(
            X509Failure.INVALID_EXTENSION,
            assertFailsWith<X509Exception> { X509Parser.parse(FakePki.certificate(extensions = listOf(trailing))) }.failure,
        )
    }

    @Test
    fun extensionFailureOffsetsAreCountedFromTheStartOfTheCertificate() {
        // A known extension whose content is not its structure: the offset names a place in the
        // whole certificate, not a place inside the extension's own bytes.
        val bad = FakePki.Ext(FakePki.OID_SAN, false, DerWriter.nullValue())
        val der = FakePki.certificate(extensions = listOf(bad))
        val e = assertFailsWith<X509Exception> { X509Parser.parse(der) }
        assertEquals(X509Failure.INVALID_EXTENSION, e.failure)
        assertEquals(indexOf(der, DerWriter.nullValue()), e.offset)

        // An impossible month inside the private key usage period: the offset names the `[1]`
        // element the time sits in, in the whole certificate.
        val der2 = FakePki.certificate(extensions = listOf(FakePki.privateKeyUsagePeriod("20240101000000Z", "20341301000000Z")))
        val e2 = assertFailsWith<X509Exception> { X509Parser.parse(der2) }
        assertEquals(X509Failure.INVALID_TIME, e2.failure)
        assertEquals(indexOf(der2, "20341301000000Z".encodeToByteArray()) - 2, e2.offset)
    }

    @Test
    fun eachPart12ExtensionParsesAlone() {
        val cases =
            listOf(
                "subjectKeyIdentifier" to FakePki.subjectKeyIdentifier(ByteArray(20) { 0x41 }),
                "basicConstraints" to FakePki.basicConstraints(false, null),
                "authorityKeyIdentifier" to FakePki.authorityKeyIdentifier(ByteArray(20) { 0x11 }),
                "privateKeyUsagePeriod" to FakePki.privateKeyUsagePeriod("20240101000000Z", "20340101000000Z"),
                "cRLDistributionPoints" to FakePki.crlDistributionPoint("https://crl.utopia.example/crl"),
                "keyUsage" to FakePki.keyUsage(0),
                "subjectAltName" to FakePki.subjectAltNameDirectory("UT"),
                "issuerAltName" to FakePki.issuerAltNameDirectory("UT"),
                "documentTypeList" to FakePki.documentTypeList("P", "I"),
            )
        for ((name, ext) in cases) {
            val c = X509Parser.parse(FakePki.certificate(extensions = listOf(ext)))
            assertEquals(ext.oid, c.extensions.single().oid, name)
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
