package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.emrtd.ChipReadReadinessListener
import io.lightine.tessera.emrtd.ChipReadReadinessRegistration
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSRecursiveLock
import kotlin.concurrent.Volatile

/**
 * Whether a new [CoreNfcChipRead] is expected to begin its session at once (TES-346), from the
 * [registry]'s facts, and the listeners that hear when that changes kind.
 *
 * - The value: [ChipReadReadiness.Unavailable] when the device cannot read chips; else
 *   [ChipReadReadiness.ReadInProgress] while a read is live; else [ChipReadReadiness.NotYet] until
 *   the later of the release bound of a session being released and the rest after the system's
 *   last end; else [ChipReadReadiness.Ready].
 * - A change is told once per change of kind, in order, on [mainQueue]; a listener removed before
 *   its call runs is not called. A timer re-reads the value when a [ChipReadReadiness.NotYet]
 *   is due to end, so a listener hears of [ChipReadReadiness.Ready] with no read event.
 *
 * One recursive lock guards the listeners, the last value told and the timer; the calls are posted
 * under it, so their order on the main queue is the order of the changes. Recursive, so a listener
 * run in place (tests, an inline main queue) may remove itself.
 *
 * A change never asks Core NFC anything (TES-346): [changed] runs on the thread of the registry
 * change, which for a read's start is the app's thread inside `CoreNfcChipRead.start`, often the
 * main thread. It uses the device's reading support as the last [current] call found it
 * (`NFCTagReaderSession.readingAvailable` is a call into the system: on a device a start right
 * after a read showed nothing for 13 seconds, and a call that waited for the busy system there is
 * the likely cause). With no listener it does nothing at all.
 */
internal class ReadinessWatch(
    private val registry: ChipReadRegistry,
    private val readingAvailable: () -> Boolean,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val mainQueue: (() -> Unit) -> Unit,
) {
    private val lock = NSRecursiveLock()
    private val listeners = mutableListOf<Entry>()
    private var told: ChipReadReadiness? = null
    private var timer: Job? = null

    // The device's reading support as the last [current] call found it; `null` before any.
    @Volatile
    private var lastAvailable: Boolean? = null

    init {
        registry.onChange = ::changed
    }

    /** The value now. Asks Core NFC whether the device can read tags: call it on the app's own terms. */
    fun current(): ChipReadReadiness {
        val available = readingAvailable()
        lastAvailable = available
        return valueOf(available)
    }

    /** The value from the registry's facts and [available], with no call into the system. */
    private fun valueOf(available: Boolean): ChipReadReadiness {
        if (!available) return ChipReadReadiness.unavailable(NfcUnavailable.Reason.NOT_SUPPORTED)
        val facts = registry.snapshot()
        if (facts.readInProgress) return ChipReadReadiness.ReadInProgress
        val at = now()
        val until = maxOf(facts.releaseDeadline ?: Long.MIN_VALUE, facts.readyAt ?: Long.MIN_VALUE)
        return if (until > at) ChipReadReadiness.notYet(until - at) else ChipReadReadiness.Ready
    }

    fun add(listener: ChipReadReadinessListener): ChipReadReadinessRegistration {
        val entry = Entry(listener)
        val value = current()
        locked {
            // With no listener, changes were not followed: the value now is the one told.
            if (told == null || listeners.isEmpty()) told = value
            listeners += entry
            schedule(value)
        }
        return entry
    }

    /**
     * The registry changed, or a wait is due to end: tell the listeners when the kind changed. Never
     * calls into the system, and does nothing while no listener is registered.
     */
    fun changed() {
        locked {
            if (listeners.isEmpty()) return@locked
            val value = valueOf(lastAvailable ?: true)
            schedule(value)
            if (sameKind(told, value)) return@locked
            told = value
            for (entry in listeners.toList()) {
                mainQueue { entry.deliver(value) }
            }
        }
    }

    // Under [lock]: one timer for the end of a wait, none otherwise.
    private fun schedule(value: ChipReadReadiness) {
        timer?.cancel()
        timer = null
        if (value is ChipReadReadiness.NotYet && listeners.isNotEmpty()) {
            timer =
                scope.launch {
                    delay(value.remainingMillis)
                    changed()
                }
        }
    }

    private fun sameKind(
        a: ChipReadReadiness?,
        b: ChipReadReadiness,
    ): Boolean =
        when (a) {
            null -> false
            is ChipReadReadiness.NotYet -> b is ChipReadReadiness.NotYet
            else -> a == b
        }

    private inner class Entry(
        private val listener: ChipReadReadinessListener,
    ) : ChipReadReadinessRegistration {
        @Volatile
        private var removed = false

        fun deliver(value: ChipReadReadiness) {
            if (removed) return
            try {
                listener.onReadinessChanged(value)
            } catch (e: Throwable) {
                // Ignored on purpose: a listener is never part of a read.
            }
        }

        override fun remove() {
            removed = true
            locked {
                listeners.remove(this)
                if (listeners.isEmpty()) {
                    timer?.cancel()
                    timer = null
                }
            }
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
