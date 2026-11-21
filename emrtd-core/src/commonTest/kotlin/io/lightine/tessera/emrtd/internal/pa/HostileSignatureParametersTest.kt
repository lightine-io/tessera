package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.RsaVectors
import io.lightine.tessera.emrtd.internal.crypto.SignatureScheme
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.x509.AlgorithmIdentifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Signature parameters and keys from untrusted certificates that must be refused before any arithmetic. */
class HostileSignatureParametersTest {
    private val sha256 = DerWriter.sequence(DerWriter.oid(SignatureAlgorithms.SHA256))

    private fun pssWithSalt(salt: Long): AlgorithmIdentifier =
        AlgorithmIdentifier(
            SignatureAlgorithms.RSASSA_PSS,
            DerWriter.sequence(
                DerWriter.explicit(0, sha256),
                DerWriter.explicit(1, DerWriter.sequence(DerWriter.oid(SignatureAlgorithms.MGF1), sha256)),
                DerWriter.explicit(2, DerWriter.integer(salt)),
            ),
        )

    @Test
    fun aPssSaltOfIntMaxValueOrMoreIsAnUnsupportedAlgorithm() {
        assertEquals(SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, 32), SignatureAlgorithms.scheme(pssWithSalt(32)))
        assertFailsWith<UnsupportedAlgorithmException> { SignatureAlgorithms.scheme(pssWithSalt(Int.MAX_VALUE.toLong())) }
        assertFailsWith<UnsupportedAlgorithmException> { SignatureAlgorithms.scheme(pssWithSalt(1L shl 40)) }
        assertFailsWith<UnsupportedAlgorithmException> { SignatureAlgorithms.scheme(pssWithSalt(-1)) }
    }

    @Test
    fun aPssSaltLongerThanTheKeyCanHoldDoesNotRun() =
        withCrypto { c ->
            val key = VerificationKey.Rsa(hex(RsaVectors.modulus), hex(RsaVectors.exponent))
            val msg = RsaVectors.MESSAGE.encodeToByteArray()
            val sig = hex(RsaVectors.pssSha256)
            // 2048-bit key, SHA-256: emLen 256 - 32 - 2 = 222 bytes of salt at most (RFC 8017, 9.1.1).
            val fits =
                SignatureChecks.verifySignature(
                    c,
                    SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, 222),
                    key,
                    msg,
                    sig,
                )
            assertEquals(PaOutcome.Failed(PaFailure.SIGNATURE_DOES_NOT_VERIFY), fits)
            for (salt in listOf(223, Int.MAX_VALUE)) {
                val scheme = SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, salt)
                assertEquals(PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM), SignatureChecks.verifySignature(c, scheme, key, msg, sig))
                // The backend refuses it on its own too, without building a signer.
                assertFailsWith<ChipCryptoException> { c.verify(scheme, key, msg, sig) }
            }
            assertEquals(
                PaOutcome.Passed,
                SignatureChecks.verifySignature(c, SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, 32), key, msg, sig),
            )
        }

    @Test
    fun theBackendRefusesRsaKeysOutsideTheSizePolicy() =
        withCrypto { c ->
            val msg = RsaVectors.MESSAGE.encodeToByteArray()
            val pkcs1 = SignatureScheme.RsaPkcs1V15(HashAlgorithm.SHA256)
            val huge =
                ByteArray(1025).also {
                    it[0] = 1
                    it[it.size - 1] = 1
                } // 8193 bits, odd
            for (key in listOf(
                VerificationKey.Rsa(huge, hex(RsaVectors.exponent)),
                VerificationKey.Rsa(hex(RsaVectors.modulus), ByteArray(9) { 0x7F }), // 71-bit exponent
                VerificationKey.Rsa(hex(RsaVectors.modulus), byteArrayOf(1)),
            )) {
                assertFailsWith<ChipCryptoException> { c.verify(pkcs1, key, msg, hex(RsaVectors.pkcs1Sha256)) }
                assertTrue(SignatureChecks.verifySignature(c, pkcs1, key, msg, hex(RsaVectors.pkcs1Sha256)) is PaOutcome.NotRun)
            }
        }
}
