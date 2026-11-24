package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.bitString
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.integer
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.nullValue
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.octetString
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.oid
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.sequence
import io.lightine.tessera.emrtd.internal.asn1.DerWriter.unsignedInteger
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/**
 * The private key of the fake chip's active authentication, made at test time from synthetic values
 * that exist nowhere else. Nothing here comes from a real document or a real chip.
 */
internal sealed class FakeAaKey {
    /** RSA: the chip signs with the private exponent (ISO/IEC 9796-2 generation, Appendix F, steps 9 and 10). */
    class Rsa(
        val modulus: ByteArray,
        val publicExponent: ByteArray,
        val privateExponent: ByteArray,
    ) : FakeAaKey() {
        /** k: the length of the modulus in bytes (section 6.1.2.2). */
        val length: Int = CryptoBytes.stripLeadingZeros(modulus).size
    }

    /** ECDSA: a fixed synthetic scalar on a curve of Table 12, and the public point that belongs to it. */
    class Ecdsa(
        val curveId: Int,
        val parameters: EcDomainParameters,
        val publicPoint: EcPoint,
        val privateKey: ByteArray,
    ) : FakeAaKey()

    companion object {
        private val PUBLIC_EXPONENT = byteArrayOf(0x01, 0x00, 0x01)

        /**
         * A synthetic RSA key built from two published Mersenne primes, 2^521 - 1 and 2^127 - 1:
         * the factors are public mathematical constants, so the key exists nowhere else. The
         * modulus comes out 648 bits with its top bit set, leaving room for M1 at every hash Doc
         * 9303 allows; the private exponent is the inverse of the public exponent modulo Euler's
         * totient. Everything is computed with the [ChipCrypto] arithmetic the protocols share.
         */
        fun rsa(crypto: ChipCrypto): Rsa {
            val p = mersennePrime(521)
            val q = mersennePrime(127)
            // A modulus above p * q, so the multiplication below does not reduce.
            val aboveProduct = ByteArray(163).also { it[0] = 1 }
            val modulus = CryptoBytes.stripLeadingZeros(crypto.modMultiply(p, q, aboveProduct))
            val phi =
                crypto.modMultiply(
                    crypto.modSubtract(p, CryptoBytes.ofInt(1), p),
                    crypto.modSubtract(q, CryptoBytes.ofInt(1), q),
                    aboveProduct,
                )
            return Rsa(modulus, PUBLIC_EXPONENT, crypto.modInverse(PUBLIC_EXPONENT, phi))
        }

        /** A synthetic ECDSA key on the standardized curve [id] of Table 12: the fixed scalar 3. */
        fun ecdsa(
            crypto: ChipCrypto,
            id: Int,
        ): Ecdsa {
            val parameters =
                StandardDomainParameterValues.forId(id) as? EcDomainParameters ?: error("standardized curve $id is not an EC set")
            val privateKey = CryptoBytes.ofInt(3)
            val publicPoint =
                crypto.ecMultiply(parameters, parameters.generator, privateKey) ?: error("the scalar gives the point at infinity")
            return Ecdsa(id, parameters, publicPoint, privateKey)
        }

        /** 2^bits - 1, every bit set: a Mersenne prime for bits 127 and 521. */
        private fun mersennePrime(bits: Int): ByteArray {
            val prime = ByteArray((bits + 7) / 8) { 0xFF.toByte() }
            prime[0] = ((1 shl (bits % 8)) - 1).toByte()
            return prime
        }
    }
}

/** How the fake chip writes its ECDSA curve into EF.DG15 (Doc 9303 Part 11, 9.5). */
internal enum class FakeAaCurveEncoding {
    /** The standardized-domain-parameter identifier of Table 12 (section 9.5.1). */
    STANDARDIZED_ID,

    /** Explicit prime-curve parameters (section 9.5.2). */
    EXPLICIT,
}

/**
 * The chip side of active authentication (Doc 9303 Part 11, sections 6.1.1 to 6.1.3): it answers
 * INTERNAL AUTHENTICATE with a signature under its private key [key], written apart from the
 * reader's code (`internal/aa`) so that the two check each other; only the [ChipCrypto] primitives
 * are shared. [hash] is the hash the chip signs with: RSA names it in the signature trailer, and
 * an ECDSA chip has its reader learn it from the chip's ActiveAuthenticationInfo, which the test
 * supplies.
 *
 * Fault injection: [tamperRecoveredMessage] and [corruptNextSignature].
 */
internal class FakeAa(
    private val key: FakeAaKey,
    private val crypto: ChipCrypto,
    private val hash: HashAlgorithm,
    encoding: FakeAaCurveEncoding = FakeAaCurveEncoding.STANDARDIZED_ID,
    private val random: (Int) -> ByteArray = crypto::randomBytes,
) : FakeCommandHandler {
    /** The EF.DG15 file this chip serves: tag `6F` around its public key (Part 11, 6.1.5). */
    val dg15: ByteArray = TlvWriter.constructed(DG15_TAG, subjectPublicKeyInfo(key, encoding))

    /** The signature bytes of the last INTERNAL AUTHENTICATE answer, for trailer-level checks. */
    var lastSignature: ByteArray? = null
        private set

    /**
     * Fault injection: sign the original message but put a changed recoverable part into the
     * message representative. A signature like this is what the digest comparison exists to catch.
     */
    var tamperRecoveredMessage = false

    /** Fault injection: flip one bit of the next returned signature. */
    var corruptNextSignature = false

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (command.cla != 0x00 || command.ins != INS_INTERNAL_AUTHENTICATE) return null
        if (command.p1 != 0x00 || command.p2 != 0x00) return status(StatusWord.INCORRECT_P1_P2)
        if (command.data.size != NONCE_LENGTH) return status(StatusWord.WRONG_LENGTH)
        var signature =
            when (key) {
                is FakeAaKey.Rsa -> rsaSignature(command.data)
                is FakeAaKey.Ecdsa -> ecdsaSignature(command.data)
            }
        if (corruptNextSignature) {
            corruptNextSignature = false
            signature[0] = (signature[0].toInt() xor 1).toByte()
        }
        lastSignature = signature
        return ResponseApdu(signature, StatusWord.SUCCESS)
    }

    /**
     * Appendix F, steps 4 to 10: the trailer, the recoverable nonce M1 of c - 4 bits, the digest of
     * M = M1 || M2, the representative F = `6A || M1 || H || T`, and the private operation.
     */
    private fun rsaSignature(m2: ByteArray): ByteArray {
        val rsaKey = key as FakeAaKey.Rsa
        val trailer = trailerOf(hash)
        val capacity = rsaKey.length * 8 - hash.outputLength * 8 - trailer.size * 8 - 4
        val m1 = random((capacity - 4) / 8)
        val digest = crypto.digest(hash, m1 + m2)
        var recoverable = m1
        if (tamperRecoveredMessage) {
            tamperRecoveredMessage = false
            recoverable = m1.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        }
        val representative = byteArrayOf(PARTIAL_RECOVERY_HEADER) + recoverable + digest + trailer
        return crypto.modPow(representative, rsaKey.privateExponent, rsaKey.modulus)
    }

    /**
     * The message is the reader's nonce alone (section 6.1.2.3); the signature is r and s in the
     * plain format, each at the byte length of the curve order (TR-03111). The signing nonce
     * starts at 2 and moves on in the impossible case of a zero component.
     */
    private fun ecdsaSignature(m: ByteArray): ByteArray {
        val ecdsaKey = key as FakeAaKey.Ecdsa
        val parameters = ecdsaKey.parameters
        val e = crypto.digest(hash, m)
        val order = parameters.n
        val orderLength = CryptoBytes.stripLeadingZeros(order).size
        var k = CryptoBytes.ofInt(2)
        while (true) {
            val rPoint = crypto.ecMultiply(parameters, parameters.generator, k) ?: error("the signing nonce gives the point at infinity")
            val r = crypto.modAdd(rPoint.x, CryptoBytes.ofInt(0), order)
            val s =
                crypto.modMultiply(
                    crypto.modInverse(k, order),
                    crypto.modAdd(e, crypto.modMultiply(r, ecdsaKey.privateKey, order), order),
                    order,
                )
            if (!CryptoBytes.isZero(r) && !CryptoBytes.isZero(s)) {
                return CryptoBytes.toFixedLength(r, orderLength) + CryptoBytes.toFixedLength(s, orderLength)
            }
            k = crypto.modAdd(k, CryptoBytes.ofInt(1), order)
        }
    }

    /** The trailer field of [algorithm] (section 6.1.2.2): `BC` for SHA-1, else the identifier and `CC`. */
    private fun trailerOf(algorithm: HashAlgorithm): ByteArray =
        when (algorithm) {
            HashAlgorithm.SHA1 -> byteArrayOf(0xBC.toByte())
            HashAlgorithm.SHA224 -> byteArrayOf(0x38.toByte(), 0xCC.toByte())
            HashAlgorithm.SHA256 -> byteArrayOf(0x34.toByte(), 0xCC.toByte())
            HashAlgorithm.SHA384 -> byteArrayOf(0x36.toByte(), 0xCC.toByte())
            HashAlgorithm.SHA512 -> byteArrayOf(0x35.toByte(), 0xCC.toByte())
        }

    /** The SubjectPublicKeyInfo of [key] (RFC 5280; Part 11, 6.1.5 and 9.5). */
    private fun subjectPublicKeyInfo(
        key: FakeAaKey,
        encoding: FakeAaCurveEncoding,
    ): ByteArray =
        when (key) {
            is FakeAaKey.Rsa -> {
                sequence(
                    sequence(oid(SecurityOids.RSA_ENCRYPTION), nullValue()),
                    bitString(sequence(unsignedInteger(key.modulus), unsignedInteger(key.publicExponent))),
                )
            }

            is FakeAaKey.Ecdsa -> {
                sequence(
                    when (encoding) {
                        FakeAaCurveEncoding.STANDARDIZED_ID -> {
                            // The form of Appendix I.1: the algorithm field is
                            // standardizedDomainParameters itself, with the Table 12 id as its parameters.
                            sequence(oid(SecurityOids.STANDARDIZED_DOMAIN_PARAMETERS), integer(key.curveId.toLong()))
                        }

                        FakeAaCurveEncoding.EXPLICIT -> {
                            sequence(oid(SecurityOids.EC_PUBLIC_KEY), explicitEcParameters(key.parameters))
                        }
                    },
                    bitString(EcPointCodec.encode(key.parameters, key.publicPoint)),
                )
            }
        }

    /** The explicit ECParameters of section 9.5.2: version 1, a prime field, the curve, the generator, order and cofactor. */
    private fun explicitEcParameters(parameters: EcDomainParameters): ByteArray =
        sequence(
            integer(1),
            sequence(oid(SecurityOids.PRIME_FIELD), unsignedInteger(parameters.p)),
            sequence(octetString(parameters.a), octetString(parameters.b)),
            octetString(EcPointCodec.encode(parameters, parameters.generator)),
            unsignedInteger(parameters.n),
            unsignedInteger(parameters.cofactor),
        )

    private fun status(sw: Int) = ResponseApdu(ByteArray(0), sw)

    private companion object {
        /** INTERNAL AUTHENTICATE (Part 11, 6.1.3), written here independently of the reader's code. */
        const val INS_INTERNAL_AUTHENTICATE = 0x88

        /** RND.IFD is 8 bytes (section 6.1.2.1). */
        const val NONCE_LENGTH = 8

        /** The tag of EF.DG15 (Part 10): `6F` around the SubjectPublicKeyInfo. */
        const val DG15_TAG = 0x6F

        /** `6A`, the header of partial message recovery (Appendix F, step 9.2). */
        const val PARTIAL_RECOVERY_HEADER: Byte = 0x6A
    }
}
