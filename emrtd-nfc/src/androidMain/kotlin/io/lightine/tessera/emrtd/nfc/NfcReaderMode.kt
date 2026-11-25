package io.lightine.tessera.emrtd.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.os.Bundle
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlin.jvm.JvmOverloads

/**
 * Reader mode, the platform's NFC dispatch an Android chip read runs on: [NfcChipRead] is the
 * callback.
 *
 * The rules (the platform facts behind them are in its `NfcActivityManager` and `NfcService`
 * source):
 * - Call [enable] once for each read, with that read, while the read screen is shown: from
 *   `onCreate` or `onResume`, on the main thread. Reader mode belongs to that Activity: the
 *   platform turns it off when the Activity pauses and on again, with the same callback, when it
 *   resumes. So do not call [enable] again in every `onResume`, and after a configuration change
 *   call it once for the new Activity.
 * - Never switch reader mode off and on while a document may be on the phone: switching it off
 *   drops the link, and the read sees a lost link. Call [disable] when the read screen is left for
 *   good, after the read ended or was cancelled.
 * - Do not use `NfcAdapter.ignore` to keep a document from being found again: the platform
 *   documentation says it does not work reliably with tags that have a random ID, and passport
 *   chips use one.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public object NfcReaderMode {
    /** The flags a chip read needs: NFC-A and NFC-B, where passport chips speak, and no NDEF check. */
    public const val FLAGS: Int =
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

    /**
     * The presence-check delay [enable] turns on by default: 1 second.
     *
     * The platform's own default is 125 ms, and it checks only outside an exchange. Some chips
     * drop the link under these checks, and a longer delay is a known remedy; whether it helps is
     * a judgement for your app to test with its own documents, not a rule. The platform
     * documentation of `EXTRA_READER_PRESENCE_CHECK_DELAY` says the value is in milliseconds.
     */
    public const val DEFAULT_PRESENCE_CHECK_DELAY_MILLIS: Long = 1_000

    /**
     * The extras [enable] passes to reader mode: only the presence-check delay.
     *
     * @param presenceCheckDelayMillis How often the platform checks that the tag is still there,
     *   in milliseconds; a value outside 0 to [Int.MAX_VALUE] is cut to that range, because the
     *   platform takes an `int`.
     */
    @JvmOverloads
    public fun extras(presenceCheckDelayMillis: Long = DEFAULT_PRESENCE_CHECK_DELAY_MILLIS): Bundle =
        Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, presenceCheckDelayMillis.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        }

    /**
     * Turns reader mode on for [activity], with [callback] as the one who hears every discovered
     * tag: pass the [NfcChipRead]. Call it once for each read, while the read screen is shown, on
     * the main thread.
     *
     * @param activity The read screen.
     * @param callback The read; the platform hands it every tag it finds while [activity] is
     *   resumed.
     * @param presenceCheckDelayMillis How often the platform checks that the tag is still there,
     *   in milliseconds.
     * @param platformSounds `false` to turn the platform's sound off, when your app gives its own
     *   feedback; the default leaves it on.
     * @return `null` when reader mode is on (or will be when [activity] resumes); otherwise why it
     *   is not, the same reasons a read reports through
     *   [ChipReadEnding.NotStarted][io.lightine.tessera.emrtd.ChipReadEnding.NotStarted]:
     *   `NOT_SUPPORTED` when the device has no NFC reader (the platform documents
     *   `UnsupportedOperationException` for that), `DISABLED` when NFC is off (the platform
     *   ignores reader mode then), `NOT_PERMITTED` when the platform refuses the app
     *   (`SecurityException`), and `REQUEST_REFUSED` for any other refusal, such as the
     *   `IllegalStateException` the platform throws when the Activity has set another discovery
     *   technology. When it is not `null`, cancel the read: it cannot get a document.
     */
    @JvmOverloads
    public fun enable(
        activity: Activity,
        callback: NfcAdapter.ReaderCallback,
        presenceCheckDelayMillis: Long = DEFAULT_PRESENCE_CHECK_DELAY_MILLIS,
        platformSounds: Boolean = true,
    ): NfcUnavailable.Reason? {
        val flags = FLAGS or if (platformSounds) 0 else NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        return try {
            val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return NfcUnavailable.Reason.NOT_SUPPORTED
            if (!adapter.isEnabled) return NfcUnavailable.Reason.DISABLED
            adapter.enableReaderMode(activity, callback, flags, extras(presenceCheckDelayMillis))
            null
        } catch (e: UnsupportedOperationException) {
            NfcUnavailable.Reason.NOT_SUPPORTED
        } catch (e: SecurityException) {
            NfcUnavailable.Reason.NOT_PERMITTED
        } catch (e: RuntimeException) {
            NfcUnavailable.Reason.REQUEST_REFUSED
        }
    }

    /**
     * Turns reader mode off for [activity]. Call it on the main thread when the read screen is
     * left for good, never during a read. Safe to call when it was never on; a refusal by the
     * platform is swallowed, because there is nothing left to end here.
     */
    public fun disable(activity: Activity) {
        try {
            NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        } catch (e: RuntimeException) {
            // The platform refused to turn the reader off; nothing is left to release.
        }
    }

    /**
     * Whether NFC can be used right now, before a read starts.
     *
     * @param context Any context of the app.
     * @return `null` when NFC is usable; otherwise why it is not, the same reason [enable] would
     *   return. The same question and the same return type as `CoreNfcChipRead.availability()` on
     *   iPhone, and [NfcChipRead.availability] is the same call from the entry point.
     */
    public fun availability(context: Context): NfcUnavailable.Reason? {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        return when {
            adapter == null -> NfcUnavailable.Reason.NOT_SUPPORTED
            !adapter.isEnabled -> NfcUnavailable.Reason.DISABLED
            else -> null
        }
    }
}
