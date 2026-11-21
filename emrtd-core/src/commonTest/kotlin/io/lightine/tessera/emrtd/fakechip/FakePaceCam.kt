package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/**
 * The chip side of the chip authentication mapping (Part 11, sections 4.4.3.3.3 and 4.4.3.5): the
 * mapping step is the generic mapping's, written again here because this side keeps its mapping
 * private key SK_Map,IC. After its token the chip appends 0x8A, the encrypted chip authentication
 * data: CA_IC = SK_IC^-1 · SK_Map,IC mod n, FE2OS at the length of n, padding method 2, AES-CBC
 * with KSEnc and IV = E(KSEnc, all-ones block).
 *
 * [staticPrivateKey] is SK_IC in the PACE group; [chipAuthenticationPublicKeyInfo] writes the
 * matching ChipAuthenticationPublicKeyInfo for EF.CardSecurity.
 *
 * Fault injection: [omitData] (no 0x8A), [corruptData] (one bit of CA_IC flipped before
 * encryption), [badPadding] (encrypts CA_IC without its padding block).
 */
internal class FakePaceCam(
    val staticPrivateKey: ByteArray,
) : FakePaceMapping {
    override val kind: PaceMapping = PaceMapping.CHIP_AUTHENTICATION

    var omitData: Boolean = false
    var corruptData: Boolean = false
    var badPadding: Boolean = false

    private var mappingPrivateKey: ByteArray? = null

    override fun map(
        run: FakePaceRun,
        readerData: ByteArray,
    ): Pair<ByteArray, DomainParameters>? {
        val crypto = run.crypto
        val params = run.staticParameters as? EcDomainParameters ?: return null
        val reader = FakePaceMath.decode(crypto, params, readerData) as EcPoint? ?: return null
        val sk = run.privateKey(params)
        mappingPrivateKey = sk
        val chipKey = FakePaceMath.publicKey(crypto, params, sk)
        val shared = FakePaceMath.agree(crypto, params, sk, reader) as EcPoint
        val sg = crypto.ecMultiply(params, params.generator, run.nonce!!)
        val g = if (sg == null) shared else crypto.ecAdd(params, sg, shared) ?: return null
        return chipKey to params.withGenerator(g)
    }

    override fun finalObjects(
        run: FakePaceRun,
        kEnc: ByteArray,
    ): ByteArray {
        if (omitData) return ByteArray(0)
        val crypto = run.crypto
        val params = run.staticParameters as EcDomainParameters
        val nLength = CryptoBytes.stripLeadingZeros(params.n).size
        val inverse = crypto.modInverse(staticPrivateKey, params.n)
        val ca = CryptoBytes.toFixedLength(crypto.modMultiply(inverse, mappingPrivateKey!!, params.n), nLength)
        if (corruptData) ca[ca.size - 1] = (ca[ca.size - 1].toInt() xor 1).toByte()
        val iv = crypto.encrypt(BlockCipher.AES, CipherMode.ECB, kEnc, null, ByteArray(16) { -1 })
        val plain = if (badPadding) ca else CryptoBytes.pad(ca, 16)
        val encrypted = crypto.encrypt(BlockCipher.AES, CipherMode.CBC, kEnc, iv, plain)
        return TlvWriter.encode(0x8A, encrypted)
    }

    /**
     * ChipAuthenticationPublicKeyInfo (Part 11, 9.2.6) for SK_IC: id-PK-ECDH, the standardized
     * parameters [parameterId], the uncompressed point, and keyId [keyId] (the PACEInfo parameter ID).
     */
    fun chipAuthenticationPublicKeyInfo(
        params: EcDomainParameters,
        parameterId: Int,
        keyId: Int? = parameterId,
    ): ByteArray {
        val point = crypto().ecMultiply(params, params.generator, staticPrivateKey)!!
        val spki =
            DerWriter.sequence(
                DerWriter.sequence(DerWriter.oid("0.4.0.127.0.7.1.2"), DerWriter.integer(parameterId.toLong())),
                DerWriter.bitString(FakePaceMath.encodePoint(params, point)),
            )
        return DerWriter.sequence(
            *listOfNotNull(
                DerWriter.oid("0.4.0.127.0.7.2.2.1.2"),
                spki,
                keyId?.let { DerWriter.integer(it.toLong()) },
            ).toTypedArray(),
        )
    }

    private fun crypto() =
        io.lightine.tessera.emrtd.internal.crypto
            .chipCrypto()
}
