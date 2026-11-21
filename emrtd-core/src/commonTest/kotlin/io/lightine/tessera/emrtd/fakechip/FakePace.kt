package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DerivedKeyType
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.KeyDerivation
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.securityinfo.SessionCipher
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/** A PACE suite a fake chip offers: protocol OID and standardized parameter ID. */
internal data class FakePaceSuite(
    val oid: String,
    val parameterId: Int,
)

/**
 * The chip side of one PACE mapping (Part 11, section 4.4.3.3). [FakePace] runs every other step;
 * each mapping card adds its own file (`FakePaceGm.kt`, then integrated and chip authentication
 * mapping).
 */
internal interface FakePaceMapping {
    val kind: PaceMapping

    /**
     * Step 2: reads the reader's mapping data (0x81 value) and returns the chip's answer (0x82
     * value) with the ephemeral domain parameters, or null to refuse the step (6A80).
     */
    fun map(
        run: FakePaceRun,
        readerData: ByteArray,
    ): Pair<ByteArray, DomainParameters>?

    /** The length in bytes of the nonce s the chip picks (one block unless the mapping needs more). */
    fun nonceLength(cipher: SmCipher): Int = cipher.blockCipher.blockSize

    /** Data objects the chip adds to its last answer after its token (0x8A for chip authentication mapping). */
    fun finalObjects(
        run: FakePaceRun,
        kEnc: ByteArray,
    ): ByteArray = ByteArray(0)
}

/** The state of one PACE run on the fake chip, after MSE:Set AT. */
internal class FakePaceRun(
    val crypto: ChipCrypto,
    val oid: String,
    val oidContent: ByteArray,
    val cipher: SmCipher,
    val staticParameters: DomainParameters,
    val mapping: FakePaceMapping,
    val passwordKey: ByteArray,
    val random: (Int) -> ByteArray,
    val privateKey: (DomainParameters) -> ByteArray,
) {
    var nonce: ByteArray? = null
    var ephemeral: DomainParameters? = null
    var nextStep: Int = 1
    var readerKey: ByteArray? = null
    var chipKey: ByteArray? = null
    var kEnc: ByteArray? = null
    var kMac: ByteArray? = null
}

/**
 * The chip side of PACE (Doc 9303 Part 11, section 4.4), written apart from the reader's engine so
 * that the two check each other: it shares only the [ChipCrypto] primitives, the KDF and the
 * standardized parameter values. On success it opens a session in [sm] with SSC 0.
 *
 * - MSE:Set AT selects a suite from [suites] and a password: [mrzInformation] (reference 1) or
 *   [can] (reference 2). An unknown suite gets 6A80, an absent password 6A88.
 * - GENERAL AUTHENTICATE runs steps 1 to 4; steps 1 to 3 must be chained (CLA 0x10), step 4 not.
 *   A wrong reader token gets 6300 (section 4.4.4.2) and ends the run.
 *
 * Fault injection: [reflectReaderKey] (answers step 3 with the reader's own key), [corruptChipToken].
 */
internal class FakePace(
    private val crypto: ChipCrypto,
    private val sm: FakeSm,
    private val suites: Set<FakePaceSuite>,
    private val mappings: List<FakePaceMapping> = listOf(FakePaceGm(), FakePaceIm()),
    private val mrzInformation: String? = null,
    private val can: String? = null,
    private val random: (Int) -> ByteArray = crypto::randomBytes,
    private val privateKey: ((DomainParameters) -> ByteArray)? = null,
) : FakeCommandHandler {
    private var run: FakePaceRun? = null

    /** Answer step 3 with the reader's own ephemeral key (a reflection). */
    var reflectReaderKey: Boolean = false

    /** Flip one bit of the chip's token. */
    var corruptChipToken: Boolean = false

    /** Answer MSE:Set AT with this status word instead of running it (any error word). */
    var refuseSetAt: Int? = null

    /** Answer step 1 with this encrypted nonce instead of the real one. */
    var nonceOverride: ByteArray? = null

    /** How many reader tokens were refused (wrong password, usually). */
    var refusedTokens: Int = 0
        private set

    /** The last MSE:Set AT data the chip received, for checks on the command. */
    var lastSetAtData: ByteArray? = null
        private set

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? =
        when {
            command.ins == 0x22 && command.cla == 0x00 && command.p1 == 0xC1 && command.p2 == 0xA4 -> setAt(command)
            command.ins == 0x86 && (command.cla == 0x00 || command.cla == 0x10) -> generalAuthenticate(command)
            else -> null
        }

    private fun setAt(c: CommandApdu): ResponseApdu {
        run = null
        refuseSetAt?.let { return status(it) }
        lastSetAtData = c.data.copyOf()
        val objects =
            try {
                TlvParser().parseAll(c.data).associate { it.tag to it.value() }
            } catch (e: TlvException) {
                return status(StatusWord.WRONG_DATA)
            }
        val oidContent = objects[0x80] ?: return status(StatusWord.WRONG_DATA)
        val reference = objects[0x83]?.singleOrNull()?.toInt() ?: return status(StatusWord.WRONG_DATA)
        val explicitId = objects[0x84]?.let { CryptoBytes.stripLeadingZeros(it).fold(0) { a, b -> (a shl 8) or (b.toInt() and 0xFF) } }
        val candidates = suites.filter { contentOf(it.oid).contentEquals(oidContent) }
        val suite =
            (if (explicitId == null) candidates.singleOrNull() else candidates.firstOrNull { it.parameterId == explicitId })
                ?: return status(StatusWord.WRONG_DATA)
        val protocol = SecurityOids.paceProtocol(suite.oid) ?: return status(StatusWord.WRONG_DATA)
        val mapping = mappings.firstOrNull { it.kind == protocol.mapping } ?: return status(StatusWord.WRONG_DATA)
        val params = StandardDomainParameterValues.forId(suite.parameterId) ?: return status(StatusWord.WRONG_DATA)
        val password =
            when (reference) {
                1 -> mrzInformation?.let { crypto.digest(HashAlgorithm.SHA1, it.encodeToByteArray()) }
                2 -> can?.encodeToByteArray()
                else -> null
            } ?: return status(0x6A88)
        val cipher = cipherOf(protocol.cipher)
        run =
            FakePaceRun(
                crypto,
                suite.oid,
                oidContent,
                cipher,
                params,
                mapping,
                KeyDerivation.derive(crypto, password, 3, keyTypeOf(cipher)),
                random,
                privateKey ?: { FakePaceMath.randomPrivateKey(crypto, it, random) },
            )
        return status(StatusWord.SUCCESS)
    }

    private fun generalAuthenticate(c: CommandApdu): ResponseApdu {
        val r = run ?: return status(StatusWord.CONDITIONS_OF_USE_NOT_SATISFIED)
        val chained = c.cla == 0x10
        if (chained != (r.nextStep < 4)) return fail(0x6883)
        val inner =
            try {
                val outer = TlvParser().parseSingle(c.data)
                if (outer.tag != 0x7C) return fail(StatusWord.WRONG_DATA)
                outer.children().associate { it.tag to it.value() }
            } catch (e: TlvException) {
                return fail(StatusWord.WRONG_DATA)
            }
        return when (r.nextStep) {
            1 -> step1(r, inner)
            2 -> step2(r, inner)
            3 -> step3(r, inner)
            else -> step4(r, inner)
        }
    }

    private fun step1(
        r: FakePaceRun,
        inner: Map<Int, ByteArray>,
    ): ResponseApdu {
        if (inner.isNotEmpty()) return fail(StatusWord.WRONG_DATA)
        val s = r.random(r.mapping.nonceLength(r.cipher))
        r.nonce = s
        val z =
            nonceOverride
                ?: crypto.encrypt(r.cipher.blockCipher, CipherMode.CBC, r.passwordKey, ByteArray(r.cipher.blockCipher.blockSize), s)
        r.nextStep = 2
        return answer(TlvWriter.encode(0x80, z))
    }

    private fun step2(
        r: FakePaceRun,
        inner: Map<Int, ByteArray>,
    ): ResponseApdu {
        val data = inner[0x81] ?: return fail(StatusWord.WRONG_DATA)
        val (chipData, ephemeral) = r.mapping.map(r, data) ?: return fail(StatusWord.WRONG_DATA)
        r.ephemeral = ephemeral
        r.nextStep = 3
        return answer(TlvWriter.encode(0x82, chipData))
    }

    private fun step3(
        r: FakePaceRun,
        inner: Map<Int, ByteArray>,
    ): ResponseApdu {
        val readerKey = inner[0x83] ?: return fail(StatusWord.WRONG_DATA)
        val params = r.ephemeral!!
        val readerElement = FakePaceMath.decode(crypto, params, readerKey) ?: return fail(StatusWord.WRONG_DATA)
        val sk = r.privateKey(params)
        val chipKey = if (reflectReaderKey) readerKey else FakePaceMath.publicKey(crypto, params, sk)
        if (!reflectReaderKey && chipKey.contentEquals(readerKey)) return fail(StatusWord.WRONG_DATA)
        val secret = FakePaceMath.secret(crypto, params, sk, readerElement)
        val keyType = keyTypeOf(r.cipher)
        r.kEnc = KeyDerivation.derive(crypto, secret, 1, keyType)
        r.kMac = KeyDerivation.derive(crypto, secret, 2, keyType)
        r.readerKey = readerKey
        r.chipKey = chipKey
        r.nextStep = 4
        return answer(TlvWriter.encode(0x84, chipKey))
    }

    private fun step4(
        r: FakePaceRun,
        inner: Map<Int, ByteArray>,
    ): ResponseApdu {
        val readerToken = inner[0x85] ?: return fail(StatusWord.WRONG_DATA)
        val params = r.ephemeral!!
        val expected = token(r, FakePaceMath.publicKeyTag(params), r.chipKey!!)
        if (!expected.contentEquals(readerToken)) {
            refusedTokens++
            return fail(0x6300)
        }
        val chipToken = token(r, FakePaceMath.publicKeyTag(params), r.readerKey!!)
        if (corruptChipToken) chipToken[0] = (chipToken[0].toInt() xor 1).toByte()
        val extra = r.mapping.finalObjects(r, r.kEnc!!)
        sm.start(r.cipher, r.kEnc!!, r.kMac!!, ByteArray(r.cipher.blockSize))
        run = null
        return answer(TlvWriter.encode(0x86, chipToken) + extra)
    }

    private fun token(
        r: FakePaceRun,
        tag: Int,
        key: ByteArray,
    ): ByteArray {
        val input = TlvWriter.constructed(0x7F49, TlvWriter.encode(0x06, r.oidContent), TlvWriter.encode(tag, key))
        return if (r.cipher == SmCipher.DESEDE) {
            crypto.retailMac(r.kMac!!, CryptoBytes.pad(input, 8))
        } else {
            crypto.cmac(r.kMac!!, input).copyOf(8)
        }
    }

    private fun answer(inner: ByteArray) = ResponseApdu(TlvWriter.encode(0x7C, inner), StatusWord.SUCCESS)

    private fun fail(sw: Int): ResponseApdu {
        run = null
        return status(sw)
    }

    private fun status(sw: Int) = ResponseApdu(ByteArray(0), sw)

    private companion object {
        fun contentOf(oid: String): ByteArray = DerWriter.oid(oid).let { it.copyOfRange(2, it.size) }

        fun cipherOf(c: SessionCipher): SmCipher =
            when (c) {
                SessionCipher.TDES_CBC_CBC -> SmCipher.DESEDE
                SessionCipher.AES_CBC_CMAC_128 -> SmCipher.AES128
                SessionCipher.AES_CBC_CMAC_192 -> SmCipher.AES192
                SessionCipher.AES_CBC_CMAC_256 -> SmCipher.AES256
            }

        fun keyTypeOf(c: SmCipher): DerivedKeyType =
            when (c) {
                SmCipher.DESEDE -> DerivedKeyType.DESEDE
                SmCipher.AES128 -> DerivedKeyType.AES128
                SmCipher.AES192 -> DerivedKeyType.AES192
                SmCipher.AES256 -> DerivedKeyType.AES256
            }
    }
}

/**
 * Group arithmetic for the fake chip, on [ChipCrypto] primitives only (not the reader's key
 * agreement helpers). DH values go on the wire without leading zeros, EC points as `04 || X || Y`.
 */
internal object FakePaceMath {
    fun publicKeyTag(params: DomainParameters): Int = if (params is DhDomainParameters) 0x84 else 0x86

    /** A random private key below the group order. */
    fun randomPrivateKey(
        crypto: ChipCrypto,
        params: DomainParameters,
        random: (Int) -> ByteArray,
    ): ByteArray {
        val order =
            when (params) {
                is DhDomainParameters -> params.q!!
                is EcDomainParameters -> params.n
            }
        while (true) {
            val k = crypto.modMultiply(random(order.size + 8), byteArrayOf(1), order)
            if (!CryptoBytes.isZero(k)) return k
        }
    }

    fun publicKey(
        crypto: ChipCrypto,
        params: DomainParameters,
        privateKey: ByteArray,
    ): ByteArray =
        when (params) {
            is DhDomainParameters -> CryptoBytes.stripLeadingZeros(crypto.modPow(params.g, privateKey, params.p))
            is EcDomainParameters -> encodePoint(params, crypto.ecMultiply(params, params.generator, privateKey)!!)
        }

    /** The peer's group element, or null when it is not a valid key. Returns DH as bytes, EC as [EcPoint]. */
    fun decode(
        crypto: ChipCrypto,
        params: DomainParameters,
        encoded: ByteArray,
    ): Any? =
        when (params) {
            is DhDomainParameters -> {
                val one = byteArrayOf(1)
                val pMinusOne = crypto.modSubtract(params.p, one, params.p)
                val ok =
                    CryptoBytes.compareUnsigned(encoded, one) > 0 &&
                        CryptoBytes.compareUnsigned(encoded, pMinusOne) < 0 &&
                        CryptoBytes.unsignedEquals(crypto.modPow(encoded, params.q!!, params.p), one)
                if (ok) encoded else null
            }

            is EcDomainParameters -> {
                val len = params.fieldLength
                if (encoded.size != 1 + 2 * len || encoded[0] != 0x04.toByte()) {
                    null
                } else {
                    val point = EcPoint(encoded.copyOfRange(1, 1 + len), encoded.copyOfRange(1 + len, encoded.size))
                    if (crypto.ecIsOnCurve(params, point)) point else null
                }
            }
        }

    /** The raw agreement: `peer ^ sk mod p` (DH) or the point `sk * peer` (EC). */
    fun agree(
        crypto: ChipCrypto,
        params: DomainParameters,
        privateKey: ByteArray,
        peer: Any,
    ): Any =
        when (params) {
            is DhDomainParameters -> crypto.modPow(peer as ByteArray, privateKey, params.p)
            is EcDomainParameters -> crypto.ecMultiply(params, peer as EcPoint, privateKey)!!
        }

    /** The KDF input K: the DH value at the length of p, or the x-coordinate at the field length. */
    fun secret(
        crypto: ChipCrypto,
        params: DomainParameters,
        privateKey: ByteArray,
        peer: Any,
    ): ByteArray =
        when (val v = agree(crypto, params, privateKey, peer)) {
            is EcPoint -> CryptoBytes.toFixedLength(v.x, (params as EcDomainParameters).fieldLength)
            else -> CryptoBytes.toFixedLength(v as ByteArray, (params as DhDomainParameters).length)
        }

    fun encodePoint(
        params: EcDomainParameters,
        point: EcPoint,
    ): ByteArray =
        byteArrayOf(0x04) + CryptoBytes.toFixedLength(point.x, params.fieldLength) + CryptoBytes.toFixedLength(point.y, params.fieldLength)
}
