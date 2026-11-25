package io.lightine.tessera.emrtd

import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ApduTransportTest {
    private class Echo : ApduTransport {
        override val maxCommandLength = 261
        override val supportsExtendedLength = false

        override suspend fun transceive(command: ByteArray): ByteArray = command + byteArrayOf(0x90.toByte(), 0x00)
    }

    private class Failing(
        val error: ApduTransportException,
    ) : ApduTransport {
        override val maxCommandLength = 261
        override val supportsExtendedLength = true

        override suspend fun transceive(command: ByteArray): ByteArray = throw error
    }

    @Test
    fun an_app_can_implement_the_transport() =
        runTest {
            val t: ApduTransport = Echo()
            assertEquals(261, t.maxCommandLength)
            assertContentEquals(byteArrayOf(1, 2, 0x90.toByte(), 0), t.transceive(byteArrayOf(1, 2)))
        }

    @Test
    fun transport_failure_carries_its_typed_error_and_its_text() =
        runTest {
            val cause = RuntimeException("platform")
            val e = ApduTransportException(NfcTimeout(3000, NfcTimeout.Limit.COMMAND), cause)
            val thrown = assertFailsWith<ApduTransportException> { Failing(e).transceive(byteArrayOf(0)) }
            assertSame(e, thrown)
            assertEquals(NfcTimeout(3000, NfcTimeout.Limit.COMMAND), thrown.error)
            assertEquals(NfcTimeout(3000, NfcTimeout.Limit.COMMAND).description, thrown.message)
            assertSame(cause, thrown.cause)
        }

    @Test
    fun exception_without_a_cause() {
        val e = ApduTransportException(NfcUnavailable(NfcUnavailable.Reason.DISABLED))
        assertEquals(null, e.cause)
        assertEquals("NFC is turned off on this device", e.message)
    }
}
