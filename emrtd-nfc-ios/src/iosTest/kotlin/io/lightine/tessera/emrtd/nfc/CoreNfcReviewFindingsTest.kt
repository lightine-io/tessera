package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The iPhone read's endings in the races the branch review (TES-346, items 8, 10, 12) found: a
 * person's cancel reported to the connect first, a session end right after a document was found,
 * and tags reported before a restart of polling.
 */
class CoreNfcReviewFindingsTest {
    @Test
    fun aCancelReportedToTheConnectFirst_isThePersonsCancelNotAFailure() =
        chipReadTest {
            val (read, rec) = start()
            present(FakeChipTag(FakeChip(), ConnectFailure(NfcUnavailable(Reason.CANCELLED_BY_USER), retryable = false)))
            step(400)
            assertEquals(ChipReadEnding.Cancelled.By.PERSON, assertIs<ChipReadEnding.Cancelled>(rec.ending).by)
            assertNothingLeft(read)
        }

    @Test
    fun aSessionEndRightAfterTheDocumentIsFound_isTheSystemsEndingNotNotStarted() =
        chipReadTest {
            val (read, rec) = start()
            // The tag and then the radio-off end arrive before the read's coroutine runs again.
            port.events.onTagsDetected(listOf(FakeChipTag(FakeChip())))
            port.systemEnd(nfcErrorFor(platform.CoreNFC.NFCErrorDomain, CoreNfcErrorCode.RADIO_DISABLED, CoreNfcErrorSource.SESSION_END))
            step(400)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(rec.ending)
            assertEquals(NfcUnavailable(Reason.DISABLED), ending.reason)
            assertNothingLeft(read)
        }

    @Test
    fun tagsReportedBeforeARestartOfPolling_areNotUsedAfterIt() =
        chipReadTest {
            val (read, rec) = start()
            val stale = FakeChipTag(FakeChip())
            // Two tags (the read restarts polling) and then, before the read sees either, a tag
            // list from before that restart.
            port.events.onTagsDetected(listOf(FakeChipTag(FakeChip()), FakeChipTag(FakeChip())))
            port.events.onTagsDetected(listOf(stale))
            step(1_000)
            assertEquals(0, stale.connectCalls, "a tag from before the restart is no longer valid")
            assertEquals(1, port.restarts)
            read.cancel()
            step()
            assertIs<ChipReadEnding.Cancelled>(rec.ending)
        }
}
