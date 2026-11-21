package io.lightine.tessera.emrtd.internal.crypto

/**
 * The cryptography the chip protocols need (ADR-031 point 2), behind one internal interface so the
 * protocol code stays in common Kotlin and each platform plugs in a vetted library: BouncyCastle's
 * lightweight API on JVM and Android ([chipCrypto]), OpenSSL on iOS (planned).
 *
 * Only bytes and the types of this package cross the interface; no library type leaks out, and
 * nothing here is public API. All integers are unsigned big-endian byte arrays; a leading zero byte
 * is allowed on input. Results that are field elements or group elements come back at the fixed
 * length the type documents, so callers can concatenate and compare them byte for byte.
 *
 * Invalid input (a key of the wrong length, data that is not a whole number of blocks, a point
 * that is not on the curve where one is required) throws [ChipCryptoException]. Its message never
 * contains key or data bytes. A signature that does not verify is not an error: the verify call
 * returns `false`, and the caller records that as a fact.
 *
 * Built from this interface in common code: the key derivation ([KeyDerivation]), Diffie-Hellman
 * and elliptic-curve key agreement with public-key validation ([DiffieHellman], [EllipticCurves]),
 * point encoding ([EcPointCodec]), padding, constant-time compare and wipe ([CryptoBytes]), and the
 * standardized domain parameter values ([StandardDomainParameterValues]).
 */
internal interface ChipCrypto {
    /** The [algorithm] hash of [data]. */
    fun digest(
        algorithm: HashAlgorithm,
        data: ByteArray,
    ): ByteArray

    /**
     * Encrypts [data] with [cipher] in [mode] and no padding: [data] must be a whole number of
     * blocks. [iv] is required for CBC (one block long) and must be null for ECB. 3DES takes a 16-
     * byte (two-key) or 24-byte key; AES a 16, 24 or 32-byte key.
     */
    fun encrypt(
        cipher: BlockCipher,
        mode: CipherMode,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray

    /** The inverse of [encrypt], with the same rules. */
    fun decrypt(
        cipher: BlockCipher,
        mode: CipherMode,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
    ): ByteArray

    /**
     * ISO/IEC 9797-1 MAC algorithm 3 with DES (the "retail MAC" of Doc 9303 Part 11, section 9.8.6.1)
     * over [paddedData], which the caller has already padded ([CryptoBytes.pad]). [key] is 16 bytes
     * (K1 and K2). Returns the 8-byte MAC.
     */
    fun retailMac(
        key: ByteArray,
        paddedData: ByteArray,
    ): ByteArray

    /** AES-CMAC (NIST SP 800-38B, RFC 4493) of [data] under [key]. Returns the full 16-byte MAC. */
    fun cmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    /** [length] bytes from a cryptographically strong random source. */
    fun randomBytes(length: Int): ByteArray

    /** `base ^ exponent mod modulus`, at the length of [modulus]. */
    fun modPow(
        base: ByteArray,
        exponent: ByteArray,
        modulus: ByteArray,
    ): ByteArray

    /** `a * b mod modulus`, at the length of [modulus]. */
    fun modMultiply(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray

    /** `a + b mod modulus`, at the length of [modulus]. */
    fun modAdd(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray

    /** `a - b mod modulus` (never negative), at the length of [modulus]. */
    fun modSubtract(
        a: ByteArray,
        b: ByteArray,
        modulus: ByteArray,
    ): ByteArray

    /** The inverse of [a] modulo [modulus], at its length. Throws if there is none. */
    fun modInverse(
        a: ByteArray,
        modulus: ByteArray,
    ): ByteArray

    /** Whether [point] satisfies the curve equation of [curve] (coordinates in range included). */
    fun ecIsOnCurve(
        curve: EcDomainParameters,
        point: EcPoint,
    ): Boolean

    /** `p + q` on [curve]; null is the point at infinity. Both points must be on the curve. */
    fun ecAdd(
        curve: EcDomainParameters,
        p: EcPoint,
        q: EcPoint,
    ): EcPoint?

    /**
     * `scalar * point` on [curve]; null is the point at infinity. [point] must be on the curve. Any
     * non-negative [scalar] is accepted, including the group order (used by key validation).
     */
    fun ecMultiply(
        curve: EcDomainParameters,
        point: EcPoint,
        scalar: ByteArray,
    ): EcPoint?

    /**
     * The raw RSA public operation `input ^ exponent mod modulus`, at the length of [modulus], with
     * no padding check (active authentication reads ISO/IEC 9796-2 itself). [input] must be smaller
     * than [modulus].
     */
    fun rsaPublic(
        modulus: ByteArray,
        exponent: ByteArray,
        input: ByteArray,
    ): ByteArray

    /**
     * Whether [signature] over [message] verifies under [key] with [scheme]. The message is hashed
     * inside. A malformed signature verifies `false`; a key that does not fit [scheme] throws.
     */
    fun verify(
        scheme: SignatureScheme,
        key: VerificationKey,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}

/** The hashes Doc 9303 uses (Part 12, section 4.1.6.4; SHA-1 for BAC, older documents and KDFs). */
internal enum class HashAlgorithm(
    val outputLength: Int,
) {
    SHA1(20),
    SHA224(28),
    SHA256(32),
    SHA384(48),
    SHA512(64),
}

/** The block ciphers of secure messaging and PACE. */
internal enum class BlockCipher(
    val blockSize: Int,
) {
    /** Two-key (or three-key) Triple DES, EDE. */
    DESEDE(8),
    AES(16),
}

/** Block cipher modes; padding is always the caller's ([CryptoBytes.pad]). */
internal enum class CipherMode {
    ECB,
    CBC,
}

/** Invalid input to [ChipCrypto]. The message names the problem and never contains bytes. */
internal open class ChipCryptoException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * This platform has no cryptography backend yet. iOS gets OpenSSL in a later change; until then
 * chip reading cannot run there.
 */
internal class ChipCryptoUnavailableException : ChipCryptoException("no chip cryptography on this platform yet")

/**
 * This platform's [ChipCrypto]: BouncyCastle on JVM and Android. Throws
 * [ChipCryptoUnavailableException] on a platform that has none yet (iOS, until OpenSSL lands).
 */
internal expect fun chipCrypto(): ChipCrypto
