package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.sm.SmCipher

/**
 * The integrated mapping of Part 11, section 4.4.3.3.2. The reader picks a random nonce t of the
 * cipher's key size k and sends it in clear (data object 0x81, an octet string, section 4.4.5.2.2;
 * the chip's 0x82 is empty and not read). Both sides then map the nonces straight into the group:
 * - ECDH: Ĝ = f_G(R_p(s, t)), with the point encoding of Appendix B (section B.2, affine);
 * - DH: ĝ = f_g(R_p(s, t)) = R_p(s, t)^a mod p, with the cofactor a = (p-1)/q, and ĝ must not be 1.
 *
 * The nonce s from the chip must be l bits long, the smallest multiple of the block size with
 * l ≥ k (section 4.4.3.3); 3DES counts as k = 128.
 *
 * The point encoding needs p ≡ 3 mod 4 (Appendix B.1). NIST P-224 does not meet it, and Table 12
 * marks it as not usable with this mapping: [PaceSuite.of] refuses the pair, and [map] refuses any
 * other curve that does not meet it.
 *
 * Timing: the arithmetic runs on the platform backend's big integers, which are not constant time,
 * so the "constant time" of Appendix B.1 is not claimed here.
 */
internal class IntegratedMapping : Mapping {
    override val kind: PaceMapping
        get() = PaceMapping.INTEGRATED

    override suspend fun map(
        context: MappingContext,
        nonce: ByteArray,
        exchange: suspend (ByteArray) -> ByteArray,
    ): PaceGroup {
        val crypto = context.crypto
        val cipher = context.suite.smCipher
        if (nonce.size != nonceLength(cipher)) throw PaceException(PaceFailure.WRONG_NONCE_LENGTH, PaceStep.MAP_NONCE)
        val group = context.suite.group
        if (group is PaceGroup.Ec && !isThreeModFour(group.parameters.p)) throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
        if (group is PaceGroup.Dh && group.parameters.q == null) throw PaceException(PaceFailure.UNSUPPORTED_SUITE)

        val t = context.randomBytes(cipher.keyType.length)
        // The chip's 0x82 is empty for this mapping (section 4.4.5.2.2); nothing in it is used.
        exchange(t)
        val prime =
            when (group) {
                is PaceGroup.Ec -> group.parameters.p
                is PaceGroup.Dh -> group.parameters.p
            }
        val x = pseudoRandomMapping(crypto, cipher, nonce, t, prime)
        try {
            if (CryptoBytes.isZero(x)) throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE)
            return when (group) {
                is PaceGroup.Ec -> PaceGroup.Ec(group.parameters.withGenerator(encodePoint(crypto, group.parameters, x)))
                is PaceGroup.Dh -> PaceGroup.Dh(group.parameters.withGenerator(mapDh(crypto, group.parameters, x)))
            }
        } finally {
            CryptoBytes.wipe(x)
        }
    }

    internal companion object {
        // Section 4.4.3.3.2, the constants of Figure 2.
        private val C0_128 = "a668892a7c41e3ca739f40b057d85904".hexToByteArray()
        private val C1_128 = "a4e136ac725f738b01c1f60217c188ad".hexToByteArray()
        private val C0_256 = "d463d65234124ef7897054986dca0a174e28df758cbaa03f240616414d5a1676".hexToByteArray()
        private val C1_256 = "54bd7255f0aaf831bec3423fcf39d69b6cbf066677d0faae5aadd99df8e53517".hexToByteArray()

        /** l in bytes: 16 for 3DES and AES-128, 32 for AES-192 and AES-256 (the length of c0 and c1). */
        fun nonceLength(cipher: SmCipher): Int =
            when (cipher) {
                SmCipher.DESEDE, SmCipher.AES128 -> 16
                SmCipher.AES192, SmCipher.AES256 -> 32
            }

        /**
         * R(s, t) of Figure 2, before the reduction mod p: x1 || x2 || ... || xn, with n the smallest
         * number such that n·l ≥ log2 p + 64 ([primeBits] is the bit length of p).
         * - init: k0 = E(t, s), CBC with a zero IV;
         * - iteration i: k_i = E(k_(i-1), c0) and x_i = E(k_(i-1), c1).
         *
         * Each k_i is cut to the key size k; only AES-192 needs it (octets 1 to 24 of 32). 3DES uses
         * the two-key form, k = 128. The caller wipes the result.
         */
        fun pseudoRandom(
            crypto: ChipCrypto,
            cipher: SmCipher,
            s: ByteArray,
            t: ByteArray,
            primeBits: Int,
        ): ByteArray {
            val l = nonceLength(cipher)
            val keySize = cipher.keyType.length
            require(s.size == l && t.size == keySize) { "nonces do not fit the cipher" }
            val c0 = if (l == 16) C0_128 else C0_256
            val c1 = if (l == 16) C1_128 else C1_256
            val n = (primeBits + 64 + l * 8 - 1) / (l * 8)
            val iv = ByteArray(cipher.blockSize)
            val out = ByteArray(n * l)
            var k = crypto.encrypt(cipher.blockCipher, CipherMode.CBC, t, iv, s).truncateTo(keySize)
            try {
                for (i in 0 until n) {
                    val x = crypto.encrypt(cipher.blockCipher, CipherMode.CBC, k, iv, c1)
                    x.copyInto(out, i * l)
                    CryptoBytes.wipe(x)
                    val next = crypto.encrypt(cipher.blockCipher, CipherMode.CBC, k, iv, c0).truncateTo(keySize)
                    CryptoBytes.wipe(k)
                    k = next
                }
            } finally {
                CryptoBytes.wipe(k)
            }
            return out
        }

        /** R_p(s, t) = int(R(s, t)) mod p, at the length of [p]. The caller wipes it. */
        fun pseudoRandomMapping(
            crypto: ChipCrypto,
            cipher: SmCipher,
            s: ByteArray,
            t: ByteArray,
            p: ByteArray,
        ): ByteArray {
            val r = pseudoRandom(crypto, cipher, s, t, bitLength(p))
            try {
                return crypto.modAdd(r, ByteArray(1), p)
            } finally {
                CryptoBytes.wipe(r)
            }
        }

        /**
         * f_G of Appendix B.2 (affine coordinates) for 0 < [t] < p, with p ≡ 3 mod 4:
         * 1. α = -t² mod p
         * 2. X2 = -b·a⁻¹·(1 + (α + α²)⁻¹) mod p
         * 3. X3 = α·X2 mod p
         * 4. h2 = X2³ + a·X2 + b mod p
         * 5. (h3 is not needed for the output)
         * 6. U = t³·h2 mod p
         * 7. A = h2^(p - 1 - (p + 1)/4) mod p
         * 8. if A²·h2 = 1 mod p: (x, y) = (X2, A·h2 mod p)
         * 9. otherwise: (x, y) = (X3, A·U mod p)
         * 10. the output is [f](x, y), f the cofactor.
         *
         * Throws [PaceException] with [PaceFailure.DEGENERATE_GENERATOR] when the encoding has no
         * result (α + α² = 0, or the point at infinity after the cofactor).
         */
        fun encodePoint(
            crypto: ChipCrypto,
            curve: EcDomainParameters,
            t: ByteArray,
        ): EcPoint {
            val p = curve.p
            val zero = ByteArray(1)
            val one = CryptoBytes.ofInt(1)

            fun mul(
                x: ByteArray,
                y: ByteArray,
            ) = crypto.modMultiply(x, y, p)

            fun add(
                x: ByteArray,
                y: ByteArray,
            ) = crypto.modAdd(x, y, p)

            val alpha = crypto.modSubtract(zero, mul(t, t), p)
            val alphaSum = add(alpha, mul(alpha, alpha))
            val denominator = mul(curve.a, alphaSum)
            if (CryptoBytes.isZero(denominator)) throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE)
            val inverse =
                try {
                    crypto.modInverse(denominator, p)
                } catch (e: ChipCryptoException) {
                    throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE, cause = e)
                }
            // -b·a⁻¹·(1 + (α+α²)⁻¹) = -b·(1 + α + α²)·(a·(α+α²))⁻¹
            val x2 = mul(crypto.modSubtract(zero, curve.b, p), mul(add(one, alphaSum), inverse))
            val x3 = mul(alpha, x2)
            val h2 = add(add(mul(mul(x2, x2), x2), mul(curve.a, x2)), curve.b)
            val u = mul(mul(mul(t, t), t), h2)
            val exponent = subtract(subtract(p, CryptoBytes.ofInt(2)), shiftRightTwo(p)) // p - 1 - (p+1)/4 for p ≡ 3 mod 4
            val a = crypto.modPow(h2, exponent, p)
            val point =
                if (CryptoBytes.unsignedEquals(mul(mul(a, a), h2), one)) {
                    EcPoint(x2, mul(a, h2))
                } else {
                    EcPoint(x3, mul(a, u))
                }
            CryptoBytes.wipe(alpha, alphaSum, denominator, inverse, h2, u, a)
            if (!crypto.ecIsOnCurve(curve, point)) throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE)
            return if (CryptoBytes.unsignedEquals(curve.cofactor, one)) {
                point
            } else {
                crypto.ecMultiply(curve, point, curve.cofactor)
                    ?: throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE)
            }
        }

        /** f_g(x) = x^a mod p with a = (p-1)/q; refuses ĝ = 1 (and 0) as the section requires. */
        fun mapDh(
            crypto: ChipCrypto,
            group: DhDomainParameters,
            x: ByteArray,
        ): ByteArray {
            val q = group.q ?: throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
            val p = group.p
            // q divides p-1 and a < p, so a = (p-1)·q⁻¹ mod p exactly.
            val a = crypto.modMultiply(crypto.modSubtract(p, CryptoBytes.ofInt(1), p), crypto.modInverse(q, p), p)
            val mapped = crypto.modPow(x, a, p)
            if (CryptoBytes.compareUnsigned(mapped, CryptoBytes.ofInt(1)) <= 0) {
                throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE)
            }
            return mapped
        }

        /** The bit length of the unsigned [value]. */
        fun bitLength(value: ByteArray): Int {
            val v = CryptoBytes.stripLeadingZeros(value)
            if (v.isEmpty()) return 0
            return (v.size - 1) * 8 + (32 - (v[0].toInt() and 0xFF).countLeadingZeroBits())
        }

        private fun isThreeModFour(p: ByteArray): Boolean = p.isNotEmpty() && (p.last().toInt() and 0x03) == 3

        private fun ByteArray.truncateTo(size: Int): ByteArray {
            if (this.size == size) return this
            val cut = copyOf(size)
            CryptoBytes.wipe(this)
            return cut
        }

        /** [value] shifted right by two bits (the integer quotient by 4). */
        private fun shiftRightTwo(value: ByteArray): ByteArray {
            val out = ByteArray(value.size)
            var carry = 0
            for (i in value.indices) {
                val b = value[i].toInt() and 0xFF
                out[i] = ((b ushr 2) or (carry shl 6)).toByte()
                carry = b and 0x03
            }
            return out
        }

        /** a - b for unsigned big-endian a ≥ b, at the length of [a]. */
        private fun subtract(
            a: ByteArray,
            b: ByteArray,
        ): ByteArray {
            val out = ByteArray(a.size)
            var borrow = 0
            for (i in a.indices) {
                val ai = a[a.size - 1 - i].toInt() and 0xFF
                val bi = if (i < b.size) b[b.size - 1 - i].toInt() and 0xFF else 0
                var d = ai - bi - borrow
                borrow = if (d < 0) 1 else 0
                if (d < 0) d += 256
                out[a.size - 1 - i] = d.toByte()
            }
            require(borrow == 0) { "negative difference" }
            return out
        }
    }
}
