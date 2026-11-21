package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.flow.ReadFlow
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
}
