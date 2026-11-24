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
 * The rules:
 * - Turn reader mode on when the read screen is resumed, and off when the screen is left. Call
 *   [enable] from the main thread, with the read that is about to run.
 * - The platform pauses and restores reader mode with the Activity by itself: a document may be
 *   found again after a pause without a new [enable]. Still call [disable] when the screen is
 *   left, so the platform's reader does not stay on behind your screen.
 * - Never switch reader mode off and on while a document may be on the phone: that drops the link
 *   and the read ends. Keep it on for the whole read.
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
    public const val DEFAULT_PRESENCE_CHECK_DELAY_MILLIS: Int = 1_000

    /**
     * The extras [enable] passes to reader mode: only the presence-check delay.
     *
     * @param presenceCheckDelayMillis How often the platform checks that the tag is still there,
     *   in milliseconds.
     */
    @JvmOverloads
    public fun extras(presenceCheckDelayMillis: Int = DEFAULT_PRESENCE_CHECK_DELAY_MILLIS): Bundle =
        Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, presenceCheckDelayMillis) }

    /**
     * Turns reader mode on for [activity], with [callback] as the one who hears every discovered
     * tag: pass the [NfcChipRead]. Call this from the main thread, when the read screen is
     * resumed, and never while a read may still hold a link.
     *
     * @param activity The read screen.
     * @param callback The read; the platform hands it every tag it finds.
     * @param presenceCheckDelayMillis How often the platform checks that the tag is still there,
     *   in milliseconds.
     * @param platformSounds `false` to turn the platform's sound off, when your app gives its own
     *   feedback; the default leaves it on.
     * @return `null` when reader mode is on; otherwise why it is not, the same reason a read would
     *   report through [ChipReadEnding.NotStarted][io.lightine.tessera.emrtd.ChipReadEnding.NotStarted]:
     *   `NOT_SUPPORTED` when the device has no NFC reader or the platform refuses reader mode,
     *   `DISABLED` when NFC is off, `NOT_PERMITTED` when the app may not use NFC, and
     *   `REQUEST_REFUSED` for any other refusal by the platform.
     */
    @JvmOverloads
    public fun enable(
        activity: Activity,
        callback: NfcAdapter.ReaderCallback,
        presenceCheckDelayMillis: Int = DEFAULT_PRESENCE_CHECK_DELAY_MILLIS,
        platformSounds: Boolean = true,
    ): NfcUnavailable.Reason? {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        if (adapter == null) return NfcUnavailable.Reason.NOT_SUPPORTED
        if (!adapter.isEnabled) return NfcUnavailable.Reason.DISABLED
        val flags = FLAGS or if (platformSounds) 0 else NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        return try {
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
     * Turns reader mode off for [activity]. Safe to call when it was never on, and from the main
     * thread, when the read screen is left; a refusal by the platform is swallowed, because there
     * is nothing left to end here.
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
     *   return.
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
