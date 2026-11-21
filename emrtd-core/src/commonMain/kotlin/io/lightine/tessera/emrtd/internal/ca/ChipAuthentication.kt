package io.lightine.tessera.emrtd.internal.ca

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.DiffieHellman
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPoint
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EllipticCurves
import io.lightine.tessera.emrtd.internal.crypto.KeyCheck
import io.lightine.tessera.emrtd.internal.crypto.KeyLimits
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.securityinfo.KeyAgreement
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfos
import io.lightine.tessera.emrtd.internal.securityinfo.SessionCipher
import io.lightine.tessera.emrtd.internal.securityinfo.StandardizedParameters
import io.lightine.tessera.emrtd.internal.sm.SecureChannel
import io.lightine.tessera.emrtd.internal.sm.SecureMessaging
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import io.lightine.tessera.emrtd.internal.crypto.DomainParameters as CryptoDomainParameters
import io.lightine.tessera.emrtd.internal.securityinfo.DomainParameters as InfoDomainParameters

/**
 * Chip authentication, Doc 9303 Part 11, section 6.2: an ephemeral-static Diffie-Hellman key
 * agreement with the chip's static key from its security infos (EF.DG14 or EF.CardSecurity). It
 * gives new secure-messaging keys, and the chip proves it holds the private key when its next
 * protected answer verifies under them (implicit authentication).
 *
 * The reader:
 * 1. picks the suites it can try ([plan]): a ChipAuthenticationInfo, the public key with its key
 *    id (never a guessed one, section 9.2.6), domain parameters it can use, and a public key that
 *    passes validation; AES suites before 3DES ones (section 8.5);
 * 2. makes an ephemeral key pair on the chip's domain parameters, computes the shared secret K and
 *    the session keys KSEnc = KDF(K, 1), KSMAC = KDF(K, 2) (sections 6.2.1 and 9.7);
 * 3. sends its ephemeral public key through the current secure channel: MSE:Set KAT for 3DES
 *    (section 6.2.4.1), MSE:Set AT and GENERAL AUTHENTICATE for AES (section 6.2.4.2). When Set KAT
 *    is refused it tries 3DES again with Set AT in the same session, for chips that implement only
 *    that (section 8.5, Note 1 b);
 * 4. on 9000 restarts secure messaging with the new keys and a zero counter
 *    ([SecureChannel.restart]). The key-agreement commands, the answer to the last one included,
 *    go through the old session with the old keys and counter (section 9.8.2): the restart comes
 *    only after that answer has been checked.
 *
 * A refusal keeps the previous session: the chip keeps its old keys (sections 6.2.2 and 6.2.4.1),
 * and the reader tries the next suite. A secure-messaging failure or a lost connection during the
 * commands ends the session as everywhere else; that exception passes through.
 *
 * Passive authentication of the infos that carried the key is a separate check (section 6.2.1:
 * "SHALL perform"; section 6.2.2, Note): this object neither does it nor assumes it. Without it, a
 * restart shows only that the chip holds the private key of a key it supplied itself, which is why
 * the outcome is [CaOutcome.RestartedWithUnverifiedKey].
 */
internal object ChipAuthentication {
    const val INS_MSE = 0x22
    const val INS_GENERAL_AUTHENTICATE = 0x86

    /** MSE P1: set for computation, decipherment, internal authentication and key agreement. */
    const val P1_SET_COMPUTATION = 0x41

    /** MSE P2: Key Agreement Template (Set KAT). */
    const val P2_KAT = 0xA6

    /** MSE P2: Authentication Template (Set AT). */
    const val P2_AT = 0xA4

    const val TAG_EPHEMERAL_KEY_KAT = 0x91
    const val TAG_KEY_REFERENCE = 0x84
    const val TAG_MECHANISM = 0x80
    const val TAG_DYNAMIC_AUTHENTICATION_DATA = 0x7C
    const val TAG_EPHEMERAL_KEY_GA = 0x80

    /**
     * The suites in [infos] this reader can try, in the order it tries them, and the ones it cannot.
     * AES comes before 3DES (section 8.5); otherwise the chip's order is kept.
     */
    fun plan(
        crypto: ChipCrypto,
        infos: SecurityInfos,
    ): CaPlan {
        val keyCount = infos.chipAuthenticationPublicKeys.size
        val suites = mutableListOf<CaSuite>()
        val skipped = mutableListOf<CaSkipped>()
        for (info in infos.chipAuthenticationInfos) {
            val key = infos.chipAuthenticationPublicKey(info.keyId)
            if (key == null) {
                val reason = if (info.keyId == null && keyCount > 1) CaSkipReason.AMBIGUOUS_PUBLIC_KEY else CaSkipReason.NO_PUBLIC_KEY
                skipped += CaSkipped(info.protocol, info.keyId, reason)
                continue
            }
            if (key.agreement != info.agreement) {
                skipped += CaSkipped(info.protocol, info.keyId, CaSkipReason.KEY_AGREEMENT_MISMATCH)
                continue
            }
            val parameters =
                try {
                    domainParameters(crypto, key.key.parameters, info.agreement)
                } catch (e: ChipCryptoException) {
                    null
                }
            if (parameters == null) {
                skipped += CaSkipped(info.protocol, info.keyId, CaSkipReason.UNSUPPORTED_DOMAIN_PARAMETERS)
                continue
            }
            if (!isValidChipKey(crypto, parameters, key.key.publicValue)) {
                skipped += CaSkipped(info.protocol, info.keyId, CaSkipReason.INVALID_PUBLIC_KEY)
                continue
            }
            // The key reference is REQUIRED when the key is ambiguous (sections 6.2.4.1 and 6.2.4.2),
            // that is, when the chip lists more than one key; it is not sent otherwise.
            val sendKeyReference = info.keyId != null && keyCount > 1
            suites += CaSuite(info, key, parameters, smCipher(info.cipher), sendKeyReference)
        }
        return CaPlan(suites.sortedBy { if (it.cipher == SmCipher.DESEDE) 1 else 0 }, skipped)
    }

    /**
     * Runs chip authentication through [channel] with the suites of [infos], in [context]. Returns
     * on the first suite the chip accepts, with [channel] restarted on the new keys; otherwise
     * [channel] keeps its keys and counter.
     *
     * [extendedLengthAllowed] says whether the chip has stated extended-length support (section
     * 9.3.1.2 allows extended length for exactly these commands). A suite whose commands do not
     * fit the transport is skipped before anything is sent ([CaSkipReason.COMMAND_TOO_LONG]):
     * section 9.3.2 rules out command chaining here.
     */
    suspend fun run(
        channel: SecureChannel,
        crypto: ChipCrypto,
        infos: SecurityInfos,
        context: CaContext,
        extendedLengthAllowed: Boolean = false,
    ): CaOutcome {
        val plan = plan(crypto, infos)
        val skipped = plan.skipped.toMutableList()
        val refusals = mutableListOf<CaRefusal>()
        if (plan.suites.isEmpty()) return CaOutcome.NotRun(context, skipped)
        var tried = false
        for (suite in plan.suites) {
            when (val r = attempt(channel, crypto, suite, extendedLengthAllowed, refusals)) {
                is AttemptResult.Skipped -> {
                    skipped += CaSkipped(suite.protocol, suite.keyId, r.reason)
                }

                AttemptResult.Refused -> {
                    tried = true
                }

                is AttemptResult.Accepted -> {
                    return CaOutcome.RestartedWithUnverifiedKey(
                        context,
                        suite.protocol,
                        suite.keyId,
                        r.commands,
                        suite.cipher,
                        skipped,
                        refusals,
                    )
                }
            }
        }
        return if (tried) CaOutcome.Refused(context, skipped, refusals) else CaOutcome.NotRun(context, skipped)
    }

    private sealed interface AttemptResult {
        object Refused : AttemptResult

        /** Nothing was sent for the suite. */
        class Skipped(
            val reason: CaSkipReason,
        ) : AttemptResult

        class Accepted(
            val commands: CaCommands,
        ) : AttemptResult
    }

    /**
     * One suite. Returns [AttemptResult.Skipped] when nothing was sent: the reader could not make
     * its key pair or the shared secret on the chip's parameters, or the commands do not fit.
     */
    private suspend fun attempt(
        channel: SecureChannel,
        crypto: ChipCrypto,
        suite: CaSuite,
        extendedLengthAllowed: Boolean,
        refusals: MutableList<CaRefusal>,
    ): AttemptResult {
        val ephemeral =
            try {
                Ephemeral.generate(crypto, suite.parameters, suite.key.key.publicValue)
            } catch (e: ChipCryptoException) {
                return AttemptResult.Skipped(CaSkipReason.KEY_AGREEMENT_FAILED)
            }
        val keyReference = if (suite.sendKeyReference) TlvWriter.minimalUnsigned(CryptoBytes.ofInt(suite.keyId!!)) else null
        val setKat = if (suite.cipher == SmCipher.DESEDE) setKat(ephemeral.publicKey, keyReference) else null
        val setAt = setAt(suite.protocol, keyReference)
        val ga = generalAuthenticate(ephemeral.publicKey)
        val fits = { c: CommandApdu -> protectedFits(c, suite.cipher, channel, extendedLengthAllowed) }
        // Set KAT carries the same key as GENERAL AUTHENTICATE plus a header, so if GENERAL
        // AUTHENTICATE does not fit, neither does Set KAT; with 3DES a Set KAT that fits is enough.
        val keys =
            try {
                if (!(setKat?.let(fits) ?: false) && !(fits(setAt) && fits(ga))) {
                    return AttemptResult.Skipped(CaSkipReason.COMMAND_TOO_LONG)
                }
                SessionKeys.derive(crypto, ephemeral.sharedSecret, suite.cipher)
            } catch (e: ChipCryptoException) {
                return AttemptResult.Skipped(CaSkipReason.KEY_AGREEMENT_FAILED)
            } finally {
                ephemeral.wipe()
            }
        var handedOver = false
        try {
            if (setKat != null && fits(setKat)) {
                val answer = channel.send(setKat, extendedLengthAllowed)
                if (answer.sw == StatusWord.SUCCESS) {
                    channel.restart(keys, suite.cipher.zeroSsc())
                    handedOver = true
                    return AttemptResult.Accepted(CaCommands.SET_KAT)
                }
                refusals += CaRefusal(suite.protocol, suite.keyId, CaStep.SET_KAT, answer.sw)
                if (!(fits(setAt) && fits(ga))) return AttemptResult.Refused
            }
            val atAnswer = channel.send(setAt)
            if (atAnswer.sw != StatusWord.SUCCESS) {
                refusals += CaRefusal(suite.protocol, suite.keyId, CaStep.SET_AT, atAnswer.sw)
                return AttemptResult.Refused
            }
            val gaAnswer = channel.send(ga, extendedLengthAllowed)
            if (gaAnswer.sw != StatusWord.SUCCESS) {
                refusals += CaRefusal(suite.protocol, suite.keyId, CaStep.GENERAL_AUTHENTICATE, gaAnswer.sw)
                return AttemptResult.Refused
            }
            // 9000 means the chip has derived the new keys (section 6.2.4.2); its 7C template
            // carries nothing for chip authentication, so its content is not read.
            channel.restart(keys, suite.cipher.zeroSsc())
            handedOver = true
            return AttemptResult.Accepted(CaCommands.SET_AT_GENERAL_AUTHENTICATE)
        } finally {
            if (!handedOver) keys.wipe()
        }
    }

    /** MSE:Set KAT: `00 22 41 A6`, data `91` ephemeral key, `84` key reference when needed (section 6.2.4.1). */
    fun setKat(
        ephemeralPublicKey: ByteArray,
        keyReference: ByteArray?,
    ): CommandApdu {
        var data = TlvWriter.encode(TAG_EPHEMERAL_KEY_KAT, ephemeralPublicKey)
        keyReference?.let { data += TlvWriter.encode(TAG_KEY_REFERENCE, it) }
        return CommandApdu(0x00, INS_MSE, P1_SET_COMPUTATION, P2_KAT, data)
    }

    /**
     * MSE:Set AT: `00 22 41 A4`, data `80` the protocol OID's value bytes, `84` key reference when
     * needed (section 6.2.4.2).
     */
    fun setAt(
        protocol: String,
        keyReference: ByteArray?,
    ): CommandApdu {
        var data = TlvWriter.encode(TAG_MECHANISM, oidValue(protocol))
        keyReference?.let { data += TlvWriter.encode(TAG_KEY_REFERENCE, it) }
        return CommandApdu(0x00, INS_MSE, P1_SET_COMPUTATION, P2_AT, data)
    }

    /** GENERAL AUTHENTICATE: `00 86 00 00`, data `7C { 80 ephemeral key }`, any answer length (section 6.2.4.2). */
    fun generalAuthenticate(ephemeralPublicKey: ByteArray): CommandApdu =
        CommandApdu(
            0x00,
            INS_GENERAL_AUTHENTICATE,
            0x00,
            0x00,
            TlvWriter.constructed(TAG_DYNAMIC_AUTHENTICATION_DATA, TlvWriter.encode(TAG_EPHEMERAL_KEY_GA, ephemeralPublicKey)),
            CommandApdu.MAX_SHORT_NE,
        )

    /** The secure-messaging cipher of a chip-authentication suite (Tables 5 and 6). */
    fun smCipher(cipher: SessionCipher): SmCipher =
        when (cipher) {
            SessionCipher.TDES_CBC_CBC -> SmCipher.DESEDE
            SessionCipher.AES_CBC_CMAC_128 -> SmCipher.AES128
            SessionCipher.AES_CBC_CMAC_192 -> SmCipher.AES192
            SessionCipher.AES_CBC_CMAC_256 -> SmCipher.AES256
        }

    /**
     * The numbers of a chip key's domain parameters, or null when this reader cannot use them: a
     * standardized id (Table 12) of the right type, explicit prime-curve parameters, or explicit DH
     * parameters (PKCS #3, or X9.42 as some chips issue, section 8.5 Note 2). Explicit parameters
     * must pass the shared size policy ([KeyLimits]).
     */
    fun domainParameters(
        crypto: ChipCrypto,
        parameters: InfoDomainParameters,
        agreement: KeyAgreement,
    ): CryptoDomainParameters? =
        when (parameters) {
            is InfoDomainParameters.Standardized -> {
                val meta = StandardizedParameters.byId(parameters.id)
                if (meta == null || meta.agreement != agreement) null else StandardDomainParameterValues.forId(parameters.id)
            }

            is InfoDomainParameters.ExplicitEc -> {
                if (agreement != KeyAgreement.ECDH) null else explicitCurve(crypto, parameters)
            }

            is InfoDomainParameters.ExplicitDh -> {
                when {
                    agreement != KeyAgreement.DH -> null
                    KeyLimits.dh(parameters.p, parameters.g, parameters.q) != KeyCheck.OK -> null
                    else -> DhDomainParameters(parameters.p, parameters.g, parameters.q)
                }
            }

            is InfoDomainParameters.Unsupported -> {
                null
            }
        }

    private fun explicitCurve(
        crypto: ChipCrypto,
        e: InfoDomainParameters.ExplicitEc,
    ): EcDomainParameters? {
        // The shared size policy first: p odd and at most 640 bits, n at least 2 (an order of 0 or 1
        // would let any point pass the subgroup check and leave no private key to draw).
        if (KeyLimits.ecCurve(e.p, e.a, e.b, e.order, e.cofactor) != KeyCheck.OK) return null
        val len = CryptoBytes.stripLeadingZeros(e.p).size
        val g = e.generator
        if (len == 0 || g.size != 1 + 2 * len || g[0] != 0x04.toByte()) return null
        val generator = EcPoint(g.copyOfRange(1, 1 + len), g.copyOfRange(1 + len, g.size))
        // An absent cofactor (it is OPTIONAL in ECParameters) is taken as 1 only to build the curve
        // object; key validation checks the order n itself, not the cofactor.
        val params = EcDomainParameters(e.p, e.a, e.b, generator, e.order, e.cofactor ?: CryptoBytes.ofInt(1))
        return try {
            if (EllipticCurves.isValidPublicKey(crypto, params, generator)) params else null
        } catch (ex: ChipCryptoException) {
            null
        }
    }

    private fun isValidChipKey(
        crypto: ChipCrypto,
        parameters: CryptoDomainParameters,
        publicValue: ByteArray,
    ): Boolean =
        try {
            when (parameters) {
                is DhDomainParameters -> {
                    DiffieHellman.isValidPublicKey(crypto, parameters, publicValue)
                }

                is EcDomainParameters -> {
                    EllipticCurves.isValidPublicKey(crypto, parameters, EcPointCodec.decode(crypto, parameters, publicValue))
                }
            }
        } catch (e: ChipCryptoException) {
            false
        }

    /**
     * Whether [plain] still fits the transport once secure messaging has wrapped it, worked out the
     * way [SecureMessaging.protect] builds it: DO'87' (or '85') with the padded data, DO'97' when
     * there is an expected length, DO'8E'; extended when the plain command or the body needs it.
     */
    internal fun protectedFits(
        plain: CommandApdu,
        cipher: SmCipher,
        channel: SecureChannel,
        extendedLengthAllowed: Boolean,
    ): Boolean {
        val bs = cipher.blockSize
        var body = 0
        if (plain.data.isNotEmpty()) {
            val encrypted = (plain.data.size / bs + 1) * bs
            val value = if (plain.ins and 1 == 1) encrypted else encrypted + 1
            body += 1 + TlvWriter.lengthOfLength(value) + value
        }
        val ne = plain.ne
        if (ne != null) body += 2 + SecureMessaging.leBytes(ne).size
        body += 2 + SecureMessaging.MAC_LENGTH
        val extended = !plain.fitsShort || body > CommandApdu.MAX_SHORT_LC
        if (extended && !(extendedLengthAllowed && channel.supportsExtendedLength)) return false
        if (body > CommandApdu.MAX_EXTENDED_LC) return false
        val protectedNe = if (extended) CommandApdu.MAX_EXTENDED_NE else CommandApdu.MAX_SHORT_NE
        val length =
            CommandApdu(
                plain.cla or SecureMessaging.CLA_SM_BITS,
                plain.ins,
                plain.p1,
                plain.p2,
                ByteArray(body),
                protectedNe,
            ).encodedLength()
        return length <= channel.maxCommandLength
    }

    /** The content octets of a DER OBJECT IDENTIFIER for [dotted] (X.690, section 8.19). */
    fun oidValue(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs.all { it >= 0 }) { "not an object identifier" }
        val out = ArrayList<Byte>()

        fun base128(v: Long) {
            val groups = ArrayList<Int>()
            var x = v
            do {
                groups.add((x and 0x7F).toInt())
                x = x shr 7
            } while (x > 0)
            for (i in groups.indices.reversed()) out.add((groups[i] or if (i > 0) 0x80 else 0).toByte())
        }
        base128(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) base128(arcs[i])
        return out.toByteArray()
    }

    /** The reader's ephemeral key pair and the shared secret with the chip's static key. */
    private class Ephemeral(
        val publicKey: ByteArray,
        val sharedSecret: ByteArray,
    ) {
        fun wipe() = CryptoBytes.wipe(sharedSecret)

        companion object {
            /**
             * A fresh key pair on the chip's [parameters] (section 6.2.1, step 2). The public key is
             * encoded as section 6.2.4.3 says: the uncompressed point for ECDH, the unsigned integer
             * with no leading zero bytes for DH (section 9.4.1). K is the x-coordinate for ECDH and
             * the DH value at the length of p (section 9.7.1).
             */
            fun generate(
                crypto: ChipCrypto,
                parameters: CryptoDomainParameters,
                chipPublicValue: ByteArray,
            ): Ephemeral =
                when (parameters) {
                    is DhDomainParameters -> {
                        val sk = DiffieHellman.generatePrivateKey(crypto, parameters)
                        try {
                            val pk = TlvWriter.minimalUnsigned(DiffieHellman.publicKey(crypto, parameters, sk))
                            Ephemeral(pk, DiffieHellman.sharedSecret(crypto, parameters, sk, chipPublicValue))
                        } finally {
                            CryptoBytes.wipe(sk)
                        }
                    }

                    is EcDomainParameters -> {
                        val sk = EllipticCurves.generatePrivateKey(crypto, parameters)
                        try {
                            val pk = EcPointCodec.encode(parameters, EllipticCurves.publicKey(crypto, parameters, sk))
                            val peer = EcPointCodec.decode(crypto, parameters, chipPublicValue)
                            Ephemeral(pk, EllipticCurves.sharedSecret(crypto, parameters, sk, peer))
                        } finally {
                            CryptoBytes.wipe(sk)
                        }
                    }
                }
        }
    }
}
