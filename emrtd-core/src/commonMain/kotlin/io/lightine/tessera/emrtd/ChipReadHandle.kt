package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * A chip read the app can stop from its own code: what a platform entry point hands back when it
 * starts a read.
 *
 * [cancel] is safe from any thread and safe to call more than once. Exactly one ending always
 * comes to the read's [ChipReadObserver], whatever the app, the person or the system does; a
 * cancel after the ending changes nothing.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public interface ChipReadHandle {
    /**
     * Stops the read. The cancelled ending goes to the observer at once; the link to the chip is
     * released in the background after it, so this returns quickly even while a command is out. A
     * read that already ended stays as it ended.
     */
    public fun cancel()

    /** `true` once the ending is decided, just before or as the observer hears it. */
    public val isEnded: Boolean
}
