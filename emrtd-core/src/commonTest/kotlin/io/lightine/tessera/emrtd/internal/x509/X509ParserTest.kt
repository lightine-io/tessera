package io.lightine.tessera.emrtd.internal.x509

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The certificates are built by FakePki with placeholder signatures: these tests cover structure only.
class X509ParserTest {
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
}
