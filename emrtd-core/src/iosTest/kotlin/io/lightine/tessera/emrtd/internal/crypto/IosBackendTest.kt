package io.lightine.tessera.emrtd.internal.crypto

import io.lightine.tessera.emrtd.fakechip.TestKeySpec
import io.lightine.tessera.emrtd.fakechip.testSigner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * iOS has the OpenSSL backend, so the common vector suite runs here (withCrypto never skips), and
 * the OpenSSL test signer makes real signatures, so the passive authentication tests run too.
 */
class IosBackendTest {
    @Test
    fun iosHasTheOpenSslBackend() {
        assertSame(OpenSslChipCrypto, chipCrypto())
    }

    @Test
    fun theTestSignerSignsWhatTheBackendVerifies() {
        val signer = testSigner()
        val message = "UTOPIA SPECIMEN".encodeToByteArray()
        val cases =
            listOf(
                TestKeySpec.Rsa(2048) to SignatureScheme.RsaPss(HashAlgorithm.SHA256, HashAlgorithm.SHA256, 32),
                TestKeySpec.Rsa(2048) to SignatureScheme.RsaPkcs1V15(HashAlgorithm.SHA256),
                TestKeySpec.Dsa(dhParams(2)) to SignatureScheme.Dsa(HashAlgorithm.SHA256),
                TestKeySpec.Ec(ecParams(13)) to SignatureScheme.Ecdsa(HashAlgorithm.SHA256, EcdsaSignatureFormat.PLAIN),
                TestKeySpec.Ec(ecParams(12)) to SignatureScheme.Ecdsa(HashAlgorithm.SHA256, EcdsaSignatureFormat.DER),
            )
        for ((spec, scheme) in cases) {
            val key = signer.generate(spec)
            val signature = signer.sign(key, scheme, message)
            assertTrue(OpenSslChipCrypto.verify(scheme, key.publicKey, message, signature), "$scheme verifies")
            assertFalse(OpenSslChipCrypto.verify(scheme, key.publicKey, message + 0, signature), "$scheme on another message")
        }
    }
}
