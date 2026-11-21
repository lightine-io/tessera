package io.lightine.tessera.emrtd.internal.crypto

/**
 * An affine elliptic-curve point. [x] and [y] are unsigned big-endian at the curve's field length
 * ([EcDomainParameters.fieldLength]); the point at infinity is never an [EcPoint] (operations
 * return null for it). `toString()` never prints the coordinates.
 */
internal class EcPoint(
    val x: ByteArray,
    val y: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is EcPoint &&
            CryptoBytes.unsignedEquals(x, other.x) &&
            CryptoBytes.unsignedEquals(y, other.y)

    override fun hashCode(): Int = CryptoBytes.stripLeadingZeros(x).contentHashCode()

    override fun toString(): String = "EcPoint"
}

/** Domain parameters of a key agreement: DH ([DhDomainParameters]) or ECDH ([EcDomainParameters]). */
internal sealed interface DomainParameters

/**
 * A prime field DH group: prime [p], generator [g], and the order [q] of the subgroup [g]
 * generates. [q] is null for PKCS #3 parameters, which do not carry it; full public-key validation
 * (RFC 2631, section 2.1.5) needs it.
 */
internal class DhDomainParameters(
    val p: ByteArray,
    val g: ByteArray,
    val q: ByteArray?,
) : DomainParameters {
    /** Byte length of [p], the length of public keys and shared secrets. */
    val length: Int = CryptoBytes.stripLeadingZeros(p).size

    /** The same group with generator [generator] (the mapped generator of PACE). */
    fun withGenerator(generator: ByteArray): DhDomainParameters = DhDomainParameters(p, generator, q)

    override fun toString(): String = "DhDomainParameters(${length * 8} bit)"
}

/**
 * Explicit prime-curve parameters `y^2 = x^3 + ax + b mod p` (Doc 9303 Part 11, section 4.4.3.2: prime
 * curves only), with [generator], its order [n], and [cofactor] h.
 */
internal class EcDomainParameters(
    val p: ByteArray,
    val a: ByteArray,
    val b: ByteArray,
    val generator: EcPoint,
    val n: ByteArray,
    val cofactor: ByteArray,
) : DomainParameters {
    /** Byte length of a field element (and of each point coordinate), from the bit length of [p]. */
    val fieldLength: Int = CryptoBytes.stripLeadingZeros(p).size

    /**
     * The same curve with generator [generator] (the mapped generator of PACE, the custom generator
     * for its ephemeral key agreement). The order and cofactor stay those of the curve's group.
     */
    fun withGenerator(generator: EcPoint): EcDomainParameters = EcDomainParameters(p, a, b, generator, n, cofactor)

    override fun toString(): String = "EcDomainParameters(${fieldLength * 8} bit field)"
}

/** A public key that verifies signatures. Built from parsed key data; holds no private part. */
internal sealed interface VerificationKey {
    /** RSA modulus and public exponent. */
    class Rsa(
        val modulus: ByteArray,
        val publicExponent: ByteArray,
    ) : VerificationKey {
        override fun toString(): String = "VerificationKey.Rsa"
    }

    /** DSA group ([p], [q], [g]) and public value [y]. */
    class Dsa(
        val p: ByteArray,
        val q: ByteArray,
        val g: ByteArray,
        val y: ByteArray,
    ) : VerificationKey {
        override fun toString(): String = "VerificationKey.Dsa"
    }

    /** An EC public point on explicit [parameters] (Part 12, section 4.1.6.3: no named curves). */
    class Ec(
        val parameters: EcDomainParameters,
        val point: EcPoint,
    ) : VerificationKey {
        override fun toString(): String = "VerificationKey.Ec"
    }
}

/** How a signature is checked (Doc 9303 Part 12, section 4.1.6). */
internal sealed interface SignatureScheme {
    /** The message hash. */
    val hash: HashAlgorithm

    /** RSASSA-PKCS1-v1_5 (RFC 8017), with the hash's DigestInfo. */
    data class RsaPkcs1V15(
        override val hash: HashAlgorithm,
    ) : SignatureScheme

    /**
     * RSASSA-PSS (RFC 4055 parameters): MGF1 with [mgfHash], salt of [saltLength] bytes, trailer
     * field 1 (0xBC).
     */
    data class RsaPss(
        override val hash: HashAlgorithm,
        val mgfHash: HashAlgorithm,
        val saltLength: Int,
    ) : SignatureScheme

    /** DSA (FIPS 186-4); the signature is the DER `SEQUENCE { r, s }`. */
    data class Dsa(
        override val hash: HashAlgorithm,
    ) : SignatureScheme

    /** ECDSA (X9.62 / TR-03111) in [format]. */
    data class Ecdsa(
        override val hash: HashAlgorithm,
        val format: EcdsaSignatureFormat,
    ) : SignatureScheme
}

/** How an ECDSA signature is encoded. */
internal enum class EcdsaSignatureFormat {
    /** DER `SEQUENCE { r, s }` (X9.62; CMS signed data). */
    DER,

    /** `r || s`, each at the byte length of the order (TR-03111 plain format; active authentication). */
    PLAIN,
}
