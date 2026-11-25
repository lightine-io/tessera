package io.lightine.tessera.emrtd.nfc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TES-346: the polling choice decides how the system session is opened, and reaches the session. */
class CoreNfcPollingTest {
    /** The way the session is opened for [polling] when the system has [configuration] (or none). */
    private fun openingFor(
        polling: CoreNfcPolling,
        configuration: String?,
    ): String =
        newTagReaderSession(
            configurationFor(polling) { configuration },
            newSessionQueue(),
            withConfiguration = { config, _ -> "configuration:$config" },
            withPolling = { "polling option" },
        )

    @Test
    fun automatic_uses_the_configuration_where_there_is_one_and_the_polling_option_before() {
        assertEquals("configuration:iso14443+pace", openingFor(CoreNfcPolling.AUTOMATIC, "iso14443+pace"))
        assertEquals("polling option", openingFor(CoreNfcPolling.AUTOMATIC, null))
    }

    @Test
    fun iso14443_always_uses_the_polling_option() {
        assertEquals("polling option", openingFor(CoreNfcPolling.ISO14443, "iso14443+pace"))
        assertEquals("polling option", openingFor(CoreNfcPolling.ISO14443, null))
    }

    @Test
    fun iso14443_never_asks_for_a_configuration() {
        var asked = false
        configurationFor<String>(CoreNfcPolling.ISO14443) {
            asked = true
            "iso14443+pace"
        }
        assertEquals(false, asked)
    }

    @Test
    fun iso14443_and_pace_uses_the_configuration_and_falls_back_to_the_polling_option() {
        assertEquals("configuration:iso14443+pace", openingFor(CoreNfcPolling.ISO14443_AND_PACE, "iso14443+pace"))
        assertEquals("polling option", openingFor(CoreNfcPolling.ISO14443_AND_PACE, null))
    }

    @Test
    fun the_default_is_automatic() {
        assertEquals(CoreNfcPolling.AUTOMATIC, CoreNfcReadSettings().polling)
        assertEquals(CoreNfcPolling.AUTOMATIC, CoreNfcReadSettings.Builder().build().polling)
    }

    @Test
    fun the_settings_keep_the_choice_through_the_builder_and_toString() {
        for (choice in CoreNfcPolling.entries) {
            val built = CoreNfcReadSettings.Builder().polling(choice).build()
            assertEquals(choice, built.polling)
            assertEquals(choice, built.toBuilder().build().polling)
            assertEquals(choice, CoreNfcReadSettings(polling = choice).toBuilder().build().polling)
            assertTrue("polling=$choice" in built.toString(), built.toString())
        }
        val changed = CoreNfcReadSettings(polling = CoreNfcPolling.ISO14443).toBuilder().settleDelayMillis(0).build()
        assertEquals(CoreNfcPolling.ISO14443, changed.polling)
        assertEquals(0L, changed.settleDelayMillis)
    }

    @Test
    fun the_read_opens_its_session_with_the_choice_in_its_settings() =
        chipReadTest {
            val (read, _) = start(settings = CoreNfcReadSettings(polling = CoreNfcPolling.ISO14443))
            assertEquals(listOf(CoreNfcPolling.ISO14443), pollings)
            read.cancel()
            step()
            assertNothingLeft(read)
        }

    @Test
    fun the_read_opens_its_session_with_automatic_by_default() =
        chipReadTest {
            val (read, _) = start()
            assertEquals(listOf(CoreNfcPolling.AUTOMATIC), pollings)
            read.cancel()
            step()
            assertNothingLeft(read)
        }
}
