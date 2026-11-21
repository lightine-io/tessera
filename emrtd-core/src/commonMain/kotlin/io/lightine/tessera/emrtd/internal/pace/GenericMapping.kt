package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EllipticCurves
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping

/**
 * The generic mapping of Part 11, section 4.4.3.3.1. Reader and chip exchange ephemeral mapping
 * keys (data objects 0x81 and 0x82, encoded as in section 4.4.5.2.1) and agree on H (ECDH) or h
 * (DH) by an anonymous key agreement in the static group; the new generator is
 * - ECDH: Ĝ = s·G + H;
 * - DH: ĝ = g^s · h mod p.
 *
 * The chip's mapping key is validated first (ECDH: on the curve and in the subgroup; DH: RFC 2631,
 * as the section's note asks). A degenerate generator (the point at infinity, or 1) stops the run.
 *
 * Open so that the chip authentication mapping, whose mapping phase is the same (section
 * 4.4.3.3.3), can build on it and read [chipMappingKey].
 */
internal open class GenericMapping : Mapping {
    override val kind: PaceMapping
        get() = PaceMapping.GENERIC

    /** The chip's mapping public key PK_Map,IC (canonical encoding), once [map] has run. */
    var chipMappingKey: ByteArray? = null
        private set

    override suspend fun map(
        context: MappingContext,
        nonce: ByteArray,
        exchange: suspend (ByteArray) -> ByteArray,
    ): PaceGroup {
        val crypto = context.crypto
        val group = context.suite.group
        val privateKey = context.newPrivateKey(group)
        try {
            val chipKey = group.validatePublicKey(crypto, exchange(group.publicKey(crypto, privateKey)))
            chipMappingKey = chipKey
            return when (group) {
                is PaceGroup.Ec -> {
                    val params = group.parameters
                    val h = EllipticCurves.sharedPoint(crypto, params, privateKey, EcPointCodec.decode(crypto, params, chipKey))
                    val sg = crypto.ecMultiply(params, params.generator, nonce)
                    // s·G is the point at infinity only when s is a multiple of n; then Ĝ = H.
                    val mapped = if (sg == null) h else crypto.ecAdd(params, sg, h)
                    mapped ?: throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE)
                    PaceGroup.Ec(params.withGenerator(mapped))
                }

                is PaceGroup.Dh -> {
                    val params = group.parameters
                    val h = group.sharedSecret(crypto, privateKey, chipKey)
                    val gs = crypto.modPow(params.g, nonce, params.p)
                    val mapped = crypto.modMultiply(gs, h, params.p)
                    CryptoBytes.wipe(h, gs)
                    if (CryptoBytes.compareUnsigned(mapped, CryptoBytes.ofInt(1)) <= 0) {
                        throw PaceException(PaceFailure.DEGENERATE_GENERATOR, PaceStep.MAP_NONCE)
                    }
                    PaceGroup.Dh(params.withGenerator(mapped))
                }
            }
        } finally {
            CryptoBytes.wipe(privateKey)
        }
    }
}
