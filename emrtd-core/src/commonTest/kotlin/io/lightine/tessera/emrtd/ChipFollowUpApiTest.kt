package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.emrtd.lds.SecurityObjectParse
import io.lightine.tessera.emrtd.lds.SecurityObjectView
import io.lightine.tessera.emrtd.trust.DocumentSignerCertificate
import io.lightine.tessera.emrtd.trust.DocumentSignerCertificateParse
import io.lightine.tessera.emrtd.trust.MasterListCheckReason
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.validation.MrzNotRunReason
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.CheckReason
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** TES-346 follow-up: one style for key input, visible clean-up, reason codes, printed comparison, signer facts, typed telemetry. */
class ChipFollowUpApiTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    // --- Item 4: the CAN builder answers with a result ------------------------------------------

    @Test
    fun canFromAnswersWithAResultInTheStyleOfTheMrzBuilders() {
        val built = assertIs<CanKeyResult.Built>(ChipAccessKey.Can.from("123456"))
        assertFalse(built.key.isCleared)
        assertEquals("Built", built.toString())

        val empty = assertIs<CanKeyResult.NotBuilt>(ChipAccessKey.Can.from(""))
        assertEquals(CanKeyProblem.EMPTY, empty.problem)
        assertNull(empty.characterIndex)

        // A space, a letter and a non-ASCII digit are all refused; the index names the first.
        for (bad in listOf("12 456", "12a456", "12345٣", " 123456")) {
            val r = assertIs<CanKeyResult.NotBuilt>(ChipAccessKey.Can.from(bad), bad)
            assertEquals(CanKeyProblem.CHARACTER_NOT_ALLOWED, r.problem)
            assertTrue(r.characterIndex != null && bad[r.characterIndex!!] !in '0'..'9')
            assertFalse(bad.trim() in r.toString() && bad.length > 1, "the text never holds the value")
        }
        // The constructor still throws the same way for the same input.
        assertFailsWith<IllegalArgumentException> { ChipAccessKey.Can("") }
        assertFailsWith<IllegalArgumentException> { ChipAccessKey.Can("12 456") }
        // The same digits, the same key bytes: a read with the result's key opens what the constructor's does.
        assertEquals("ChipAccessKey.Can", built.key.toString())
    }

    @Test
    fun theTwoKeyBuildersCanBeHandledByTheSameCode() {
        // The pattern an app writes for both kinds of key: Built gives the key, NotBuilt gives a problem.
        fun describe(result: Any): String =
            when (result) {
                is MrzKeyResult.Built -> "key"
                is MrzKeyResult.NotBuilt -> "problem ${result.problem}"
                is CanKeyResult.Built -> "key"
                is CanKeyResult.NotBuilt -> "problem ${result.problem}"
                else -> error("unexpected")
            }
        assertEquals("key", describe(ChipAccessKey.Can.from("123456")))
        assertEquals("problem EMPTY", describe(ChipAccessKey.Can.from("")))
        assertEquals("problem EMPTY", describe(ChipAccessKey.Mrz.fromTypedFields("", "900315", "340314")))
    }

    // --- Item 5: typed fields stay strict; normalising is separate and visible -----------------

    @Test
    fun typedFieldsStayStrictAndNormalizingIsAVisibleSeparateStep() {
        // Strict: lower case and spaces are refused with the field and the problem, never fixed quietly.
        val strict = assertIs<MrzKeyResult.NotBuilt>(ChipAccessKey.Mrz.fromTypedFields("t22000129", "900315", "340314"))
        assertEquals(MrzField.DOCUMENT_NUMBER, strict.field)
        assertEquals(MrzKeyProblem.CHARACTER_NOT_ALLOWED, strict.problem)
        assertIs<MrzKeyResult.NotBuilt>(ChipAccessKey.Mrz.fromTypedFields(" T22000129 ", "900315", "340314"))

        val n = ChipAccessKey.Mrz.normalizeTypedFields("  t22000129 ", "900315", " 340314")
        assertEquals("T22000129", n.documentNumber)
        assertEquals("900315", n.dateOfBirth)
        assertEquals("340314", n.dateOfExpiry)
        assertEquals(setOf(MrzField.DOCUMENT_NUMBER, MrzField.DATE_OF_EXPIRY), n.changedFields)
        assertTrue(n.wasChanged)
        assertFalse("T22000129" in n.toString(), "toString shows fields only")

        // The normalised values give the same key as the person's values typed right.
        val fromNormalized =
            assertIs<MrzKeyResult.Built>(ChipAccessKey.Mrz.fromTypedFields(n.documentNumber, n.dateOfBirth, n.dateOfExpiry))
        val direct = assertIs<MrzKeyResult.Built>(ChipAccessKey.Mrz.fromTypedFields("T22000129", "900315", "340314"))
        assertEquals(direct.key.checkDigitFindings, fromNormalized.key.checkDigitFindings)
    }

    @Test
    fun normalizingDoesExactlyTwoThingsAndNothingElse() {
        val clean = ChipAccessKey.Mrz.normalizeTypedFields("T22000129", "900315", "340314")
        assertTrue(clean.changedFields.isEmpty())
        assertFalse(clean.wasChanged)

        // An inner space, a filler, a digit for a letter and a non-ASCII letter all stay as typed.
        val odd = ChipAccessKey.Mrz.normalizeTypedFields("t22 00<0I", "9O0315", "34é314")
        assertEquals("T22 00<0I", odd.documentNumber)
        assertEquals("9O0315", odd.dateOfBirth)
        assertEquals("34é314", odd.dateOfExpiry)
        assertEquals(setOf(MrzField.DOCUMENT_NUMBER), odd.changedFields)
        // Tabs and line ends are not spaces: kept, so the strict builder refuses them and says so.
        assertEquals("\tT1", ChipAccessKey.Mrz.normalizeTypedFields("\tt1", "900315", "340314").documentNumber)
        // ASCII only: no locale-dependent change such as the German sharp s.
        assertEquals("ßA", ChipAccessKey.Mrz.normalizeTypedFields("ßa", "900315", "340314").documentNumber)
    }

    // --- Item 9: every reason has a stable, unique code ----------------------------------------

    @Test
    fun everyCheckReasonOfEveryModuleHasANonEmptyUniqueCodeOfTheDocumentedShape() {
        val shape = Regex("[a-z]+(-[a-z]+)*\\.[a-z0-9]+(-[a-z0-9]+)*")
        val groups: Map<String, List<CheckReason>> =
            mapOf(
                "chip" to ChipCheckReason.entries,
                "master-list" to MasterListCheckReason.entries,
                "mrz" to MrzNotRunReason.entries,
            )
        val all = mutableListOf<String>()
        for ((prefix, reasons) in groups) {
            assertTrue(reasons.isNotEmpty(), prefix)
            for (reason in reasons) {
                val code = reason.code
                assertTrue(code.isNotEmpty(), "$reason has no code")
                assertTrue(shape.matches(code), "$reason: $code")
                assertTrue(code.startsWith("$prefix."), "$reason: $code")
                assertEquals(code, code.lowercase())
                all += code
            }
        }
        assertEquals(all.size, all.toSet().size, "a code is used twice: ${all.groupBy { it }.filterValues { it.size > 1 }.keys}")
        // A reason is named without a cast to Enum.
        val outcome: CheckOutcome = CheckOutcome.Failed(ChipCheckReason.HASH_DIFFERS)
        assertEquals("chip.hash-differs", (outcome as CheckOutcome.Failed).reason?.code)
        assertEquals("master-list.signature-does-not-verify", MasterListCheckReason.SIGNATURE_DOES_NOT_VERIFY.code)
        assertEquals("mrz.invalid-length", MrzNotRunReason.INVALID_LENGTH.code)
    }

    // --- Item 8: the printed MRZ goes in the options, the facts come back as a check ------------

    private fun printed(documentNumber: String = "T22000129"): MrzDocument {
        val lines =
            (
                MrzGenerator.generateTD3(
                    documentType = "P",
                    issuingState = "UTO",
                    documentNumber = documentNumber,
                    primaryIdentifier = "SPECIMEN",
                    secondaryIdentifier = "ALEX MORGAN",
                    nationality = "UTO",
                    dateOfBirth = LocalDate(1990, 3, 15),
                    sex = Sex.FEMALE,
                    dateOfExpiry = LocalDate(2034, 3, 14),
                    personalNumber = "S98765432",
                ) as GenerationResult.Success
            ).mrz
        return (MrzParser.parse(lines) as ParseResult.Success).document
    }

    private fun specimen(): MrzDocument = (MrzParser.parse(FakeReadFlowChip.lines) as ParseResult.Success).document

    private suspend inline fun withFlow(block: (FlowChip) -> Unit) {
        withCrypto { c -> withKeys { k -> block(FakeReadFlowChip.build(c, k, FlowAccess.PLAIN)) } }
    }

    private fun options(
        flow: FlowChip,
        printed: MrzDocument?,
        time: Instant? = checkTime,
        dataGroups: Set<Int> = setOf(1, 2),
    ) = ChipReadOptions
        .Builder(
            FakeReadFlowChip.mrzKey(),
        ).dataGroups(dataGroups)
        .trustAnchors(listOf(flow.anchor))
        .checkTime(time)
        .printedDocument(printed)
        .build()

    @Test
    fun aReadWithAPrintedDocumentCarriesTheComparisonAsACheckWithNoValues() =
        runTest {
            withFlow { flow ->
                val same = (ChipReader().read(flow.chip, options(flow, specimen())) as ChipReadResult.Complete).read
                val check = same.checks.single { it.kind == ChipCheckKind.Dg1MatchesPrintedMrz }
                assertEquals(CheckOutcome.Passed, check.outcome)
                val detail = assertIs<ChipCheckDetail.PrintedMrzFields>(check.detail)
                assertTrue(detail.sameFormat)
                assertEquals(MrzField.entries.toList(), detail.fields.keys.toList())
                assertTrue(detail.fields.values.none { it == ChipFieldMatch.DIFFERENT })
                assertEquals(ChipFieldMatch.SAME, detail.fields.getValue(MrzField.DOCUMENT_NUMBER))
                assertTrue(detail.differingFields.isEmpty())
                // The detail names fields and verdict words; no document value is in it.
                for (value in listOf("T22000129", "SPECIMEN", "MORGAN", "900315", "S98765432")) {
                    assertFalse(value in check.toString(), value)
                }
                assertEquals(CheckOutcome.Passed, same.outcomeOf(ChipCheckKind.Dg1MatchesPrintedMrz))
            }
        }

    @Test
    fun aDifferentPrintedDocumentFailsTheCheckAndNamesTheFieldNotTheValue() =
        runTest {
            withFlow { flow ->
                val read = (ChipReader().read(flow.chip, options(flow, printed("T22000130"))) as ChipReadResult.Complete).read
                val check = read.checks.single { it.kind == ChipCheckKind.Dg1MatchesPrintedMrz }
                assertEquals(CheckOutcome.Failed(ChipCheckReason.FIELDS_DIFFER), check.outcome)
                val detail = assertIs<ChipCheckDetail.PrintedMrzFields>(check.detail)
                assertEquals(ChipFieldMatch.DIFFERENT, detail.fields.getValue(MrzField.DOCUMENT_NUMBER))
                assertTrue(MrzField.DOCUMENT_NUMBER in detail.differingFields)
                assertFalse("T2200013" in check.toString())
                // The chip's values stay the chip's.
                assertEquals(
                    "T22000129",
                    read.dg1!!
                        .document.commonFields.documentNumber,
                )
            }
        }

    @Test
    fun aFieldTheOtherFormatLacksIsNotComparableAndTheFormatsDifferFailsTheCheck() =
        runTest {
            withFlow { flow ->
                val td1 =
                    (
                        MrzGenerator.generateTD1(
                            documentType = "I",
                            issuingState = "UTO",
                            documentNumber = "T22000129",
                            primaryIdentifier = "SPECIMEN",
                            secondaryIdentifier = "ALEX MORGAN",
                            nationality = "UTO",
                            dateOfBirth = LocalDate(1990, 3, 15),
                            sex = Sex.FEMALE,
                            dateOfExpiry = LocalDate(2034, 3, 14),
                            optionalData1 = "",
                            optionalData2 = "",
                        ) as GenerationResult.Success
                    ).mrz
                val doc = (MrzParser.parse(td1) as ParseResult.Success).document
                val read = (ChipReader().read(flow.chip, options(flow, doc)) as ChipReadResult.Complete).read
                val check = read.checks.single { it.kind == ChipCheckKind.Dg1MatchesPrintedMrz }
                assertEquals(CheckOutcome.Failed(ChipCheckReason.FIELDS_DIFFER), check.outcome)
                val detail = assertIs<ChipCheckDetail.PrintedMrzFields>(check.detail)
                assertFalse(detail.sameFormat)
            }
        }

    @Test
    fun noPrintedDocumentMeansNoSuchCheckAndNoCheckTimeStillCompares() =
        runTest {
            withFlow { flow ->
                val without = (ChipReader().read(flow.chip, options(flow, null)) as ChipReadResult.Complete).read
                assertNull(without.outcomeOf(ChipCheckKind.Dg1MatchesPrintedMrz))
            }
            withFlow { flow ->
                // No check time: DG1's parsed view is absent, and the comparison still runs on the raw characters.
                val read = (ChipReader().read(flow.chip, options(flow, specimen(), time = null)) as ChipReadResult.Complete).read
                assertNull(read.dg1)
                assertEquals(CheckOutcome.Passed, read.outcomeOf(ChipCheckKind.Dg1MatchesPrintedMrz))
            }
            withFlow { flow ->
                // DG1 not asked for: nothing to compare, said as a reason.
                val read = (ChipReader().read(flow.chip, options(flow, specimen(), dataGroups = setOf(2))) as ChipReadResult.Complete).read
                assertEquals(CheckOutcome.NotRun(ChipCheckReason.NO_CHIP_MRZ), read.outcomeOf(ChipCheckKind.Dg1MatchesPrintedMrz))
            }
        }

    @Test
    fun theKeyBuildersDoNotSetThePrintedDocumentOnTheirOwn() {
        val doc = specimen()
        val key = assertIs<MrzKeyResult.Built>(ChipAccessKey.Mrz.from(doc)).key
        assertNull(ChipReadOptions(key).printedDocument)
        assertNull(ChipReadOptions.Builder(key).build().printedDocument)
        assertTrue(
            ChipReadOptions
                .Builder(key)
                .printedDocument(doc)
                .build()
                .printedDocument === doc,
        )
        assertEquals("true", ChipReadOptions(key, printedDocument = doc).toString().substringAfter("printedDocument=").substringBefore(")"))
    }

    // --- Item 10: facts about every unreadable certificate ----------------------------------------

    @Test
    fun everyUnreadableSignerCertificateReportsItsPositionLengthProblemAndOffset() {
        val groups = listOf(1 to ByteArray(32) { 1 })
        val shortSequence = DerWriter.sequence(DerWriter.integer(1))
        val cut = DerWriter.sequence(DerWriter.sequence(DerWriter.integer(1)), DerWriter.integer(7))
        val good = FakePki.cscaCertificate()
        val sod = FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups), certificates = listOf(shortSequence, good, cut))
        val view = assertIs<SecurityObjectParse.Parsed>(SecurityObjectView.parse(sod)).view

        assertEquals(1, view.certificates.size)
        assertEquals(2, view.unreadableCertificateCount)
        val facts = view.unreadableCertificates
        assertEquals(listOf(0, 2), facts.map { it.position })
        assertEquals(listOf(shortSequence.size, cut.size), facts.map { it.length })
        // The same problem and offset the trust code gives for the same bytes.
        for ((fact, der) in facts.zip(listOf(shortSequence, cut))) {
            val trust = assertIs<DocumentSignerCertificateParse.Unreadable>(DocumentSignerCertificate.fromDer(der))
            assertEquals(trust.problem, fact.problem)
            assertEquals(trust.offset, fact.offset)
        }
        // Facts only: no certificate content in the text.
        assertTrue(facts.all { "position=" in it.toString() && "length=" in it.toString() })
        assertTrue(assertNotNull(facts.firstOrNull()).toString().length < 120)
    }

    @Test
    fun aSecurityObjectWithOnlyReadableCertificatesHasNoUnreadableFacts() {
        val groups = listOf(1 to ByteArray(32) { 1 })
        val sod = FakePki.sod(FakePki.ldsSecurityObject(1, groups = groups), certificates = listOf(FakePki.cscaCertificate()))
        val view = assertIs<SecurityObjectParse.Parsed>(SecurityObjectView.parse(sod)).view
        assertTrue(view.unreadableCertificates.isEmpty())
        assertEquals(0, view.unreadableCertificateCount)
    }

    // --- Item 11: a typed sink needs no cast -----------------------------------------------------

    private class OtherEvent : TelemetryEvent {
        override val name: String = "other.event"
    }

    @Test
    fun aChipReadEventSinkGetsTypedEventsAndDropsOtherKinds() =
        runTest {
            val seen = mutableListOf<ChipReadEvent>()
            val sink = ChipReadEventSink { event -> seen += event }
            sink.record(OtherEvent())
            assertTrue(seen.isEmpty())
            withFlow { flow ->
                val result = ChipReader(sink).read(flow.chip, options(flow, null))
                assertIs<ChipReadResult.Complete>(result)
            }
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.any { it.step == ChipReadStep.READ_FILE })
            assertTrue(seen.any { it.step == ChipReadStep.READ && it.outcome == ChipReadStepOutcome.SUCCEEDED })
        }

    // --- Item 2: the hand-over keeps order, ends once and ends last ------------------------------

    private class Queue {
        val pending = ArrayDeque<() -> Unit>()

        fun post(block: () -> Unit) {
            pending.addLast(block)
        }

        fun runAll() {
            while (pending.isNotEmpty()) pending.removeFirst()()
        }
    }

    private class Log : ChipReadObserver {
        val calls = mutableListOf<String>()
        var failOnUpdate = false

        override fun onUpdate(update: ChipReadUpdate) {
            calls += "update:${update.state}"
            if (failOnUpdate) error("the observer failed")
        }

        override fun onEnding(ending: ChipReadEnding) {
            calls += "ending"
        }
    }

    @Test
    fun theHandoffDeliversInTheOrderOfTheCallsOnlyWhenThePostedBlocksRunAndTheEndingLastOnce() =
        runTest {
            val queue = Queue()
            val log = Log()
            log.failOnUpdate = true // an exception must never reach the thread the block runs on
            val coordinator =
                ChipReadCoordinator(ChipReadOptions(FakeReadFlowChip.mrzKey()), LinkLossPolicy.End, HandoffObserver(log, queue::post))
            coordinator.start()
            coordinator.waiting(0)
            coordinator.waiting(2_000)
            assertTrue(log.calls.isEmpty(), "nothing runs before the thread runs its queue")
            coordinator.cancel(ChipReadEnding.Cancelled.By.APP)
            coordinator.cancel(ChipReadEnding.Cancelled.By.APP)
            coordinator.notStarted(NfcUnavailable.Reason.DISABLED)
            queue.runAll()
            assertEquals(
                listOf(
                    "update:Starting",
                    "update:WaitingForDocument(0ms)",
                    "update:WaitingForDocument(2000ms)",
                    "update:Stopping",
                    "ending",
                ),
                log.calls,
            )
            assertEquals(1, log.calls.count { it == "ending" })
            assertEquals("ending", log.calls.last())
        }

    @Test
    fun theHandoffDropsEverythingAfterTheEndingWasPosted() {
        val queue = Queue()
        val log = Log()
        val handoff = HandoffObserver(log, queue::post)
        val ending = ChipReadEnding.NotStarted(NfcUnavailable.Reason.DISABLED, ChipReadProgress(0.0, 0, 0, null))
        handoff.onEnding(ending)
        handoff.onEnding(ending)
        queue.runAll()
        assertEquals(listOf("ending"), log.calls)
    }
}
