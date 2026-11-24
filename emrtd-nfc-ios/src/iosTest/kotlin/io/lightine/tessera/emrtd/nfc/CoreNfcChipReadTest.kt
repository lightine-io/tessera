package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipStartSelection
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TES-346: the iOS entry point that owns the whole read, driven against fake Core NFC objects and
 * a scripted chip on the test's virtual clock. Each test also checks that no system session is
 * left alive.
 */
class CoreNfcChipReadTest {
    private companion object {
        const val FOUND = "Found. Hold still."
        const val HOLD = "Hold your iPhone near the document"
    }

    // --- The happy path -------------------------------------------------------------------------

    @Test
    fun happy_path__states_in_order__texts_on_the_sheet__one_complete_ending__success_end() =
        chipReadTest {
            val chip = FakeChip()
            val (read, rec) = start()
            assertEquals(listOf("Starting", "WaitingForDocument"), rec.names)
            assertTrue(port.began)
            assertEquals(listOf(HOLD), port.alerts)
            assertTrue(LiveSessions.contains(read), "the read is kept alive while its system session is")

            step(500)
            present(FakeChipTag(chip))
            assertEquals("DocumentFound", rec.names.last())
            assertEquals(FOUND, port.alerts.last())
            assertTrue(chip.sent.isEmpty(), "nothing is sent while the document settles")

            step(400)
            val ending = assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(1, ending.attempts)
            assertEquals(100, ending.progress.percent)
            val names = rec.names
            assertEquals(listOf("Starting", "WaitingForDocument", "DocumentFound", "Connecting", "OpeningAccess"), names.take(5))
            assertEquals("CheckingFiles", names.last())
            assertTrue("ReadingFile" in names)
            assertEquals(listOf<String?>(null), port.endings, "a complete read ends with the plain end once")
            assertEquals("Done", port.alerts.last())
            assertTrue(read.isEnded)
            assertNothingLeft(read)
        }

    @Test
    fun happy_path__the_sheet_text_changes_only_when_it_differs_and_at_most_every_100_ms() =
        chipReadTest {
            // Each answer takes 30 ms, so the read's texts come faster than the sheet may change.
            val chip = FakeChip(scope = scope, answerDelayMillis = 30)
            val (read, rec) = start()
            step(500)
            present(FakeChipTag(chip))
            step(60_000)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertTrue(port.alerts.any { it.startsWith("Reading the document") }, "${port.alerts}")
            val times = port.alertTimes
            // Every text during the read is at least 100 ms after the one before. The last text is
            // the ending's, which the read sets at once (CoreNfcChipRead.finish): it is checked
            // to be the ending's text, not its interval.
            for (i in 1 until times.size - 1) {
                assertTrue(times[i] - times[i - 1] >= 100, "texts at $times")
            }
            assertEquals(CoreNfcDefaultSheetTexts.textForEnding(rec.ending), port.alerts.last())
            for (i in 1 until port.alerts.size) assertTrue(port.alerts[i] != port.alerts[i - 1], "${port.alerts}")
            assertNothingLeft(read)
        }

    // --- Requirement 5: the start selection -----------------------------------------------------

    @Test
    fun transport__reports_the_application_selected_only_for_the_emrtd_application() {
        assertEquals(
            ChipStartSelection.EMRTD_APPLICATION,
            CoreNfcTransport(FakeChannel(initialSelectedAid = "A0000002471001")).startSelection,
        )
        assertEquals(
            ChipStartSelection.EMRTD_APPLICATION,
            CoreNfcTransport(FakeChannel(initialSelectedAid = "a0000002471001")).startSelection,
        )
        assertEquals(ChipStartSelection.UNKNOWN, CoreNfcTransport(FakeChannel(initialSelectedAid = "A0000002472001")).startSelection)
        assertEquals(ChipStartSelection.UNKNOWN, CoreNfcTransport(FakeChannel(initialSelectedAid = null)).startSelection)
    }

    @Test
    fun transport__the_application_selected_by_the_system_makes_the_reader_select_the_master_file_first() =
        chipReadTest {
            val told = FakeChip(initialSelectedAid = "A0000002471001")
            val (read, rec) = start()
            present(FakeChipTag(told))
            step(400)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            assertEquals(
                "00A4000C",
                told.sent
                    .first()
                    .copyOfRange(0, 4)
                    .toHex(),
            )
            assertNothingLeft(read)
        }

    // --- Settings -------------------------------------------------------------------------------

    @Test
    fun settings__defaults_and_limits() {
        val defaults = CoreNfcReadSettings()
        assertEquals(LinkLossPolicy.Resume(2), defaults.linkLoss)
        assertEquals(400, defaults.settleDelayMillis)
        assertEquals(5_000, defaults.commandTimeoutMillis)
        assertEquals(1_000, defaults.waitAfterSystemEndMillis)
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(commandTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(settleDelayMillis = -1) }
        assertFailsWith<IllegalArgumentException> { CoreNfcReadSettings(waitAfterSystemEndMillis = -1) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}
