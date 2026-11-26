package io.lightine.tessera.emrtd

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * What a read does when the link to the chip is lost: usually the document moved away from the
 * phone. The platform read settings (Android `NfcReadSettings`, iOS `CoreNfcReadSettings`) default
 * to [Resume] with 2 restarts; pass another policy there to change it.
 *
 * **For an ordinary app, keep [Resume] with 2 restarts.** A slip of the document then costs a
 * second of "hold it again", not a new start, and what the app gets is as reliable as one
 * uninterrupted read (see [Resume] for why).
 *
 * Whatever the policy, the read starts again only after a loss of the link itself, before the chip
 * answered: [NfcTagLost] or [NfcTimeout]. It never starts again after the chip answered with a
 * refusal (a wrong key, for example; see below for the one more try on the same link), after an
 * error in the protected session, after a malformed file, or after the person, the app or the
 * system ended the read. Each new start runs the access protocol again with the same key: the
 * chip forgets its session keys when it loses power (ICAO Doc 9303 Part 11, section 9.8.5). The
 * MRZ and the card access number are not blocked by tries (TR-03110 Part 1, section 2.3), so with
 * `maxRestarts` 3 a read makes at most four access attempts through restarts, none of them
 * after a refusal, as far as the read knows: the platform settings' command time limit also
 * counts as a lost link, so a chip that answers a command after that limit has its answer
 * dropped unread. When that late answer was a refusal of the key, the next
 * start uses the same key again, within the restarts.
 *
 * A loss before the attempt sent its first command that uses the key (BAC's EXTERNAL
 * AUTHENTICATE, PACE's first GENERAL AUTHENTICATE) is outside every policy, [End] included: the
 * chip was asked nothing about the key, so it uses no restart and starts no new
 * attempt. The read reports [ChipReadState.NoAnswerYet] and tries the same document again, for a
 * time the platform sets.
 *
 * A chip that refuses the key on a later attempt ends the read with [ChipReadEnding.KeyNotAccepted]
 * and nothing from earlier attempts: another document may have been placed on the phone, and the
 * SDK cannot tell.
 *
 * A refusal is not a lost link and uses no restart, under every policy. Only the first refusal of
 * a read, before any try reached the command that uses the key, leads to one more try of the
 * access protocol on the same link ([ChipReadEnding.KeyNotAccepted] gives the rule): so a read
 * with `maxRestarts` 3 makes at most five authentication attempts with its key, and never more
 * than one after a refusal.
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
public sealed class LinkLossPolicy {
    /** The most times a read may start again after a lost link: 0 for [End]. */
    public abstract val maxRestarts: Int

    /**
     * End the read at the first lost link: [ChipReadEnding.Partial] with what was read when access
     * had been granted, [ChipReadEnding.Failed] otherwise.
     *
     * @since 1.0.0
     */
    public data object End : LinkLossPolicy() {
        override val maxRestarts: Int get() = 0
    }

    /**
     * Start the whole read again, from the beginning, when the document is found again; at most
     * [maxRestarts] times. Everything from the earlier attempt is dropped, so the result always
     * comes from one uninterrupted chip session.
     *
     * Choose it when each result must come from a single session; it re-reads every file, so it
     * takes longer than [Resume] after a loss during a large file.
     *
     * @property maxRestarts How many times the read may start again: 1 to 3.
     * @throws IllegalArgumentException when [maxRestarts] is outside 1 to 3.
     * @since 1.0.0
     */
    public class ReadAgain
        @Throws(IllegalArgumentException::class)
        constructor(
            override val maxRestarts: Int,
        ) : LinkLossPolicy() {
            init {
                require(maxRestarts in RESTARTS) { "maxRestarts must be 1 to 3" }
            }

            /** Equal when [maxRestarts] is equal. */
            override fun equals(other: Any?): Boolean = other is ReadAgain && other.maxRestarts == maxRestarts

            /** A hash of [maxRestarts]. */
            override fun hashCode(): Int = maxRestarts

            /** `ReadAgain(` the restarts `)`. */
            override fun toString(): String = "ReadAgain($maxRestarts)"
        }

    /**
     * Continue the read where it stopped when the document is found again; at most [maxRestarts]
     * times in a row that make no progress. A lost link after an attempt that read more bytes of
     * the data groups than the attempts before it (counting the bytes kept, and a data group cut
     * off) uses no restart, so a read that keeps getting somewhere is not ended by the limit; the
     * platform's total time limit still ends it (Android `NfcReadSettings.totalTimeLimitMillis`;
     * on iPhone the system session's own limit).
     *
     * The access protocol runs again, then the reader reads EF.SOD (the chip's signed list of data
     * group hashes) again and compares it byte for byte with the earlier one:
     * - When they are equal, the data groups already read are kept and not read again, and a data
     *   group cut off by the loss continues from the byte where it stopped. A continued data group
     *   is kept only when its hash matches the one in EF.SOD at once; otherwise it is read again in
     *   full, once, in the same session. Every data group is still checked against EF.SOD at the
     *   end, as in any read.
     * - When they differ, or EF.SOD could not be read in one of the attempts, nothing from the
     *   earlier attempt is kept and the read starts over, as with [ReadAgain]. [ChipRead.resume]
     *   records which case happened.
     *
     * Files that no hash can vouch for are always read again in full: EF.COM, EF.SOD itself, and
     * the files of the master file (EF.CardAccess, EF.CardSecurity). Chip authentication and active
     * authentication run again in the new session: their outcomes, and every other check that
     * needs the chip, always come from the last attempt. [ChipFile.attempt] says which attempt read
     * each file. The identity check is the signed data, never the chip's radio identifier (many
     * chips give a random one).
     *
     * @property maxRestarts How many times the read may continue after a lost link that made no
     *   progress: 1 to 3.
     * @throws IllegalArgumentException when [maxRestarts] is outside 1 to 3.
     * @since 1.0.0
     */
    public class Resume
        @Throws(IllegalArgumentException::class)
        constructor(
            override val maxRestarts: Int,
        ) : LinkLossPolicy() {
            init {
                require(maxRestarts in RESTARTS) { "maxRestarts must be 1 to 3" }
            }

            /** Equal when [maxRestarts] is equal. */
            override fun equals(other: Any?): Boolean = other is Resume && other.maxRestarts == maxRestarts

            /** A hash of [maxRestarts]. */
            override fun hashCode(): Int = maxRestarts

            /** `Resume(` the restarts `)`. */
            override fun toString(): String = "Resume($maxRestarts)"
        }

    /** Constants for [LinkLossPolicy]. */
    public companion object {
        /** The restarts the policies allow: 1 to 3. */
        @JvmField
        public val RESTARTS: IntRange = 1..3

        /**
         * [End]: the read ends at the first lost link. A short form for Swift and Java, where the
         * nested object is awkward to name: `LinkLossPolicy.end()`.
         *
         * @since 1.0.0
         */
        @JvmStatic
        public fun end(): LinkLossPolicy = End

        /**
         * [ReadAgain] with [maxRestarts] restarts: `LinkLossPolicy.readAgain(2)`.
         *
         * @param maxRestarts How many times the read may start again: 1 to 3 ([RESTARTS]).
         * @throws IllegalArgumentException when [maxRestarts] is outside 1 to 3.
         * @since 1.0.0
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        public fun readAgain(maxRestarts: Int): LinkLossPolicy = ReadAgain(maxRestarts)

        /**
         * [Resume] with [maxRestarts] restarts: `LinkLossPolicy.resume(2)`, the default of the
         * platform read settings.
         *
         * @param maxRestarts How many times the read may continue: 1 to 3 ([RESTARTS]).
         * @throws IllegalArgumentException when [maxRestarts] is outside 1 to 3.
         * @since 1.0.0
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        public fun resume(maxRestarts: Int): LinkLossPolicy = Resume(maxRestarts)
    }
}
