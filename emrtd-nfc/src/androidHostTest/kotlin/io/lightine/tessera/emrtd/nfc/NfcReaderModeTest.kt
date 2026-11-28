package io.lightine.tessera.emrtd.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle
import io.lightine.tessera.types.errors.NfcUnavailable
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The platform's NFC adapter for these tests: present or absent, on or off, and able to throw what
 * a real device throws when it refuses `enableReaderMode` (a missing permission, an unsupported
 * call, any other runtime fault). Robolectric's own adapter shadow cannot throw on request.
 */
@Implements(NfcAdapter::class)
class ScriptedNfcAdapterShadow {
    @Implementation
    fun isEnabled(): Boolean = on

    @Implementation
    fun enableReaderMode(
        activity: Activity?,
        callback: NfcAdapter.ReaderCallback?,
        flags: Int,
        extras: Bundle?,
    ) {
        refusal?.let { throw it }
        readerModeRequests += extras
    }

    companion object {
        var present = true
        var on = true
        var refusal: RuntimeException? = null

        /** The extras of each accepted request. */
        val readerModeRequests = mutableListOf<Bundle?>()

        fun reset() {
            present = true
            on = true
            refusal = null
            readerModeRequests.clear()
        }

        @Implementation
        @JvmStatic
        fun getDefaultAdapter(context: Context?): NfcAdapter? = if (present) Shadow.newInstanceOf(NfcAdapter::class.java) else null
    }
}

/**
 * `NfcReaderMode.enable` with the real code, against Robolectric's NFC adapter: the answer for NFC
 * off, for a successful request, and for each way the platform refuses it. Other tests replace
 * reader mode with a fake; these run what a consumer's app runs.
 *
 * Not covered here: `TAG_READING_DISABLED`. The check starts at Android 15 (API level 35) and the
 * Robolectric runtime for it is not in this build's cache; below 35 the SDK reports only what it knows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ScriptedNfcAdapterShadow::class])
class NfcReaderModeTest {
    private val callback = NfcAdapter.ReaderCallback { }

    @Before
    fun hardwareIsThere() {
        ScriptedNfcAdapterShadow.reset()
    }

    @After
    fun reset() {
        ScriptedNfcAdapterShadow.reset()
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `an adapter that is switched off gives DISABLED and reader mode is not requested`() {
        val activity = activity()
        ScriptedNfcAdapterShadow.on = false

        assertEquals(NfcUnavailable.Reason.DISABLED, NfcReaderMode.enable(activity, callback))
        assertEquals(NfcUnavailable.Reason.DISABLED, NfcReaderMode.availability(activity))
        assertTrue(ScriptedNfcAdapterShadow.readerModeRequests.isEmpty())
    }

    @Test
    fun `an adapter that is on and accepts the request gives null and reader mode is on`() {
        val activity = activity()

        assertNull(NfcReaderMode.enable(activity, callback, presenceCheckDelayMillis = 250))

        val extras = ScriptedNfcAdapterShadow.readerModeRequests.single()
        assertEquals(250, extras?.getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
    }

    @Test
    fun `a SecurityException from the platform gives NOT_PERMITTED`() {
        val activity = activity()
        ScriptedNfcAdapterShadow.refusal = SecurityException("no permission")

        assertEquals(NfcUnavailable.Reason.NOT_PERMITTED, NfcReaderMode.enable(activity, callback))
    }

    @Test
    fun `an UnsupportedOperationException from the platform gives NOT_SUPPORTED`() {
        val activity = activity()
        ScriptedNfcAdapterShadow.refusal = UnsupportedOperationException("no reader mode")

        assertEquals(NfcUnavailable.Reason.NOT_SUPPORTED, NfcReaderMode.enable(activity, callback))
    }

    @Test
    fun `any other RuntimeException from the platform gives REQUEST_REFUSED`() {
        val activity = activity()
        ScriptedNfcAdapterShadow.refusal = IllegalStateException("the service is gone")

        assertEquals(NfcUnavailable.Reason.REQUEST_REFUSED, NfcReaderMode.enable(activity, callback))
    }

    @Test
    fun `a device with no NFC hardware gives NOT_SUPPORTED`() {
        ScriptedNfcAdapterShadow.present = false
        assertEquals(NfcUnavailable.Reason.NOT_SUPPORTED, NfcReaderMode.enable(activity(), callback))
    }
}
