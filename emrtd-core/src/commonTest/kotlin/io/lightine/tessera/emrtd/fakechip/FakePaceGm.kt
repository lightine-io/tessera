package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping

/**
 * The chip side of the generic mapping (Part 11, section 4.4.3.3.1): the chip sends its mapping
 * key, agrees on H (ECDH) or h (DH) with the reader's, and maps the nonce to Ĝ = s·G + H or
 * ĝ = g^s · h mod p. A reader key that is not a valid group element gets 6A80.
 */
internal class FakePaceGm : FakePaceMapping {
    override val kind: PaceMapping = PaceMapping.GENERIC

    override fun map(
        run: FakePaceRun,
        readerData: ByteArray,
    ): Pair<ByteArray, DomainParameters>? {
        val crypto = run.crypto
        val params = run.staticParameters
        val reader = FakePaceMath.decode(crypto, params, readerData) ?: return null
        val sk = run.privateKey(params)
        val chipKey = FakePaceMath.publicKey(crypto, params, sk)
        val shared = FakePaceMath.agree(crypto, params, sk, reader)
        val s = run.nonce!!
        val mapped: DomainParameters =
            when (params) {
                is EcDomainParameters -> {
                    val sg = crypto.ecMultiply(params, params.generator, s)
                    val g = if (sg == null) shared as EcPoint else crypto.ecAdd(params, sg, shared as EcPoint) ?: return null
                    params.withGenerator(g)
                }

                is DhDomainParameters -> {
                    params.withGenerator(crypto.modMultiply(crypto.modPow(params.g, s, params.p), shared as ByteArray, params.p))
                }
            }
        return chipKey to mapped
    }
}
