package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.parsing.ParseResult

/**
 * Accepts a reading only after the same parsed document decodes on [threshold] frames.
 *
 * It selects among readings the camera produced and never builds one. Agreement is on the whole
 * [MrzDocument], not per character. The frames need not be consecutive: the count is the total
 * since the last [reset], and other frames between them do not reset it. A
 * [ParseResult.PartialSuccess] still counts. A misread that repeats on every frame also reaches
 * agreement. The class is not thread-safe: drive one instance
 * from one collector per scan session, and call [reset] when a new session begins.
 *
 * @property threshold how many frames in total must decode the same document. At least 1; 1
 *   accepts the first decode. The default is [DEFAULT_THRESHOLD].
 * @throws IllegalArgumentException if [threshold] is less than 1.
 * @since 0.5.0
 */
public class MrzDecodeConsensus
    @Throws(IllegalArgumentException::class)
    constructor(
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
         * Records one decoded frame and reports whether a reading has been agreed on.
         *
         * A [ParseResult.Failure] has no document, so it does not count and returns
         * [ConsensusVerdict.Gathering] with the current lead. After a reading is confirmed, every call
         * returns the same [ConsensusVerdict.Confirmed].
         *
         * @param decoded the decoded frame result.
         * @return [ConsensusVerdict.Confirmed] once [threshold] frames agree, otherwise
         *   [ConsensusVerdict.Gathering].
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

        /** Clears all votes and the confirmed reading. */
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

        /** Constants for [MrzDecodeConsensus]. */
        public companion object {
            /** The default [threshold]: 2 frames. */
            public const val DEFAULT_THRESHOLD: Int = 2
        }
    }

/**
 * The outcome of offering one decoded frame to a [MrzDecodeConsensus].
 *
 * @since 0.5.0
 */
public sealed interface ConsensusVerdict {
    /**
     * Not enough frames agree yet.
     *
     * @property agreement the vote count of the leading reading; 0 before any parseable frame.
     * @property threshold the number of agreeing frames required.
     * @since 0.5.0
     */
    public data class Gathering(
        public val agreement: Int,
        public val threshold: Int,
    ) : ConsensusVerdict

    /**
     * Enough frames decoded the same document.
     *
     * @property decoded the decoded result of the frame that reached the threshold.
     * @since 0.5.0
     */
    public data class Confirmed(
        public val decoded: MrzScanResult.Decoded,
    ) : ConsensusVerdict
}
