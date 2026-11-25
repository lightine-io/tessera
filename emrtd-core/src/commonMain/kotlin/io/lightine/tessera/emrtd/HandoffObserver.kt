package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Gives every call of a [ChipReadObserver] to a thread of the platform's choice, in order, for the
 * platform read entry points (`ObserverThread.MAIN`): each call is handed to [post], one after the
 * other, and [post] must run what it gets first in, first out on the one thread.
 *
 * The coordinator calls its observer one call at a time and in order, so posting each call in the
 * order it arrives keeps that order. The ending is posted once and is the last call: anything after
 * it is dropped. An exception [app] throws is ignored, as everywhere in a chip read, so it never
 * reaches the thread it was handed to.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. Internal: see [InternalTesseraChipApi].
 *
 * @param app The app's observer.
 * @param post Runs a block later on the target thread, in the order of the calls, and never runs it
 *   on the calling thread before it returns.
 * @since 1.0.0
 */
@OptIn(ExperimentalAtomicApi::class)
@InternalTesseraChipApi
@ExperimentalTesseraChipApi
public class HandoffObserver(
    private val app: ChipReadObserver,
    private val post: (() -> Unit) -> Unit,
) : ChipReadObserver {
    private val endingPosted = AtomicBoolean(false)

    override fun onUpdate(update: ChipReadUpdate) {
        if (endingPosted.load()) return
        post {
            try {
                app.onUpdate(update)
            } catch (e: Throwable) {
                // Ignored on purpose: the observer is never part of the read.
            }
        }
    }

    override fun onEnding(ending: ChipReadEnding) {
        if (!endingPosted.compareAndSet(expectedValue = false, newValue = true)) return
        post {
            try {
                app.onEnding(ending)
            } catch (e: Throwable) {
                // Ignored on purpose, as for an update.
            }
        }
    }
}
