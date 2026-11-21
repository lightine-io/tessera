package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EllipticCurves
import io.lightine.tessera.emrtd.internal.pa.PaCheck
import io.lightine.tessera.emrtd.internal.pa.PaCheckKind
import io.lightine.tessera.emrtd.internal.pa.PaOutcome
import io.lightine.tessera.emrtd.internal.pa.SignedObject
import io.lightine.tessera.emrtd.internal.securityinfo.ChipAuthenticationPublicKeyInfo
import io.lightine.tessera.emrtd.internal.securityinfo.ChipSecurityFiles
import io.lightine.tessera.emrtd.internal.securityinfo.DomainParameters
import io.lightine.tessera.emrtd.internal.securityinfo.KeyAgreement
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoException
import io.lightine.tessera.emrtd.internal.sm.SessionKeys

/**
 * The chip authentication mapping of Part 11, sections 4.4.3.3.3 and 4.4.3.5. The mapping phase is
 * the generic mapping's ([GenericMapping]); after the tokens, the chip's last answer carries the
 * encrypted chip authentication data A_IC (data object 0x8A, section 4.4.5). This class decrypts it
 * ([afterAuthentication]); [ChipAuthMappingCheck] later checks it against the chip's static key.
 *
 * Decryption (sections 4.4.3.5.3, 4.4.3.5.4, 4.4.5.6): AES-CBC with KSEnc and IV = E(KSEnc, -1),
 * where -1 is the 128-bit block with every bit set; ISO/IEC 9797-1 padding method 2; the plain value
 * CA_IC is FE2OS-encoded, at the byte length of the group order n (leading zeros kept).
 *
 * The data never stops the run: the session keys are good once both tokens verified. What was
 * received, and what could not be read, goes into [ChipAuthMappingEvidence] as a fact.
 */
internal class ChipAuthMapping : GenericMapping() {
    override val kind: PaceMapping
        get() = PaceMapping.CHIP_AUTHENTICATION

    override fun afterAuthentication(
        context: MappingContext,
        keys: SessionKeys,
        response: AuthenticationResponse,
    ): ChipAuthMappingEvidence {
        val suite = context.suite
        val mappingKey = checkNotNull(chipMappingKey) { "the mapping step has not run" }
        val encrypted = response.objects[TAG_ENCRYPTED_CHIP_AUTHENTICATION_DATA]
        val decrypted = encrypted?.let { decrypt(context.crypto, suite, keys.encKey, it) }
        return ChipAuthMappingEvidence(
            suite = suite,
            chipMappingKey = mappingKey.copyOf(),
            encryptedData = encrypted?.copyOf(),
            chipAuthenticationData = decrypted?.getOrNull(),
            dataFailure =
                when {
                    encrypted == null -> CamFailure.DATA_ABSENT
                    else -> decrypted?.exceptionOrNull()?.let { CamFailure.DATA_MALFORMED }
                },
        )
    }

    internal companion object {
        /** The data object of the encrypted chip authentication data in the last answer (section 4.4.5). */
        const val TAG_ENCRYPTED_CHIP_AUTHENTICATION_DATA = 0x8A
        private const val AES_BLOCK = 16

        /**
         * IV = E(KSEnc, -1) (section 4.4.3.5.4): AES of the all-ones block under [encKey].
         */
        fun iv(
            crypto: ChipCrypto,
            encKey: ByteArray,
        ): ByteArray = crypto.encrypt(BlockCipher.AES, CipherMode.ECB, encKey, null, ByteArray(AES_BLOCK) { -1 })

        /**
         * Decrypts A_IC and returns CA_IC as FE2OS (section 4.4.5.6): a failure when the length is not
         * whole blocks, the padding is wrong, or the value is not exactly the byte length of n.
         */
        fun decrypt(
            crypto: ChipCrypto,
            suite: PaceSuite,
            encKey: ByteArray,
            encrypted: ByteArray,
        ): Result<ByteArray> {
            val group =
                suite.group as? PaceGroup.Ec ?: return Result.failure(ChipCryptoException("chip authentication mapping is ECDH only"))
            if (encrypted.isEmpty() || encrypted.size % AES_BLOCK != 0) return Result.failure(ChipCryptoException("not whole blocks"))
            val iv = iv(crypto, encKey)
            val padded = crypto.decrypt(BlockCipher.AES, CipherMode.CBC, encKey, iv, encrypted)
            val plain = CryptoBytes.unpad(padded)
            CryptoBytes.wipe(padded, iv)
            if (plain == null) return Result.failure(ChipCryptoException("wrong padding"))
            if (plain.size != orderLength(group.parameters)) {
                CryptoBytes.wipe(plain)
                return Result.failure(ChipCryptoException("not the length of the group order"))
            }
            return Result.success(plain)
        }

        /** The byte length of the group order n: the FE2OS length of CA_IC. */
        fun orderLength(params: EcDomainParameters): Int = CryptoBytes.stripLeadingZeros(params.n).size
    }
}

/**
 * What the chip authentication mapping learned during PACE. The key check needs EF.CardSecurity,
 * read after PACE, so it runs later ([ChipAuthMappingCheck.run]).
 */
internal class ChipAuthMappingEvidence(
    /** The suite PACE ran; its parameter ID is also the key ID of the chip's static key (Part 11, 9.2.1). */
    val suite: PaceSuite,
    /** PK_Map,IC: the chip's mapping public key, uncompressed point. */
    val chipMappingKey: ByteArray,
    /** A_IC as received (the value of data object 0x8A), or null when the chip sent none. */
    val encryptedData: ByteArray?,
    /** CA_IC, decrypted and unpadded (FE2OS, the length of n), or null when absent or unreadable. */
    val chipAuthenticationData: ByteArray?,
    /** Why there is no [chipAuthenticationData]: absent or malformed. Null when it was read. */
    val dataFailure: CamFailure?,
) : MappingEvidence {
    /** The key ID of the static chip authentication key the chip used: the PACEInfo parameter ID. */
    val keyId: Int get() = suite.parameterId

    fun wipe() = CryptoBytes.wipe(chipAuthenticationData)

    override fun toString(): String = "ChipAuthMappingEvidence($suite, dataFailure=$dataFailure)"
}

/** Why the chip authentication mapping check came out Failed: a fact about what the chip sent. */
internal enum class CamFailure {
    /** The chip's last answer had no data object 0x8A (Part 11, 4.4.5: it MUST be present). */
    DATA_ABSENT,

    /** A_IC is not whole AES blocks, its padding is wrong, or CA_IC is not the length of n. */
    DATA_MALFORMED,

    /** CA_IC is zero or not below n. */
    DATA_OUT_OF_RANGE,

    /** KA(CA_IC, PK_IC, D_IC) is not PK_Map,IC (Part 11, 4.4.3.5.2). */
    KEY_DOES_NOT_MATCH,
}

/** Why the chip authentication mapping check was not run, or is not complete. */
internal enum class CamNotRun {
    /** EF.CardSecurity was not read. */
    CARD_SECURITY_NOT_READ,

    /** EF.CardSecurity's signed content could not be read. */
    UNREADABLE_CARD_SECURITY,

    /** EF.CardSecurity has no ChipAuthenticationPublicKeyInfo with the key ID PACE used. */
    NO_CHIP_AUTHENTICATION_KEY,

    /** The static key is not an ECDH key in the PACE group D_IC, or its point is not valid there. */
    UNUSABLE_CHIP_AUTHENTICATION_KEY,

    /** No crypto backend on this platform. */
    CRYPTO_UNAVAILABLE,

    /**
     * The calculation holds, but the passive authentication facts of EF.CardSecurity, where the
     * static key comes from, have not both passed (Part 11, 4.4.3.5.2 note). Incomplete, not passed.
     */
    CARD_SECURITY_NOT_AUTHENTICATED,
}

/** The outcome of the chip authentication mapping check. Maps to the public `CheckOutcome`. */
internal sealed interface CamOutcome {
    data object Passed : CamOutcome

    data class Failed(
        val reason: CamFailure,
    ) : CamOutcome

    data class NotRun(
        val reason: CamNotRun,
    ) : CamOutcome
}

/**
 * The chip authentication mapping fact (the plan's `PaceCamChipAuthentication` check kind).
 *
 * - [keyMatches]: the calculation alone, PK_Map,IC = KA(CA_IC, PK_IC, D_IC); null when it could not
 *   be made. It is a calculation anyone can repeat with the same bytes.
 * - [outcome]: what the read reports. Passed only when the calculation holds AND the EF.CardSecurity
 *   passive authentication facts the key depends on passed (signature verifies, signer chains to a
 *   supplied root). Until then it is NotRun([CamNotRun.CARD_SECURITY_NOT_AUTHENTICATED]).
 */
internal class CamCheck(
    val keyMatches: Boolean?,
    val outcome: CamOutcome,
)

/**
 * Checks the chip authentication data of a PACE-CAM run (Part 11, 4.4.3.5.2) and combines it with
 * passive authentication of EF.CardSecurity (the note of 4.4.3.5.2).
 */
internal object ChipAuthMappingCheck {
    /**
     * The passive authentication facts the static key relies on: EF.CardSecurity's signature, and
     * its signer chaining to a root the app supplied. Both must be Passed.
     */
    val requiredCardSecurityFacts: List<PaCheckKind> =
        listOf(
            PaCheckKind.SignatureVerifies(SignedObject.CARD_SECURITY),
            PaCheckKind.SignerChainsToSuppliedRoot(SignedObject.CARD_SECURITY),
        )

    /**
     * [evidence] from PACE; [cardSecurity] the raw EF.CardSecurity, or null when not read;
     * [passiveAuthentication] the facts of [io.lightine.tessera.emrtd.internal.pa.PassiveAuthentication.run]
     * over the same EF.CardSecurity; [crypto] null when the platform has no backend.
     */
    fun run(
        crypto: ChipCrypto?,
        evidence: ChipAuthMappingEvidence,
        cardSecurity: ByteArray?,
        passiveAuthentication: List<PaCheck>,
    ): CamCheck {
        evidence.dataFailure?.let { return CamCheck(null, CamOutcome.Failed(it)) }
        val ca = evidence.chipAuthenticationData ?: return CamCheck(null, CamOutcome.Failed(CamFailure.DATA_MALFORMED))
        if (cardSecurity == null) return notRun(CamNotRun.CARD_SECURITY_NOT_READ)
        val infos =
            try {
                ChipSecurityFiles.parseCardSecurityContent(cardSecurity)
            } catch (e: SecurityInfoException) {
                return notRun(CamNotRun.UNREADABLE_CARD_SECURITY)
            }
        val key = infos.chipAuthenticationPublicKey(evidence.keyId) ?: return notRun(CamNotRun.NO_CHIP_AUTHENTICATION_KEY)
        if (crypto == null) return notRun(CamNotRun.CRYPTO_UNAVAILABLE)
        val matches =
            when (val m = keyMatches(crypto, evidence, ca, key)) {
                is CamOutcome.Passed -> true
                is CamOutcome.Failed -> return CamCheck(false, m)
                is CamOutcome.NotRun -> return CamCheck(null, m)
            }
        val authenticated =
            requiredCardSecurityFacts.all { kind ->
                passiveAuthentication.any { it.kind == kind && it.outcome == PaOutcome.Passed }
            }
        return CamCheck(matches, if (authenticated) CamOutcome.Passed else CamOutcome.NotRun(CamNotRun.CARD_SECURITY_NOT_AUTHENTICATED))
    }

    private fun notRun(reason: CamNotRun) = CamCheck(null, CamOutcome.NotRun(reason))

    /**
     * PK_Map,IC = KA(CA_IC, PK_IC, D_IC): for ECDH the point CA_IC · PK_IC, compared whole (both
     * coordinates) with the chip's mapping key. The static key must be in D_IC, the PACE group.
     */
    internal fun keyMatches(
        crypto: ChipCrypto,
        evidence: ChipAuthMappingEvidence,
        ca: ByteArray,
        key: ChipAuthenticationPublicKeyInfo,
    ): CamOutcome {
        val group = evidence.suite.group as? PaceGroup.Ec ?: return CamOutcome.NotRun(CamNotRun.UNUSABLE_CHIP_AUTHENTICATION_KEY)
        val params = group.parameters
        if (key.agreement != KeyAgreement.ECDH || !sameGroup(key.key.parameters, evidence.suite.parameterId, params)) {
            return CamOutcome.NotRun(CamNotRun.UNUSABLE_CHIP_AUTHENTICATION_KEY)
        }
        if (CryptoBytes.isZero(ca) || CryptoBytes.compareUnsigned(ca, params.n) >= 0) {
            return CamOutcome.Failed(CamFailure.DATA_OUT_OF_RANGE)
        }
        val staticKey =
            try {
                EcPointCodec.decode(crypto, params, key.key.publicValue)
            } catch (e: ChipCryptoException) {
                return CamOutcome.NotRun(CamNotRun.UNUSABLE_CHIP_AUTHENTICATION_KEY)
            }
        if (!EllipticCurves.isValidPublicKey(
                crypto,
                params,
                staticKey,
            )
        ) {
            return CamOutcome.NotRun(CamNotRun.UNUSABLE_CHIP_AUTHENTICATION_KEY)
        }
        val computed = crypto.ecMultiply(params, staticKey, ca) ?: return CamOutcome.Failed(CamFailure.KEY_DOES_NOT_MATCH)
        val same = CryptoBytes.constantTimeEquals(EcPointCodec.encode(params, computed), evidence.chipMappingKey)
        return if (same) CamOutcome.Passed else CamOutcome.Failed(CamFailure.KEY_DOES_NOT_MATCH)
    }

    /** Whether the static key's parameters are D_IC: the same standardized ID, or the same explicit curve. */
    private fun sameGroup(
        keyParameters: DomainParameters,
        parameterId: Int,
        params: EcDomainParameters,
    ): Boolean =
        when (keyParameters) {
            is DomainParameters.Standardized -> {
                keyParameters.id == parameterId
            }

            is DomainParameters.ExplicitEc -> {
                CryptoBytes.unsignedEquals(keyParameters.p, params.p) &&
                    CryptoBytes.unsignedEquals(keyParameters.a, params.a) &&
                    CryptoBytes.unsignedEquals(keyParameters.b, params.b) &&
                    CryptoBytes.unsignedEquals(keyParameters.order, params.n) &&
                    keyParameters.generator.contentEquals(EcPointCodec.encode(params, params.generator))
            }

            else -> {
                false
            }
        }
}
