package io.lightine.tessera.emrtd.internal.crypto

/**
 * Diffie-Hellman over a prime field (PKCS #3; Doc 9303 Part 11, section 9.6), with the public-key
 * validation of RFC 2631, section 2.1.5 that sections 4.4.3.3.1 and 9.4.5 ask for.
 */
internal object DiffieHellman {
    /** A random private key in `[1, q-1]` (or `[1, p-2]` when the group has no q). */
    fun generatePrivateKey(
        crypto: ChipCrypto,
        params: DhDomainParameters,
    ): ByteArray = randomBelow(crypto, params.q ?: crypto.modSubtract(params.p, CryptoBytes.ofInt(1), params.p))

    /** `g ^ privateKey mod p`, at the length of p. */
    fun publicKey(
        crypto: ChipCrypto,
        params: DhDomainParameters,
        privateKey: ByteArray,
    ): ByteArray = crypto.modPow(params.g, privateKey, params.p)

    /**
     * RFC 2631, section 2.1.5: [y] lies in `[2, p-1]` and `y ^ q mod p == 1`. Without q (PKCS #3
     * parameters) only the range check runs, and `p-1` (order two) is refused as well.
     */
    fun isValidPublicKey(
        crypto: ChipCrypto,
        params: DhDomainParameters,
        y: ByteArray,
    ): Boolean {
        val pMinusOne = crypto.modSubtract(params.p, CryptoBytes.ofInt(1), params.p)
        if (CryptoBytes.compareUnsigned(y, CryptoBytes.ofInt(2)) < 0) return false
        if (CryptoBytes.compareUnsigned(y, pMinusOne) > 0) return false
        val q = params.q ?: return !CryptoBytes.unsignedEquals(y, pMinusOne)
        return CryptoBytes.unsignedEquals(crypto.modPow(y, q, params.p), CryptoBytes.ofInt(1))
    }

    /**
     * The shared secret `peer ^ privateKey mod p`, at the length of p (the octet string K of section
     * 9.7.1). Throws [ChipCryptoException] when [peer] fails [isValidPublicKey].
     */
    fun sharedSecret(
        crypto: ChipCrypto,
        params: DhDomainParameters,
        privateKey: ByteArray,
        peer: ByteArray,
    ): ByteArray {
        if (!isValidPublicKey(crypto, params, peer)) throw ChipCryptoException("DH public key failed validation")
        return crypto.modPow(peer, privateKey, params.p)
    }
}

/**
 * Elliptic-curve Diffie-Hellman on explicit prime-curve parameters (ECKA, BSI TR-03111; Doc 9303 Part
 * 11, section 9.6), with public-key validation (section 9.4.5). A custom generator, such as the
 * mapped generator of PACE, is a parameter set from [EcDomainParameters.withGenerator].
 */
internal object EllipticCurves {
    /** A random private key in `[1, n-1]`. */
    fun generatePrivateKey(
        crypto: ChipCrypto,
        params: EcDomainParameters,
    ): ByteArray = randomBelow(crypto, params.n)

    /** `privateKey * G` for the generator of [params]. */
    fun publicKey(
        crypto: ChipCrypto,
        params: EcDomainParameters,
        privateKey: ByteArray,
    ): EcPoint =
        crypto.ecMultiply(params, params.generator, privateKey)
            ?: throw ChipCryptoException("private key gives the point at infinity")

    /**
     * Full public-key validation (TR-03111): coordinates in `[0, p-1]`, the point is on the curve,
     * and `n * point` is the point at infinity, so it lies in the generator's subgroup. The point at
     * infinity cannot be an [EcPoint], so it never passes.
     */
    fun isValidPublicKey(
        crypto: ChipCrypto,
        params: EcDomainParameters,
        point: EcPoint,
    ): Boolean {
        if (CryptoBytes.compareUnsigned(point.x, params.p) >= 0) return false
        if (CryptoBytes.compareUnsigned(point.y, params.p) >= 0) return false
        if (!crypto.ecIsOnCurve(params, point)) return false
        return crypto.ecMultiply(params, point, params.n) == null
    }

    /**
     * The shared secret: the x-coordinate of `privateKey * peer` at the field length (section 9.7.1,
     * note). Throws [ChipCryptoException] when [peer] fails [isValidPublicKey] or the result is the
     * point at infinity.
     */
    fun sharedSecret(
        crypto: ChipCrypto,
        params: EcDomainParameters,
        privateKey: ByteArray,
        peer: EcPoint,
    ): ByteArray = CryptoBytes.toFixedLength(sharedPoint(crypto, params, privateKey, peer).x, params.fieldLength)

    /**
     * The shared point `privateKey * peer` (the generic mapping of PACE needs the whole point H).
     * Same checks as [sharedSecret].
     */
    fun sharedPoint(
        crypto: ChipCrypto,
        params: EcDomainParameters,
        privateKey: ByteArray,
        peer: EcPoint,
    ): EcPoint {
        if (!isValidPublicKey(crypto, params, peer)) throw ChipCryptoException("EC public key failed validation")
        return crypto.ecMultiply(params, peer, privateKey)
            ?: throw ChipCryptoException("key agreement gives the point at infinity")
    }
}

/**
 * The uncompressed point encoding `04 || X || Y` (SEC 1, section 2.3.3), the only one Doc 9303 Part 11
 * allows (section 4.4.3.2).
 */
internal object EcPointCodec {
    private const val UNCOMPRESSED: Byte = 0x04

    /** `04 || X || Y`, each coordinate at the field length of [params]. */
    fun encode(
        params: EcDomainParameters,
        point: EcPoint,
    ): ByteArray {
        val len = params.fieldLength
        return byteArrayOf(UNCOMPRESSED) +
            CryptoBytes.toFixedLength(point.x, len) +
            CryptoBytes.toFixedLength(point.y, len)
    }

    /**
     * Reads `04 || X || Y`. Throws [ChipCryptoException] for any other form (compressed, hybrid,
     * the infinity byte), a wrong length, a coordinate not below p, or a point not on the curve.
     * Subgroup membership is [EllipticCurves.isValidPublicKey]'s job.
     */
    fun decode(
        crypto: ChipCrypto,
        params: EcDomainParameters,
        encoded: ByteArray,
    ): EcPoint {
        val len = params.fieldLength
        if (encoded.size != 1 + 2 * len) throw ChipCryptoException("encoded point has the wrong length")
        if (encoded[0] != UNCOMPRESSED) throw ChipCryptoException("encoded point is not uncompressed")
        val point = EcPoint(encoded.copyOfRange(1, 1 + len), encoded.copyOfRange(1 + len, encoded.size))
        if (CryptoBytes.compareUnsigned(point.x, params.p) >= 0 || CryptoBytes.compareUnsigned(point.y, params.p) >= 0) {
            throw ChipCryptoException("point coordinate out of range")
        }
        if (!crypto.ecIsOnCurve(params, point)) throw ChipCryptoException("point is not on the curve")
        return point
    }
}

/** A uniformly random number in `[1, bound-1]`, by rejection sampling on [bound]'s bit length. */
internal fun randomBelow(
    crypto: ChipCrypto,
    bound: ByteArray,
): ByteArray {
    val b = CryptoBytes.stripLeadingZeros(bound)
    if (b.isEmpty() || CryptoBytes.compareUnsigned(b, CryptoBytes.ofInt(2)) < 0) {
        throw ChipCryptoException("bound too small")
    }
    // Keep only as many bits in the top byte as the bound has, so at least half the draws succeed.
    val unusedTopBits = (b[0].toInt() and 0xFF).countLeadingZeroBits() - 24
    val mask = 0xFF ushr unusedTopBits
    while (true) {
        val candidate = crypto.randomBytes(b.size)
        candidate[0] = (candidate[0].toInt() and mask).toByte()
        if (!CryptoBytes.isZero(candidate) && CryptoBytes.compareUnsigned(candidate, b) < 0) return candidate
        CryptoBytes.wipe(candidate)
    }
}
