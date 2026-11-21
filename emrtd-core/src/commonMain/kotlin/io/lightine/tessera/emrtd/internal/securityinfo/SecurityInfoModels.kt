package io.lightine.tessera.emrtd.internal.securityinfo

/**
 * Why chip security data could not be read. Reported with an offset, never with data bytes: the
 * bytes come from the chip and may be hostile or damaged.
 */
internal enum class SecurityInfoFailure {
    /** The DER bytes are malformed (see [io.lightine.tessera.emrtd.internal.asn1.Asn1Failure]). */
    MALFORMED_DER,

    /** The BER-TLV bytes are malformed (see [io.lightine.tessera.emrtd.internal.tlv.TlvFailure]). */
    MALFORMED_TLV,

    /** A tag, a shape or an element count is not the one the structure requires. */
    UNEXPECTED_STRUCTURE,

    /** The version is not the one the protocol defines (PACEInfo 2; the others 1). */
    UNSUPPORTED_VERSION,

    /** A value is out of range: a negative id, a missing key value, an oversized number. */
    INVALID_VALUE,

    /** The signed content of EF.CardSecurity does not have the SecurityObject content type. */
    WRONG_CONTENT_TYPE,
}

/** Chip security data could not be read. Carries only the reason and an offset. */
internal class SecurityInfoException(
    val failure: SecurityInfoFailure,
    val offset: Int,
) : IllegalStateException("SecurityInfo: $failure at offset $offset")

/** The key agreement of a protocol or key: Diffie-Hellman over a prime field, or over an elliptic curve. */
internal enum class KeyAgreement { DH, ECDH }

/** How PACE maps the shared secret to the generator (Doc 9303 Part 11, 4.4.3). */
internal enum class PaceMapping { GENERIC, INTEGRATED, CHIP_AUTHENTICATION }

/** The secure-messaging cipher and MAC a protocol names: 3DES with a retail MAC, or AES with CMAC. */
internal enum class SessionCipher(
    val keyBits: Int,
) {
    TDES_CBC_CBC(112),
    AES_CBC_CMAC_128(128),
    AES_CBC_CMAC_192(192),
    AES_CBC_CMAC_256(256),
}

/** A standardized domain parameter set (Doc 9303 Part 11, Table 12). */
internal class StandardizedParameter(
    val id: Int,
    val name: String,
    val agreement: KeyAgreement,
    val sizeBits: Int,
    /**
     * False for the set Table 12 marks as not usable with the integrated mapping (NIST P-224: its
     * prime is not 3 mod 4, which the point encoding of Appendix B needs).
     */
    val usableWithIntegratedMapping: Boolean = true,
)

/**
 * How a PACEInfo's `parameterId` is to be read.
 *
 * Only [STANDARDIZED] is something the reader can use. Explicit domain parameters sent by the chip
 * are unprotected and may be insecure, so a reader must not use them unless it knows them to be safe
 * (Part 11, 9.2.2); this module never offers them as usable.
 */
internal enum class ParameterIdKind {
    /** No `parameterId` was given. */
    ABSENT,

    /** An id from Table 12 whose type fits the PACE key agreement. */
    STANDARDIZED,

    /** An id from Table 12 whose type does not fit the key agreement (a DH id with ECDH, or the reverse). */
    KEY_AGREEMENT_MISMATCH,

    /** An id reserved for future standardized sets (3 to 7, 19 to 31). */
    RESERVED,

    /** An id from 32 up: it names a PACEDomainParameterInfo the chip sends, which is not used. */
    EXPLICIT,
}

/** One entry of a SecurityInfos set. */
internal sealed interface SecurityInfo {
    /** The protocol object identifier, in dotted form. */
    val protocol: String
}

/** PACE (Part 11, 9.2.1). [parameterId] is the raw value; [parameterKind] says how to read it. */
internal data class PaceInfo(
    override val protocol: String,
    val mapping: PaceMapping,
    val agreement: KeyAgreement,
    val cipher: SessionCipher,
    val version: Int,
    val parameterId: Int?,
    val parameterKind: ParameterIdKind,
) : SecurityInfo {
    /** The standardized set this PACEInfo names, or `null` if it names none the reader can use. */
    val standardizedParameter: StandardizedParameter?
        get() = if (parameterKind == ParameterIdKind.STANDARDIZED) StandardizedParameters.byId(parameterId!!) else null

    /**
     * True when the PACEInfo names a standardized set that fits its key agreement and its mapping
     * (Table 12: NIST P-224 cannot be used with the integrated mapping).
     */
    val usable: Boolean
        get() =
            parameterKind == ParameterIdKind.STANDARDIZED &&
                (mapping != PaceMapping.INTEGRATED || standardizedParameter?.usableWithIntegratedMapping == true)

    /** With chip authentication mapping, [parameterId] is also the key id of the chip's CA public key. */
    val caKeyId: Int? get() = if (mapping == PaceMapping.CHIP_AUTHENTICATION) parameterId else null
}

/**
 * Explicit domain parameters for PACE (Part 11, 9.2.2). Kept as an observation. They are never
 * offered for use: see [ParameterIdKind].
 */
internal data class PaceDomainParameterInfo(
    override val protocol: String,
    val agreement: KeyAgreement,
    val mapping: PaceMapping,
    val parameters: DomainParameters,
    val parameterId: Int?,
) : SecurityInfo

/** Chip authentication (ChipAuthenticationInfo, Part 11, 9.2.5). */
internal data class ChipAuthenticationInfo(
    override val protocol: String,
    val agreement: KeyAgreement,
    val cipher: SessionCipher,
    val version: Int,
    val keyId: Int?,
) : SecurityInfo

/** A chip authentication public key (ChipAuthenticationPublicKeyInfo, Part 11, 9.2.6). */
internal class ChipAuthenticationPublicKeyInfo(
    override val protocol: String,
    val agreement: KeyAgreement,
    val key: SubjectPublicKey,
    val keyId: Int?,
) : SecurityInfo

/** Active authentication with ECDSA (ActiveAuthenticationInfo, Part 11, 9.2.4). */
internal data class ActiveAuthenticationInfo(
    override val protocol: String,
    val version: Int,
    val signatureAlgorithm: String,
) : SecurityInfo

/**
 * Terminal authentication (Part 11, 9.2.8). Recognized and not interpreted: Terminal Authentication
 * is out of scope for the SDK, and its parameters are never used.
 */
internal data class TerminalAuthenticationInfo(
    override val protocol: String,
) : SecurityInfo

/** A secured copy of EF.DIR (EFDIRInfo, Part 11, 9.2.10). */
internal class EfDirInfo(
    override val protocol: String,
    val applications: List<EfDirEntry>,
    val encodedEfDir: ByteArray,
) : SecurityInfo

/** An entry with a protocol this module does not know. Ignored, as the standard allows (9.2). */
internal data class UnknownSecurityInfo(
    override val protocol: String,
) : SecurityInfo

/** An entry with a known protocol that could not be read. It is not used. [protocol] is null if even that was unreadable. */
internal data class RejectedSecurityInfo(
    val protocol: String?,
    val failure: SecurityInfoFailure,
    val offset: Int,
)

/** A parsed SecurityInfos set: the entries that were read and the ones that were rejected. */
internal class SecurityInfos(
    val entries: List<SecurityInfo>,
    val rejected: List<RejectedSecurityInfo>,
) {
    val paceInfos: List<PaceInfo> get() = entries.filterIsInstance<PaceInfo>()

    /** The PACEInfos the reader may use: they name a standardized domain parameter set. */
    val usablePaceInfos: List<PaceInfo> get() = paceInfos.filter { it.usable }

    /** PACEInfos that name explicit, reserved or mismatched parameters: flagged, never used. */
    val unusablePaceInfos: List<PaceInfo> get() = paceInfos.filter { !it.usable }

    /** The explicit domain parameters the chip sent. Observations only. */
    val explicitDomainParameters: List<PaceDomainParameterInfo> get() = entries.filterIsInstance<PaceDomainParameterInfo>()

    val chipAuthenticationInfos: List<ChipAuthenticationInfo> get() = entries.filterIsInstance<ChipAuthenticationInfo>()

    val chipAuthenticationPublicKeys: List<ChipAuthenticationPublicKeyInfo>
        get() = entries.filterIsInstance<ChipAuthenticationPublicKeyInfo>()

    val activeAuthenticationInfos: List<ActiveAuthenticationInfo> get() = entries.filterIsInstance<ActiveAuthenticationInfo>()

    val terminalAuthenticationInfos: List<TerminalAuthenticationInfo> get() = entries.filterIsInstance<TerminalAuthenticationInfo>()

    val efDirInfo: EfDirInfo? get() = entries.filterIsInstance<EfDirInfo>().firstOrNull()

    val unknown: List<UnknownSecurityInfo> get() = entries.filterIsInstance<UnknownSecurityInfo>()

    /**
     * The chip authentication public key with [keyId], or, if [keyId] is null, the only key when
     * there is exactly one. `null` otherwise: a key is never guessed (Part 11, 9.2.6).
     */
    fun chipAuthenticationPublicKey(keyId: Int?): ChipAuthenticationPublicKeyInfo? {
        val keys = chipAuthenticationPublicKeys
        return if (keyId == null) keys.singleOrNull() else keys.firstOrNull { it.keyId == keyId }
    }

    /** The CA public key a chip-authentication-mapping [pace] names: its key id equals the `parameterId`. */
    fun caKeyFor(pace: PaceInfo): ChipAuthenticationPublicKeyInfo? = pace.caKeyId?.let { id -> chipAuthenticationPublicKey(id) }

    /** The ChipAuthenticationInfo for the key [keyId] (or the only one if [keyId] is null). */
    fun chipAuthenticationInfo(keyId: Int?): ChipAuthenticationInfo? {
        val infos = chipAuthenticationInfos
        return if (keyId == null) infos.singleOrNull() else infos.firstOrNull { it.keyId == keyId }
    }
}

/** A domain parameter set as an AlgorithmIdentifier carries it (Part 11, 9.5). */
internal sealed interface DomainParameters {
    /** A set from Table 12, by id (9.5.1). */
    data class Standardized(
        val id: Int,
    ) : DomainParameters

    /** Explicit elliptic-curve parameters over a prime field (ECParameters, 9.5.2). Values are unsigned big-endian. */
    class ExplicitEc(
        val p: ByteArray,
        val a: ByteArray,
        val b: ByteArray,
        /** The base point, uncompressed encoding. */
        val generator: ByteArray,
        val order: ByteArray,
        val cofactor: ByteArray?,
    ) : DomainParameters

    /** Explicit DH parameters. [q] is only present in X9.42 form. Values are unsigned big-endian. */
    class ExplicitDh(
        val format: DhFormat,
        val p: ByteArray,
        val g: ByteArray,
        val q: ByteArray?,
    ) : DomainParameters

    /** An algorithm or parameter form this module does not read (named curves, binary fields, other OIDs). */
    data class Unsupported(
        val algorithm: String,
    ) : DomainParameters
}

/** The encoding of Diffie-Hellman parameters and keys. */
internal enum class DhFormat {
    /** PKCS#3 (`dhKeyAgreement`): Doc 9303 compliant. */
    PKCS3,

    /** ANSI X9.42 (`dhpublicnumber`): issued by some chips, not compliant (Part 11, 8.5 Note 2). */
    X942,
}

/**
 * A SubjectPublicKeyInfo of a chip authentication key. [publicValue] is the DH public value y as an
 * unsigned big-endian number, or the elliptic-curve point as encoded; for an algorithm this module
 * does not read, the raw BIT STRING bytes.
 */
internal class SubjectPublicKey(
    val parameters: DomainParameters,
    val publicValue: ByteArray,
)

/** An application listed in EF.DIR. */
internal class EfDirEntry(
    val aid: ByteArray,
    /** The LDS application this AID names, or `null` for any other. */
    val application: LdsApplication?,
)

/** The applications Part 10 (3.11.2, Table 31) names by AID. */
internal enum class LdsApplication {
    LDS1_EMRTD,
    TRAVEL_RECORDS,
    VISA_RECORDS,
    ADDITIONAL_BIOMETRICS,
}
