package io.lightine.tessera.emrtd.internal.aa

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.securityinfo.DomainParameterReader
import io.lightine.tessera.emrtd.internal.securityinfo.DomainParameters
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoException
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.securityinfo.SubjectPublicKey
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser

/**
 * EF.DG15 could not be turned into a key this module can verify. Carries only the reason.
 */
internal class ActiveAuthenticationKeyException(
    val failure: ActiveAuthenticationFailure,
) : IllegalStateException("active authentication public key: $failure")

/**
 * The active-authentication public key, read from EF.DG15 (Doc 9303 Part 11, 6.1.5): the tag `6F`
 * data group of Part 10 around a DER SubjectPublicKeyInfo. Reading it says nothing about whether
 * the key is authentic; that is passive authentication's question, over the hash of EF.DG15 in the
 * document security object.
 */
internal sealed class ActiveAuthenticationPublicKey {
    /**
     * The length in bytes of the chip's signature: the modulus for RSA (section 6.1.2.2), or `r`
     * and `s`, each at the byte length of the curve order, for ECDSA (TR-03111 plain format).
     */
    abstract val signatureLength: Int

    /** RSA: the modulus at its own byte length, and the public exponent. */
    class Rsa(
        val modulus: ByteArray,
        val publicExponent: ByteArray,
    ) : ActiveAuthenticationPublicKey() {
        override val signatureLength: Int
            get() = modulus.size

        override fun toString(): String = "ActiveAuthenticationPublicKey.Rsa(${modulus.size * 8} bit)"
    }

    /** ECDSA on explicit or standardized prime-curve parameters, the public point uncompressed. */
    class Ecdsa(
        val parameters: EcDomainParameters,
        val point: EcPoint,
    ) : ActiveAuthenticationPublicKey() {
        override val signatureLength: Int
            get() = 2 * CryptoBytes.stripLeadingZeros(parameters.n).size

        override fun toString(): String = "ActiveAuthenticationPublicKey.Ecdsa(${parameters.fieldLength * 8} bit field)"
    }
}

/**
 * Reads the [ActiveAuthenticationPublicKey] of EF.DG15. RSA keys (`rsaEncryption`) and ECDSA keys
 * (`id-ecPublicKey` with standardized or explicit prime-curve parameters, Part 11, 9.5) come back
 * ready to verify; anything else - a named curve, another algorithm, a damaged file - is refused
 * with [ActiveAuthenticationKeyException], never thrown past this module's caller.
 */
internal object ActiveAuthenticationKeys {
    /** The tag of EF.DG15 (`6F`), the data group object around the public key. */
    private const val DG15_TAG = 0x6F

    /**
     * Parses the bytes of EF.DG15 as read from the chip. Throws [ActiveAuthenticationKeyException]
     * for anything this module cannot verify.
     */
    fun parse(
        crypto: ChipCrypto,
        file: ByteArray,
    ): ActiveAuthenticationPublicKey =
        try {
            val dataGroup = TlvParser().parseSingle(file)
            if (dataGroup.tag != DG15_TAG || !dataGroup.isConstructed) {
                throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.PUBLIC_KEY_UNREADABLE)
            }
            val reader = DerReader(dataGroup.value())
            val key = DomainParameterReader.readSubjectPublicKey(reader)
            reader.finish()
            when (val parameters = key.parameters) {
                is DomainParameters.Unsupported -> {
                    if (parameters.algorithm == SecurityOids.RSA_ENCRYPTION) {
                        rsa(key.publicValue)
                    } else {
                        throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.UNSUPPORTED_PUBLIC_KEY)
                    }
                }

                is DomainParameters.Standardized, is DomainParameters.ExplicitEc -> {
                    ecdsa(crypto, key)
                }

                else -> {
                    throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.UNSUPPORTED_PUBLIC_KEY)
                }
            }
        } catch (e: ActiveAuthenticationKeyException) {
            throw e
        } catch (e: Asn1Exception) {
            throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.PUBLIC_KEY_UNREADABLE)
        } catch (e: TlvException) {
            throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.PUBLIC_KEY_UNREADABLE)
        } catch (e: SecurityInfoException) {
            throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.PUBLIC_KEY_UNREADABLE)
        } catch (e: ChipCryptoException) {
            throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.INVALID_PUBLIC_KEY)
        }

    /** The DER `RSAPublicKey ::= SEQUENCE { modulus, publicExponent }` from the BIT STRING. */
    private fun rsa(der: ByteArray): ActiveAuthenticationPublicKey.Rsa {
        val outer = DerReader(der)
        val key = outer.readSequence()
        val modulus = CryptoBytes.stripLeadingZeros(key.readUnsignedInteger())
        val exponent = CryptoBytes.stripLeadingZeros(key.readUnsignedInteger())
        key.finish()
        outer.finish()
        if (modulus.isEmpty() || (modulus[0].toInt() and 1) == 0 || exponent.isEmpty()) {
            throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.INVALID_PUBLIC_KEY)
        }
        return ActiveAuthenticationPublicKey.Rsa(modulus, exponent)
    }

    private fun ecdsa(
        crypto: ChipCrypto,
        key: SubjectPublicKey,
    ): ActiveAuthenticationPublicKey.Ecdsa {
        val parameters: EcDomainParameters =
            when (val declared = key.parameters) {
                is DomainParameters.Standardized -> {
                    StandardDomainParameterValues.forId(declared.id) as? EcDomainParameters
                        ?: throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.UNSUPPORTED_PUBLIC_KEY)
                }

                is DomainParameters.ExplicitEc -> {
                    explicitEcParameters(crypto, declared)
                }

                else -> {
                    throw ActiveAuthenticationKeyException(ActiveAuthenticationFailure.UNSUPPORTED_PUBLIC_KEY)
                }
            }
        // The point is checked as far as the curve allows: coordinates in range and on the curve.
        // Subgroup membership is the curve's cofactor's business, and every Table 12 curve has
        // cofactor 1.
        val point = EcPointCodec.decode(crypto, parameters, key.publicValue)
        return ActiveAuthenticationPublicKey.Ecdsa(parameters, point)
    }

    /** Explicit ECParameters (section 9.5.2) as the crypto's curve form; the generator is decoded and checked on the way. */
    private fun explicitEcParameters(
        crypto: ChipCrypto,
        declared: DomainParameters.ExplicitEc,
    ): EcDomainParameters {
        val cofactor = declared.cofactor ?: CryptoBytes.ofInt(1)
        // The scaffold carries p, a and b for the generator's checks; its own generator is never used.
        val scaffold = EcDomainParameters(declared.p, declared.a, declared.b, EcPoint(ByteArray(0), ByteArray(0)), declared.order, cofactor)
        val generator = EcPointCodec.decode(crypto, scaffold, declared.generator)
        return EcDomainParameters(declared.p, declared.a, declared.b, generator, declared.order, cofactor)
    }
}
