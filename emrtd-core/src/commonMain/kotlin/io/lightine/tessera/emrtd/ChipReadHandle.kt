package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * A chip read in progress, as the app sees it: it can be cancelled, and it says whether it ended.
 * The platform entry points hand one back when they start a read (Android `NfcChipRead`, iOS
 * `CoreNfcChipRead`).
 *
 * The read's updates and its one ending go to the [ChipReadObserver] given when it started.
 * Exactly one ending always comes, whatever the app, the person or the system does.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public interface ChipReadHandle {
    /** `true` once the read's ending is decided; the observer gets it at once. */
    public val isEnded: Boolean

    /**
     * Cancels the read: the observer gets [ChipReadState.Stopping], then [ChipReadEnding.Cancelled]
     * by [ChipReadEnding.Cancelled.By.APP] at once, unless the read had already ended. The SDK
     * releases the link to the chip in the background after that, so this returns quickly even
     * while a command is out. Safe to call more than once and from any thread; only the first call
     * counts, and a read that already ended stays as it ended.
     */
    public fun cancel()
}
