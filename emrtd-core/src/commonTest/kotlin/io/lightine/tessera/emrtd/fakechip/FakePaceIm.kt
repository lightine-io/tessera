package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.sm.SmCipher

/**
 * The chip side of the integrated mapping (Part 11, section 4.4.3.3.2), written apart from the
 * reader's [io.lightine.tessera.emrtd.internal.pace.IntegratedMapping] so the two check each other:
 * - the nonce s is l bits: 16 bytes for 3DES and AES-128, 32 for AES-192 and AES-256;
 * - the reader's t (0x81) must be k bits (16, 16, 24, 32 bytes); otherwise 6A80;
 * - the chip answers an empty 0x82 (section 4.4.5.2.2);
 * - ECDH maps with the Jacobian form of the point encoding (Appendix B.3, then x = X·Z⁻², y = Y·Z⁻³),
 *   where the reader uses the affine form (B.2). B.3 as printed gives the same x but y times the
 *   Legendre symbol of Z (Y = A·h2 carries Z^(3(3p-1)/2), not Z³), so B.1's "identical point" holds
 *   only when Z is a square; for Appendix H.1 it is not. The chip multiplies y by Z^((p-1)/2) to
 *   land on the point B.2 and H.1 give (a sign only: the key agreement uses x alone, so a chip on
 *   either point would still agree with the reader);
 * - DH maps with ĝ = R_p^((p-1)/q) mod p, the cofactor found by plain long division.
 */
internal class FakePaceIm : FakePaceMapping {
    override val kind: PaceMapping = PaceMapping.INTEGRATED

    override fun nonceLength(cipher: SmCipher): Int = if (cipher == SmCipher.AES192 || cipher == SmCipher.AES256) 32 else 16

    override fun map(
        run: FakePaceRun,
        readerData: ByteArray,
    ): Pair<ByteArray, DomainParameters>? {
        val crypto = run.crypto
        if (readerData.size != keyBytes(run.cipher)) return null
        val params = run.staticParameters
        val p =
            when (params) {
                is EcDomainParameters -> params.p
                is DhDomainParameters -> params.p
            }
        val x = rp(crypto, run.cipher, run.nonce!!, readerData, p)
        if (CryptoBytes.isZero(x)) return null
        val mapped: DomainParameters =
            when (params) {
                is EcDomainParameters -> {
                    params.withGenerator(jacobianEncoding(crypto, params, x) ?: return null)
                }

                is DhDomainParameters -> {
                    val a = Big.divide(Big.minus(params.p, byteArrayOf(1)), params.q!!)
                    val g = crypto.modPow(x, a, params.p)
                    if (CryptoBytes.compareUnsigned(g, byteArrayOf(1)) <= 0) return null
                    params.withGenerator(g)
                }
            }
        return ByteArray(0) to mapped
    }

    internal companion object {
        fun keyBytes(cipher: SmCipher): Int =
            when (cipher) {
                SmCipher.DESEDE -> 16
                SmCipher.AES128 -> 16
                SmCipher.AES192 -> 24
                SmCipher.AES256 -> 32
            }

        /** R_p(s, t) of Figure 2: the key chain k_i and outputs x_i, then the integer mod p. */
        fun rp(
            crypto: ChipCrypto,
            cipher: SmCipher,
            s: ByteArray,
            t: ByteArray,
            p: ByteArray,
        ): ByteArray {
            val wide = s.size == 32
            val c0 =
                if (wide) "D463D65234124EF7897054986DCA0A174E28DF758CBAA03F240616414D5A1676" else "A668892A7C41E3CA739F40B057D85904"
            val c1 =
                if (wide) "54BD7255F0AAF831BEC3423FCF39D69B6CBF066677D0FAAE5AADD99DF8E53517" else "A4E136AC725F738B01C1F60217C188AD"
            val bits = Big.bitLength(p)
            val iv = ByteArray(cipher.blockCipher.blockSize)
            val keySize = keyBytes(cipher)

            fun e(
                key: ByteArray,
                data: ByteArray,
            ) = crypto.encrypt(cipher.blockCipher, CipherMode.CBC, key.copyOfRange(0, keySize), iv, data)
            var key = e(t, s)
            var out = ByteArray(0)
            while (out.size * 8 < bits + 64) {
                out += e(key, c1.hexToByteArray())
                key = e(key, c0.hexToByteArray())
            }
            return crypto.modMultiply(out, byteArrayOf(1), p)
        }

        /** Appendix B.3 (Jacobian coordinates), returned as an affine point; null when it has none. */
        fun jacobianEncoding(
            crypto: ChipCrypto,
            curve: EcDomainParameters,
            t: ByteArray,
        ): EcPoint? {
            val p = curve.p
            val one = byteArrayOf(1)

            fun m(vararg xs: ByteArray): ByteArray = xs.reduce { acc, v -> crypto.modMultiply(acc, v, p) }

            fun plus(vararg xs: ByteArray): ByteArray = xs.reduce { acc, v -> crypto.modAdd(acc, v, p) }

            fun neg(x: ByteArray): ByteArray = crypto.modSubtract(byteArrayOf(0), x, p)
            val alpha = neg(m(t, t))
            val alphaSum = plus(alpha, m(alpha, alpha))
            val z = m(curve.a, alphaSum)
            if (CryptoBytes.isZero(z)) return null
            val x2 = m(neg(curve.b), z, plus(one, alphaSum))
            val x3 = m(alpha, x2)
            val z2 = m(z, z)
            val z4 = m(z2, z2)
            val z6 = m(z4, z2)
            val h2 = plus(m(x2, x2, x2), m(curve.a, x2, z4), m(curve.b, z6))
            val u = m(neg(alpha), t, h2)
            val e = Big.minus(Big.minus(p, one), Big.divide(Big.plus(p, one), byteArrayOf(4)))
            val a = crypto.modPow(h2, e, p)
            val (bigX, bigY) = if (CryptoBytes.unsignedEquals(m(a, a, h2), one)) x2 to m(a, h2) else x3 to m(a, u)
            val zInverse = crypto.modInverse(z, p)
            val zi2 = m(zInverse, zInverse)
            val legendre = crypto.modPow(z, Big.divide(Big.minus(p, one), byteArrayOf(2)), p)
            var point = EcPoint(m(bigX, zi2), m(bigY, zi2, zInverse, legendre))
            if (!CryptoBytes.unsignedEquals(curve.cofactor, one)) point = crypto.ecMultiply(curve, point, curve.cofactor) ?: return null
            return point
        }
    }

    /** Plain unsigned big-endian arithmetic for the fake chip (shift-and-subtract division). */
    internal object Big {
        fun bitLength(v: ByteArray): Int {
            val s = CryptoBytes.stripLeadingZeros(v)
            return if (s.isEmpty()) 0 else s.size * 8 - (s[0].toInt() and 0xFF).countLeadingZeroBits() + 24
        }

        private fun bit(
            v: ByteArray,
            i: Int,
        ): Int = if (i / 8 >= v.size) 0 else (v[v.size - 1 - i / 8].toInt() ushr (i % 8)) and 1

        fun plus(
            a: ByteArray,
            b: ByteArray,
        ): ByteArray {
            val n = maxOf(a.size, b.size) + 1
            val out = ByteArray(n)
            var carry = 0
            for (i in 0 until n) {
                val s = byte(a, i) + byte(b, i) + carry
                out[n - 1 - i] = s.toByte()
                carry = s ushr 8
            }
            return CryptoBytes.stripLeadingZeros(out)
        }

        fun minus(
            a: ByteArray,
            b: ByteArray,
        ): ByteArray {
            val out = ByteArray(a.size)
            var borrow = 0
            for (i in a.indices) {
                var d = byte(a, i) - byte(b, i) - borrow
                borrow = if (d < 0) 1 else 0
                if (d < 0) d += 256
                out[a.size - 1 - i] = d.toByte()
            }
            check(borrow == 0)
            return CryptoBytes.stripLeadingZeros(out)
        }

        fun divide(
            a: ByteArray,
            b: ByteArray,
        ): ByteArray {
            var remainder = ByteArray(0)
            val bits = bitLength(a)
            val quotient = ByteArray((bits + 7) / 8)
            for (i in bits - 1 downTo 0) {
                remainder = plus(remainder, remainder)
                if (bit(a, i) == 1) remainder = plus(remainder, byteArrayOf(1))
                if (CryptoBytes.compareUnsigned(remainder, b) >= 0) {
                    remainder = minus(remainder, b)
                    val idx = quotient.size - 1 - i / 8
                    quotient[idx] = (quotient[idx].toInt() or (1 shl (i % 8))).toByte()
                }
            }
            return quotient
        }

        private fun byte(
            v: ByteArray,
            i: Int,
        ): Int = if (i >= v.size) 0 else v[v.size - 1 - i].toInt() and 0xFF
    }
}
