package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.securityinfo.KeyAgreement
import io.lightine.tessera.emrtd.internal.securityinfo.PaceInfo
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.securityinfo.ParameterIdKind
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityOids
import io.lightine.tessera.emrtd.internal.securityinfo.SessionCipher
import io.lightine.tessera.emrtd.internal.securityinfo.StandardizedParameter
import io.lightine.tessera.emrtd.internal.securityinfo.StandardizedParameters
import io.lightine.tessera.emrtd.internal.sm.SmCipher

/**
 * One PACE algorithm combination the reader can run (Part 11, Tables 2 and 3): the protocol OID
 * with its mapping, key agreement and cipher, and a standardized domain parameter set (Table 12).
 *
 * Only standardized parameters are offered: explicit ones from PACEDomainParameterInfo are
 * unprotected and may be insecure (section 9.2.2), so the reader never uses them.
 */
internal class PaceSuite private constructor(
    /** The protocol OID, dotted, as sent in MSE:Set AT and in the token input. */
    val oid: String,
    val mapping: PaceMapping,
    val agreement: KeyAgreement,
    val sessionCipher: SessionCipher,
    val parameter: StandardizedParameter,
    /** The static domain parameters D_IC. */
    val group: PaceGroup,
) {
    /** The ID of the standardized domain parameters (Table 12). */
    val parameterId: Int
        get() = parameter.id

    /** The secure-messaging cipher of the session keys and of Kπ. */
    val smCipher: SmCipher
        get() =
            when (sessionCipher) {
                SessionCipher.TDES_CBC_CBC -> SmCipher.DESEDE
                SessionCipher.AES_CBC_CMAC_128 -> SmCipher.AES128
                SessionCipher.AES_CBC_CMAC_192 -> SmCipher.AES192
                SessionCipher.AES_CBC_CMAC_256 -> SmCipher.AES256
            }

    override fun toString(): String = "PaceSuite($mapping, $agreement, $sessionCipher, ${parameter.name})"

    internal companion object {
        /**
         * The suite for a PACE protocol [oid] and standardized [parameterId]. Throws
         * [PaceException] with [PaceFailure.UNSUPPORTED_SUITE] when the OID is not a PACE protocol
         * or the ID is not a standardized set of the OID's key agreement (or one Table 12 rules out
         * for the OID's mapping: NIST P-224 with the integrated mapping).
         */
        fun of(
            oid: String,
            parameterId: Int,
        ): PaceSuite {
            val protocol = SecurityOids.paceProtocol(oid) ?: throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
            if (StandardizedParameters.kindOf(parameterId, protocol.agreement) != ParameterIdKind.STANDARDIZED) {
                throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
            }
            val parameter = StandardizedParameters.byId(parameterId) ?: throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
            // Table 12: NIST P-224 cannot be used with the integrated mapping.
            if (protocol.mapping == PaceMapping.INTEGRATED && !parameter.usableWithIntegratedMapping) {
                throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
            }
            val group =
                when (val values = StandardDomainParameterValues.forId(parameterId)) {
                    is DhDomainParameters -> PaceGroup.Dh(values)
                    is EcDomainParameters -> PaceGroup.Ec(values)
                    null -> throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
                }
            return PaceSuite(oid, protocol.mapping, protocol.agreement, protocol.cipher, parameter, group)
        }

        /** The suite a chip's PACEInfo names; [PaceFailure.UNSUPPORTED_SUITE] when it is not usable. */
        fun of(info: PaceInfo): PaceSuite {
            val id = info.parameterId
            if (!info.usable || id == null) throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
            return of(info.protocol, id)
        }
    }
}
