package io.lightine.tessera.emrtd.nfc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/** Runs blocks one at a time, in the order posted, away from the caller; [post] returns at once. */
internal fun interface SerialWorker {
    fun post(block: () -> Unit)
}

/** Runs each block at once, in the caller: for tests that check the sheet's calls in step. */
internal val InlineWorker = SerialWorker { it() }

/**
 * A worker on a serial dispatch queue of its own, one per read: neither the session's delegate queue
 * nor the main queue, so a Core NFC call that does not return holds up nothing but this queue.
 */
internal fun newSheetQueueWorker(): SerialWorker {
    val queue = checkNotNull(dispatch_queue_create(SHEET_QUEUE_LABEL, null))
    return SerialWorker { block -> dispatch_async(queue) { block() } }
}

private const val SHEET_QUEUE_LABEL = "io.lightine.tessera.emrtd.nfc.sheet"

/**
 * Makes every Core NFC call of a read's sheet and ending on [worker], never in the caller: the read
 * hands over a text or its ending and goes on at once.
 *
 * On a device, setting the sheet's text while the session polled with no document found did not
 * return for about 12 seconds (TES-346; why is not known). Before this writer, that call ran on the
 * thread that hands the updates to the app, and held up every update and the app's cancel behind it.
 *
 * - Texts are conflated: while a call runs, only the latest text waits, and texts in between are
 *   never set.
 * - The ending comes after every text asked for before it and drops a text still waiting: the
 *   final text (a success) is set, then the session is ended. When that has not happened a moment
 *   later (a call stuck), [endNow] ends the session from the caller anyway; [OwnedReaderSession]
 *   makes sure the session is ended only once.
 * - [onSlow] hears of each text setting that took longer than [SLOW_SET_MILLIS], once: when it
 *   returns, or at [endNow] while it has not returned yet.
 */
internal class SheetWriter(
    private val session: OwnedReaderSession,
    private val worker: SerialWorker,
    private val now: () -> Long,
    private val onSlow: (durationMillis: Long, returned: Boolean) -> Unit = { _, _ -> },
) {
    private class Ending(
        val finalText: String?,
        val errorMessage: String?,
    )

    private val lock = NSLock()
    private var pending: String? = null
    private var ending: Ending? = null
    private var endingTaken = false
    private var endingDone = false

    // A drain is posted or running on the worker.
    private var running = false

    // When the text setting in flight started, and whether it was already reported as slow.
    private var setSince: Long? = null
    private var setReported = false
    private var idle: CompletableDeferred<Unit>? = null

    /** Whether the ending's calls are done on the worker. */
    val isEndingDone: Boolean get() = locked { endingDone }

    /** Asks for [text] on the sheet; replaces a text still waiting. Nothing once the ending was asked for. */
    fun show(text: String) {
        val post =
            locked {
                if (ending != null) return
                pending = text
                startDrain()
            }
        if (post) worker.post(::drain)
    }

    /**
     * Asks for the ending, once: [finalText] is set first, when given, then the session is ended with
     * [errorMessage] (`null` for success). A text still waiting is dropped.
     */
    fun end(
        finalText: String?,
        errorMessage: String?,
    ) {
        val post =
            locked {
                if (ending != null) return
                ending = Ending(finalText, errorMessage)
                pending = null
                startDrain()
            }
        if (post) worker.post(::drain)
    }

    /**
     * The ending's wait is over: ends the session from the caller with the error message asked for,
     * whether or not the worker got to it, and reports a text setting still in flight as slow.
     */
    fun endNow() {
        var stuckFor: Long? = null
        val asked =
            locked {
                val since = setSince
                if (since != null && !setReported) {
                    val took = now() - since
                    if (took > SLOW_SET_MILLIS) {
                        setReported = true
                        stuckFor = took
                    }
                }
                ending
            }
        stuckFor?.let { report(it, returned = false) }
        if (asked != null) session.end(asked.errorMessage)
    }

    /** Waits until the worker has nothing left to do, at most [limitMillis]. */
    suspend fun awaitIdle(limitMillis: Long) {
        val signal =
            locked {
                if (!running) return
                idle ?: CompletableDeferred<Unit>().also { idle = it }
            }
        withTimeoutOrNull(limitMillis) { signal.await() }
    }

    // Under [lock]: whether the caller must post a drain.
    private fun startDrain(): Boolean {
        if (running) return false
        running = true
        return true
    }

    private fun drain() {
        while (true) {
            var text: String? = null
            var end: Ending? = null
            var done: CompletableDeferred<Unit>? = null
            locked {
                val asked = ending
                when {
                    asked != null && !endingTaken -> {
                        endingTaken = true
                        end = asked
                    }

                    pending != null -> {
                        text = pending
                        pending = null
                    }

                    else -> {
                        running = false
                        done = idle
                        idle = null
                    }
                }
            }
            val taken = end
            val next = text
            when {
                taken != null -> {
                    taken.finalText?.let(::set)
                    session.end(taken.errorMessage)
                    locked { endingDone = true }
                }

                next != null -> {
                    set(next)
                }

                else -> {
                    done?.complete(Unit)
                    return
                }
            }
        }
    }

    private fun set(text: String) {
        val start = now()
        locked {
            setSince = start
            setReported = false
        }
        try {
            session.setAlert(text)
        } finally {
            val took = now() - start
            val report =
                locked {
                    setSince = null
                    !setReported && took > SLOW_SET_MILLIS
                }
            if (report) report(took, returned = true)
        }
    }

    private fun report(
        took: Long,
        returned: Boolean,
    ) {
        try {
            onSlow(took, returned)
        } catch (e: Throwable) {
            // Ignored on purpose: telemetry is never part of the read.
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /** A text setting slower than this is reported. */
        const val SLOW_SET_MILLIS = 500L
    }
}
