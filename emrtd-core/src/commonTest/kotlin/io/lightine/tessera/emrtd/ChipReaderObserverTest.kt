package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.fakechip.FakeCommandHandler
import io.lightine.tessera.emrtd.fakechip.FakeReadFlowChip
import io.lightine.tessera.emrtd.fakechip.FlowAccess
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.RecordingObserver
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.emrtd.internal.pa.withKeys
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.types.errors.ChipKeyCheckDigitMismatch
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import io.lightine.tessera.types.vocabulary.MrzField
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * [ChipReader.read] with an observer, the MRZ check-digit gate, and the shortened start when the
 * platform says the application is already selected (with the answers two real chips gave:
 * 6982 under the application, then 9000 for EF.CardAccess's SELECT and 6986 for its READ BINARY).
 */
class ChipReaderObserverTest {
    private val checkTime = Instant.parse("2026-06-01T12:00:00Z")

    private inline fun withChips(block: (ChipCrypto, TestKeys) -> Unit) {
        withCrypto { c -> withKeys { keys -> block(c, keys) } }
    }

    private fun options(
        flow: FlowChip,
        key: ChipAccessKey = FakeReadFlowChip.mrzKey(),
        allowMismatch: Boolean = false,
    ) = ChipReadOptions(key, setOf(1, 2), listOf(flow.anchor), checkTime, allowCheckDigitMismatch = allowMismatch)

    private fun sent(flow: FlowChip): List<String> =
        flow.chip.received.map { b -> b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() } }

    private fun plainBeforeBac(flow: FlowChip): List<String> = sent(flow).takeWhile { !it.startsWith("0084") }

    @Test
    fun theObserverGetsEachStepWithProgressAndTheResultIsTheSame() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val observer = RecordingObserver()
                val result = ChipReader(NoOpTelemetrySink).read(flow.chip, options(flow), observer)
                assertIs<ChipReadResult.Complete>(result)
                assertTrue(observer.endings.isEmpty())
                assertTrue(observer.updates.all { it.attempt == 1 && it.maxAttempts == 1 })
                assertEquals(ChipReadState.CheckingFiles, observer.updates.last().state)
                val fractions = observer.updates.map { it.progress.fraction }
                assertEquals(fractions.sorted(), fractions)
                assertTrue(fractions.last() > 0.3 && fractions.last() < 1.0, "$fractions")
                assertTrue(
                    observer.updates.any {
                        (it.state as? ChipReadState.OpeningAccess)?.protocol == ChipAccessProtocol.BAC
                    },
                )
            }
        }

    @Test
    fun anUpdateListenerLambdaGetsTheSameUpdatesAsAnObserver() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                val heard = mutableListOf<ChipReadUpdate>()
                // TES-346: the low-level read takes the update half only; a lambda is enough.
                val result = ChipReader(NoOpTelemetrySink).read(flow.chip, options(flow)) { heard += it }
                assertIs<ChipReadResult.Complete>(result)
                assertEquals(ChipReadState.CheckingFiles, heard.last().state)
                assertTrue(heard.all { it.attempt == 1 && it.maxAttempts == 1 })
            }
        }

    @Test
    fun anObserverThatThrowsNeverChangesTheRead() =
        runTest {
            withChips { c, keys ->
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY)
                val observer = RecordingObserver(throwing = true)
                val result = ChipReader(NoOpTelemetrySink).read(flow.chip, options(flow), observer)
                assertIs<ChipReadResult.Complete>(result)
                assertTrue(observer.updates.size > 5)
            }
        }

    @Test
    fun aKeyWhoseCheckDigitsDoNotMatchSendsNoCommand() =
        runTest {
            withChips { c, keys ->
                val line2 = FakeReadFlowChip.lines[1]
                val wrongDigit = if (line2[19] == '0') '1' else '0'
                val key =
                    ChipAccessKey.Mrz(
                        line2.substring(0, 9),
                        line2[9],
                        line2.substring(13, 19),
                        wrongDigit,
                        line2.substring(21, 27),
                        line2[27],
                    )
                assertEquals(setOf(MrzField.DATE_OF_BIRTH), key.checkDigitFindings)
                for (access in listOf(FlowAccess.BAC, FlowAccess.PACE_ONLY)) {
                    val flow = FakeReadFlowChip.build(c, keys, access)
                    val failed = assertIs<ChipReadResult.Failed>(ChipReader(NoOpTelemetrySink).read(flow.chip, options(flow, key)))
                    assertEquals(ChipKeyCheckDigitMismatch(setOf(MrzField.DATE_OF_BIRTH)), failed.error)
                    assertNull(failed.access)
                    assertTrue(flow.chip.received.isEmpty(), "$access: ${sent(flow)}")
                }
                // Allowed: the read goes ahead (and this chip, set up with the printed digit, refuses the key).
                val flow = FakeReadFlowChip.build(c, keys, FlowAccess.BAC)
                ChipReader(NoOpTelemetrySink).read(flow.chip, options(flow, key, allowMismatch = true))
                assertTrue(flow.chip.received.isNotEmpty())
                assertEquals(1, flow.bac!!.refused)
            }
        }

    @Test
    fun theApplicationSelectedAtStartSkipsTheSelectThatIsBoundToFail() =
        runTest {
            withChips { c, keys ->
                val selectCardAccess = "00A4020C02011C"
                val selectMf = "00A4000C"
                val selectApp = "00A4040C07A0000002471001"

                // BAC chip, application current and said so: SELECT MF first, then EF.CardAccess (6A82), then the application.
                val told = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, startInApplication = true)
                told.chip.startSelection = ChipStartSelection.EMRTD_APPLICATION
                assertIs<ChipReadResult.Complete>(ChipReader(NoOpTelemetrySink).read(told.chip, options(told)))
                assertEquals(listOf(selectMf, selectCardAccess, selectApp), plainBeforeBac(told))

                // The same chip with no word from the platform: today's start, one command more.
                val unknown = FakeReadFlowChip.build(c, keys, FlowAccess.BAC, startInApplication = true)
                assertIs<ChipReadResult.Complete>(ChipReader(NoOpTelemetrySink).read(unknown.chip, options(unknown)))
                assertEquals(listOf(selectCardAccess, selectMf, selectCardAccess, selectApp), plainBeforeBac(unknown))

                // A PACE chip, application current and said so: EF.CardAccess is read after one SELECT MF.
                val pace = FakeReadFlowChip.build(c, keys, FlowAccess.PACE_ONLY, startInApplication = true)
                pace.chip.startSelection = ChipStartSelection.EMRTD_APPLICATION
                val read = assertIs<ChipReadResult.Complete>(ChipReader(NoOpTelemetrySink).read(pace.chip, options(pace))).read
                assertEquals(listOf(selectMf, selectCardAccess), sent(pace).take(2))
                assertEquals(ChipAccessProtocol.PACE, read.access.protocol)

                // The chip refuses SELECT MF in both forms: EF.CardAccess cannot be reached, and BAC runs.
                val stuck =
                    FakeReadFlowChip.build(
                        c,
                        keys,
                        FlowAccess.BAC,
                        startInApplication = true,
                        acceptsSelectMf = false,
                        acceptsSelectMfById = false,
                    )
                stuck.chip.startSelection = ChipStartSelection.EMRTD_APPLICATION
                assertIs<ChipReadResult.Complete>(ChipReader(NoOpTelemetrySink).read(stuck.chip, options(stuck)))
                assertEquals(listOf(selectMf, "00A4000C023F00", selectApp), plainBeforeBac(stuck))
            }
        }

    /**
     * Two real BAC-only chips: SELECT EF.CardAccess under the application answered 6982; after
     * SELECT MF the same SELECT answered 9000 although the file is absent, and READ BINARY then
     * answered 6986. That is "no EF.CardAccess": no error, no false downgrade finding.
     */
    @Test
    fun aChipThatTakesTheSelectOfAnAbsentCardAccessAndRefusesItsReadHasNoCardAccess() =
        runTest {
            withChips { c, keys ->
                for (told in listOf(true, false)) {
                    val label = "told=$told"
                    var selected = false
                    val quirk =
                        FakeCommandHandler { chip, cmd ->
                            val cardAccess = cmd.ins == ChipCommands.INS_SELECT && cmd.data.contentEquals(byteArrayOf(0x01, 0x1C))
                            when {
                                cardAccess && chip.currentApplication == null -> {
                                    selected = true
                                    ResponseApdu(ByteArray(0), 0x9000)
                                }

                                cmd.ins == ChipCommands.INS_READ_BINARY && selected && cmd.cla == 0 -> {
                                    selected = false
                                    ResponseApdu(ByteArray(0), 0x6986)
                                }

                                cmd.ins == ChipCommands.INS_SELECT -> {
                                    selected = false
                                    null
                                }

                                else -> {
                                    null
                                }
                            }
                        }
                    val flow =
                        FakeReadFlowChip.build(
                            c,
                            keys,
                            FlowAccess.BAC,
                            startInApplication = true,
                            caInApplication = true,
                            leadingHandlers = listOf(quirk),
                        )
                    if (told) flow.chip.startSelection = ChipStartSelection.EMRTD_APPLICATION
                    val read = assertIs<ChipReadResult.Complete>(ChipReader(NoOpTelemetrySink).read(flow.chip, options(flow)), label).read
                    assertEquals(ChipAccessProtocol.BAC, read.access.protocol, label)
                    assertFalse(read.access.cardAccessRead, label)
                    assertTrue(read.fileErrors.isEmpty(), "$label: ${read.fileErrors}")
                    for (check in read.checks.filter {
                        it.kind is ChipCheckKind.CardAccessMatchesSignedCopy || it.kind is ChipCheckKind.PaceOfferMatchesSignedCopy
                    }) {
                        assertFalse(check.outcome is CheckOutcome.Failed, "$label: $check")
                    }
                    val expected =
                        if (told) {
                            listOf("00A4000C", "00A4020C02011C", "00B00000DF", "00A4040C07A0000002471001")
                        } else {
                            listOf("00A4020C02011C", "00A4000C", "00A4020C02011C", "00B00000DF", "00A4040C07A0000002471001")
                        }
                    assertEquals(expected, plainBeforeBac(flow), label)
                }
            }
        }
}
