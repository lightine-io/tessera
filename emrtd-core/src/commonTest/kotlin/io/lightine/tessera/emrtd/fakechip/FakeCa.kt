package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.ca.CaContext
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DerivedKeyType
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.DiffieHellman
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EllipticCurves
import io.lightine.tessera.emrtd.internal.crypto.KeyDerivation
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters as CryptoDomainParameters

/** How a fake chip writes its CA public key's domain parameters into its security infos. */
internal enum class FakeCaKeyEncoding {
    /** `standardizedDomainParameters` with the Table 12 id. */
    STANDARDIZED,

    /** Explicit: ECParameters for ECDH, X9.42 (`dhpublicnumber`, with q) for DH. */
    EXPLICIT,

    /** DH only: PKCS #3 (`dhKeyAgreement`, no q). */
    PKCS3,
}

/** A static chip-authentication key pair, made at test time on standardized domain parameters [parameterId]. */
internal class FakeCaKey(
    val keyId: Int?,
    val parameterId: Int,
    val parameters: CryptoDomainParameters,
    val privateKey: ByteArray,
    /** DH: y with no leading zeros; ECDH: the uncompressed point. */
    val publicValue: ByteArray,
    val encoding: FakeCaKeyEncoding = FakeCaKeyEncoding.EXPLICIT,
) {
    val isDh: Boolean get() = parameters is DhDomainParameters

    /** The same key with another public value written into the infos (a damaged key). */
    fun withPublicValue(value: ByteArray) = FakeCaKey(keyId, parameterId, parameters, privateKey, value, encoding)

    companion object {
        fun generate(
            crypto: ChipCrypto,
            parameterId: Int,
            keyId: Int? = null,
            encoding: FakeCaKeyEncoding = FakeCaKeyEncoding.EXPLICIT,
        ): FakeCaKey =
            when (val p = StandardDomainParameterValues.forId(parameterId)!!) {
                is DhDomainParameters -> {
                    val sk = DiffieHellman.generatePrivateKey(crypto, p)
                    FakeCaKey(keyId, parameterId, p, sk, TlvWriter.minimalUnsigned(DiffieHellman.publicKey(crypto, p, sk)), encoding)
                }

                is EcDomainParameters -> {
                    val sk = EllipticCurves.generatePrivateKey(crypto, p)
                    FakeCaKey(keyId, parameterId, p, sk, EcPointCodec.encode(p, EllipticCurves.publicKey(crypto, p, sk)), encoding)
                }
            }
    }
}

/**
 * One chip-authentication suite the fake chip offers: [cipherCode] is the last arc of the OID
 * (1 = 3DES, 2/3/4 = AES-128/192/256), for the key [keyId].
 */
internal class FakeCaOffer(
    val dh: Boolean,
    val cipherCode: Int,
    val keyId: Int? = null,
) {
    val protocol: String get() = "${SecurityOids.ID_CA}.${if (dh) 1 else 2}.$cipherCode"

    val cipher: SmCipher
        get() =
            when (cipherCode) {
                1 -> SmCipher.DESEDE
                2 -> SmCipher.AES128
                3 -> SmCipher.AES192
                else -> SmCipher.AES256
            }
}

/**
 * The chip side of chip authentication (Doc 9303 Part 11, section 6.2.4): MSE:Set KAT, MSE:Set AT
 * and GENERAL AUTHENTICATE, inside the secure-messaging session of [sm]. On success it restarts the
 * session with the new keys and a zero counter; [FakeSm] still wraps the answer to that command with
 * the keys and counter it unwrapped the command with, which is the rule of section 9.8.2.
 *
 * Behaviour switches for the cases real chips show:
 * - [setKatSupported] false: a chip that implements only Set AT, also for 3DES (section 8.5, Note 1);
 *   Set KAT gets 6A86.
 * - [contexts]: where the chip offers chip authentication (CHIP-9); elsewhere MSE gets 6A88.
 * - [refuseGeneralAuthenticate]: answer GENERAL AUTHENTICATE with this status word and keep the old keys.
 */
internal class FakeCa(
    private val sm: FakeSm,
    private val crypto: ChipCrypto,
    val keys: List<FakeCaKey>,
    val offers: List<FakeCaOffer>,
    val setKatSupported: Boolean = true,
    val contexts: Set<CaContext> = setOf(CaContext.MASTER_FILE, CaContext.EMRTD_APPLICATION),
    var refuseGeneralAuthenticate: Int? = null,
) : FakeCommandHandler {
    private var pending: Pair<FakeCaOffer, FakeCaKey>? = null

    /** How many times the session was restarted with chip-authentication keys. */
    var restarts: Int = 0
        private set

    /** The session keys (KSEnc, KSMAC) of the last restart, for tests that check the wrapping independently. */
    var lastKeys: Pair<ByteArray, ByteArray>? = null
        private set

    /** The INS and P2 of each chip-authentication command received, in order. */
    val commands = mutableListOf<Pair<Int, Int>>()

    /** The data field of each chip-authentication command received (after secure messaging is removed). */
    val commandData = mutableListOf<ByteArray>()

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (command.cla != 0x00) return null
        val isMse = command.ins == 0x22 && command.p1 == 0x41 && (command.p2 == 0xA6 || command.p2 == 0xA4)
        val isGa = command.ins == 0x86 && command.p1 == 0 && command.p2 == 0 && pending != null
        if (!isMse && !isGa) return null
        commands += command.ins to command.p2
        commandData += command.data
        if (!sm.isActive) return status(StatusWord.SECURITY_STATUS_NOT_SATISFIED)
        val context = if (chip.currentApplication == null) CaContext.MASTER_FILE else CaContext.EMRTD_APPLICATION
        if (isMse && context !in contexts) return status(REFERENCED_DATA_NOT_FOUND)
        return when {
            isGa -> generalAuthenticate(command)
            command.p2 == 0xA6 -> setKat(command)
            else -> setAt(command)
        }
    }

    private fun setKat(c: CommandApdu): ResponseApdu {
        if (!setKatSupported) return status(StatusWord.INCORRECT_P1_P2)
        val objects = parse(c.data) ?: return status(StatusWord.WRONG_DATA)
        val ephemeral = objects[0x91] ?: return status(StatusWord.WRONG_DATA)
        val key = keyFor(objects[0x84]) ?: return status(REFERENCED_DATA_NOT_FOUND)
        offers.firstOrNull { it.cipherCode == 1 && it.dh == key.isDh && it.keyId == key.keyId }
            ?: return status(StatusWord.WRONG_DATA)
        return if (agree(key, SmCipher.DESEDE, ephemeral)) status(StatusWord.SUCCESS) else status(StatusWord.WRONG_DATA)
    }

    private fun setAt(c: CommandApdu): ResponseApdu {
        pending = null
        val objects = parse(c.data) ?: return status(StatusWord.WRONG_DATA)
        val mechanism = objects[0x80] ?: return status(StatusWord.WRONG_DATA)
        val key = keyFor(objects[0x84]) ?: return status(REFERENCED_DATA_NOT_FOUND)
        val offer =
            offers.firstOrNull { oidValue(it.protocol).contentEquals(mechanism) && it.keyId == key.keyId }
                ?: return status(StatusWord.WRONG_DATA)
        pending = offer to key
        return status(StatusWord.SUCCESS)
    }

    private fun generalAuthenticate(c: CommandApdu): ResponseApdu {
        val (offer, key) = pending!!
        pending = null
        refuseGeneralAuthenticate?.let { return status(it) }
        val template =
            try {
                TlvParser().parseSingle(c.data)
            } catch (e: TlvException) {
                return status(StatusWord.WRONG_DATA)
            }
        if (template.tag != 0x7C) return status(StatusWord.WRONG_DATA)
        val ephemeral = template.child(0x80)?.value() ?: return status(StatusWord.WRONG_DATA)
        if (!agree(key, offer.cipher, ephemeral)) return status(StatusWord.WRONG_DATA)
        return ResponseApdu(byteArrayOf(0x7C, 0x00), StatusWord.SUCCESS)
    }

    /** Key agreement and the session restart. False when the ephemeral key fails validation. */
    private fun agree(
        key: FakeCaKey,
        cipher: SmCipher,
        ephemeral: ByteArray,
    ): Boolean {
        val secret =
            try {
                when (val p = key.parameters) {
                    is DhDomainParameters -> {
                        DiffieHellman.sharedSecret(crypto, p, key.privateKey, ephemeral)
                    }

                    is EcDomainParameters -> {
                        EllipticCurves.sharedSecret(
                            crypto,
                            p,
                            key.privateKey,
                            EcPointCodec.decode(crypto, p, ephemeral),
                        )
                    }
                }
            } catch (e: ChipCryptoException) {
                return false
            }
        val enc = KeyDerivation.derive(crypto, secret, 1, cipher.keyType)
        val mac = KeyDerivation.derive(crypto, secret, 2, cipher.keyType)
        CryptoBytes.wipe(secret)
        sm.start(cipher, enc, mac, cipher.zeroSsc())
        lastKeys = enc to mac
        restarts++
        return true
    }

    private fun keyFor(reference: ByteArray?): FakeCaKey? =
        if (reference == null) {
            keys.singleOrNull()
        } else {
            val id = reference.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            keys.firstOrNull { it.keyId == id }
        }

    private fun parse(data: ByteArray): Map<Int, ByteArray>? =
        try {
            TlvParser().parseAll(data).associate { it.tag to it.value() }
        } catch (e: TlvException) {
            null
        }

    private fun status(sw: Int) = ResponseApdu(ByteArray(0), sw)

    companion object {
        const val REFERENCED_DATA_NOT_FOUND = 0x6A88

        /** The content octets of a DER OID, from the test-only DER writer. */
        fun oidValue(dotted: String): ByteArray = DerWriter.oid(dotted).let { it.copyOfRange(2, it.size) }

        /** The SecurityInfos set (DER) for [offers] and [keys]: ChipAuthenticationInfo and ChipAuthenticationPublicKeyInfo entries. */
        fun securityInfos(
            offers: List<FakeCaOffer>,
            keys: List<FakeCaKey>,
        ): ByteArray {
            val entries = mutableListOf<ByteArray>()
            for (o in offers) {
                val id = o.keyId?.let { DerWriter.integer(it.toLong()) } ?: ByteArray(0)
                entries += DerWriter.sequence(DerWriter.oid(o.protocol), DerWriter.integer(1), id)
            }
            for (k in keys) {
                val id = k.keyId?.let { DerWriter.integer(it.toLong()) } ?: ByteArray(0)
                val pk = if (k.isDh) SecurityOids.ID_PK_DH else SecurityOids.ID_PK_ECDH
                entries += DerWriter.sequence(DerWriter.oid(pk), subjectPublicKeyInfo(k), id)
            }
            return DerWriter.set(*entries.toTypedArray())
        }

        /** EF.DG14: tag 6E around [securityInfos]. */
        fun dg14(
            offers: List<FakeCaOffer>,
            keys: List<FakeCaKey>,
        ): ByteArray = TlvWriter.encode(0x6E, securityInfos(offers, keys))

        private fun subjectPublicKeyInfo(k: FakeCaKey): ByteArray {
            val algorithm =
                when (val p = k.parameters) {
                    is DhDomainParameters -> {
                        when (k.encoding) {
                            FakeCaKeyEncoding.STANDARDIZED -> {
                                standardized(k.parameterId)
                            }

                            FakeCaKeyEncoding.EXPLICIT -> {
                                DerWriter.sequence(
                                    DerWriter.oid(SecurityOids.DH_PUBLIC_NUMBER),
                                    DerWriter.sequence(uint(p.p), uint(p.g), uint(p.q!!)),
                                )
                            }

                            FakeCaKeyEncoding.PKCS3 -> {
                                DerWriter.sequence(DerWriter.oid(SecurityOids.DH_KEY_AGREEMENT), DerWriter.sequence(uint(p.p), uint(p.g)))
                            }
                        }
                    }

                    is EcDomainParameters -> {
                        if (k.encoding == FakeCaKeyEncoding.STANDARDIZED) {
                            standardized(k.parameterId)
                        } else {
                            val len = p.fieldLength
                            DerWriter.sequence(
                                DerWriter.oid(SecurityOids.EC_PUBLIC_KEY),
                                DerWriter.sequence(
                                    DerWriter.integer(1),
                                    DerWriter.sequence(DerWriter.oid(SecurityOids.PRIME_FIELD), uint(p.p)),
                                    DerWriter.sequence(
                                        DerWriter.octetString(CryptoBytes.toFixedLength(p.a, len)),
                                        DerWriter.octetString(CryptoBytes.toFixedLength(p.b, len)),
                                    ),
                                    DerWriter.octetString(EcPointCodec.encode(p, p.generator)),
                                    uint(p.n),
                                    uint(p.cofactor),
                                ),
                            )
                        }
                    }
                }
            val keyBits = if (k.isDh) uint(k.publicValue) else k.publicValue
            return DerWriter.sequence(algorithm, DerWriter.bitString(keyBits))
        }

        private fun standardized(id: Int) =
            DerWriter.sequence(DerWriter.oid(SecurityOids.STANDARDIZED_DOMAIN_PARAMETERS), DerWriter.integer(id.toLong()))

        private fun uint(v: ByteArray) = DerWriter.unsignedInteger(TlvWriter.minimalUnsigned(v))
    }
}
