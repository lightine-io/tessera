package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * Which thread a platform chip read calls its [ChipReadObserver] on. The same choice, with the same
 * default, in the Android and the iPhone read settings (`NfcReadSettings.observerOn` and
 * `CoreNfcReadSettings.observerOn`).
 *
 * With either value the calls never overlap, come in order, and the one ending comes last, exactly
 * once.
 *
 * May gain members in a later release: an exhaustive `when` over it then stops compiling until
 * the new member is handled. An `else` branch keeps it compiling without handling the new member:
 * that is your choice, and today the compiler warns that such an `else` is redundant.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ObserverThread {
    /**
     * The platform's main thread (Android main looper, iPhone main queue), the thread a screen
     * updates on. Every call is handed over in the order the SDK made it, so the order holds, and
     * no call is made before `start` returned: even from the main thread, the first update comes
     * after it. The default: nearly every app shows the updates on screen.
     */
    MAIN,

    /**
     * Whatever thread the SDK happens to be on: the read's own thread, or the thread that called
     * `start` or `cancel` for the updates and the ending those cause. Not promised to be any
     * particular thread; calls may come before `start` returns. For an observer that only records
     * values and hands them on itself.
     */
    CALLER,
}
