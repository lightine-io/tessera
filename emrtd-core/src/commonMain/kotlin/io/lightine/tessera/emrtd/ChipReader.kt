package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.flow.FlowReporter
import io.lightine.tessera.emrtd.internal.flow.FlowSink
import io.lightine.tessera.emrtd.internal.flow.ProgressTracker
import io.lightine.tessera.emrtd.internal.flow.ReadFlow
import io.lightine.tessera.emrtd.internal.flow.ResumeCarry
import io.lightine.tessera.telemetry.NoOpTelemetrySink
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmOverloads

/**
 * Reads a document's chip in one call: opens it, reads the files your app asks for, runs the checks
 * and returns the facts.
 *
 * The read follows ICAO Doc 9303 Part 11, Appendix J: PACE when the chip offers a suite the reader
 * runs, otherwise BAC, otherwise a plain read; never both in one session. Then EF.COM, EF.SOD, the
 * data groups, chip authentication or active authentication, and the passive-authentication checks
 * against the roots your app supplied. Every check is reported as a [ChipCheck]; none of them says
 * the document is genuine, and the SDK makes no trust decision.
 *
 * One reader can run many reads, one at a time per transport. Session keys are wiped when each
 * read ends. The access key is not cleared; your app calls [ChipAccessKey.clear] when it is done.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @param telemetry Where step events ([ChipReadEvent]) go: the sink registered in
 *   [TelemetrySinkRegistry] when the reader is made, or [NoOpTelemetrySink] when none is.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipReader internal constructor(
    private val telemetry: TelemetrySink,
    private val cryptoProvider: () -> ChipCrypto,
) {
    /** A reader that sends step events to [telemetry]. */
    @JvmOverloads
    public constructor(telemetry: TelemetrySink = TelemetrySinkRegistry.current) : this(telemetry, ::chipCrypto)

    /**
     * Reads the chip behind [transport] as [options] say.
     *
     * It never throws for a chip, transport or content problem: those end in
     * [ChipReadResult.Partial] or [ChipReadResult.Failed] with a typed error. Only cancellation of
     * the calling coroutine is thrown.
     *
     * @param transport The platform's connection to the chip, already open.
     * @param options The key, the data groups, the roots and the check time.
     * @return [ChipReadResult.Complete], [ChipReadResult.Partial] or [ChipReadResult.Failed].
     * @throws CancellationException when the calling coroutine is cancelled.
     */
    @Throws(CancellationException::class)
    public suspend fun read(
        transport: ApduTransport,
        options: ChipReadOptions,
    ): ChipReadResult = ReadFlow(transport, options, telemetry, cryptoProvider).run()

    /**
     * Reads the chip behind [transport] as [options] say, and tells [updates] where the read is.
     *
     * The same read as the two-argument [read], with the same result. [updates] gets an update as
     * the read opens access ([ChipReadState.OpeningAccess]), reads each file part by part
     * ([ChipReadState.ReadingFile], with the byte counts once the file's length is known), runs
     * chip or active authentication ([ChipReadState.AuthenticatingChip]) and runs the checks
     * ([ChipReadState.CheckingFiles]), each with the progress. Every update has attempt 1 of 1:
     * this call makes one attempt, on a link your app owns. The result is the ending, so the
     * listener has no ending call; a [ChipReadObserver] can be passed, and its
     * [ChipReadObserver.onEnding] is not called.
     *
     * The updates come on the calling coroutine, one at a time. An exception the listener throws is
     * ignored and never changes the read.
     *
     * @param transport The platform's connection to the chip, already open.
     * @param options The key, the data groups, the roots and the check time.
     * @param updates Where the updates go; `null` for none.
     * @return [ChipReadResult.Complete], [ChipReadResult.Partial] or [ChipReadResult.Failed].
     * @throws CancellationException when the calling coroutine is cancelled.
     * @since 1.0.0
     */
    @Throws(CancellationException::class)
    public suspend fun read(
        transport: ApduTransport,
        options: ChipReadOptions,
        updates: ChipReadUpdateListener?,
    ): ChipReadResult {
        if (updates == null) return read(transport, options)
        val sink =
            FlowSink { state, progress ->
                try {
                    updates.onUpdate(ChipReadUpdate(state, progress, 1, 1))
                } catch (e: Throwable) {
                    // Ignored on purpose: the listener is never part of the result.
                }
            }
        return ReadFlow(transport, options, telemetry, cryptoProvider, FlowReporter(ProgressTracker(), sink)).run()
    }

    /** One attempt for the read coordinator: its result and what it leaves for a resume. */
    internal suspend fun readAttempt(
        transport: ApduTransport,
        options: ChipReadOptions,
        reporter: FlowReporter,
        attempt: Int,
        carry: ResumeCarry?,
    ): Pair<ChipReadResult, ResumeCarry> {
        val flow = ReadFlow(transport, options, telemetry, cryptoProvider, reporter, attempt, carry)
        val result = flow.run()
        return result to flow.carryOut()
    }
}
