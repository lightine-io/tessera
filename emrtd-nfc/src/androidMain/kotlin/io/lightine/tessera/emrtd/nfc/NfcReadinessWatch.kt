package io.lightine.tessera.emrtd.nfc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Build
import io.lightine.tessera.emrtd.ChipReadReadiness
import io.lightine.tessera.emrtd.ChipReadReadinessListener
import io.lightine.tessera.emrtd.ChipReadReadinessRegistration
import io.lightine.tessera.types.errors.NfcUnavailable

/**
 * Where the readiness watch learns about NFC on the device, so it can be tested with a fake: the
 * platform by default.
 */
internal interface NfcStateSource {
    /** Why NFC cannot be used now, or `null`: [NfcReaderMode.availability]. */
    fun availability(context: Context): NfcUnavailable.Reason?

    /**
     * Starts hearing when the NFC adapter's state changes, on the main thread; returns the call
     * that stops it. [context] is the application context.
     */
    fun listen(
        context: Context,
        onChange: () -> Unit,
    ): () -> Unit
}

/** The platform: the adapter for the state, the system's `ACTION_ADAPTER_STATE_CHANGED` broadcast for its changes. */
internal object PlatformNfcState : NfcStateSource {
    override fun availability(context: Context): NfcUnavailable.Reason? = NfcReaderMode.availability(context)

    override fun listen(
        context: Context,
        onChange: () -> Unit,
    ): () -> Unit {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) onChange()
                }
            }
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        // The broadcast comes from the system only: not exported from Android 13 on, where the
        // flag exists. With no Handler, onReceive runs on the main thread.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered: nothing is left to release.
            }
        }
    }
}

/**
 * Whether a new [NfcChipRead] is expected to be accepted at once (TES-346), and the listeners that
 * hear when that changes kind.
 *
 * - The value: [ChipReadReadiness.Unavailable] when NFC cannot be used ([NfcStateSource.availability]);
 *   else [ChipReadReadiness.ReadInProgress] while a read is live in [registry]; else
 *   [ChipReadReadiness.Ready]. Android has no wait between reads, so never
 *   [ChipReadReadiness.NotYet].
 * - The adapter-state broadcast is heard only while at least one listener is registered: the
 *   receiver goes on with the first listener and off with the last, with the application context.
 * - A change is told once per change of kind, in order, through [main]; a listener removed before
 *   its call runs is not called.
 *
 * One lock guards the listeners, the last value told, the receiver and the context; the calls are
 * posted under it, so their order on the main thread is the order of the changes.
 */
internal class NfcReadinessWatch(
    private val registry: NfcReadRegistry,
    private val state: NfcStateSource,
    private val main: MainThread,
) {
    private val lock = Any()
    private val listeners = mutableListOf<Entry>()
    private var told: ChipReadReadiness? = null
    private var context: Context? = null
    private var stopListening: (() -> Unit)? = null

    init {
        registry.onChange = ::changed
    }

    /** The value now, asked of [context]'s application context. */
    fun current(context: Context): ChipReadReadiness = valueFor(appContextOf(context))

    private fun valueFor(context: Context): ChipReadReadiness {
        val unavailable = state.availability(context)
        return when {
            unavailable != null -> ChipReadReadiness.unavailable(unavailable)
            registry.isLive() -> ChipReadReadiness.ReadInProgress
            else -> ChipReadReadiness.Ready
        }
    }

    fun add(
        context: Context,
        listener: ChipReadReadinessListener,
    ): ChipReadReadinessRegistration {
        val app = appContextOf(context)
        val entry = Entry(listener)
        synchronized(lock) {
            if (listeners.isEmpty()) {
                this.context = app
                told = valueFor(app)
                stopListening = state.listen(app, ::changed)
            }
            listeners += entry
        }
        return entry
    }

    /** NFC's state or the registry changed: tell the listeners when the kind changed. */
    fun changed() {
        synchronized(lock) {
            val app = context ?: return
            val value = valueFor(app)
            if (value == told) return
            told = value
            for (entry in listeners.toList()) {
                main.post { entry.deliver(value) }
            }
        }
    }

    /** Whether the adapter-state broadcast is heard now: for tests. */
    internal val isListening: Boolean get() = synchronized(lock) { stopListening != null }

    private fun appContextOf(context: Context): Context = context.applicationContext ?: context

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
            val stop =
                synchronized(lock) {
                    if (!listeners.remove(this) || listeners.isNotEmpty()) return
                    context = null
                    told = null
                    stopListening.also { stopListening = null }
                }
            stop?.invoke()
        }
    }
}
