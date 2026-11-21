package io.lightine.tessera.emrtd.internal.securityinfo

/** Object identifiers of the security protocols (Doc 9303 Part 11, 9.2 and 9.5; Part 10, 3.11.4). */
internal object SecurityOids {
    private const val BSI_DE = "0.4.0.127.0.7"

    /** id-PK: chip authentication public keys. `.1` DH, `.2` ECDH. */
    const val ID_PK = "$BSI_DE.2.2.1"
    const val ID_PK_DH = "$ID_PK.1"
    const val ID_PK_ECDH = "$ID_PK.2"

    /** id-TA: terminal authentication. */
    const val ID_TA = "$BSI_DE.2.2.2"

    /** id-CA: chip authentication. `.1.c` DH, `.2.c` ECDH, `c` the cipher 1 to 4. */
    const val ID_CA = "$BSI_DE.2.2.3"

    /** id-PACE: `.m.c`, `m` the key agreement and mapping, `c` the cipher 1 to 4. */
    const val ID_PACE = "$BSI_DE.2.2.4"

    /** standardizedDomainParameters (9.5.1). */
    const val STANDARDIZED_DOMAIN_PARAMETERS = "$BSI_DE.1.2"

    /** id-SecurityObject: the content type of EF.CardSecurity. */
    const val ID_SECURITY_OBJECT = "$BSI_DE.3.2.1"

    /** id-icao-mrtd-security, with the active authentication and EF.DIR entries under it. */
    const val ID_ICAO_MRTD_SECURITY = "2.23.136.1.1"
    const val ID_AA = "$ID_ICAO_MRTD_SECURITY.5"
    const val ID_EFDIR = "$ID_ICAO_MRTD_SECURITY.13"

    const val DH_PUBLIC_NUMBER = "1.2.840.10046.2.1"
    const val DH_KEY_AGREEMENT = "1.2.840.113549.1.3.1"
    const val EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    const val PRIME_FIELD = "1.2.840.10045.1.1"

    /** rsaEncryption (RFC 5280): the active-authentication key of EF.DG15 (Part 11, 6.1.5). */
    const val RSA_ENCRYPTION = "1.2.840.113549.1.1.1"

    /** CMS ContentInfo type signedData. */
    const val CMS_SIGNED_DATA = "1.2.840.113549.1.7.2"

    internal class PaceProtocol(
        val mapping: PaceMapping,
        val agreement: KeyAgreement,
        val cipher: SessionCipher,
    )

    internal class PaceGroup(
        val mapping: PaceMapping,
        val agreement: KeyAgreement,
    )

    private val paceGroups =
        mapOf(
            1 to PaceGroup(PaceMapping.GENERIC, KeyAgreement.DH),
            2 to PaceGroup(PaceMapping.GENERIC, KeyAgreement.ECDH),
            3 to PaceGroup(PaceMapping.INTEGRATED, KeyAgreement.DH),
            4 to PaceGroup(PaceMapping.INTEGRATED, KeyAgreement.ECDH),
            6 to PaceGroup(PaceMapping.CHIP_AUTHENTICATION, KeyAgreement.ECDH),
        )

    private fun cipherOf(code: Int): SessionCipher? =
        when (code) {
            1 -> SessionCipher.TDES_CBC_CBC
            2 -> SessionCipher.AES_CBC_CMAC_128
            3 -> SessionCipher.AES_CBC_CMAC_192
            4 -> SessionCipher.AES_CBC_CMAC_256
            else -> null
        }

    /** The arcs of [oid] below [prefix], or null if [oid] is not exactly [count] numeric arcs below it. */
    private fun arcsBelow(
        oid: String,
        prefix: String,
        count: Int,
    ): List<Int>? {
        if (!oid.startsWith("$prefix.")) return null
        val arcs = oid.substring(prefix.length + 1).split('.')
        if (arcs.size != count) return null
        return arcs.map { it.toIntOrNull() ?: return null }
    }

    /** The meaning of a PACE protocol OID (`id-PACE.m.c`), or null for any other OID. */
    fun paceProtocol(oid: String): PaceProtocol? {
        val arcs = arcsBelow(oid, ID_PACE, 2) ?: return null
        val group = paceGroups[arcs[0]] ?: return null
        val cipher = cipherOf(arcs[1]) ?: return null
        // The standard lists the chip authentication mapping only with AES.
        if (group.mapping == PaceMapping.CHIP_AUTHENTICATION && cipher == SessionCipher.TDES_CBC_CBC) return null
        return PaceProtocol(group.mapping, group.agreement, cipher)
    }

    /** The meaning of a PACE domain parameter OID (`id-PACE.m`), or null for any other OID. */
    fun paceDomainParameterType(oid: String): PaceGroup? {
        val arcs = arcsBelow(oid, ID_PACE, 1) ?: return null
        return paceGroups[arcs[0]]
    }

    /** The meaning of a chip authentication OID (`id-CA.a.c`), or null for any other OID. */
    fun chipAuthentication(oid: String): Pair<KeyAgreement, SessionCipher>? {
        val arcs = arcsBelow(oid, ID_CA, 2) ?: return null
        val agreement =
            when (arcs[0]) {
                1 -> KeyAgreement.DH
                2 -> KeyAgreement.ECDH
                else -> return null
            }
        val cipher = cipherOf(arcs[1]) ?: return null
        return agreement to cipher
    }

    /** The key agreement of a chip authentication public key OID (`id-PK-DH`, `id-PK-ECDH`), or null. */
    fun publicKeyAgreement(oid: String): KeyAgreement? =
        when (oid) {
            ID_PK_DH -> KeyAgreement.DH
            ID_PK_ECDH -> KeyAgreement.ECDH
            else -> null
        }

    /** True for id-TA and the OIDs below it. */
    fun isTerminalAuthentication(oid: String): Boolean = oid == ID_TA || oid.startsWith("$ID_TA.")
}

/** The standardized domain parameter sets of Doc 9303 Part 11, Table 12. Only the table: the values live with the crypto. */
internal object StandardizedParameters {
    private val table: Map<Int, StandardizedParameter> =
        listOf(
            StandardizedParameter(0, "1024-bit MODP group with 160-bit prime order subgroup", KeyAgreement.DH, 1024),
            StandardizedParameter(1, "2048-bit MODP group with 224-bit prime order subgroup", KeyAgreement.DH, 2048),
            StandardizedParameter(2, "2048-bit MODP group with 256-bit prime order subgroup", KeyAgreement.DH, 2048),
            StandardizedParameter(8, "NIST P-192", KeyAgreement.ECDH, 192),
            StandardizedParameter(9, "BrainpoolP192r1", KeyAgreement.ECDH, 192),
            StandardizedParameter(10, "NIST P-224", KeyAgreement.ECDH, 224),
            StandardizedParameter(11, "BrainpoolP224r1", KeyAgreement.ECDH, 224),
            StandardizedParameter(12, "NIST P-256", KeyAgreement.ECDH, 256),
            StandardizedParameter(13, "BrainpoolP256r1", KeyAgreement.ECDH, 256),
            StandardizedParameter(14, "BrainpoolP320r1", KeyAgreement.ECDH, 320),
            StandardizedParameter(15, "NIST P-384", KeyAgreement.ECDH, 384),
            StandardizedParameter(16, "BrainpoolP384r1", KeyAgreement.ECDH, 384),
            StandardizedParameter(17, "BrainpoolP512r1", KeyAgreement.ECDH, 512),
            StandardizedParameter(18, "NIST P-521", KeyAgreement.ECDH, 521),
        ).associateBy { it.id }

    /** First id an explicit parameter set may use: 0 to 31 are standardized or reserved. */
    const val FIRST_EXPLICIT_ID = 32

    fun byId(id: Int): StandardizedParameter? = table[id]

    /** How a PACEInfo `parameterId` is to be read for [agreement]. */
    fun kindOf(
        id: Int?,
        agreement: KeyAgreement,
    ): ParameterIdKind =
        when {
            id == null -> {
                ParameterIdKind.ABSENT
            }

            id >= FIRST_EXPLICIT_ID -> {
                ParameterIdKind.EXPLICIT
            }

            else -> {
                val known = table[id]
                when {
                    known == null -> ParameterIdKind.RESERVED
                    known.agreement == agreement -> ParameterIdKind.STANDARDIZED
                    else -> ParameterIdKind.KEY_AGREEMENT_MISMATCH
                }
            }
        }
}
