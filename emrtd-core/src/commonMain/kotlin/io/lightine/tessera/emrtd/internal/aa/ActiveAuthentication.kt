package io.lightine.tessera.emrtd.internal.aa

import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.EcdsaSignatureFormat
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.SignatureScheme
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey
import io.lightine.tessera.emrtd.internal.securityinfo.ActiveAuthenticationInfo

/**
 * Why the active-authentication check did not pass. Carries no chip bytes and no document values,
 * like every failure the chip code reports.
 */
internal enum class ActiveAuthenticationFailure {
    /** EF.DG15 is not the tag `6F` data group holding a SubjectPublicKeyInfo (Part 11, 6.1.5). */
    PUBLIC_KEY_UNREADABLE,

    /** A well-formed key of a form this module does not verify: a named curve, another algorithm. */
    UNSUPPORTED_PUBLIC_KEY,

    /** The key does not hold: a point not on its curve, a modulus that is not a positive odd number. */
    INVALID_PUBLIC_KEY,

    /** The chip publishes no ActiveAuthenticationInfo, so the hash its ECDSA key signs with is unknown (9.2.4). */
    MISSING_SIGNATURE_ALGORITHM,

    /** The ActiveAuthenticationInfo names an algorithm this module does not use (9.2.4 with 6.1.2.3). */
    UNSUPPORTED_SIGNATURE_ALGORITHM,

    /** INTERNAL AUTHENTICATE was refused; the status word is carried with the failure. */
    CHIP_REFUSED,

    /** The answer is not as long as the key's signature is (6.1.3). */
    WRONG_SIGNATURE_LENGTH,

    /** The answer is not an ISO/IEC 9796-2 message representative this module reads (6.1.2.2). */
    MALFORMED_SIGNATURE,

    /** The signature does not verify: the recovered digest, or the whole ECDSA signature, is wrong. */
    WRONG_SIGNATURE,
}

/**
 * The result of the active-authentication check (Doc 9303 Part 11, 6.1.6): a fact for the reader's
 * report, never a judgment. A pass says the chip answered a fresh challenge with a signature that
 * verifies under the public key in its EF.DG15; it does not say the chip is genuine, and whether
 * the key itself is authentic is passive authentication's question, not this check's.
 */
internal sealed interface ActiveAuthenticationResult {
    /** The chip's signature verified under the public key in its EF.DG15. */
    data object Passed : ActiveAuthenticationResult

    /**
     * The check ran and did not pass. [failure] says where it stopped; [sw] is the status word the
     * chip refused with, when there is one.
     */
    data class Failed(
        val failure: ActiveAuthenticationFailure,
        val sw: Int? = null,
    ) : ActiveAuthenticationResult

    /** The chip offered no active authentication: no EF.DG15 was read from it. */
    data object NotApplicable : ActiveAuthenticationResult
}

/**
 * Active Authentication, Doc 9303 Part 11, section 6.1: the reader sends a fresh 8-byte nonce with
 * INTERNAL AUTHENTICATE, the chip signs it with its active-authentication private key, and the
 * reader checks the answer under the public key the chip stores in EF.DG15: RSA with ISO/IEC
 * 9796-2 scheme 1, partial message recovery ([RsaMessageRecovery]), or ECDSA in the plain format,
 * with the hash the chip's ActiveAuthenticationInfo names ([AaSignatureOids]).
 *
 * One instance runs the check once: it draws one nonce and refuses a second run, so this reader
 * never signs two challenges with one nonce (section 6.1.2.1). The nonce comes from [random], the
 * platform's strong source by default; the BAC and PACE nonces are separate draws from the same
 * source, never values this module reuses.
 *
 * The check is sent through [exchange] as the caller opened it: through a secure-messaging channel
 * when one is open, plain otherwise (section 6.1.1). Nothing here says the chip is genuine
 * (section 6.1.6): the result is an internal fact the reader's report carries as it is.
 */
internal class ActiveAuthentication(
    private val exchange: ApduSender,
    private val crypto: ChipCrypto,
) {
    private var ran = false

    /**
     * Runs the protocol of section 6.1.1 over [exchange].
     *
     * @param dg15 the bytes of EF.DG15 as read from the chip, or `null` when the chip has no such
     *   file: the check does not apply then, and nothing is sent.
     * @param aaInfo the ActiveAuthenticationInfo from DG14's SecurityInfos (section 9.2.4). An
     *   ECDSA chip names its hash there and the check cannot run without it; an RSA chip names its
     *   hash in the signature trailer and the entry is not consulted.
     * @param extendedLengthAllowed whether the chip has stated it accepts extended-length APDUs
     *   (section 9.3.1.2): the answer of a key longer than 256 bytes is then asked for in one command.
     * @param random the reader's nonce source. The default is the platform's strong random source.
     */
    suspend fun authenticate(
        dg15: ByteArray?,
        aaInfo: ActiveAuthenticationInfo?,
        extendedLengthAllowed: Boolean = false,
        random: (Int) -> ByteArray = crypto::randomBytes,
    ): ActiveAuthenticationResult {
        check(!ran) { "active authentication has run already: one nonce, one invocation (Part 11, 6.1.2.1)" }
        ran = true
        if (dg15 == null) return ActiveAuthenticationResult.NotApplicable

        val key =
            try {
                ActiveAuthenticationKeys.parse(crypto, dg15)
            } catch (e: ActiveAuthenticationKeyException) {
                return ActiveAuthenticationResult.Failed(e.failure)
            }
        var ecdsaHash: HashAlgorithm? = null
        if (key is ActiveAuthenticationPublicKey.Ecdsa) {
            val info =
                aaInfo ?: return ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.MISSING_SIGNATURE_ALGORITHM)
            ecdsaHash =
                AaSignatureOids.ecdsaPlainHash(info.signatureAlgorithm)
                    ?: return ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.UNSUPPORTED_SIGNATURE_ALGORITHM)
        }

        // RND.IFD: 8 fresh bytes (section 6.1.2.1), drawn here and nowhere else.
        val nonce = random(NONCE_LENGTH)
        if (nonce.size != NONCE_LENGTH) error("the random source returned ${nonce.size} bytes, not $NONCE_LENGTH")

        // Section 6.1.3: the answer is the signature. Ask for 256 bytes (the worked example's Le),
        // or for the whole signature when it is longer and the chip takes extended length (6.1.4, note).
        val expected = key.signatureLength
        val ne =
            if (expected > CommandApdu.MAX_SHORT_NE && extendedLengthAllowed && exchange.supportsExtendedLength) {
                expected
            } else {
                CommandApdu.MAX_SHORT_NE
            }
        val answer = exchange.send(command(nonce, ne), extendedLengthAllowed)
        if (answer.sw != StatusWord.SUCCESS) {
            return ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.CHIP_REFUSED, answer.sw)
        }
        if (answer.data.size != expected) {
            return ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.WRONG_SIGNATURE_LENGTH)
        }
        return when (key) {
            is ActiveAuthenticationPublicKey.Rsa -> {
                RsaMessageRecovery.verify(crypto, key, nonce, answer.data)
            }

            is ActiveAuthenticationPublicKey.Ecdsa -> {
                verifyEcdsa(
                    key,
                    ecdsaHash ?: return ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.MISSING_SIGNATURE_ALGORITHM),
                    nonce,
                    answer.data,
                )
            }
        }
    }

    /** The ECDSA step: the plain signature over the nonce, with the hash the chip announced (6.1.2.3). */
    private fun verifyEcdsa(
        key: ActiveAuthenticationPublicKey.Ecdsa,
        hash: HashAlgorithm,
        nonce: ByteArray,
        signature: ByteArray,
    ): ActiveAuthenticationResult =
        try {
            val scheme = SignatureScheme.Ecdsa(hash, EcdsaSignatureFormat.PLAIN)
            val publicKey = VerificationKey.Ec(key.parameters, key.point)
            if (crypto.verify(scheme, publicKey, nonce, signature)) {
                ActiveAuthenticationResult.Passed
            } else {
                ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.WRONG_SIGNATURE)
            }
        } catch (e: ChipCryptoException) {
            ActiveAuthenticationResult.Failed(ActiveAuthenticationFailure.INVALID_PUBLIC_KEY)
        }

    internal companion object {
        /** INTERNAL AUTHENTICATE (ISO/IEC 7816-4): the protocol's one command (section 6.1.3). */
        const val INS_INTERNAL_AUTHENTICATE = 0x88

        /** RND.IFD, the reader's nonce: 8 bytes (section 6.1.2.1). */
        const val NONCE_LENGTH = 8

        /**
         * The command of Appendix F, step 2: `00 88 00 00`, the nonce as the data field, and Le.
         * Secure messaging wraps the same command (section 6.1.1), so it is built plain here.
         */
        fun command(
            nonce: ByteArray,
            ne: Int = CommandApdu.MAX_SHORT_NE,
        ): CommandApdu {
            require(nonce.size == NONCE_LENGTH) { "the nonce must be $NONCE_LENGTH bytes" }
            return CommandApdu(0x00, INS_INTERNAL_AUTHENTICATE, 0x00, 0x00, nonce, ne)
        }
    }
}

/**
 * The signature algorithms an ActiveAuthenticationInfo can name (Doc 9303 Part 11, 9.2.4): the
 * plain ECDSA forms of BSI TR-03111. Section 6.1.2.3 allows only the four SHA-2 hashes, so
 * TR-03111's SHA-1 and RIPEMD-160 forms are refused, like any other algorithm.
 */
internal object AaSignatureOids {
    /** The TR-03111 algorithm arc below bsi-de. */
    private const val BSI_ALGORITHMS = "0.4.0.127.0.7.1"

    const val ECDSA_PLAIN_SHA224 = "$BSI_ALGORITHMS.4.2.1"
    const val ECDSA_PLAIN_SHA256 = "$BSI_ALGORITHMS.4.2.2"
    const val ECDSA_PLAIN_SHA384 = "$BSI_ALGORITHMS.4.2.3"
    const val ECDSA_PLAIN_SHA512 = "$BSI_ALGORITHMS.4.2.4"

    /** The hash the ecdsa-plain OID [oid] names, or `null` for any other algorithm. */
    fun ecdsaPlainHash(oid: String): HashAlgorithm? =
        when (oid) {
            ECDSA_PLAIN_SHA224 -> HashAlgorithm.SHA224
            ECDSA_PLAIN_SHA256 -> HashAlgorithm.SHA256
            ECDSA_PLAIN_SHA384 -> HashAlgorithm.SHA384
            ECDSA_PLAIN_SHA512 -> HashAlgorithm.SHA512
            else -> null
        }
}
