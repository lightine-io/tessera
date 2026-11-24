package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcUnavailable
import io.lightine.tessera.types.errors.NfcUnavailable.Reason
import platform.CoreNFC.NFCErrorDomain
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The parts of the Core NFC bridge that run without a reader: building an APDU, the answer and
 * error of a command, and the queue each session gets.
 */
class CoreNfcPlatformTest {
    private val readBinary = byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x01)

    private class Outcome {
        var calls = 0
        var answer: ByteArray? = null
        var error: NfcError? = null
    }

    private fun send(
        command: ByteArray,
        send: (apdu: platform.CoreNFC.NFCISO7816APDU, completion: ApduCompletion) -> Unit,
    ): Outcome {
        val outcome = Outcome()
        sendApdu(command, { answer, error ->
            outcome.calls++
            outcome.answer = answer
            outcome.error = error
        }, send)
        return outcome
    }

    @Test
    fun bytes_core_nfc_does_not_take_as_an_apdu_give_null() {
        assertNull(commandApdu(byteArrayOf()))
        assertNull(commandApdu(byteArrayOf(0x00)))
        assertNotNull(commandApdu(readBinary))
    }

    @Test
    fun a_command_core_nfc_cannot_build_is_refused_without_sending_it() {
        val outcome = send(byteArrayOf(0x00)) { _, _ -> fail("nothing may be sent") }
        assertEquals(1, outcome.calls)
        assertNull(outcome.answer)
        assertEquals(NfcUnavailable(Reason.REQUEST_REFUSED), outcome.error)
    }

    @Test
    fun the_answer_gets_its_status_word() {
        val outcome =
            send(readBinary) { apdu, completion ->
                assertEquals(0xB0.toUByte(), apdu.instructionCode)
                completion(byteArrayOf(0x41).toNSData(), 0x90.toUByte(), 0x00.toUByte(), null)
            }
        assertContentEquals(byteArrayOf(0x41, 0x90.toByte(), 0x00), outcome.answer)
        assertNull(outcome.error)
    }

    @Test
    fun a_command_error_maps_as_a_tag_operation() {
        fun errorFor(code: Long): NfcError? =
            send(readBinary) { _, completion ->
                completion(null, 0.toUByte(), 0.toUByte(), NSError.errorWithDomain(NFCErrorDomain, code, null))
            }.error

        assertEquals(NfcUnavailable(Reason.REQUEST_REFUSED), errorFor(CoreNfcErrorCode.PACKET_TOO_LONG))
        assertEquals(NfcTagLost, errorFor(CoreNfcErrorCode.TAG_CONNECTION_LOST))
        assertEquals(NfcTagLost, errorFor(9_999))
    }

    @Test
    fun each_session_gets_its_own_queue() {
        val first = newSessionQueue()
        val second = newSessionQueue()
        assertTrue(first != second)
    }

    @Test
    fun both_ways_of_making_the_session_get_the_queue() {
        val queue = newSessionQueue()
        val passed = mutableListOf<Pair<String, NSObject>>()

        newTagReaderSession<String, Unit>(
            "configuration",
            queue,
            withConfiguration = { _, q -> passed += "configuration" to q },
            withPolling = { fail("the Configuration is there") },
        )
        newTagReaderSession<String, Unit>(
            null,
            queue,
            withConfiguration = { _, _ -> fail("there is no Configuration") },
            withPolling = { q -> passed += "polling" to q },
        )

        assertEquals(listOf("configuration" to queue, "polling" to queue), passed)
    }

    @Test
    fun the_configuration_exists_on_the_simulator_runtime() {
        // The Simulator runs iOS 26.4 or later, so the Configuration path is the one taken there.
        assertNotNull(PlatformReaderSession.emrtdConfiguration())
    }
}
