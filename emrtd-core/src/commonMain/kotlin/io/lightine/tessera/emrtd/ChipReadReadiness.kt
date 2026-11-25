package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcUnavailable

/**
 * Whether a new chip read started now is expected to be accepted at once: for an app that enables
 * its "read the chip" button only when a read can begin. The same shape on both platforms: on
 * iPhone, ask `CoreNfcChipRead.readiness()` and register a listener with
 * `CoreNfcChipRead.addReadinessListener`; on Android, `NfcChipRead.readiness(context)` and
 * `NfcChipRead.addReadinessListener(context, listener)`. Android has no wait between reads, so it
 * never reports [NotYet]; its listener also hears NFC switched on and off. iPhone has no signal
 * for that, so there a radio switched off shows only when a read starts.
 *
 * It is the SDK's own bookkeeping, stated as facts, not a promise from the system: a start is
 * always allowed, and a start made while the value is [NotYet] waits in
 * [ChipReadState.Starting] for the same time, so no read is lost by starting early.
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
public sealed class ChipReadReadiness {
    /**
     * No read is running, and nothing the SDK knows of holds a new one back.
     *
     * @since 1.0.0
     */
    public data object Ready : ChipReadReadiness()

    /**
     * No read is running, but a new one would wait in [ChipReadState.Starting] first. On iPhone:
     * the system ended the last reader session on its own, or refused one as busy, and the SDK
     * treats the system as ready the read setting `waitAfterSystemEndMillis` after it saw that end;
     * or the last session the SDK ended was not yet reported closed by the system, for which it
     * waits at most 5 seconds. Android never reports this value: it has no wait between reads.
     *
     * @property remainingMillis The longest a read started now would wait before it begins a
     *   session, in milliseconds, as the SDK counts it now: at least 1. An upper bound, not a
     *   forecast: when the wait is for the system's report that the last session closed, the value
     *   counts the 5-second bound, and turns [Ready] as soon as the report comes, often sooner
     *   (on an iPhone about 2.7 seconds after a read ended, with 5000 reported).
     * @since 1.0.0
     */
    public class NotYet internal constructor(
        public val remainingMillis: Long,
    ) : ChipReadReadiness() {
        /** `NotYet(` the time `)`. */
        override fun toString(): String = "NotYet(${remainingMillis}ms)"
    }

    /**
     * A read is running in this app: a new start now ends at once with
     * [ChipReadEnding.NotStarted] and [NfcUnavailable.Reason.READ_IN_PROGRESS].
     *
     * @since 1.0.0
     */
    public data object ReadInProgress : ChipReadReadiness()

    /**
     * Chips cannot be read on this device, for [reason], as the platform read's `availability()`
     * reports it: on Android no NFC reader, NFC off, or its option for reading tags off; on iPhone
     * only no NFC reader, the one thing Core NFC can tell before a read.
     *
     * @property reason Why.
     * @since 1.0.0
     */
    public class Unavailable internal constructor(
        public val reason: NfcUnavailable.Reason,
    ) : ChipReadReadiness() {
        /** `Unavailable(` the reason `)`. */
        override fun toString(): String = "Unavailable($reason)"

        /** Equal when the reasons are equal. */
        override fun equals(other: Any?): Boolean = other is Unavailable && other.reason == reason

        /** A hash of the reason. */
        override fun hashCode(): Int = reason.hashCode()
    }

    /** Constructors for the platform modules. */
    @InternalTesseraChipApi
    public companion object {
        /**
         * A [NotYet] for [remainingMillis], at least 1. Internal: see [InternalTesseraChipApi].
         *
         * @param remainingMillis The longest a start now would wait, in milliseconds.
         * @return The value.
         * @since 1.0.0
         */
        @InternalTesseraChipApi
        public fun notYet(remainingMillis: Long): NotYet = NotYet(remainingMillis.coerceAtLeast(1))

        /**
         * An [Unavailable] for [reason]. Internal: see [InternalTesseraChipApi].
         *
         * @param reason Why.
         * @return The value.
         * @since 1.0.0
         */
        @InternalTesseraChipApi
        public fun unavailable(reason: NfcUnavailable.Reason): Unavailable = Unavailable(reason)
    }
}

/**
 * Hears when a platform read's [ChipReadReadiness] changes: from one kind to another (for example
 * from [ChipReadReadiness.ReadInProgress] to [ChipReadReadiness.NotYet], then to
 * [ChipReadReadiness.Ready], or from [ChipReadReadiness.Ready] to
 * [ChipReadReadiness.Unavailable] when NFC is switched off on Android), never twice in a row with
 * the same kind; two [ChipReadReadiness.Unavailable] values with different reasons count as
 * different. Called on the main thread, one call at a time, in order. Not called at registration: register, then ask `readiness()` for
 * the value now. An exception it throws is ignored.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public fun interface ChipReadReadinessListener {
    /**
     * The readiness changed.
     *
     * @param readiness The new value.
     */
    public fun onReadinessChanged(readiness: ChipReadReadiness)
}

/**
 * A registered [ChipReadReadinessListener]: [remove] it when the screen goes away. A listener the
 * SDK holds keeps what it refers to alive until it is removed.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public interface ChipReadReadinessRegistration {
    /**
     * Removes the listener. A change already on its way to the main thread is dropped, so called on
     * the main thread, the listener is not called after this returns. Safe to call more than once,
     * from any thread.
     */
    public fun remove()
}
