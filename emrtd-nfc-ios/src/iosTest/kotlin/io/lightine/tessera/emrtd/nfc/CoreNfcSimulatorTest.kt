package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The real Core NFC path, where the Simulator can run it: the Simulator has no NFC reader, so the
 * platform says reading is not available and the session fails before it opens. Reading a chip
 * needs a device (the owner's device pass).
 */
class CoreNfcSimulatorTest {
    @Test
    fun the_simulator_has_no_nfc_reader() {
        assertFalse(CoreNfcSession.isReadingAvailable)
    }

    @Test
    fun the_real_session_reports_not_supported_on_the_simulator() =
        runTest {
            val error =
                assertFailsWith<ApduTransportException> {
                    CoreNfcSession("Hold your iPhone near the document", "Reading failed").connect()
                }.error
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED), error)
        }
}
