package io.lightine.tessera.emrtd.nfc

import platform.Foundation.NSLock
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/**
 * A worker on a serial dispatch queue of its own, one per read, for the read's `restartPolling`
 * calls: neither the session's delegate queue, the main queue, nor the sheet's queue, so a call that
 * does not return holds up no update, no tag report, no text and no ending.
 */
internal fun newSearchQueueWorker(): SerialWorker {
    val queue = checkNotNull(dispatch_queue_create(SEARCH_QUEUE_LABEL, null))
    return SerialWorker { block -> dispatch_async(queue) { block() } }
}

private const val SEARCH_QUEUE_LABEL = "io.lightine.tessera.emrtd.nfc.search"

/**
 * Makes every `restartPolling` call of a read on [worker], never in the caller (TES-346).
 *
 * On a device, when the first contact between phone and chip happened in a weak position, Core NFC
 * reported no tag at all and the system ended the session about 22 seconds later; moving the
 * document did not help. While the read waits for a document, it asks for a restart on a period
 * ([CoreNfcReadSettings.searchRestartMillis]) so a failed first contact gets a fresh try with the
 * sheet still up.
 *
 * The rules, each decided under one lock:
 * - A search is one wait for a document ([searchBegan]); it stops when a tag is reported
 *   ([tagReported], called on the delegate queue before the tag is handed to the read) or the read
 *   ends ([stop], or [isEnding]).
 * - A periodic restart is posted only while its search goes on, and only when no other restart is
 *   posted or running: a call that does not return is never piled on.
 * - When the worker runs a periodic restart, it checks again: a tag reported, a newer search or
 *   the ending in the meantime drop it. A tag reported after that check, while the call runs, is
 *   still handled once by the read: Core NFC may make it invalid (`NFCTagReaderSession.h`,
 *   `restartPolling`), then its connect fails and the read restarts as after any failed connect.
 * - A restart the read asks for after a lost link or an unusable tag ([restartNow]) is posted the
 *   same way, and dropped once the read ends.
 */
internal class SearchRestarter(
    private val worker: SerialWorker,
    private val isEnding: () -> Boolean,
) {
    private val lock = NSLock()
    private var search = 0L
    private var searching = false
    private var stopped = false

    // Restarts posted or running on the worker.
    private var queued = 0

    /** A new wait for a document begins, with no tag reported yet; returns its number. */
    fun searchBegan(): Long =
        locked {
            search++
            searching = !stopped
            search
        }

    /** A tag was reported: no periodic restart for this search any more. */
    fun tagReported() = locked { searching = false }

    /** The read is ending: no restart from now on. */
    fun stop() =
        locked {
            stopped = true
            searching = false
        }

    /**
     * Asks for a restart of [session] while search [forSearch] goes on.
     *
     * @return `true` when a restart was posted.
     */
    fun restartWhileSearching(
        forSearch: Long,
        session: OwnedReaderSession,
    ): Boolean {
        val post =
            locked {
                if (!stillSearching(forSearch) || queued > 0) {
                    false
                } else {
                    queued++
                    true
                }
            }
        if (post) worker.post { run(session) { stillSearching(forSearch) } }
        return post
    }

    /** Asks for a restart of [session] now: after a lost link, a failed connect or an unusable tag. */
    fun restartNow(session: OwnedReaderSession) {
        val post =
            locked {
                if (stopped) {
                    false
                } else {
                    queued++
                    true
                }
            }
        if (post) worker.post { run(session) { !stopped } }
    }

    // Under [lock].
    private fun stillSearching(forSearch: Long): Boolean = !stopped && searching && search == forSearch

    private fun run(
        session: OwnedReaderSession,
        allowed: () -> Boolean,
    ) {
        try {
            val go = locked { allowed() } && !isEnding()
            if (go) session.restartPolling()
        } finally {
            locked { queued-- }
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
}
