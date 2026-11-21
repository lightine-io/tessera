package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.SignatureScheme
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey

/** What kind of key to make for the fake PKI. */
internal sealed interface TestKeySpec {
    data class Rsa(
        val bits: Int,
    ) : TestKeySpec

    /** DSA on an existing group: [group] must carry its subgroup order q (an RFC 5114 group does). */
    class Dsa(
        val group: DhDomainParameters,
    ) : TestKeySpec

    class Ec(
        val curve: EcDomainParameters,
    ) : TestKeySpec
}

/** A key pair made at test time. [privateKey] is the backend's own object; only the signer reads it. */
internal class TestKeyPair(
    val publicKey: VerificationKey,
    val privateKey: Any,
)

/**
 * Makes keys and real signatures for the fake PKI, in tests only. The SDK verifies signatures but
 * never makes them, so signing lives in the test sources: BouncyCastle in jvmTest and
 * androidHostTest (src/bouncyCastleTest), OpenSSL in iosTest. Keys are made fresh in each test run and never written to
 * disk.
 */
internal interface TestSigner {
    fun generate(spec: TestKeySpec): TestKeyPair

    /** A signature over [message] in [scheme]'s encoding (DER for DSA and ECDSA unless the scheme says plain). */
    fun sign(
        key: TestKeyPair,
        scheme: SignatureScheme,
        message: ByteArray,
    ): ByteArray
}

/** This platform's test signer: BouncyCastle on the JVM and Android, OpenSSL on iOS. */
internal expect fun testSigner(): TestSigner
