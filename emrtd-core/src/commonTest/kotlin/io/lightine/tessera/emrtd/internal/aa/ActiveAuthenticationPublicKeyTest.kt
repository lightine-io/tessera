package io.lightine.tessera.emrtd.internal.aa

import io.lightine.tessera.emrtd.fakechip.FakeAa
import io.lightine.tessera.emrtd.fakechip.FakeAaCurveEncoding
import io.lightine.tessera.emrtd.fakechip.FakeAaKey
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.bitString
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.nullValue
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.oid
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.sequence
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.unsignedInteger
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The active-authentication public key out of EF.DG15 (Part 11, 6.1.5): the two algorithms Doc 9303
 * allows, read through the same SubjectPublicKeyInfo reader the chip-authentication keys use, and
 * the forms this module refuses.
 */
class ActiveAuthenticationPublicKeyTest {
    @Test
    fun rsaKeyReadsModulusAndExponent() =
        withCrypto { c ->
            val key = FakeAaKey.rsa(c)
            val dg15 = FakeAa(key, c, HashAlgorithm.SHA256).dg15
            val parsed = ActiveAuthenticationKeys.parse(c, dg15) as ActiveAuthenticationPublicKey.Rsa
            assertTrue(CryptoBytes.unsignedEquals(parsed.modulus, key.modulus))
            assertEquals("010001", parsed.publicExponent.toHex())
            assertEquals(key.length, parsed.signatureLength)
        }

    @Test
    fun ecdsaKeyReadsStandardizedAndExplicitParameters() =
        withCrypto { c ->
            for (encoding in FakeAaCurveEncoding.entries) {
                val key = FakeAaKey.ecdsa(c, 13) // BrainpoolP256r1
                val dg15 = FakeAa(key, c, HashAlgorithm.SHA256, encoding).dg15
                val parsed = ActiveAuthenticationKeys.parse(c, dg15) as ActiveAuthenticationPublicKey.Ecdsa
                assertTrue(CryptoBytes.unsignedEquals(parsed.parameters.p, key.parameters.p), "$encoding")
                assertTrue(CryptoBytes.unsignedEquals(parsed.parameters.n, key.parameters.n), "$encoding")
                assertEquals(key.publicPoint, parsed.point)
                assertEquals(2 * CryptoBytes.stripLeadingZeros(key.parameters.n).size, parsed.signatureLength, "$encoding")
            }
        }

    @Test
    fun aNamedCurveIsRefused() =
        withCrypto { c ->
            // id-ecPublicKey with a named curve (prime256v1): Part 11, 9.5.2 allows standardized or
            // explicit parameters only, so no chip protocol may use this key.
            val key = FakeAaKey.ecdsa(c, 12)
            val dg15 =
                TlvWriter.constructed(
                    0x6F,
                    sequence(
                        sequence(oid(SecurityOids.EC_PUBLIC_KEY), oid("1.2.840.10045.3.1.7")),
                        bitString(EcPointCodec.encode(key.parameters, key.publicPoint)),
                    ),
                )
            val e = assertFailsWith<ActiveAuthenticationKeyException> { ActiveAuthenticationKeys.parse(c, dg15) }
            assertEquals(ActiveAuthenticationFailure.UNSUPPORTED_PUBLIC_KEY, e.failure)
        }

    @Test
    fun aFileThatIsNotTheDg15ObjectIsRefused() =
        withCrypto { c ->
            val key = FakeAaKey.rsa(c)
            val spki =
                sequence(
                    sequence(oid(SecurityOids.RSA_ENCRYPTION), nullValue()),
                    bitString(sequence(unsignedInteger(key.modulus), unsignedInteger(key.publicExponent))),
                )
            val badFiles =
                listOf(
                    ByteArray(0),
                    ByteArray(8) { 0x30 },
                    TlvWriter.encode(0x6E, ByteArray(0)), // the DG14 tag, not DG15's
                    spki, // the SubjectPublicKeyInfo without its data group object
                )
            for (file in badFiles) {
                val e = assertFailsWith<ActiveAuthenticationKeyException> { ActiveAuthenticationKeys.parse(c, file) }
                assertEquals(ActiveAuthenticationFailure.PUBLIC_KEY_UNREADABLE, e.failure)
            }
        }

    @Test
    fun aPointOutsideTheFieldIsRefused() =
        withCrypto { c ->
            // P-224 with the y coordinate equal to the prime: out of range, so the key is invalid.
            val key = FakeAaKey.ecdsa(c, 10)
            val point = byteArrayOf(0x04) + key.publicPoint.x + key.parameters.p
            val dg15 =
                TlvWriter.constructed(
                    0x6F,
                    sequence(
                        // The Appendix I.1 form: standardizedDomainParameters with the id as parameters.
                        sequence(oid(SecurityOids.STANDARDIZED_DOMAIN_PARAMETERS), unsignedInteger(hex("0A"))),
                        bitString(point),
                    ),
                )
            val e = assertFailsWith<ActiveAuthenticationKeyException> { ActiveAuthenticationKeys.parse(c, dg15) }
            assertEquals(ActiveAuthenticationFailure.INVALID_PUBLIC_KEY, e.failure)
        }

    @Test
    fun anEvenModulusIsRefused() =
        withCrypto { c ->
            val dg15 =
                TlvWriter.constructed(
                    0x6F,
                    sequence(
                        sequence(oid(SecurityOids.RSA_ENCRYPTION), nullValue()),
                        bitString(sequence(unsignedInteger(byteArrayOf(0x04)), unsignedInteger(byteArrayOf(0x01, 0x00, 0x01)))),
                    ),
                )
            val e = assertFailsWith<ActiveAuthenticationKeyException> { ActiveAuthenticationKeys.parse(c, dg15) }
            assertEquals(ActiveAuthenticationFailure.INVALID_PUBLIC_KEY, e.failure)
        }
}
