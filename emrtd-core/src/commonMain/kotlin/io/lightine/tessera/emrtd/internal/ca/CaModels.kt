package io.lightine.tessera.emrtd.internal.ca

import io.lightine.tessera.emrtd.internal.securityinfo.ChipAuthenticationInfo
import io.lightine.tessera.emrtd.internal.securityinfo.ChipAuthenticationPublicKeyInfo
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters as CryptoDomainParameters

/**
 * Where chip authentication runs (Doc 9303 Part 11, section 6.2; CHIP-9). A chip may offer it in the
 * master file, in the eMRTD application, or in both. Its security infos say which: EF.CardSecurity
 * in the master file lists the keys for the master file, EF.DG14 in the application the keys for
 * the application (Part 11, section 9.2.11). Chips that follow EU Extended Access Control offer it
 * in the application.
 *
 * [ChipAuthentication] sends no SELECT: the caller runs it while the right file is current (before
 * selecting the application for the master file, after it for the application) and passes the
 * context, which the outcome records.
 */
internal enum class CaContext {
    MASTER_FILE,
    EMRTD_APPLICATION,
}

/** The two ways of sending the ephemeral key (Part 11, section 6.2.4). */
internal enum class CaCommands {
    /** MSE:Set KAT, for the 3DES suites only (section 6.2.4.1). */
    SET_KAT,

    /** MSE:Set AT followed by GENERAL AUTHENTICATE (section 6.2.4.2): the AES suites, and 3DES as a fallback. */
    SET_AT_GENERAL_AUTHENTICATE,
}

/** A command of chip authentication. */
internal enum class CaStep {
    SET_KAT,
    SET_AT,
    GENERAL_AUTHENTICATE,
}

/** Why an offered suite was not tried. Nothing was sent for it. */
internal enum class CaSkipReason {
    /** No ChipAuthenticationPublicKeyInfo with the key id of the ChipAuthenticationInfo. */
    NO_PUBLIC_KEY,

    /** The ChipAuthenticationInfo has no key id and the chip lists more than one key: a key is never guessed (section 9.2.6). */
    AMBIGUOUS_PUBLIC_KEY,

    /** The protocol says DH and the key says ECDH, or the reverse. */
    KEY_AGREEMENT_MISMATCH,

    /** The key's domain parameters are not ones this reader can use (a named curve, an unknown id, a binary field). */
    UNSUPPORTED_DOMAIN_PARAMETERS,

    /** The chip's public key failed validation (TR-03111 for ECDH, RFC 2631 for DH) or does not decode. */
    INVALID_PUBLIC_KEY,

    /** The commands would not fit the transport: extended length is needed and not allowed or not supported. */
    COMMAND_TOO_LONG,

    /** The reader could not make its ephemeral key pair or the shared secret on the chip's parameters. */
    KEY_AGREEMENT_FAILED,
}

/** An offered suite that was not tried, and why. */
internal class CaSkipped(
    val protocol: String,
    val keyId: Int?,
    val reason: CaSkipReason,
)

/** A command the chip refused, with its status word. The previous session keys stay in force (section 6.2.2). */
internal class CaRefusal(
    val protocol: String,
    val keyId: Int?,
    val step: CaStep,
    val sw: Int,
)

/**
 * One chip-authentication suite the reader can try: a ChipAuthenticationInfo, the public key it
 * names, the key's domain parameters as numbers, and whether the key reference `84` is sent.
 */
internal class CaSuite(
    val info: ChipAuthenticationInfo,
    val key: ChipAuthenticationPublicKeyInfo,
    val parameters: CryptoDomainParameters,
    val cipher: SmCipher,
    val sendKeyReference: Boolean,
) {
    val protocol: String get() = info.protocol

    /** The key id the chip's infos give for this suite, if any. */
    val keyId: Int? get() = info.keyId ?: key.keyId
}

/** The suites to try, in order (AES before 3DES, section 8.5), and the ones that cannot be tried. */
internal class CaPlan(
    val suites: List<CaSuite>,
    val skipped: List<CaSkipped>,
)

/**
 * What chip authentication did. Facts only: whether the chip agreed keys is what its status word
 * says; whether it holds the private key shows when its next protected answer verifies under the
 * new keys (implicit authentication, section 6.2), which is the caller's next command.
 */
internal sealed interface CaOutcome {
    val context: CaContext

    /** Suites that were offered and not tried. */
    val skipped: List<CaSkipped>

    /** Commands the chip refused before the outcome, in order. */
    val refusals: List<CaRefusal>

    /**
     * The chip agreed new keys and secure messaging restarted with them (section 6.2.2) and a send
     * sequence counter of zero (sections 9.8.6.3 and 9.8.7.3).
     *
     * The key came from the chip's own security infos (EF.DG14 or EF.CardSecurity), which chip
     * authentication does not check. Until passive authentication of that file has passed, the
     * restart shows only that the chip holds the private key of a key it supplied itself: it proves
     * nothing about the chip being genuine (sections 6.2.1 and 6.2.2, Note).
     */
    class RestartedWithUnverifiedKey(
        override val context: CaContext,
        val protocol: String,
        val keyId: Int?,
        val commands: CaCommands,
        val cipher: SmCipher,
        override val skipped: List<CaSkipped>,
        override val refusals: List<CaRefusal>,
    ) : CaOutcome {
        /** True when 3DES went through Set AT because Set KAT was refused (section 8.5, Note 1 b). */
        val usedSetAtFallback: Boolean
            get() = cipher == SmCipher.DESEDE && commands == CaCommands.SET_AT_GENERAL_AUTHENTICATE
    }

    /** The infos offer no chip authentication this reader can try. Nothing was sent. */
    class NotRun(
        override val context: CaContext,
        override val skipped: List<CaSkipped>,
    ) : CaOutcome {
        override val refusals: List<CaRefusal> get() = emptyList()
    }

    /** The chip refused every suite that was tried. The previous session continues (section 6.2.2). */
    class Refused(
        override val context: CaContext,
        override val skipped: List<CaSkipped>,
        override val refusals: List<CaRefusal>,
    ) : CaOutcome
}
