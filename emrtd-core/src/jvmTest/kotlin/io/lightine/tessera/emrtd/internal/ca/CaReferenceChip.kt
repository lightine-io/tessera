package io.lightine.tessera.emrtd.internal.ca

import io.lightine.tessera.emrtd.ApduTransport
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.pkcs.DHParameter
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.asn1.x9.X9ECPoint
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.agreement.DHBasicAgreement
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.DESEngine
import org.bouncycastle.crypto.engines.DESedeEngine
import org.bouncycastle.crypto.generators.DHKeyPairGenerator
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.macs.ISO9797Alg3Mac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.params.DHKeyGenerationParameters
import org.bouncycastle.crypto.params.DHParameters
import org.bouncycastle.crypto.params.DHPrivateKeyParameters
import org.bouncycastle.crypto.params.DHPublicKeyParameters
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.BigIntegers
import java.math.BigInteger
import java.security.SecureRandom

/**
 * A reference chip for chip authentication, written straight from Doc 9303 Part 11 (sections 6.2,
 * 9.7 and 9.8) on BouncyCastle's own classes: its key pairs, key agreement, KDF, block ciphers,
 * MACs and ASN.1 encoder. It shares no code with the SDK's chip code (not `ChipCrypto`, not the
 * secure-messaging codec, not the domain parameter table, not the TLV code), so a test that passes
 * against it checks the SDK against an independent implementation. It is not an ICAO example:
 * Part 11 prints none for chip authentication.
 *
 * It holds one static key pair and answers, inside a secure-messaging session it was started with:
 * MSE:Set KAT, MSE:Set AT, GENERAL AUTHENTICATE, SELECT EF and READ BINARY of one file.
 */
internal class CaReferenceChip(
    private val group: Group,
    private val offeredCiphers: List<Int>,
    oldCipher: Cipher,
    oldKeys: Pair<ByteArray, ByteArray>,
    oldSsc: ByteArray,
    private val file: ByteArray,
    private val setKatSupported: Boolean = true,
) : ApduTransport {
    /** A key agreement group: a BouncyCastle EC curve or DH group, by name. */
    sealed interface Group {
        class Ec(
            val name: String,
            val domain: ECDomainParameters,
        ) : Group

        class Dh(
            val name: String,
            val params: DHParameters,
            val x942: Boolean,
        ) : Group
    }

    /** The cipher suites of Tables 5 and 6, by the last OID arc. */
    enum class Cipher(
        val code: Int,
        val keyLength: Int,
        val blockSize: Int,
    ) {
        TDES(1, 16, 8),
        AES128(2, 16, 16),
        AES192(3, 24, 16),
        AES256(4, 32, 16),
    }

    private class Session(
        val cipher: Cipher,
        val kEnc: ByteArray,
        val kMac: ByteArray,
        val ssc: ByteArray,
    )

    override val maxCommandLength: Int = 65_544
    override val supportsExtendedLength: Boolean = true

    private val random = SecureRandom()
    private val keyPair: AsymmetricCipherKeyPair = generateKeyPair()
    private var session: Session? = Session(oldCipher, oldKeys.first.copyOf(), oldKeys.second.copyOf(), oldSsc.copyOf())
    private var pendingCipher: Cipher? = null
    private var selected = false

    /** How many times the session restarted with chip-authentication keys. */
    var restarts = 0
        private set

    /** The INS/P2 of the chip-authentication commands received. */
    val commands = mutableListOf<Pair<Int, Int>>()

    private val isDh = group is Group.Dh
    private val caOid = "0.4.0.127.0.7.2.2.3"
    private val pkOid = "0.4.0.127.0.7.2.2.1"

    fun protocolOid(code: Int) = "$caOid.${if (isDh) 1 else 2}.$code"

    /** The SecurityInfos set with one ChipAuthenticationInfo per offered cipher and the public key, DER-encoded by BouncyCastle. */
    fun securityInfos(): ByteArray {
        val v = ASN1EncodableVector()
        for (code in offeredCiphers) {
            v.add(DERSequence(arrayOf(ASN1ObjectIdentifier(protocolOid(code)), ASN1Integer(1))))
        }
        v.add(DERSequence(arrayOf(ASN1ObjectIdentifier("$pkOid.${if (isDh) 1 else 2}"), subjectPublicKeyInfo())))
        return DERSet(v).encoded
    }

    private fun subjectPublicKeyInfo(): SubjectPublicKeyInfo =
        when (group) {
            is Group.Ec -> {
                val d = group.domain
                val x9 = X9ECParameters(d.curve, X9ECPoint(d.g, false), d.n, d.h)
                SubjectPublicKeyInfo(
                    AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, x9),
                    (keyPair.public as ECPublicKeyParameters).q.getEncoded(false),
                )
            }

            is Group.Dh -> {
                val p = group.params
                val algorithm =
                    if (group.x942) {
                        AlgorithmIdentifier(
                            X9ObjectIdentifiers.dhpublicnumber,
                            org.bouncycastle.asn1.x9
                                .DomainParameters(p.p, p.g, p.q, null, null),
                        )
                    } else {
                        AlgorithmIdentifier(PKCSObjectIdentifiers.dhKeyAgreement, DHParameter(p.p, p.g, 0))
                    }
                SubjectPublicKeyInfo(algorithm, ASN1Integer((keyPair.public as DHPublicKeyParameters).y))
            }
        }

    private fun generateKeyPair(): AsymmetricCipherKeyPair =
        when (group) {
            is Group.Ec -> {
                ECKeyPairGenerator().apply { init(ECKeyGenerationParameters(group.domain, random)) }.generateKeyPair()
            }

            is Group.Dh -> {
                DHKeyPairGenerator().apply { init(DHKeyGenerationParameters(random, group.params)) }.generateKeyPair()
            }
        }

    override suspend fun transceive(command: ByteArray): ByteArray {
        val c = Apdu.parse(command)
        val s = session ?: return sw(0x6982)
        if (c.cla and 0x0C != 0x0C) {
            session = null
            return sw(0x6982)
        }
        increment(s.ssc)
        val plain = unwrap(s, c)
        if (plain == null) {
            session = null
            return sw(0x6988)
        }
        val (data, status) = answer(plain)
        increment(s.ssc)
        // The answer is protected with the session that received the command: after a key
        // agreement that is still the old one (section 9.8.2).
        return wrap(s, data, status)
    }

    private fun answer(c: Apdu): Pair<ByteArray, Int> {
        when {
            c.ins == 0x22 && c.p1 == 0x41 && c.p2 == 0xA6 -> {
                commands += c.ins to c.p2
                pendingCipher = null
                if (!setKatSupported) return ByteArray(0) to 0x6A86
                if (Cipher.TDES.code !in offeredCiphers) return ByteArray(0) to 0x6A80
                val objects = Tlv.parse(c.data)
                val key = objects[0x91] ?: return ByteArray(0) to 0x6A80
                return ByteArray(0) to (if (agree(Cipher.TDES, key)) 0x9000 else 0x6A80)
            }

            c.ins == 0x22 && c.p1 == 0x41 && c.p2 == 0xA4 -> {
                commands += c.ins to c.p2
                val mechanism = Tlv.parse(c.data)[0x80] ?: return ByteArray(0) to 0x6A80
                val oid = ASN1ObjectIdentifier.getInstance(byteArrayOf(0x06, mechanism.size.toByte()) + mechanism).id
                val cipher = Cipher.entries.firstOrNull { protocolOid(it.code) == oid && it.code in offeredCiphers }
                pendingCipher = cipher
                return ByteArray(0) to (if (cipher == null) 0x6A80 else 0x9000)
            }

            c.ins == 0x86 && c.p1 == 0 && c.p2 == 0 -> {
                commands += c.ins to c.p2
                val cipher = pendingCipher ?: return ByteArray(0) to 0x6985
                pendingCipher = null
                val template = Tlv.parse(c.data)[0x7C] ?: return ByteArray(0) to 0x6A80
                val key = Tlv.parse(template)[0x80] ?: return ByteArray(0) to 0x6A80
                return if (agree(cipher, key)) byteArrayOf(0x7C, 0x00) to 0x9000 else ByteArray(0) to 0x6A80
            }

            c.ins == 0xA4 && c.p1 == 0x02 && c.p2 == 0x0C -> {
                selected = c.data.contentEquals(byteArrayOf(0x01, 0x01))
                return ByteArray(0) to (if (selected) 0x9000 else 0x6A82)
            }

            c.ins == 0xB0 -> {
                if (!selected) return ByteArray(0) to 0x6986
                val offset = (c.p1 shl 8) or c.p2
                val n = minOf(c.ne ?: 0, file.size - offset)
                return file.copyOfRange(offset, offset + n) to 0x9000
            }

            else -> {
                return ByteArray(0) to 0x6D00
            }
        }
    }

    /** Section 6.2.1: K = KA(SK_IC, PK_IFD), then the session keys (section 9.7); the new session starts at SSC 0. */
    private fun agree(
        cipher: Cipher,
        ephemeral: ByteArray,
    ): Boolean {
        val k: ByteArray =
            try {
                when (group) {
                    is Group.Ec -> {
                        val d = group.domain
                        val q = d.curve.decodePoint(ephemeral)
                        if (!q.multiply(d.n).isInfinity) return false
                        val shared = q.multiply((keyPair.private as ECPrivateKeyParameters).d).normalize()
                        if (shared.isInfinity) return false
                        BigIntegers.asUnsignedByteArray((d.curve.fieldSize + 7) / 8, shared.affineXCoord.toBigInteger())
                    }

                    is Group.Dh -> {
                        val y = BigInteger(1, ephemeral)
                        val p = group.params.p
                        if (y <= BigInteger.ONE || y >= p.subtract(BigInteger.ONE)) return false
                        val agreement = DHBasicAgreement().apply { init(keyPair.private as DHPrivateKeyParameters) }
                        val z = agreement.calculateAgreement(DHPublicKeyParameters(y, group.params))
                        BigIntegers.asUnsignedByteArray((p.bitLength() + 7) / 8, z)
                    }
                }
            } catch (e: IllegalArgumentException) {
                return false
            }
        session = Session(cipher, kdf(k, 1, cipher), kdf(k, 2, cipher), ByteArray(cipher.blockSize))
        restarts++
        return true
    }

    private fun kdf(
        k: ByteArray,
        counter: Int,
        cipher: Cipher,
    ): ByteArray {
        val digest: Digest = if (cipher == Cipher.TDES || cipher == Cipher.AES128) SHA1Digest() else SHA256Digest()
        digest.update(k, 0, k.size)
        digest.update(byteArrayOf(0, 0, 0, counter.toByte()), 0, 4)
        val out = ByteArray(digest.digestSize)
        digest.doFinal(out, 0)
        return out.copyOf(cipher.keyLength)
    }

    private fun unwrap(
        s: Session,
        c: Apdu,
    ): Apdu? {
        val objects = Tlv.parseList(c.data)
        if (objects.isEmpty() || objects.last().first != 0x8E) return null
        val header = pad(byteArrayOf(c.cla.toByte(), c.ins.toByte(), c.p1.toByte(), c.p2.toByte()), s.cipher.blockSize)
        val covered = objects.dropLast(1).fold(header) { acc, o -> acc + Tlv.encode(o.first, o.second) }
        if (!mac(s, covered).contentEquals(objects.last().second)) return null
        var data = ByteArray(0)
        var ne: Int? = null
        for ((tag, value) in objects.dropLast(1)) {
            when (tag) {
                0x87 -> data = unpad(crypt(s, value.copyOfRange(1, value.size), encrypt = false)) ?: return null
                0x85 -> data = unpad(crypt(s, value, encrypt = false)) ?: return null
                0x97 -> ne = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }.let { if (it == 0) 65_536 else it }
                else -> return null
            }
        }
        return Apdu(c.cla and 0x0C.inv(), c.ins, c.p1, c.p2, data, ne)
    }

    private fun wrap(
        s: Session,
        data: ByteArray,
        status: Int,
    ): ByteArray {
        var body = ByteArray(0)
        if (data.isNotEmpty()) body += Tlv.encode(0x87, byteArrayOf(1) + crypt(s, pad(data, s.cipher.blockSize), encrypt = true))
        body += Tlv.encode(0x99, byteArrayOf((status shr 8).toByte(), status.toByte()))
        body += Tlv.encode(0x8E, mac(s, body))
        return body + byteArrayOf((status shr 8).toByte(), status.toByte())
    }

    private fun engine(cipher: Cipher): BlockCipher = if (cipher == Cipher.TDES) DESedeEngine() else AESEngine.newInstance()

    /** CBC with a zero IV for 3DES (section 9.8.6.1), IV = E(KSEnc, SSC) for AES (section 9.8.7.1). */
    private fun crypt(
        s: Session,
        data: ByteArray,
        encrypt: Boolean,
    ): ByteArray {
        val iv =
            if (s.cipher == Cipher.TDES) {
                ByteArray(8)
            } else {
                val e = AESEngine.newInstance().apply { init(true, KeyParameter(s.kEnc)) }
                ByteArray(16).also { e.processBlock(s.ssc, 0, it, 0) }
            }
        val cbc = CBCBlockCipher.newInstance(engine(s.cipher))
        cbc.init(encrypt, ParametersWithIV(KeyParameter(s.kEnc), iv))
        val out = ByteArray(data.size)
        var i = 0
        while (i < data.size) {
            cbc.processBlock(data, i, out, i)
            i += cbc.blockSize
        }
        return out
    }

    /** Retail MAC (ISO/IEC 9797-1 MAC algorithm 3) for 3DES, CMAC cut to 8 bytes for AES, over SSC || data, padded. */
    private fun mac(
        s: Session,
        data: ByteArray,
    ): ByteArray {
        val input = pad(s.ssc + data, s.cipher.blockSize)
        val m = if (s.cipher == Cipher.TDES) ISO9797Alg3Mac(DESEngine()) else CMac(AESEngine.newInstance(), 64)
        m.init(KeyParameter(s.kMac))
        m.update(input, 0, input.size)
        val out = ByteArray(m.macSize)
        m.doFinal(out, 0)
        return out
    }

    private fun pad(
        d: ByteArray,
        bs: Int,
    ): ByteArray {
        val out = ByteArray((d.size / bs + 1) * bs)
        d.copyInto(out)
        out[d.size] = 0x80.toByte()
        return out
    }

    private fun unpad(d: ByteArray): ByteArray? {
        var i = d.size - 1
        while (i >= 0 && d[i] == 0.toByte()) i--
        return if (i < 0 || d[i] != 0x80.toByte()) null else d.copyOf(i)
    }

    private fun increment(ssc: ByteArray) {
        for (i in ssc.indices.reversed()) {
            ssc[i] = (ssc[i] + 1).toByte()
            if (ssc[i] != 0.toByte()) return
        }
    }

    private fun sw(sw: Int) = byteArrayOf((sw shr 8).toByte(), sw.toByte())

    /** A command APDU, short or extended (ISO/IEC 7816-4, section 5.1). */
    class Apdu(
        val cla: Int,
        val ins: Int,
        val p1: Int,
        val p2: Int,
        val data: ByteArray,
        val ne: Int?,
    ) {
        companion object {
            fun parse(b: ByteArray): Apdu {
                fun u(i: Int) = b[i].toInt() and 0xFF
                val h = { data: ByteArray, ne: Int? -> Apdu(u(0), u(1), u(2), u(3), data, ne) }
                if (b.size == 4) return h(ByteArray(0), null)
                if (b.size == 5) return h(ByteArray(0), if (u(4) == 0) 256 else u(4))
                if (u(4) != 0) {
                    val lc = u(4)
                    val data = b.copyOfRange(5, 5 + lc)
                    val ne = if (b.size > 5 + lc) (if (u(5 + lc) == 0) 256 else u(5 + lc)) else null
                    return h(data, ne)
                }
                val lc = (u(5) shl 8) or u(6)
                val data = b.copyOfRange(7, 7 + lc)
                val ne = if (b.size > 7 + lc) ((u(7 + lc) shl 8) or u(8 + lc)).let { if (it == 0) 65_536 else it } else null
                return h(data, ne)
            }
        }
    }

    /** Minimal BER-TLV for one-byte and two-byte tags (the ones these commands use). */
    object Tlv {
        fun parseList(b: ByteArray): List<Pair<Int, ByteArray>> {
            val out = mutableListOf<Pair<Int, ByteArray>>()
            var i = 0
            while (i < b.size) {
                var tag = b[i++].toInt() and 0xFF
                if (tag and 0x1F == 0x1F) tag = (tag shl 8) or (b[i++].toInt() and 0xFF)
                var len = b[i++].toInt() and 0xFF
                if (len > 0x80) {
                    val n = len and 0x7F
                    len = 0
                    repeat(n) { len = (len shl 8) or (b[i++].toInt() and 0xFF) }
                }
                out += tag to b.copyOfRange(i, i + len)
                i += len
            }
            return out
        }

        fun parse(b: ByteArray): Map<Int, ByteArray> = parseList(b).toMap()

        fun encode(
            tag: Int,
            value: ByteArray,
        ): ByteArray {
            val t = if (tag > 0xFF) byteArrayOf((tag shr 8).toByte(), tag.toByte()) else byteArrayOf(tag.toByte())
            val n = value.size
            val l =
                when {
                    n < 0x80 -> byteArrayOf(n.toByte())
                    n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
                    else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
                }
            return t + l + value
        }
    }
}
