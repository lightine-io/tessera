package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.DiffieHellman
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EllipticCurves
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/**
 * The group a PACE key agreement runs in, DH or ECDH, with the encodings of Part 11, section 9.4:
 * public keys go on the wire as plain values (section 9.4.5), a DH value as an unsigned integer
 * without leading zero bytes (9.4.1, 9.4.3), an EC point uncompressed (9.4.1).
 *
 * Every public key from the chip is validated before use: on the curve and in the subgroup for
 * ECDH, in range and in the subgroup (RFC 2631) for DH.
 */
internal sealed class PaceGroup {
    abstract val parameters: DomainParameters

    /** The tag of the public value in a public key data object: 0x84 (DH y) or 0x86 (EC point Y). */
    abstract val publicKeyTag: Int

    /** The longest encoded public key, for sizing the answer of a step. */
    abstract val maxPublicKeyLength: Int

    /** A fresh random private key. */
    abstract fun generatePrivateKey(crypto: ChipCrypto): ByteArray

    /** The encoded public key of [privateKey]. */
    abstract fun publicKey(
        crypto: ChipCrypto,
        privateKey: ByteArray,
    ): ByteArray

    /**
     * Checks the chip's encoded public key and returns its canonical encoding (the form [publicKey]
     * produces), which is also what the token input carries. Throws [PaceException] with
     * [PaceFailure.INVALID_PUBLIC_KEY].
     */
    abstract fun validatePublicKey(
        crypto: ChipCrypto,
        encoded: ByteArray,
    ): ByteArray

    /** The shared secret K of section 9.7.1 with the (validated) [peer] key. */
    abstract fun sharedSecret(
        crypto: ChipCrypto,
        privateKey: ByteArray,
        peer: ByteArray,
    ): ByteArray

    /** The public key data object of section 9.4 for the token (section 4.4.3.4): OID and key only. */
    fun tokenInput(
        oidContent: ByteArray,
        publicKey: ByteArray,
    ): ByteArray = TlvWriter.constructed(0x7F49, TlvWriter.encode(0x06, oidContent), TlvWriter.encode(publicKeyTag, publicKey))

    /** DH over a prime field (section 4.4.3.1). */
    class Dh(
        override val parameters: DhDomainParameters,
    ) : PaceGroup() {
        override val publicKeyTag: Int = 0x84
        override val maxPublicKeyLength: Int get() = parameters.length

        override fun generatePrivateKey(crypto: ChipCrypto): ByteArray = DiffieHellman.generatePrivateKey(crypto, parameters)

        override fun publicKey(
            crypto: ChipCrypto,
            privateKey: ByteArray,
        ): ByteArray = TlvWriter.minimalUnsigned(DiffieHellman.publicKey(crypto, parameters, privateKey))

        override fun validatePublicKey(
            crypto: ChipCrypto,
            encoded: ByteArray,
        ): ByteArray {
            val value = CryptoBytes.stripLeadingZeros(encoded)
            if (value.isEmpty() || value.size > parameters.length) throw PaceException(PaceFailure.INVALID_PUBLIC_KEY)
            if (!DiffieHellman.isValidPublicKey(crypto, parameters, value)) throw PaceException(PaceFailure.INVALID_PUBLIC_KEY)
            return value
        }

        override fun sharedSecret(
            crypto: ChipCrypto,
            privateKey: ByteArray,
            peer: ByteArray,
        ): ByteArray =
            try {
                DiffieHellman.sharedSecret(crypto, parameters, privateKey, peer)
            } catch (e: ChipCryptoException) {
                throw PaceException(PaceFailure.INVALID_PUBLIC_KEY, cause = e)
            }
    }

    /** ECDH on a prime curve with uncompressed points (section 4.4.3.2). */
    class Ec(
        override val parameters: EcDomainParameters,
    ) : PaceGroup() {
        override val publicKeyTag: Int = 0x86
        override val maxPublicKeyLength: Int get() = 1 + 2 * parameters.fieldLength

        override fun generatePrivateKey(crypto: ChipCrypto): ByteArray = EllipticCurves.generatePrivateKey(crypto, parameters)

        override fun publicKey(
            crypto: ChipCrypto,
            privateKey: ByteArray,
        ): ByteArray = EcPointCodec.encode(parameters, EllipticCurves.publicKey(crypto, parameters, privateKey))

        override fun validatePublicKey(
            crypto: ChipCrypto,
            encoded: ByteArray,
        ): ByteArray {
            val point =
                try {
                    EcPointCodec.decode(crypto, parameters, encoded)
                } catch (e: ChipCryptoException) {
                    throw PaceException(PaceFailure.INVALID_PUBLIC_KEY, cause = e)
                }
            if (!EllipticCurves.isValidPublicKey(crypto, parameters, point)) throw PaceException(PaceFailure.INVALID_PUBLIC_KEY)
            return EcPointCodec.encode(parameters, point)
        }

        override fun sharedSecret(
            crypto: ChipCrypto,
            privateKey: ByteArray,
            peer: ByteArray,
        ): ByteArray =
            try {
                EllipticCurves.sharedSecret(crypto, parameters, privateKey, EcPointCodec.decode(crypto, parameters, peer))
            } catch (e: ChipCryptoException) {
                throw PaceException(PaceFailure.INVALID_PUBLIC_KEY, cause = e)
            }
    }
}
