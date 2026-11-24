package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.internal.apdu.StatusWord

/** The steps of PACE (Doc 9303 Part 11, section 4.4.1 and Table 4), named in failures. */
internal enum class PaceStep {
    /** MSE:Set AT, which selects the protocol and the password (section 4.4.4.1). */
    SET_AUTHENTICATION_TEMPLATE,

    /** GENERAL AUTHENTICATE step 1: the chip's encrypted nonce. */
    ENCRYPTED_NONCE,

    /** GENERAL AUTHENTICATE step 2: the mapping data. */
    MAP_NONCE,

    /** GENERAL AUTHENTICATE step 3: the ephemeral public keys. */
    KEY_AGREEMENT,

    /** GENERAL AUTHENTICATE step 4: the authentication tokens. */
    MUTUAL_AUTHENTICATION,
}

/** Why PACE did not open the chip. Carries no key, nonce, token or document bytes. */
internal enum class PaceFailure {
    /**
     * The protocol OID or parameter ID is not one the reader runs: not a PACE OID, a mapping the
     * reader has no implementation for, or parameters that are not a standardized set of Table 12
     * fitting the key agreement (explicit parameters from the chip are never used; section 9.2.2).
     */
    UNSUPPORTED_SUITE,

    /** MSE:Set AT answered 6A80: the chip does not support the algorithm (section 4.4.4.1). */
    ALGORITHM_NOT_SUPPORTED,

    /** MSE:Set AT answered 6A88: the chip has no such password or domain parameters. */
    REFERENCED_DATA_NOT_FOUND,

    /** MSE:Set AT answered another error status word. */
    SET_AUTHENTICATION_TEMPLATE_REFUSED,

    /**
     * MSE:Set AT answered with a status word about the password, not the suite: a warning (62xx,
     * 63xx; TR-03110-3, B.14.1: 63CX "the password has been selected", X tries left; 6283 "the
     * password is deactivated") or 6982 to 6985 (blocked, deactivated, suspended; B.14.2). Another
     * suite uses the same password, so no other suite is tried.
     */
    PASSWORD_STATE,

    /**
     * A GENERAL AUTHENTICATE step answered an error status word. At the mutual authentication
     * step, 6300 ("authentication failed") usually means the password (MRZ values or CAN) does not
     * match the one the chip was set up with.
     */
    STEP_REFUSED,

    /** The chip's answer lacks the dynamic authentication data (7C) or the step's data object. */
    MALFORMED_RESPONSE,

    /** The encrypted nonce is empty or not a whole number of cipher blocks (section 4.4.3.3). */
    WRONG_NONCE_LENGTH,

    /**
     * A public key from the chip failed validation: not on the curve or not in the subgroup (ECDH),
     * outside the range or the subgroup (DH, RFC 2631; section 4.4.3.3.1 note).
     */
    INVALID_PUBLIC_KEY,

    /** The mapping gave a degenerate generator (the point at infinity, or 1 for DH). */
    DEGENERATE_GENERATOR,

    /**
     * The chip's ephemeral public key equals the reader's (section 4.4.1, step 3d): a reflected key,
     * so the run is stopped before any token is sent.
     */
    EQUAL_EPHEMERAL_KEYS,

    /** The chip's authentication token does not match the one computed over the reader's key. */
    WRONG_CHIP_TOKEN,

    /**
     * A command or the expected answer of a step does not fit a short APDU, and extended length is
     * not allowed (the transport or the chip's statement, Part 11, section 9.3.1.2), or a command
     * is longer than the transport sends. Checked before MSE:Set AT for the largest command of the
     * run, so [PaceException.step] is [PaceStep.SET_AUTHENTICATION_TEMPLATE] and nothing was sent;
     * at a later step the chip had already answered a GENERAL AUTHENTICATE.
     */
    EXTENDED_LENGTH_REQUIRED,
}

/**
 * PACE failed. [sw] is the refusing status word and [step] the step, when there is one. The
 * message names only these, never key or data bytes.
 */
internal class PaceException(
    val failure: PaceFailure,
    val step: PaceStep? = null,
    val sw: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(
        "PACE failed: $failure" +
            (step?.let { " at $it" } ?: "") +
            (sw?.let { " (status ${StatusWord.hex(it)})" } ?: ""),
        cause,
    )
