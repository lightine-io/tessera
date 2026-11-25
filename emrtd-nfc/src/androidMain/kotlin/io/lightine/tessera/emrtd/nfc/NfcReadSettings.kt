package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipCommandTimeLimit
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.NfcTimeout
import kotlin.jvm.JvmOverloads

/**
 * How an [NfcChipRead] behaves around the radio. Every value has a default that suits an ordinary
 * app: create it with no arguments, or do not pass one at all.
 *
 * Every time is a `Long` in milliseconds. [linkLoss], [settleDelayMillis], [commandTimeoutMillis],
 * [readTimeoutMillis], [commandTimeoutBeforeAccessMillis] and [observerOn] mean the same, with the
 * same defaults, as in the iPhone read's settings
 * (`CoreNfcReadSettings`). The two waits are Android's own: on iPhone the system session's
 * 60-second limit bounds every wait. [presenceCheckDelayMillis] and [platformSounds] set reader
 * mode, which only the Activity-owning `NfcChipRead.start(activity, ...)` turns on for you.
 *
 * Kotlin callers use the constructor with named arguments; Java callers can use [Builder], which
 * also changes one value of an existing settings object without naming the others
 * ([toBuilder]).
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property linkLoss What the read does when the link to the chip is lost, usually because the
 *   document moved: the default, [LinkLossPolicy.Resume] with 2 restarts, waits for the document
 *   to come back and goes on from where it stopped, at most twice. Each new start opens the chip
 *   again with the same key, so a read can make up to 3 authentication attempts with that key,
 *   one per start. It never starts again after the chip answered with a refusal of the key: that
 *   ends the read at once. The MRZ and the card access number are not blocked by tries, so the
 *   worst a chip may do is answer more slowly. Use [LinkLossPolicy.End] for one attempt only.
 * @property settleDelayMillis How long the read waits after a document is found before the first
 *   command, so the hand can settle: frames are often lost in the first moment after detection.
 *   Default 400. Cancelling the read cuts it short.
 * @property commandTimeoutMillis The longest wait for the chip's answer to one command that makes
 *   the chip do public-key or key-agreement work: the steps of BAC and PACE from the first that
 *   uses the key (BAC's EXTERNAL AUTHENTICATE, PACE's GENERAL AUTHENTICATE), chip authentication
 *   and active authentication; also any extended-length command, whose answer can be up to 64 KB.
 *   Every other command has a shorter limit: [commandTimeoutBeforeAccessMillis] before access,
 *   [readTimeoutMillis] after it. It is the longest limit of the three: a longer value of either
 *   of the others is cut to it. Each limit is set on the link as the platform's ISO-DEP time
 *   limit, only when the next command needs a different one. When it passes, the platform reports
 *   the tag as lost (it cannot tell a slow chip from a document that left), so [linkLoss] decides
 *   what follows. Default 5000: ICAO Doc 9303 Part 10, appendix B.11, lets a chip take up to 5
 *   seconds per block.
 *   The SDK's own command time limit counts as a lost link, so the chip may still answer the
 *   command after the limit, and that late answer is dropped unread: when the late answer was a
 *   refusal of the key, the read does not know it, and [linkLoss] may start again with the same
 *   key, within its restarts.
 * @property waitForReturnMillis How long the read waits for the document to come back after a lost
 *   link, counted from the loss, before it ends with [ChipReadEnding.Partial] or
 *   [ChipReadEnding.Failed]. Default 15000. It also bounds the tries while a found chip does not
 *   answer its first commands
 *   ([io.lightine.tessera.emrtd.ChipReadState.NoAnswerYet]), counted from the first unanswered
 *   try: then the read ends with [ChipReadEnding.Failed] and, when no try got past those first
 *   commands, [io.lightine.tessera.types.errors.NfcNoAnswer].
 * @property waitForDocumentMillis How long the read may take to find a document and set up the
 *   first link to it, counted from the start, across tags whose link could not be set up (on
 *   iPhone the system session's 60-second limit plays this part, so its settings have no such value);
 *   [NO_LIMIT], the default, waits without a limit. When it
 *   passes, the read ends with [ChipReadEnding.Failed], 0 attempts, and an [NfcTimeout] with
 *   limit [NfcTimeout.Limit.WAIT_FOR_DOCUMENT]: your app set this limit, so the ending is not
 *   [ChipReadEnding.EndedBySystem]. Once a first link is up it no longer applies:
 *   [waitForReturnMillis] bounds the waits after a lost link.
 * @property presenceCheckDelayMillis How often the platform checks that the document is still
 *   there, set when the Activity-owning start turns reader mode on. Default 1000: the platform's
 *   own 125 ms drops the link of some chips, and a longer delay is a known remedy; whether it helps
 *   is for your app to test with its own documents. Not used by the callback form, where your app
 *   passes it to [NfcReaderMode.enable].
 * @property platformSounds Whether the platform plays its own sound when a document is found, set
 *   when the Activity-owning start turns reader mode on. Default `true`; `false` when your app
 *   gives its own feedback, because the system sound can make people move the document.
 * @property observerOn The thread the observer is called on: [ObserverThread.MAIN] by default, so
 *   the updates reach a screen without a hop of your own; [ObserverThread.CALLER] for the
 *   read's own threads. Either way the calls come one at a time, in order, with the ending last.
 * @property telemetry Where the read's step events go (step, protocol, status word, file id, byte
 *   count, time): the sink you give here. With `null`, the default, the sink registered in
 *   `TelemetrySinkRegistry` when the read starts, which is a sink that drops everything when none
 *   is registered. The events never hold document or chip data.
 * @property readTimeoutMillis The longest wait for the chip's answer to one ordinary command after
 *   access: selecting a file and reading it with short APDUs, under secure messaging or not, and
 *   the other commands that need no public-key work on the chip. Default 2000: on a device a
 *   protected read of 223 bytes took 45 to 65 milliseconds, while a chip that went silent in the
 *   middle of a file kept the read waiting for the full limit each time. ISO/IEC 14443-4 lets a
 *   chip take up to about 4.95 seconds for one frame, so a slow but legal chip may need more:
 *   raise it then. A command not answered in time is a lost link, as with [commandTimeoutMillis]:
 *   [linkLoss] decides what follows. When it is longer than [commandTimeoutMillis],
 *   [commandTimeoutMillis] applies.
 * @property commandTimeoutBeforeAccessMillis The longest wait for the chip's answer to one
 *   command before access: the commands each attempt sends before the key is used (selecting
 *   and reading EF.CardAccess, selecting the application, then BAC's GET CHALLENGE, which only
 *   asks for a random number, or PACE's MSE:Set AT, which only chooses the protocol and password).
 *   They need no public-key work on the chip and normally answer in milliseconds, so a chip that
 *   is found but gets too little power is noticed sooner. Default 1500. From the first command
 *   that uses the key on (BAC's EXTERNAL AUTHENTICATE, PACE's first GENERAL AUTHENTICATE),
 *   [commandTimeoutMillis] and [readTimeoutMillis] apply, so a slow step of the access protocol,
 *   chip authentication or a protected read is never cut by this value. When it is longer than
 *   [commandTimeoutMillis],
 *   [commandTimeoutMillis] applies before access too. A command not answered in time is a try that
 *   costs no restart ([io.lightine.tessera.emrtd.ChipReadState.NoAnswerYet]).
 * @throws IllegalArgumentException when [settleDelayMillis], [waitForReturnMillis] or
 *   [waitForDocumentMillis] is negative, [commandTimeoutMillis], [readTimeoutMillis] or
 *   [commandTimeoutBeforeAccessMillis] is not in 1 to [Int.MAX_VALUE], or
 *   [presenceCheckDelayMillis] is not in 0 to [Int.MAX_VALUE] (the platform takes an `int`).
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class NfcReadSettings
    @JvmOverloads
    @Throws(IllegalArgumentException::class)
    constructor(
        public val linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS),
        public val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
        public val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        public val waitForReturnMillis: Long = DEFAULT_WAIT_FOR_RETURN_MILLIS,
        public val waitForDocumentMillis: Long = NO_LIMIT,
        public val telemetry: TelemetrySink? = null,
        public val presenceCheckDelayMillis: Long = DEFAULT_PRESENCE_CHECK_DELAY_MILLIS,
        public val platformSounds: Boolean = true,
        public val observerOn: ObserverThread = ObserverThread.MAIN,
        public val commandTimeoutBeforeAccessMillis: Long = DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS,
        public val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
    ) {
        init {
            require(settleDelayMillis >= 0) { "settleDelayMillis must not be negative, was $settleDelayMillis" }
            require(commandTimeoutMillis in 1..Int.MAX_VALUE) {
                "commandTimeoutMillis must be positive and fit an Int, was $commandTimeoutMillis"
            }
            require(commandTimeoutBeforeAccessMillis in 1..Int.MAX_VALUE) {
                "commandTimeoutBeforeAccessMillis must be positive and fit an Int, was $commandTimeoutBeforeAccessMillis"
            }
            require(readTimeoutMillis in 1..Int.MAX_VALUE) {
                "readTimeoutMillis must be positive and fit an Int, was $readTimeoutMillis"
            }
            require(waitForReturnMillis >= 0) { "waitForReturnMillis must not be negative, was $waitForReturnMillis" }
            require(waitForDocumentMillis >= 0) { "waitForDocumentMillis must not be negative, was $waitForDocumentMillis" }
            require(presenceCheckDelayMillis in 0..Int.MAX_VALUE) {
                "presenceCheckDelayMillis must be in 0 to ${Int.MAX_VALUE}, was $presenceCheckDelayMillis"
            }
        }

        /** The values, never document data. */
        override fun toString(): String =
            "NfcReadSettings(linkLoss=$linkLoss, settleDelayMillis=$settleDelayMillis, " +
                "commandTimeoutMillis=$commandTimeoutMillis, readTimeoutMillis=$readTimeoutMillis, " +
                "commandTimeoutBeforeAccessMillis=$commandTimeoutBeforeAccessMillis, " +
                "waitForReturnMillis=$waitForReturnMillis, " +
                "waitForDocumentMillis=$waitForDocumentMillis, presenceCheckDelayMillis=$presenceCheckDelayMillis, " +
                "platformSounds=$platformSounds, observerOn=$observerOn)"

        /**
         * A builder that starts from these values, so one value can change without naming the
         * others: `settings.toBuilder().settleDelayMillis(0).build()`.
         *
         * @since 1.0.0
         */
        public fun toBuilder(): Builder =
            Builder()
                .linkLoss(linkLoss)
                .settleDelayMillis(settleDelayMillis)
                .commandTimeoutMillis(commandTimeoutMillis)
                .waitForReturnMillis(waitForReturnMillis)
                .waitForDocumentMillis(waitForDocumentMillis)
                .telemetry(telemetry)
                .presenceCheckDelayMillis(presenceCheckDelayMillis)
                .platformSounds(platformSounds)
                .observerOn(observerOn)
                .commandTimeoutBeforeAccessMillis(commandTimeoutBeforeAccessMillis)
                .readTimeoutMillis(readTimeoutMillis)

        /** The time limit for one command before access: the smaller of the two limits. */
        internal val effectiveTimeoutBeforeAccessMillis: Long
            get() = minOf(commandTimeoutBeforeAccessMillis, commandTimeoutMillis)

        /** The time limit for one command of [kind]: see the three limits. */
        internal fun timeoutFor(kind: ChipCommandTimeLimit): Long =
            when (kind) {
                ChipCommandTimeLimit.BEFORE_ACCESS -> effectiveTimeoutBeforeAccessMillis
                ChipCommandTimeLimit.READ -> minOf(readTimeoutMillis, commandTimeoutMillis)
                ChipCommandTimeLimit.LONG -> commandTimeoutMillis
            }

        /**
         * Builds [NfcReadSettings] step by step, with the constructor's defaults.
         *
         * @since 1.0.0
         */
        @ExperimentalTesseraChipApi
        public class Builder {
            private var linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS)
            private var settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS
            private var commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS
            private var waitForReturnMillis: Long = DEFAULT_WAIT_FOR_RETURN_MILLIS
            private var waitForDocumentMillis: Long = NO_LIMIT
            private var telemetry: TelemetrySink? = null
            private var presenceCheckDelayMillis: Long = DEFAULT_PRESENCE_CHECK_DELAY_MILLIS
            private var platformSounds: Boolean = true
            private var observerOn: ObserverThread = ObserverThread.MAIN
            private var commandTimeoutBeforeAccessMillis: Long = DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS
            private var readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS

            /** What the read does when the link to the chip is lost. */
            public fun linkLoss(policy: LinkLossPolicy): Builder = apply { linkLoss = policy }

            /** How long to wait after a document is found before the first command. */
            public fun settleDelayMillis(millis: Long): Builder = apply { settleDelayMillis = millis }

            /** The longest wait for the chip's answer to one command that makes the chip do public-key work. */
            public fun commandTimeoutMillis(millis: Long): Builder = apply { commandTimeoutMillis = millis }

            /** The longest wait for the chip's answer to one ordinary command after access. */
            public fun readTimeoutMillis(millis: Long): Builder = apply { readTimeoutMillis = millis }

            /** The longest wait for the chip's answer to one command before access. */
            public fun commandTimeoutBeforeAccessMillis(millis: Long): Builder = apply { commandTimeoutBeforeAccessMillis = millis }

            /** How long to wait for the document to come back after a lost link. */
            public fun waitForReturnMillis(millis: Long): Builder = apply { waitForReturnMillis = millis }

            /** How long the read may take to find a document and link to it. */
            public fun waitForDocumentMillis(millis: Long): Builder = apply { waitForDocumentMillis = millis }

            /** Waits for a document without a limit, the default: the same as [NO_LIMIT]. */
            public fun noLimitForDocument(): Builder = apply { waitForDocumentMillis = NO_LIMIT }

            /** Where the read's step events go; `null` uses the sink registered when the read starts. */
            public fun telemetry(sink: TelemetrySink?): Builder = apply { telemetry = sink }

            /** How often the platform checks that the document is still there, in reader mode. */
            public fun presenceCheckDelayMillis(millis: Long): Builder = apply { presenceCheckDelayMillis = millis }

            /** Whether the platform plays its sound when a document is found. */
            public fun platformSounds(on: Boolean): Builder = apply { platformSounds = on }

            /** The thread the observer is called on. */
            public fun observerOn(thread: ObserverThread): Builder = apply { observerOn = thread }

            /**
             * The settings.
             *
             * @throws IllegalArgumentException as the [NfcReadSettings] constructor does.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): NfcReadSettings =
                NfcReadSettings(
                    linkLoss,
                    settleDelayMillis,
                    commandTimeoutMillis,
                    waitForReturnMillis,
                    waitForDocumentMillis,
                    telemetry,
                    presenceCheckDelayMillis,
                    platformSounds,
                    observerOn,
                    commandTimeoutBeforeAccessMillis,
                    readTimeoutMillis,
                )
        }

        /** The default values. */
        public companion object {
            /** The restarts of the default [linkLoss]: 2. */
            public const val DEFAULT_RESTARTS: Int = 2

            /** The default [settleDelayMillis]: 400. */
            public const val DEFAULT_SETTLE_DELAY_MILLIS: Long = ChipReadCoordinator.DEFAULT_SETTLE_DELAY_MILLIS

            /** The default [commandTimeoutMillis]: 5000, the same as [IsoDepTransport.DEFAULT_TIMEOUT_MILLIS]. */
            public const val DEFAULT_COMMAND_TIMEOUT_MILLIS: Long = IsoDepTransport.DEFAULT_TIMEOUT_MILLIS

            /** The default [commandTimeoutBeforeAccessMillis]: 1500. */
            public const val DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS: Long =
                ChipReadCoordinator.DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS

            /** The default [readTimeoutMillis]: 2000. */
            public const val DEFAULT_READ_TIMEOUT_MILLIS: Long = ChipReadCoordinator.DEFAULT_READ_TIMEOUT_MILLIS

            /** The default [waitForReturnMillis]: 15000. */
            public const val DEFAULT_WAIT_FOR_RETURN_MILLIS: Long = 15_000

            /** The default [presenceCheckDelayMillis]: 1000, the same as [NfcReaderMode.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS]. */
            public const val DEFAULT_PRESENCE_CHECK_DELAY_MILLIS: Long = NfcReaderMode.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS

            /** The value of [waitForDocumentMillis] that means no limit: [Long.MAX_VALUE]. */
            public const val NO_LIMIT: Long = Long.MAX_VALUE
        }
    }
