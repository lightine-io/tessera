package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.CommandTooLong
import io.lightine.tessera.emrtd.internal.apdu.ExtendedLengthRequired
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.sm.SecureChannel
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter

/** How the reader runs PACE beyond the suite and the password. */
internal class PaceOptions(
    /**
     * Send the domain parameter ID in MSE:Set AT (tag 0x84). Section 4.4.4.1: required when the
     * chip offers more than one parameter set for PACE, so the caller sets it when EF.CardAccess
     * lists several; otherwise it is left out (Note 2: send nothing the chip may not expect).
     */
    val sendParameterId: Boolean = false,
    /**
     * Whether the chip has stated it accepts extended length (Part 11, section 9.3.1.2). Needed by
     * the 2048-bit DH groups, whose public keys do not fit a short APDU.
     */
    val extendedLengthAllowed: Boolean = false,
)

/** A PACE run that succeeded. */
internal class PaceResult(
    /** The secure channel with the new session keys and send sequence counter 0. */
    val channel: SecureChannel,
    val suite: PaceSuite,
    val passwordType: PacePasswordType,
    /** What the mapping learned beyond the keys; null for the generic mapping. */
    val mappingEvidence: MappingEvidence?,
)

/**
 * Password Authenticated Connection Establishment, Doc 9303 Part 11, section 4.4: the reader proves
 * it knows the password (MRZ or CAN), and reader and chip agree on strong session keys for secure
 * messaging.
 *
 * The run (section 4.4.1, Table 4):
 * 1. MSE:Set AT (`00 22 C1 A4`): 0x80 the protocol OID (value only), 0x83 the password reference,
 *    optionally 0x84 the parameter ID ([PaceOptions.sendParameterId]).
 * 2. GENERAL AUTHENTICATE (INS 0x86, P1P2 0000) four times, chained: every command but the last has
 *    the chaining bit (CLA 0x10, section 4.4.4.3). Each carries dynamic authentication data 0x7C.
 *    - step 1: an empty 0x7C; the chip answers 0x80, the encrypted nonce z. The reader decrypts
 *      s = D(Kπ, z), CBC with a zero IV (section 4.4.3.3), Kπ from the password (section 9.7.3);
 *    - step 2: the [Mapping] (0x81 / 0x82) gives the ephemeral group;
 *    - step 3: ephemeral keys 0x83 / 0x84. The chip's key is validated and must differ from the
 *      reader's (section 4.4.1, step 3d); K = KA(...), KSEnc = KDF(K, 1), KSMAC = KDF(K, 2);
 *    - step 4: the tokens 0x85 / 0x86, MAC(KSMAC, public key data object) over the other side's key
 *      (section 4.4.3.4): retail MAC for 3DES, CMAC cut to 8 bytes for AES.
 * 3. Secure messaging starts with the new keys and SSC 0 (sections 9.8.6.3, 9.8.7.3).
 *
 * The caller runs PACE in the master file before selecting the application, and sends everything
 * after a successful [open] through the returned channel. Transport failures pass through
 * unchanged; everything else is a [PaceException].
 */
internal object Pace {
    const val INS_MSE = 0x22
    const val INS_GENERAL_AUTHENTICATE = 0x86
    const val MSE_SET_AT_P1 = 0xC1
    const val MSE_SET_AT_P2 = 0xA4
    const val TAG_DYNAMIC_AUTHENTICATION_DATA = 0x7C
    const val TAG_CRYPTOGRAPHIC_MECHANISM = 0x80
    const val TAG_PASSWORD_REFERENCE = 0x83
    const val TAG_PARAMETER_ID = 0x84
    const val TAG_ENCRYPTED_NONCE = 0x80
    const val TAG_MAPPING_DATA_READER = 0x81
    const val TAG_MAPPING_DATA_CHIP = 0x82
    const val TAG_EPHEMERAL_KEY_READER = 0x83
    const val TAG_EPHEMERAL_KEY_CHIP = 0x84
    const val TAG_TOKEN_READER = 0x85
    const val TAG_TOKEN_CHIP = 0x86
    const val TOKEN_LENGTH = 8
    private const val CLA_PLAIN = 0x00
    private const val CLA_CHAINED = 0x10
    private const val SW_WRONG_DATA = 0x6A80
    private const val SW_REFERENCED_DATA_NOT_FOUND = 0x6A88

    /** The MSE:Set AT command for [suite] and [password] (section 4.4.4.1). */
    fun setAuthenticationTemplate(
        suite: PaceSuite,
        password: PacePasswordType,
        sendParameterId: Boolean,
    ): CommandApdu {
        var data =
            TlvWriter.encode(TAG_CRYPTOGRAPHIC_MECHANISM, oidContent(suite.oid)) +
                TlvWriter.encode(TAG_PASSWORD_REFERENCE, byteArrayOf(password.reference.toByte()))
        if (sendParameterId) {
            data += TlvWriter.encode(TAG_PARAMETER_ID, TlvWriter.minimalUnsigned(CryptoBytes.ofInt(suite.parameterId)))
        }
        return CommandApdu(CLA_PLAIN, INS_MSE, MSE_SET_AT_P1, MSE_SET_AT_P2, data)
    }

    /**
     * Runs PACE with [key] over the plain [exchange] and returns the secure channel it opens.
     * [mapping] defaults to the reader's implementation of the suite's mapping. [newPrivateKey] and
     * [randomBytes] are the random sources (tests pass the worked examples' values).
     *
     * @throws PaceException when the suite is not supported, the chip refuses, or an answer is wrong.
     */
    suspend fun open(
        exchange: ApduSender,
        crypto: ChipCrypto,
        key: ChipAccessKey,
        suite: PaceSuite,
        options: PaceOptions = PaceOptions(),
        mapping: Mapping? = Mappings.forKind(suite.mapping),
        newPrivateKey: (PaceGroup) -> ByteArray = { it.generatePrivateKey(crypto) },
        randomBytes: (Int) -> ByteArray = crypto::randomBytes,
    ): PaceResult {
        if (mapping == null || mapping.kind != suite.mapping) throw PaceException(PaceFailure.UNSUPPORTED_SUITE)
        val passwordType = PacePasswordType.of(key)
        val context = MappingContext(crypto, suite, newPrivateKey, randomBytes)
        val run = Run(exchange, crypto, suite, options)
        try {
            return run.execute(key, passwordType, mapping, context)
        } finally {
            mapping.wipe()
        }
    }

    private class Run(
        val exchange: ApduSender,
        val crypto: ChipCrypto,
        val suite: PaceSuite,
        val options: PaceOptions,
    ) {
        private val cipher: SmCipher = suite.smCipher
        private val oid: ByteArray = oidContent(suite.oid)

        suspend fun execute(
            key: ChipAccessKey,
            passwordType: PacePasswordType,
            mapping: Mapping,
            context: MappingContext,
        ): PaceResult {
            val setAtCommand = setAuthenticationTemplate(suite, passwordType, options.sendParameterId)
            // A size limit that can be known now is checked before anything is sent: once a GENERAL
            // AUTHENTICATE has been answered, the password has been used and no other suite may run.
            if (!fits(setAtCommand) || !fits(largestGeneralAuthenticate())) {
                throw PaceException(PaceFailure.EXTENDED_LENGTH_REQUIRED, PaceStep.SET_AUTHENTICATION_TEMPLATE)
            }
            val setAt = send(PaceStep.SET_AUTHENTICATION_TEMPLATE, setAtCommand)
            if (setAt.sw != StatusWord.SUCCESS) {
                val failure =
                    when {
                        isPasswordState(setAt.sw) -> PaceFailure.PASSWORD_STATE
                        setAt.sw == SW_WRONG_DATA -> PaceFailure.ALGORITHM_NOT_SUPPORTED
                        setAt.sw == SW_REFERENCED_DATA_NOT_FOUND -> PaceFailure.REFERENCED_DATA_NOT_FOUND
                        else -> PaceFailure.SET_AUTHENTICATION_TEMPLATE_REFUSED
                    }
                throw PaceException(failure, PaceStep.SET_AUTHENTICATION_TEMPLATE, setAt.sw)
            }

            // Step 1: the encrypted nonce.
            val z =
                generalAuthenticate(
                    PaceStep.ENCRYPTED_NONCE,
                    null,
                    ByteArray(0),
                    TAG_ENCRYPTED_NONCE,
                    last = false,
                ).getValue(TAG_ENCRYPTED_NONCE)
            if (z.isEmpty() || z.size % cipher.blockSize != 0) throw PaceException(PaceFailure.WRONG_NONCE_LENGTH, PaceStep.ENCRYPTED_NONCE)
            val kPi = PacePassword.derive(crypto, key, cipher)
            val s =
                try {
                    crypto.decrypt(cipher.blockCipher, CipherMode.CBC, kPi, ByteArray(cipher.blockSize), z)
                } finally {
                    CryptoBytes.wipe(kPi)
                }

            // Step 2: the mapping.
            val ephemeral =
                try {
                    mapping.map(context, s) { data ->
                        generalAuthenticate(PaceStep.MAP_NONCE, TAG_MAPPING_DATA_READER, data, TAG_MAPPING_DATA_CHIP, last = false)
                            .getValue(TAG_MAPPING_DATA_CHIP)
                    }
                } finally {
                    CryptoBytes.wipe(s)
                }

            // Step 3: the key agreement in the ephemeral group.
            val privateKey = context.newPrivateKey(ephemeral)
            val readerKey: ByteArray
            val chipKey: ByteArray
            val secret: ByteArray
            try {
                readerKey = ephemeral.publicKey(crypto, privateKey)
                val received =
                    generalAuthenticate(PaceStep.KEY_AGREEMENT, TAG_EPHEMERAL_KEY_READER, readerKey, TAG_EPHEMERAL_KEY_CHIP, last = false)
                        .getValue(TAG_EPHEMERAL_KEY_CHIP)
                chipKey = ephemeral.validatePublicKey(crypto, received)
                if (CryptoBytes.unsignedEquals(
                        chipKey,
                        readerKey,
                    )
                ) {
                    throw PaceException(PaceFailure.EQUAL_EPHEMERAL_KEYS, PaceStep.KEY_AGREEMENT)
                }
                secret = ephemeral.sharedSecret(crypto, privateKey, chipKey)
            } finally {
                CryptoBytes.wipe(privateKey)
            }
            val keys =
                try {
                    SessionKeys.derive(crypto, secret, cipher)
                } finally {
                    CryptoBytes.wipe(secret)
                }

            // Step 4: the tokens.
            try {
                val readerToken = token(keys, ephemeral.tokenInput(oid, chipKey))
                val answer = generalAuthenticate(PaceStep.MUTUAL_AUTHENTICATION, TAG_TOKEN_READER, readerToken, TAG_TOKEN_CHIP, last = true)
                val expected = token(keys, ephemeral.tokenInput(oid, readerKey))
                if (!CryptoBytes.constantTimeEquals(expected, answer.getValue(TAG_TOKEN_CHIP))) {
                    throw PaceException(PaceFailure.WRONG_CHIP_TOKEN, PaceStep.MUTUAL_AUTHENTICATION)
                }
                val evidence = mapping.afterAuthentication(context, keys, AuthenticationResponse(answer))
                // The channel owns the keys from here and wipes them when the session ends.
                return PaceResult(SecureChannel(exchange, crypto, keys, cipher.zeroSsc()), suite, passwordType, evidence)
            } catch (e: Throwable) {
                keys.wipe()
                throw e
            }
        }

        /** The authentication token of section 4.4.3.4 over [input]. */
        private fun token(
            keys: SessionKeys,
            input: ByteArray,
        ): ByteArray =
            when (cipher.blockCipher) {
                BlockCipher.DESEDE -> crypto.retailMac(keys.macKey, CryptoBytes.pad(input, cipher.blockSize))
                BlockCipher.AES -> crypto.cmac(keys.macKey, input).copyOf(TOKEN_LENGTH)
            }

        /**
         * One GENERAL AUTHENTICATE step: sends 0x7C holding [tag] with [value] (an empty 0x7C when
         * [tag] is null) and returns the data objects of the chip's 0x7C by tag, which must hold
         * [expectedTag].
         */
        private suspend fun generalAuthenticate(
            step: PaceStep,
            tag: Int?,
            value: ByteArray,
            expectedTag: Int,
            last: Boolean,
        ): Map<Int, ByteArray> {
            val answer = send(step, generalAuthenticateCommand(tag, value, last))
            if (answer.sw != StatusWord.SUCCESS) throw PaceException(PaceFailure.STEP_REFUSED, step, answer.sw)
            val objects =
                try {
                    val outer = TlvParser().parseSingle(answer.data)
                    if (outer.tag != TAG_DYNAMIC_AUTHENTICATION_DATA) throw PaceException(PaceFailure.MALFORMED_RESPONSE, step)
                    outer.children().associate { it.tag to it.value() }
                } catch (e: TlvException) {
                    throw PaceException(PaceFailure.MALFORMED_RESPONSE, step, cause = e)
                }
            if (expectedTag !in objects) throw PaceException(PaceFailure.MALFORMED_RESPONSE, step)
            return objects
        }

        private fun generalAuthenticateCommand(
            tag: Int?,
            value: ByteArray,
            last: Boolean,
        ): CommandApdu {
            val inner = if (tag == null) ByteArray(0) else TlvWriter.encode(tag, value)
            val data = TlvWriter.encode(TAG_DYNAMIC_AUTHENTICATION_DATA, inner)
            // An answer holds 0x7C around one public key at most, plus a few short objects.
            val needsLongAnswer = suite.group.maxPublicKeyLength + 16 > CommandApdu.MAX_SHORT_NE
            val ne = if (needsLongAnswer && options.extendedLengthAllowed) CommandApdu.MAX_EXTENDED_NE else CommandApdu.MAX_SHORT_NE
            return CommandApdu(if (last) CLA_PLAIN else CLA_CHAINED, INS_GENERAL_AUTHENTICATE, 0x00, 0x00, data, ne)
        }

        /**
         * The longest GENERAL AUTHENTICATE of the run: a public key of the group's largest size
         * (mapping data of the generic and chip authentication mappings, and the ephemeral keys,
         * are public keys of this group; the integrated mapping's nonce is shorter).
         */
        private fun largestGeneralAuthenticate(): CommandApdu =
            generalAuthenticateCommand(TAG_EPHEMERAL_KEY_READER, ByteArray(suite.group.maxPublicKeyLength), last = false)

        /** Whether [command] can be sent: short, or extended where allowed, and within the transport's limit. */
        private fun fits(command: CommandApdu): Boolean {
            val extendedOk = options.extendedLengthAllowed && exchange.supportsExtendedLength
            return (command.fitsShort || extendedOk) && command.encodedLength() <= exchange.maxCommandLength
        }

        /**
         * Sends one command exactly once ([exchange] is the raw sender: no 61xx or 6Cxx follow-up).
         * A command the transport cannot carry becomes [PaceFailure.EXTENDED_LENGTH_REQUIRED] at
         * [step].
         */
        private suspend fun send(
            step: PaceStep,
            command: CommandApdu,
        ): ResponseApdu =
            try {
                exchange.send(command, options.extendedLengthAllowed)
            } catch (e: ExtendedLengthRequired) {
                throw PaceException(PaceFailure.EXTENDED_LENGTH_REQUIRED, step, cause = e)
            } catch (e: CommandTooLong) {
                throw PaceException(PaceFailure.EXTENDED_LENGTH_REQUIRED, step, cause = e)
            }
    }

    /**
     * A status word to MSE:Set AT that is about the password, not the suite (TR-03110-3, B.14.1
     * and B.14.2): a warning 62xx or 63xx (63CX: selected with X tries left) or 6982 to 6985.
     */
    internal fun isPasswordState(sw: Int): Boolean =
        sw shr 8 == 0x62 ||
            sw shr 8 == 0x63 ||
            sw == StatusWord.SECURITY_STATUS_NOT_SATISFIED ||
            sw == StatusWord.AUTHENTICATION_METHOD_BLOCKED ||
            sw == StatusWord.REFERENCE_DATA_NOT_USABLE ||
            sw == StatusWord.CONDITIONS_OF_USE_NOT_SATISFIED

    /** The content octets of the DER encoding of a dotted [oid] (tag 0x06 left out). */
    internal fun oidContent(oid: String): ByteArray {
        val arcs = oid.split('.').map { it.toLong() }
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs.all { it >= 0 }) { "not an object identifier" }
        val out = ArrayList<Byte>()

        fun base128(value: Long) {
            val groups = ArrayList<Byte>()
            var v = value
            do {
                groups.add(0, (v and 0x7F).toByte())
                v = v ushr 7
            } while (v > 0)
            for (i in 0 until groups.lastIndex) groups[i] = (groups[i].toInt() or 0x80).toByte()
            out.addAll(groups)
        }
        base128(arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach { base128(it) }
        return out.toByteArray()
    }
}
