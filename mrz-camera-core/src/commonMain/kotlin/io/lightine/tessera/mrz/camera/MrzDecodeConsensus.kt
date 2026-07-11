package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.parsing.ParseResult

/**
 * A frame-agreement gate over the live decode stream: it accepts a reading only once the **same** parsed
 * document has been seen on [threshold] frames, so a single bad frame does not win.
 *
 * ## Why this exists
 *
 * The MRZ name field carries **no check digit**, so an OCR misread inside it (device-observed: a filler `<`
 * read as the letter `K`, turning `ASGAR<` into `ASGARK`) produces a perfectly valid
 * [`ParseResult.Success`][ParseResult.Success] — indistinguishable, by checksum, from a correct read. Nothing
 * structural catches it, and it must not be "corrected" in text: `K` is a valid MRZ letter, so mapping it back
 * to `<` would infer which of two valid characters was meant — exactly the oracle behaviour Principle 1
 * forbids (contrast the chevron *recovery* in [MrzFrameAnalyzer], which only maps glyphs *outside* the MRZ
 * alphabet, where no choice exists). The one honest lever left is to read the document more than once and take
 * the reading the frames agree on: a transient misread is a minority of one, the correct reading the majority.
 *
 * ## Reader, not oracle
 *
 * This gate **selects among readings the camera actually produced** — it never fabricates one. Agreement is on
 * the *whole* parsed [MrzDocument], never per-character voting (which could synthesise a reading no single frame
 * ever saw). The document it confirms is one an actual frame decoded, and that frame's result — including its
 * verbatim [`recognizedText`][MrzScanResult.Decoded.recognizedText] (Principle 5) — is what travels on. It
 * makes no trust decision: a [`PartialSuccess`][ParseResult.PartialSuccess] (a check-digit mismatch) still
 * confirms and still routes to review; the gate only asks "did enough frames read the *same* thing", not "is
 * this document good".
 *
 * ## Scope and limits
 *
 * Consensus removes **transient** misreads — the "*sometimes* ASGARK" that varies frame to frame. A
 * **systematic** misread (the same wrong glyph on every frame, e.g. fixed glare) will reach agreement too;
 * only the human review step catches that. So [threshold] past a small value buys little against the real
 * failure mode while adding latency to every successful read. Two is a good default: a transient misread
 * would have to strike two independent frames *identically* to survive it — vanishingly unlikely — while the
 * read stays fast; raise it only where confidence matters more than speed.
 *
 * ## Statefulness
 *
 * Stateful and **not** thread-safe: drive it from a single collector of one scan session, and [reset] it when
 * a new session or reading method begins (so a prior session's votes do not carry over). The per-frame
 * [analyser][MrzFrameAnalyzer] stays stateless; agreement lives here, on top of the stream.
 *
 * ## Usage
 *
 * ```
 * val consensus = MrzDecodeConsensus(threshold = 3)
 * scanner.results.collect { result ->
 *     if (result is MrzScanResult.Decoded && result.parse !is ParseResult.Failure) {
 *         when (val verdict = consensus.offer(result)) {
 *             is ConsensusVerdict.Confirmed -> route(verdict.decoded) // enough frames agreed — act on it
 *             is ConsensusVerdict.Gathering  -> { /* verdict.agreement of verdict.threshold so far */ }
 *         }
 *     }
 * }
 * ```
 *
 * @property threshold how many frames must decode the same document before it is [confirmed][ConsensusVerdict.Confirmed]
 *   (must be `>= 1`; `1` accepts the first decode, i.e. no consensus). Defaults to [DEFAULT_THRESHOLD].
 */
public class MrzDecodeConsensus(
    public val threshold: Int = DEFAULT_THRESHOLD,
) {
    init {
        require(threshold >= 1) { "threshold must be >= 1, was $threshold" }
    }

    // Votes per distinct reading, keyed on the parsed document (value-equal — a differing field, e.g. the
    // name, is a different key). A LinkedHashMap keeps first-seen order, purely so behaviour is deterministic
    // if two readings ever tie at the threshold on the same frame (they cannot: one frame casts one vote).
    private val tally = LinkedHashMap<MrzDocument, Int>()

    // Latched once a reading reaches the threshold, so a late duplicate frame re-offered before the caller's
    // own guard trips still returns the settled reading rather than re-counting.
    private var confirmed: MrzScanResult.Decoded? = null

    /**
     * Records one decoded frame and reports whether a reading has now been agreed on. Expects a **parseable**
     * decode ([`Success`][ParseResult.Success] / [`PartialSuccess`][ParseResult.PartialSuccess]); a
     * [`Failure`][ParseResult.Failure] carries no document to agree on and never counts (it reports the
     * current [`Gathering`][ConsensusVerdict.Gathering] without advancing). Idempotent once confirmed.
     */
    public fun offer(decoded: MrzScanResult.Decoded): ConsensusVerdict {
        confirmed?.let { return ConsensusVerdict.Confirmed(it) }

        val document =
            decoded.parse.documentOrNull()
                ?: return ConsensusVerdict.Gathering(agreement = leadCount(), threshold = threshold)

        val count = (tally[document] ?: 0) + 1
        tally[document] = count
        return if (count >= threshold) {
            confirmed = decoded
            ConsensusVerdict.Confirmed(decoded)
        } else {
            ConsensusVerdict.Gathering(agreement = count, threshold = threshold)
        }
    }

    /** Clears all votes and the settled reading — call when a new scan session or reading method begins. */
    public fun reset() {
        tally.clear()
        confirmed = null
    }

    private fun leadCount(): Int = tally.values.maxOrNull() ?: 0

    private fun ParseResult.documentOrNull(): MrzDocument? =
        when (this) {
            is ParseResult.Success -> document
            is ParseResult.PartialSuccess -> document
            is ParseResult.Failure -> null
        }

    public companion object {
        /**
         * The default number of agreeing frames — 2. Enough to shed a transient single-frame misread (a second
         * frame would have to reproduce the exact same misread to survive it) while adding only one extra
         * frame's latency to a successful read; see the class note on why higher values give diminishing
         * returns against the real (transient) failure mode.
         */
        public const val DEFAULT_THRESHOLD: Int = 2
    }
}

/**
 * The outcome of offering one decoded frame to a [MrzDecodeConsensus].
 */
public sealed interface ConsensusVerdict {
    /**
     * Not enough agreement yet. [agreement] is the vote count of the current leading reading (0 when the
     * offered frame carried no parseable document); [threshold] is the target.
     */
    public data class Gathering(
        public val agreement: Int,
        public val threshold: Int,
    ) : ConsensusVerdict

    /** [threshold] frames have decoded the same document; [decoded] is the frame result to act on. */
    public data class Confirmed(
        public val decoded: MrzScanResult.Decoded,
    ) : ConsensusVerdict
}
