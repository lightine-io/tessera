package io.lightine.tessera.emrtd.internal.flow

import io.lightine.tessera.emrtd.AccessReport
import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.CheckOutcome
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipCheck
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipCheckReason
import io.lightine.tessera.emrtd.ChipFile
import io.lightine.tessera.emrtd.ChipFileContext
import io.lightine.tessera.emrtd.ChipFileError
import io.lightine.tessera.emrtd.ChipPaceOffer
import io.lightine.tessera.emrtd.ChipPassword
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.ChipReadStepOutcome
import io.lightine.tessera.emrtd.ChipSignedCopy
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.internal.aa.ActiveAuthentication
import io.lightine.tessera.emrtd.internal.aa.ActiveAuthenticationFailure
import io.lightine.tessera.emrtd.internal.aa.ActiveAuthenticationResult
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.CommandTooLong
import io.lightine.tessera.emrtd.internal.apdu.ExtendedLengthRequired
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.MalformedResponse
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.bac.Bac
import io.lightine.tessera.emrtd.internal.bac.BacException
import io.lightine.tessera.emrtd.internal.bac.BacFailure
import io.lightine.tessera.emrtd.internal.ca.CaContext
import io.lightine.tessera.emrtd.internal.ca.CaOutcome
import io.lightine.tessera.emrtd.internal.ca.CaSkipReason
import io.lightine.tessera.emrtd.internal.ca.ChipAuthentication
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoUnavailableException
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.files.FileNotFound
import io.lightine.tessera.emrtd.internal.files.FileProtocolFailure
import io.lightine.tessera.emrtd.internal.files.FileReadFailure
import io.lightine.tessera.emrtd.internal.files.FileReader
import io.lightine.tessera.emrtd.internal.files.FileSecurityStatusNotSatisfied
import io.lightine.tessera.emrtd.internal.files.FileStatusFailure
import io.lightine.tessera.emrtd.internal.files.MalformedFile
import io.lightine.tessera.emrtd.internal.pa.CertTimes
import io.lightine.tessera.emrtd.internal.pa.PaCheck
import io.lightine.tessera.emrtd.internal.pa.PaCheckKind
import io.lightine.tessera.emrtd.internal.pa.PaInputs
import io.lightine.tessera.emrtd.internal.pa.PaOutcome
import io.lightine.tessera.emrtd.internal.pa.PassiveAuthentication
import io.lightine.tessera.emrtd.internal.pa.SignedObject
import io.lightine.tessera.emrtd.internal.pace.ChipAuthMappingCheck
import io.lightine.tessera.emrtd.internal.pace.ChipAuthMappingEvidence
import io.lightine.tessera.emrtd.internal.pace.Pace
import io.lightine.tessera.emrtd.internal.pace.PaceException
import io.lightine.tessera.emrtd.internal.pace.PaceFailure
import io.lightine.tessera.emrtd.internal.pace.PaceOptions
import io.lightine.tessera.emrtd.internal.pace.PaceSuite
import io.lightine.tessera.emrtd.internal.securityinfo.AtrInfoParser
import io.lightine.tessera.emrtd.internal.securityinfo.ChipSecurityFiles
import io.lightine.tessera.emrtd.internal.securityinfo.KeyAgreement
import io.lightine.tessera.emrtd.internal.securityinfo.PaceInfo
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoException
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfos
import io.lightine.tessera.emrtd.internal.securityinfo.SessionCipher
import io.lightine.tessera.emrtd.internal.sm.SecureChannel
import io.lightine.tessera.emrtd.internal.sm.SecureMessagingException
import io.lightine.tessera.emrtd.internal.tlv.TlvException
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import io.lightine.tessera.emrtd.internal.x509.CertTime
import io.lightine.tessera.emrtd.lds.BiometricGroup
import io.lightine.tessera.emrtd.lds.Dg11View
import io.lightine.tessera.emrtd.lds.Dg12View
import io.lightine.tessera.emrtd.lds.Dg13View
import io.lightine.tessera.emrtd.lds.Dg16View
import io.lightine.tessera.emrtd.lds.Dg1View
import io.lightine.tessera.emrtd.lds.DisplayedImageGroup
import io.lightine.tessera.emrtd.lds.EfComView
import io.lightine.tessera.emrtd.lds.LdsParseResult
import io.lightine.tessera.emrtd.lds.SecurityObjectParse
import io.lightine.tessera.emrtd.lds.SecurityObjectView
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipBlank
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.ChipFileNotFound
import io.lightine.tessera.types.errors.ChipMalformedFile
import io.lightine.tessera.types.errors.ChipUnexpectedAnswer
import io.lightine.tessera.types.errors.ChipUnsupportedAlgorithm
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/** Ends the read with [error]; the caught place decides Partial or Failed. */
private class Stop(
    val error: ChipError,
) : Exception()

/**
 * One whole chip read, in the order of Doc 9303 Part 11, Appendix J.1 (with the EF.CardSecurity
 * part of J.2):
 *
 * 1. Access (section 4.2): read EF.CardAccess in the master file. When it offers a PACE suite the
 *    reader runs, run PACE with the best one (the chip authentication mapping first, as J.1
 *    recommends, then the strongest cipher), sending the parameter ID when EF.CardAccess lists more
 *    than one suite (section 4.4.4.1). Otherwise select the application and run BAC; a chip that
 *    refuses GET CHALLENGE and lets the reader read EF.COM in the clear is a plain chip (section
 *    4.1, Note 1). BAC and PACE never both run in one session (section 4.1).
 * 2. After PACE, read EF.CardSecurity in the master file, and run chip authentication there when it
 *    lists the keys and the chip authentication mapping did not run (J.2 step 4).
 * 3. Select the application; read EF.COM and EF.SOD, then DG14 and DG15 when listed.
 * 4. Chip authentication in the application with DG14's keys, or else active authentication with
 *    DG15 (J.1 step 3), never both.
 * 5. Read the data groups the app asked for.
 * 6. Passive authentication over everything read (J.1 step 2; no chip command), then the chip
 *    authentication facts, which Appendix J calls complete only together with passive
 *    authentication of the file that holds the key.
 *
 * Once access is granted, a stop gives Partial with the checks run over what was read; before it
 * (or for a blank chip) it gives Failed. Every session key is wiped when the read ends, however it
 * ends. Telemetry events carry protocol names, status words, file identifiers, byte counts and
 * timings only, and a sink that throws never changes the read.
 */
internal class ReadFlow(
    private val transport: ApduTransport,
    private val options: ChipReadOptions,
    private val telemetry: TelemetrySink,
    private val cryptoProvider: () -> ChipCrypto = ::chipCrypto,
) {
    private val plain = PlainApduSender(transport)
    private var channel: SecureChannel? = null
    private val sender: ApduSender get() = channel ?: plain
    private var reader = FileReader(plain)
    private var crypto: ChipCrypto? = null
    private var extended = false

    private val files = ArrayList<ChipFile>()
    private val fileErrors = ArrayList<ChipFileError>()

    // Access.
    private var protocol = ChipAccessProtocol.NONE
    private var accessDone = false
    private var cardAccessRead = false
    private var offered: List<ChipPaceOffer> = emptyList()
    private var cardAccessInfos: SecurityInfos? = null
    private var paceSuite: PaceSuite? = null
    private var camEvidence: ChipAuthMappingEvidence? = null

    // Master-file and application content.
    private var cardAccess: ByteArray? = null
    private var cardSecurity: ByteArray? = null
    private var cardSecurityInfos: SecurityInfos? = null
    private var dg14Infos: SecurityInfos? = null

    // Chip authentication (one fact per place it was tried) and active authentication.
    private val caChecks = LinkedHashMap<ChipFileContext?, CaState>()
    private var aaOutcome: CheckOutcome = CheckOutcome.NotRun(ChipCheckReason.READ_STOPPED)
    private var aaNeedsKeyFileChecks = false

    private class CaState(
        var outcome: CheckOutcome,
        var keysSwitched: Boolean = false,
    )

    /** The secure channels this read opened, for tests: every one is closed when [run] returns. */
    internal val openedChannels = ArrayList<SecureChannel>()

    /** The chip authentication mapping evidence, for tests: wiped when [run] returns. */
    internal val mappingEvidence: ChipAuthMappingEvidence? get() = camEvidence

    suspend fun run(): ChipReadResult {
        val start = TimeSource.Monotonic.markNow()
        val result =
            try {
                execute()
                ChipReadResult.Complete(assemble())
            } catch (s: Stop) {
                stopped(s.error)
            } catch (e: ApduTransportException) {
                stopped(e.error)
            } catch (e: SecureMessagingException) {
                stopped(FlowMappings.secureMessaging(e))
            } catch (e: MalformedResponse) {
                stopped(ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.NO_STATUS_WORD))
            } catch (e: CommandTooLong) {
                stopped(ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNUSABLE_FILE_ANSWER))
            } finally {
                openedChannels.forEach { it.close() }
                camEvidence?.wipe()
            }
        val outcome = if (result is ChipReadResult.Complete) ChipReadStepOutcome.SUCCEEDED else ChipReadStepOutcome.FAILED
        event(ChipReadStep.READ, outcome, start, protocol = protocol.name)
        return result
    }

    private fun stopped(error: ChipError): ChipReadResult =
        if (accessDone && error != ChipBlank) {
            ChipReadResult.Partial(assemble(), error)
        } else {
            ChipReadResult.Failed(error, if (accessDone) accessReport() else null)
        }

    private suspend fun execute() {
        val key = options.accessKey
        if (key.isCleared) throw Stop(ChipAccessDenied(ChipAccessProtocol.NONE, ChipAccessDenied.Reason.KEY_CLEARED))
        crypto =
            try {
                cryptoProvider()
            } catch (e: ChipCryptoUnavailableException) {
                null
            }

        readExtendedLengthStatement()
        val infos = readCardAccess()
        val suites = infos?.let(::paceSuites).orEmpty()
        if (suites.isNotEmpty()) {
            openWithPace(key, suites, infos!!.paceInfos.size > 1)
            readCardSecurity()
            selectApplication()
        } else {
            openWithoutPace(key, infos)
        }
        readApplication()
    }

    // --- Access -------------------------------------------------------------------------------

    /** EF.ATR/INFO (Part 10, 3.10): read only when the app and the transport allow extended length. */
    private suspend fun readExtendedLengthStatement() {
        if (!options.allowExtendedLength || !transport.supportsExtendedLength) return
        val bytes = readMasterFile(FILE_ATR_INFO) ?: return
        extended =
            try {
                AtrInfoParser.parse(bytes).extendedLcLe
            } catch (e: SecurityInfoException) {
                false
            }
    }

    private suspend fun readCardAccess(): SecurityInfos? {
        val bytes = readMasterFile(LdsIds.EF_CARD_ACCESS) ?: return null
        cardAccessRead = true
        cardAccess = bytes
        val infos =
            try {
                ChipSecurityFiles.parseCardAccess(bytes)
            } catch (e: SecurityInfoException) {
                fileErrors +=
                    ChipFileError(
                        LdsIds.EF_CARD_ACCESS,
                        true,
                        ChipMalformedFile(LdsIds.EF_CARD_ACCESS, ChipMalformedFile.Reason.NOT_VALID_TLV, e.offset),
                    )
                return null
            }
        cardAccessInfos = infos
        offered =
            infos.paceInfos.map { info ->
                ChipPaceOffer(info.protocol, FlowMappings.mapping(info.mapping), info.parameterId, suiteOf(info) != null)
            }
        return infos
    }

    private fun suiteOf(info: PaceInfo): PaceSuite? =
        try {
            PaceSuite.of(info)
        } catch (e: PaceException) {
            null
        }

    /** The suites the reader runs, best first: the chip authentication mapping, then the strongest cipher, ECDH before DH. */
    private fun paceSuites(infos: SecurityInfos): List<PaceSuite> =
        infos.usablePaceInfos
            .mapNotNull(::suiteOf)
            .sortedWith(
                compareBy<PaceSuite>(
                    { mappingRank(it.mapping) },
                    { -cipherRank(it.sessionCipher) },
                    { if (it.agreement == KeyAgreement.ECDH) 0 else 1 },
                ),
            )

    private fun mappingRank(m: PaceMapping): Int =
        when (m) {
            PaceMapping.CHIP_AUTHENTICATION -> 0
            PaceMapping.GENERIC -> 1
            PaceMapping.INTEGRATED -> 2
        }

    private fun cipherRank(c: SessionCipher): Int =
        when (c) {
            SessionCipher.TDES_CBC_CBC -> 0
            SessionCipher.AES_CBC_CMAC_128 -> 1
            SessionCipher.AES_CBC_CMAC_192 -> 2
            SessionCipher.AES_CBC_CMAC_256 -> 3
        }

    private suspend fun openWithPace(
        key: ChipAccessKey,
        suites: List<PaceSuite>,
        sendParameterId: Boolean,
    ) {
        val c =
            crypto
                ?: throw Stop(
                    ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.NO_CRYPTOGRAPHY_ON_PLATFORM),
                )
        var refusedSw: Int? = null
        var needsExtended = false
        for (suite in suites) {
            val start = TimeSource.Monotonic.markNow()
            val name = paceName(suite)
            try {
                val result = Pace.open(plain, c, key, suite, PaceOptions(sendParameterId, extended))
                // The channel is owned here before anything else can fail, so its keys are always wiped.
                useChannel(result.channel)
                camEvidence = result.mappingEvidence as? ChipAuthMappingEvidence
                protocol = ChipAccessProtocol.PACE
                paceSuite = suite
                accessDone = true
                event(ChipReadStep.PACE, ChipReadStepOutcome.SUCCEEDED, start, protocol = name)
                return
            } catch (e: PaceException) {
                event(ChipReadStep.PACE, ChipReadStepOutcome.FAILED, start, protocol = name, sw = e.sw)
                when {
                    e.failure == PaceFailure.UNSUPPORTED_SUITE -> {
                        // Nothing was sent for this suite; try the next one.
                    }

                    e.failure == PaceFailure.EXTENDED_LENGTH_REQUIRED -> {
                        needsExtended = true
                    }

                    e.failure in SET_AT_REFUSALS -> {
                        refusedSw = e.sw
                    }

                    else -> {
                        throw Stop(FlowMappings.pace(e))
                    }
                }
            } catch (e: ExtendedLengthRequired) {
                event(ChipReadStep.PACE, ChipReadStepOutcome.FAILED, start, protocol = name)
                needsExtended = true
            } catch (e: CommandTooLong) {
                // A command longer than the transport sends: the same limit as a missing extended length.
                event(ChipReadStep.PACE, ChipReadStepOutcome.FAILED, start, protocol = name)
                needsExtended = true
            } catch (e: ChipCryptoException) {
                event(ChipReadStep.PACE, ChipReadStepOutcome.FAILED, start, protocol = name)
                throw Stop(ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.CRYPTOGRAPHY_FAILED))
            }
        }
        val reason =
            when {
                needsExtended -> ChipUnsupportedAlgorithm.Reason.EXTENDED_LENGTH_REQUIRED
                refusedSw != null -> ChipUnsupportedAlgorithm.Reason.CHIP_REFUSED_SUITE
                else -> ChipUnsupportedAlgorithm.Reason.NO_SUPPORTED_SUITE
            }
        throw Stop(ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, reason, refusedSw))
    }

    private fun useChannel(ch: SecureChannel) {
        openedChannels += ch
        channel = ch
        reader = FileReader(ch)
    }

    private suspend fun openWithoutPace(
        key: ChipAccessKey,
        infos: SecurityInfos?,
    ) {
        // Part 11, 4.2 step 4: BAC runs in the application, so select it first.
        selectApplication()
        val offersUnusablePace = infos != null && infos.paceInfos.isNotEmpty()
        val c = crypto
        if (key is ChipAccessKey.Mrz && c != null) {
            val start = TimeSource.Monotonic.markNow()
            try {
                val opened = Bac.open(plain, c, key)
                useChannel(opened)
                protocol = ChipAccessProtocol.BAC
                accessDone = true
                event(ChipReadStep.BAC, ChipReadStepOutcome.SUCCEEDED, start, protocol = "BAC")
                return
            } catch (e: BacException) {
                event(ChipReadStep.BAC, ChipReadStepOutcome.FAILED, start, protocol = "BAC", sw = e.sw)
                if (e.failure != BacFailure.CHALLENGE_REFUSED) throw Stop(FlowMappings.bac(e))
                if (probePlain()) return
                throw Stop(noWayIn(offersUnusablePace, FlowMappings.bac(e)))
            } catch (e: ChipCryptoException) {
                event(ChipReadStep.BAC, ChipReadStepOutcome.FAILED, start, protocol = "BAC")
                throw Stop(ChipUnsupportedAlgorithm(ChipAccessProtocol.BAC, ChipUnsupportedAlgorithm.Reason.CRYPTOGRAPHY_FAILED))
            }
        }
        // No BAC possible here (a card access number, or no cryptography): a plain chip still reads.
        if (probePlain()) return
        val error =
            when {
                key is ChipAccessKey.Can -> ChipAccessDenied(ChipAccessProtocol.NONE, ChipAccessDenied.Reason.KEY_NEEDS_PACE)
                else -> ChipUnsupportedAlgorithm(ChipAccessProtocol.BAC, ChipUnsupportedAlgorithm.Reason.NO_CRYPTOGRAPHY_ON_PLATFORM)
            }
        throw Stop(noWayIn(offersUnusablePace, error))
    }

    private fun noWayIn(
        offersUnusablePace: Boolean,
        otherwise: ChipError,
    ): ChipError =
        if (offersUnusablePace) {
            ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.NO_SUPPORTED_SUITE)
        } else {
            otherwise
        }

    /** A plain chip (Part 11, 4.1 Note 1): EF.COM reads without any access protocol. Keeps it when it does. */
    private suspend fun probePlain(): Boolean {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val bytes = reader.readFile(LdsIds.EF_COM, extended)
            files += ChipFile(LdsIds.EF_COM, false, bytes)
            protocol = ChipAccessProtocol.NONE
            accessDone = true
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.SUCCEEDED, start, fileId = LdsIds.EF_COM, bytes = bytes.size)
            true
        } catch (e: FileSecurityStatusNotSatisfied) {
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.FAILED, start, fileId = LdsIds.EF_COM, sw = SW_SECURITY)
            false
        } catch (e: FileReadFailure) {
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.FAILED, start, fileId = LdsIds.EF_COM)
            false
        }
    }

    private suspend fun selectApplication() {
        val start = TimeSource.Monotonic.markNow()
        try {
            reader.selectApplication(extended)
            event(ChipReadStep.SELECT_APPLICATION, ChipReadStepOutcome.SUCCEEDED, start)
        } catch (e: FileNotFound) {
            event(ChipReadStep.SELECT_APPLICATION, ChipReadStepOutcome.FAILED, start, sw = SW_NOT_FOUND)
            throw Stop(ChipBlank)
        } catch (e: FileReadFailure) {
            event(ChipReadStep.SELECT_APPLICATION, ChipReadStepOutcome.FAILED, start)
            throw Stop(fileFailure(e, null))
        }
    }

    /** After PACE: EF.CardSecurity (J.2 steps 2 and 3), then chip authentication in the master file when it applies. */
    private suspend fun readCardSecurity() {
        val bytes = readMasterFile(FILE_CARD_SECURITY) ?: return
        cardSecurity = bytes
        val infos =
            try {
                ChipSecurityFiles.parseCardSecurityContent(bytes)
            } catch (e: SecurityInfoException) {
                fileErrors +=
                    ChipFileError(
                        FILE_CARD_SECURITY,
                        true,
                        ChipMalformedFile(FILE_CARD_SECURITY, ChipMalformedFile.Reason.NOT_VALID_TLV, e.offset),
                    )
                return
            }
        cardSecurityInfos = infos
        if (camEvidence != null || infos.chipAuthenticationInfos.isEmpty()) return
        runChipAuthentication(infos, CaContext.MASTER_FILE)
    }

    /** Reads a master-file EF; `null` when the chip does not have it or does not let the reader read it. */
    private suspend fun readMasterFile(fid: Int): ByteArray? {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val bytes = reader.readFile(fid, extended)
            files += ChipFile(fid, true, bytes)
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.SUCCEEDED, start, fileId = fid, bytes = bytes.size)
            bytes
        } catch (e: FileNotFound) {
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.SKIPPED, start, fileId = fid, sw = SW_NOT_FOUND)
            null
        } catch (e: FileReadFailure) {
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.FAILED, start, fileId = fid)
            null
        }
    }

    // --- Application --------------------------------------------------------------------------

    private suspend fun readApplication() {
        val efCom = appFile(LdsIds.EF_COM) ?: readAppFile(LdsIds.EF_COM, true)
        val sod = readAppFile(LdsIds.EF_SOD, true)
        val listed = listedDataGroups(efCom, sod)

        val dg14 = readDataGroup(14, requested = 14 in options.dataGroups, listed = listed)
        val dg15 = readDataGroup(15, requested = 15 in options.dataGroups, listed = listed)
        var dg14Unreadable = false
        dg14Infos =
            dg14?.let {
                try {
                    ChipSecurityFiles.parseDg14(it)
                } catch (e: SecurityInfoException) {
                    dg14Unreadable = true
                    fileErrors +=
                        ChipFileError(FILE_DG14, false, ChipMalformedFile(FILE_DG14, ChipMalformedFile.Reason.NOT_VALID_TLV, e.offset))
                    null
                }
            }

        chipOrActiveAuthentication(dg14Infos, dg14Unreadable, dg15)

        for (n in options.dataGroups.sorted()) {
            if (n == 14 || n == 15) continue
            readDataGroup(n, requested = true, listed = listed)
        }
    }

    /** The data groups EF.COM and EF.SOD name together, or `null` when neither could be read. */
    private fun listedDataGroups(
        efCom: ByteArray?,
        sod: ByteArray?,
    ): Set<Int>? {
        val fromCom = efCom?.let { (EfComView.parse(it) as? LdsParseResult.Success)?.view?.dataGroups }
        val fromSod =
            sod?.let {
                (SecurityObjectView.parse(it) as? SecurityObjectParse.Parsed)?.view?.dataGroupHashes?.map { h ->
                    h.number
                }
            }
        if (fromCom == null && fromSod == null) return null
        return (fromCom.orEmpty() + fromSod.orEmpty()).toSet()
    }

    private suspend fun readDataGroup(
        number: Int,
        requested: Boolean,
        listed: Set<Int>?,
    ): ByteArray? {
        val fid = 0x0100 + number
        if (listed != null && number !in listed) {
            if (requested) fileErrors += ChipFileError(fid, false, ChipFileNotFound(fid, listedByChip = false))
            return null
        }
        return readAppFile(fid, requested)
    }

    /**
     * Reads one application EF. A missing, protected or malformed file becomes an entry in
     * [fileErrors] (when it was asked for) and the read goes on; a lost tag or a lost session ends it.
     */
    private suspend fun readAppFile(
        fid: Int,
        requested: Boolean,
    ): ByteArray? {
        val start = TimeSource.Monotonic.markNow()
        return try {
            val bytes = reader.readFile(fid, extended)
            files += ChipFile(fid, false, bytes)
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.SUCCEEDED, start, fileId = fid, bytes = bytes.size)
            bytes
        } catch (e: FileNotFound) {
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.FAILED, start, fileId = fid, sw = SW_NOT_FOUND)
            if (requested) fileErrors += ChipFileError(fid, false, ChipFileNotFound(fid, listedByChip = true))
            null
        } catch (e: FileReadFailure) {
            event(ChipReadStep.READ_FILE, ChipReadStepOutcome.FAILED, start, fileId = fid, sw = (e as? FileStatusFailure)?.statusWord)
            fileErrors += ChipFileError(fid, false, fileFailure(e, fid))
            null
        }
    }

    private fun fileFailure(
        e: FileReadFailure,
        fid: Int?,
    ): ChipError =
        when (e) {
            is FileNotFound -> ChipFileNotFound(fid ?: e.fid ?: 0, listedByChip = true)
            is FileSecurityStatusNotSatisfied -> ChipAccessDenied(protocol, ChipAccessDenied.Reason.FILE_PROTECTED, SW_SECURITY)
            is FileStatusFailure -> ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNEXPECTED_STATUS_WORD, e.statusWord)
            is MalformedFile -> ChipMalformedFile(fid, ChipMalformedFile.Reason.NOT_VALID_TLV)
            is FileProtocolFailure -> ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNUSABLE_FILE_ANSWER)
        }

    // --- Chip and active authentication -------------------------------------------------------

    private val keysSwitched: Boolean get() = caChecks.values.any { it.keysSwitched }

    private suspend fun chipOrActiveAuthentication(
        dg14: SecurityInfos?,
        dg14Unreadable: Boolean,
        dg15: ByteArray?,
    ) {
        val otherUsed = CheckOutcome.NotRun(ChipCheckReason.OTHER_CHIP_AUTHENTICATION_USED)
        if (camEvidence != null) {
            caChecks[null] = CaState(otherUsed)
            aaOutcome = otherUsed
            return
        }
        // Chip authentication in the master file that switched keys is done (J.2 step 4); otherwise
        // the application is tried with DG14's keys (J.1 step 3). A master-file attempt that did not
        // switch keys keeps its own fact beside the application's.
        if (!keysSwitched) {
            when {
                dg14Unreadable -> {
                    caChecks[ChipFileContext.EMRTD_APPLICATION] = CaState(CheckOutcome.NotRun(ChipCheckReason.UNREADABLE_FILE))
                }

                dg14 != null && dg14.chipAuthenticationInfos.isNotEmpty() -> {
                    runChipAuthentication(dg14, CaContext.EMRTD_APPLICATION)
                }

                caChecks.isEmpty() -> {
                    caChecks[null] = CaState(CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED))
                }
            }
        }
        when {
            dg15 == null -> aaOutcome = CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED)
            keysSwitched -> aaOutcome = otherUsed
            else -> runActiveAuthentication(dg15, dg14)
        }
    }

    /**
     * Chip authentication in [context] (Part 11, 6.2). When the chip agrees new keys, the next
     * protected answer shows whether it holds the private key: the reader selects the application
     * under the new keys at once, and only a 9000 whose checksum verifies under them confirms it.
     * Any other answer leaves Failed(NEW_KEYS_NOT_CONFIRMED); a lost session also ends the read.
     */
    private suspend fun runChipAuthentication(
        infos: SecurityInfos,
        context: CaContext,
    ) {
        val where = FlowMappings.context(context)
        val ch = channel
        val c = crypto
        if (ch == null) {
            caChecks[where] = CaState(CheckOutcome.NotRun(ChipCheckReason.NO_SECURE_MESSAGING))
            return
        }
        if (c == null) {
            caChecks[where] = CaState(CheckOutcome.NotRun(ChipCheckReason.CRYPTO_UNAVAILABLE))
            return
        }
        val start = TimeSource.Monotonic.markNow()
        val outcome =
            try {
                ChipAuthentication.run(ch, c, infos, context, extended)
            } catch (e: ChipCryptoException) {
                event(ChipReadStep.CHIP_AUTHENTICATION, ChipReadStepOutcome.FAILED, start, protocol = "CA")
                caChecks[where] = CaState(CheckOutcome.NotRun(ChipCheckReason.CRYPTOGRAPHY_FAILED))
                return
            }
        when (outcome) {
            is CaOutcome.NotRun -> {
                event(ChipReadStep.CHIP_AUTHENTICATION, ChipReadStepOutcome.SKIPPED, start, protocol = "CA")
                val reason =
                    if (outcome.skipped.any { it.reason == CaSkipReason.KEY_AGREEMENT_FAILED }) {
                        ChipCheckReason.KEY_AGREEMENT_FAILED
                    } else {
                        ChipCheckReason.NO_USABLE_SUITE
                    }
                caChecks[where] = CaState(CheckOutcome.NotRun(reason))
            }

            is CaOutcome.Refused -> {
                event(
                    ChipReadStep.CHIP_AUTHENTICATION,
                    ChipReadStepOutcome.FAILED,
                    start,
                    protocol = "CA",
                    sw = outcome.refusals.lastOrNull()?.sw,
                )
                caChecks[where] = CaState(CheckOutcome.Failed(ChipCheckReason.CHIP_REFUSED))
            }

            is CaOutcome.RestartedWithUnverifiedKey -> {
                // The keys changed; until the chip's next answer verifies under them, the fact is a failure.
                val state = CaState(CheckOutcome.Failed(ChipCheckReason.NEW_KEYS_NOT_CONFIRMED), keysSwitched = true)
                caChecks[where] = state
                try {
                    reader.selectApplication(extended)
                } catch (e: FileReadFailure) {
                    // An answer that verified but is not 9000 does not confirm the keys.
                    event(ChipReadStep.CHIP_AUTHENTICATION, ChipReadStepOutcome.FAILED, start, protocol = "CA")
                    return
                }
                event(ChipReadStep.CHIP_AUTHENTICATION, ChipReadStepOutcome.SUCCEEDED, start, protocol = "CA")
                state.outcome = CheckOutcome.NotRun(ChipCheckReason.KEY_FILE_CHECKS_NOT_PASSED)
            }
        }
    }

    private suspend fun runActiveAuthentication(
        dg15: ByteArray,
        dg14: SecurityInfos?,
    ) {
        val c = crypto
        if (c == null) {
            aaOutcome = CheckOutcome.NotRun(ChipCheckReason.CRYPTO_UNAVAILABLE)
            return
        }
        val start = TimeSource.Monotonic.markNow()
        val result =
            try {
                ActiveAuthentication(sender, c).authenticate(dg15, dg14?.activeAuthenticationInfos?.firstOrNull(), extended)
            } catch (e: ChipCryptoException) {
                event(ChipReadStep.ACTIVE_AUTHENTICATION, ChipReadStepOutcome.FAILED, start, protocol = "AA")
                aaOutcome = CheckOutcome.NotRun(ChipCheckReason.CRYPTOGRAPHY_FAILED)
                return
            }
        when (result) {
            ActiveAuthenticationResult.Passed -> {
                event(ChipReadStep.ACTIVE_AUTHENTICATION, ChipReadStepOutcome.SUCCEEDED, start, protocol = "AA")
                aaOutcome = CheckOutcome.NotRun(ChipCheckReason.KEY_FILE_CHECKS_NOT_PASSED)
                aaNeedsKeyFileChecks = true
            }

            is ActiveAuthenticationResult.Failed -> {
                event(ChipReadStep.ACTIVE_AUTHENTICATION, ChipReadStepOutcome.FAILED, start, protocol = "AA", sw = result.sw)
                aaOutcome = FlowMappings.aaOutcome(result.failure)
                if (result.failure == ActiveAuthenticationFailure.PUBLIC_KEY_UNREADABLE) {
                    fileErrors += ChipFileError(FILE_DG15, false, ChipMalformedFile(FILE_DG15, ChipMalformedFile.Reason.INVALID_ELEMENT))
                }
            }

            ActiveAuthenticationResult.NotApplicable -> {
                aaOutcome = CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED)
            }
        }
    }

    // --- Result -------------------------------------------------------------------------------

    private fun accessReport(): AccessReport {
        val suite = paceSuite
        val password =
            when {
                protocol == ChipAccessProtocol.NONE -> null
                options.accessKey is ChipAccessKey.Can -> ChipPassword.CAN
                else -> ChipPassword.MRZ
            }
        return AccessReport(
            protocol = protocol,
            password = password,
            paceProtocolOid = suite?.oid,
            paceMapping = suite?.let { FlowMappings.mapping(it.mapping) },
            paceParameterId = suite?.parameterId,
            cardAccessRead = cardAccessRead,
            offeredPace = offered,
            extendedLengthAllowed = extended,
        )
    }

    private fun appFile(fid: Int): ByteArray? = files.firstOrNull { it.fileId == fid && !it.inMasterFile }?.raw()

    private fun assemble(): ChipRead {
        val parseErrors = ArrayList<ChipFileError>()

        fun <T> view(
            fid: Int,
            parse: (ByteArray) -> LdsParseResult<T>,
        ): T? {
            val bytes = appFile(fid) ?: return null
            return when (val r = parse(bytes)) {
                is LdsParseResult.Success -> r.view
                is LdsParseResult.Failure -> null.also { parseErrors += ChipFileError(fid, false, r.error) }
            }
        }
        val efCom = view(LdsIds.EF_COM, EfComView::parse)
        val sod =
            appFile(LdsIds.EF_SOD)?.let {
                when (val r = SecurityObjectView.parse(it)) {
                    is SecurityObjectParse.Parsed -> {
                        r.view
                    }

                    is SecurityObjectParse.Unreadable -> {
                        null.also { parseErrors += ChipFileError(LdsIds.EF_SOD, false, FlowMappings.securityObject(r.problem, r.offset)) }
                    }
                }
            }
        // DG1's dates are read against the app's check time; the SDK reads no clock.
        val dg1 = options.checkTime?.let { time -> view(FILE_DG1) { Dg1View.parse(it, time) } }
        val dg2 = view(0x0102, BiometricGroup::parse)
        val dg5 = view(0x0105, DisplayedImageGroup::parseDg5)
        val dg7 = view(0x0107, DisplayedImageGroup::parseDg7)
        val dg11 = view(0x010B, Dg11View::parse)
        val dg12 = view(0x010C, Dg12View::parse)
        val dg13 = view(0x010D, Dg13View::parse)
        val dg16 = view(0x0110, Dg16View::parse)

        val pa = passiveAuthentication(dg12)
        val checks = ArrayList<ChipCheck>()
        pa.mapTo(checks) { cardAccessCheck(FlowMappings.check(it)) }
        checks += paceOfferCheck()
        if (caChecks.isEmpty()) caChecks[null] = CaState(CheckOutcome.NotRun(ChipCheckReason.READ_STOPPED))
        for ((where, state) in caChecks) {
            val passed = state.keysSwitched && state.outcome is CheckOutcome.NotRun && keyFileChecksPassed(pa, where)
            checks += ChipCheck(ChipCheckKind.ChipAuthentication(where), if (passed) CheckOutcome.Passed else state.outcome)
        }
        checks += ChipCheck(ChipCheckKind.PaceChipAuthenticationMapping, camOutcome(pa))
        val aaPassed = aaNeedsKeyFileChecks && applicationKeyFilePassed(pa, 15)
        checks += ChipCheck(ChipCheckKind.ActiveAuthentication, if (aaPassed) CheckOutcome.Passed else aaOutcome)
        return ChipRead(
            access = accessReport(),
            files = files.toList(),
            fileErrors = fileErrors + parseErrors,
            efCom = efCom,
            dg1 = dg1,
            dg2 = dg2,
            dg5 = dg5,
            dg7 = dg7,
            dg11 = dg11,
            dg12 = dg12,
            dg13 = dg13,
            dg16 = dg16,
            securityObject = sod,
            checks = checks,
        )
    }

    /** The signed copy of EF.CardAccess's entries: EF.CardSecurity when it was read and readable, else DG14. */
    private fun signedCopy(): Pair<ChipSignedCopy, SecurityInfos>? =
        cardSecurityInfos?.let { ChipSignedCopy.CARD_SECURITY to it } ?: dg14Infos?.let { ChipSignedCopy.DG14 to it }

    /**
     * EF.CardAccess is compared with its signed copy in both directions. Passive authentication
     * checks that every EF.CardAccess entry is in the copy; a missing, empty or stripped EF.CardAccess
     * passes that trivially, so when the signed copy lists PACE entries EF.CardAccess lacks, the
     * outcome becomes Failed(PACE_MISSING_FROM_CARD_ACCESS) instead of Passed or
     * NotApplicable(NO_CARD_ACCESS).
     */
    private fun cardAccessCheck(check: ChipCheck): ChipCheck {
        if (check.kind !is ChipCheckKind.CardAccessMatchesSignedCopy) return check
        val outcome = check.outcome
        val noCardAccess = outcome is CheckOutcome.NotApplicable && outcome.reason == ChipCheckReason.NO_CARD_ACCESS
        if (outcome != CheckOutcome.Passed && !noCardAccess) return check
        val copy = signedCopy() ?: return check
        val listed = cardAccessInfos?.paceInfos.orEmpty()
        if (copy.second.paceInfos.all { it in listed }) return check
        return ChipCheck(
            ChipCheckKind.CardAccessMatchesSignedCopy(copy.first),
            CheckOutcome.Failed(ChipCheckReason.PACE_MISSING_FROM_CARD_ACCESS),
        )
    }

    /** [ChipCheckKind.PaceOfferMatchesSignedCopy]: the signed copy's PACE entries against EF.CardAccess and the access used. */
    private fun paceOfferCheck(): ChipCheck {
        val copy = signedCopy()
        val kind = ChipCheckKind.PaceOfferMatchesSignedCopy(copy?.first)
        if (copy == null) {
            val reason = if (dg14Unparsed()) ChipCheckReason.UNREADABLE_SIGNED_COPY else ChipCheckReason.NO_SIGNED_COPY
            return ChipCheck(kind, CheckOutcome.NotRun(reason))
        }
        val signedPace = copy.second.paceInfos
        if (signedPace.isEmpty()) {
            val unsignedPace = cardAccessInfos?.paceInfos.orEmpty()
            // PACE that only the unsigned file lists is the other direction, checked by CardAccessMatchesSignedCopy.
            return ChipCheck(
                kind,
                if (unsignedPace.isEmpty()) CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED) else CheckOutcome.Passed,
            )
        }
        val listed = cardAccessInfos?.paceInfos.orEmpty()
        val outcome =
            when {
                !signedPace.all { it in listed } -> CheckOutcome.Failed(ChipCheckReason.PACE_MISSING_FROM_CARD_ACCESS)
                protocol != ChipAccessProtocol.PACE -> CheckOutcome.Failed(ChipCheckReason.PACE_OFFERED_BUT_NOT_USED)
                else -> CheckOutcome.Passed
            }
        return ChipCheck(kind, outcome)
    }

    private fun dg14Unparsed(): Boolean = fileErrors.any { it.fileId == FILE_DG14 && !it.inMasterFile && it.error is ChipMalformedFile }

    private fun passiveAuthentication(dg12: Dg12View?): List<PaCheck> {
        val start = TimeSource.Monotonic.markNow()
        val groups = files.filter { it.dataGroup != null }.associate { it.dataGroup!! to it.raw() }
        val issue =
            dg12?.dateOfIssue?.let { d ->
                val y = d.year
                val m = d.month
                val day = d.day
                if (y != null && m != null && day != null) CertTime(y, m, day, 0, 0, 0) else null
            }
        val inputs =
            PaInputs(
                securityObject = appFile(LdsIds.EF_SOD),
                dataGroups = groups,
                cardAccess = cardAccess,
                cardSecurity = cardSecurity,
                roots = options.trustAnchors.map { it.certificate },
                at = options.checkTime?.let { CertTimes.fromEpochSeconds(it.epochSeconds) },
                documentCode = appFile(FILE_DG1)?.let(::documentCode),
                issueDate = issue,
            )
        val checks = PassiveAuthentication { crypto ?: throw ChipCryptoUnavailableException() }.run(inputs)
        event(ChipReadStep.PASSIVE_AUTHENTICATION, ChipReadStepOutcome.SUCCEEDED, start)
        return checks
    }

    /** The document code (the first two MRZ characters) straight from DG1's bytes, with no clock and no parse. */
    private fun documentCode(dg1: ByteArray): String? =
        try {
            val mrz =
                TlvParser()
                    .parseSingle(dg1)
                    .children()
                    .firstOrNull { it.tag == TAG_MRZ }
                    ?.value()
            mrz?.takeIf { it.size >= 2 }?.copyOf(2)?.decodeToString()
        } catch (e: TlvException) {
            null
        }

    private fun passed(
        pa: List<PaCheck>,
        kind: PaCheckKind,
    ): Boolean = pa.any { it.kind == kind && it.outcome == PaOutcome.Passed }

    /** The file with the key passed its hash (for a data group), signature and root checks. */
    private fun keyFileChecksPassed(
        pa: List<PaCheck>,
        context: ChipFileContext?,
    ): Boolean =
        when (context) {
            ChipFileContext.MASTER_FILE -> {
                passed(pa, PaCheckKind.SignatureVerifies(SignedObject.CARD_SECURITY)) &&
                    passed(pa, PaCheckKind.SignerChainsToSuppliedRoot(SignedObject.CARD_SECURITY))
            }

            ChipFileContext.EMRTD_APPLICATION -> {
                applicationKeyFilePassed(pa, 14)
            }

            null -> {
                false
            }
        }

    private fun applicationKeyFilePassed(
        pa: List<PaCheck>,
        dataGroup: Int,
    ): Boolean =
        passed(pa, PaCheckKind.DataGroupHashMatches(dataGroup)) &&
            passed(pa, PaCheckKind.SignatureVerifies(SignedObject.SECURITY_OBJECT)) &&
            passed(pa, PaCheckKind.SignerChainsToSuppliedRoot(SignedObject.SECURITY_OBJECT))

    private fun camOutcome(pa: List<PaCheck>): CheckOutcome {
        val evidence = camEvidence ?: return CheckOutcome.NotApplicable(ChipCheckReason.NOT_OFFERED)
        return try {
            FlowMappings.camOutcome(ChipAuthMappingCheck.run(crypto, evidence, cardSecurity, pa).outcome)
        } catch (e: ChipCryptoException) {
            CheckOutcome.NotRun(ChipCheckReason.CRYPTOGRAPHY_FAILED)
        }
    }

    // --- Telemetry ----------------------------------------------------------------------------

    private fun paceName(suite: PaceSuite): String {
        val mapping =
            when (suite.mapping) {
                PaceMapping.GENERIC -> "GM"
                PaceMapping.INTEGRATED -> "IM"
                PaceMapping.CHIP_AUTHENTICATION -> "CAM"
            }
        val cipher =
            when (suite.sessionCipher) {
                SessionCipher.TDES_CBC_CBC -> "3DES"
                SessionCipher.AES_CBC_CMAC_128 -> "AES-128"
                SessionCipher.AES_CBC_CMAC_192 -> "AES-192"
                SessionCipher.AES_CBC_CMAC_256 -> "AES-256"
            }
        return "PACE-${suite.agreement}-$mapping-$cipher"
    }

    /** Sends one event. The app's sink may throw; that never changes the read (only cancellation passes). */
    private fun event(
        step: ChipReadStep,
        outcome: ChipReadStepOutcome,
        start: TimeSource.Monotonic.ValueTimeMark,
        protocol: String? = null,
        fileId: Int? = null,
        bytes: Int? = null,
        sw: Int? = null,
    ) {
        try {
            telemetry.record(ChipReadEvent(step, outcome, protocol, fileId, bytes, sw, start.elapsedNow().inWholeMilliseconds))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ignored on purpose: telemetry is never part of the result.
        }
    }

    private companion object {
        const val FILE_ATR_INFO = 0x2F01
        const val FILE_CARD_SECURITY = 0x011D
        const val FILE_DG1 = 0x0101
        const val FILE_DG14 = 0x010E
        const val FILE_DG15 = 0x010F
        const val TAG_MRZ = 0x5F1F
        const val SW_NOT_FOUND = 0x6A82
        const val SW_SECURITY = 0x6982

        /** MSE:Set AT refusals: the chip did not take the suite, so nothing was agreed and the next suite may be tried. */
        val SET_AT_REFUSALS =
            setOf(
                PaceFailure.ALGORITHM_NOT_SUPPORTED,
                PaceFailure.REFERENCED_DATA_NOT_FOUND,
                PaceFailure.SET_AUTHENTICATION_TEMPLATE_REFUSED,
            )
    }
}
