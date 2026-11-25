package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipCommandTimeLimit
import io.lightine.tessera.emrtd.ChipReadCoordinator
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * How a [CoreNfcChipRead] behaves around the radio. Every value has a default that suits an
 * ordinary app: create it with no arguments, or do not pass one at all.
 *
 * Every time is a `Long` in milliseconds, as in the Android read's settings (`NfcReadSettings`),
 * where [linkLoss], [settleDelayMillis], [commandTimeoutMillis], [readTimeoutMillis],
 * [commandTimeoutBeforeAccessMillis] and [observerOn] mean the same,
 * with the same defaults. There is no wait-for-document limit as on Android
 * (`NfcReadSettings.waitForDocumentMillis`): the system session's own 60-second limit bounds the
 * wait for a document, and the system ends the read when it passes
 * ([io.lightine.tessera.emrtd.ChipReadEnding.EndedBySystem]); cancel the read for a shorter limit.
 *
 * Experimental: see [ExperimentalTesseraChipApi]. In Swift, `CoreNfcReadSettings()` gives the
 * defaults; the full initializer takes every value. To change one value without naming the others,
 * use [Builder]: `CoreNfcReadSettings.Builder().settleDelayMillis(millis: 0).build()`, or
 * [toBuilder] on an existing object.
 *
 * @property linkLoss What the read does when the link to the chip is lost, usually because the
 *   document moved: the default, [LinkLossPolicy.Resume] with 2 restarts, waits for the document
 *   to come back in the same system session and goes on from where it stopped, at most twice.
 *   Each new start opens the chip again with the same key, so a read can make up to 3
 *   authentication attempts with that key, one per start. It never starts again after the chip
 *   answered with a refusal of the key: that ends the read at once. The MRZ and the card access
 *   number are not blocked by tries, so the worst a chip may do is answer more slowly. Use
 *   [LinkLossPolicy.End] for one attempt only.
 * @property settleDelayMillis How long the read waits after the system finds a document before the
 *   first command, so the hand can settle: frames are often lost in the first second after
 *   detection. Default 400. Cancelling the read or the end of the system session cuts it short.
 * @property commandTimeoutMillis The longest wait for the chip's answer to one command that makes
 *   the chip do public-key or key-agreement work: the steps of BAC and PACE from the first that
 *   uses the key (BAC's EXTERNAL AUTHENTICATE, PACE's GENERAL AUTHENTICATE), chip authentication
 *   and active authentication; also any extended-length command, whose answer can be up to 64 KB.
 *   Every other command has a shorter limit: [commandTimeoutBeforeAccessMillis] before access,
 *   [readTimeoutMillis] after it. It is the longest limit of the three: a longer value of either
 *   of the others is cut to it. When it passes, the command counts as a lost link: nothing more is sent on that link, a late answer is
 *   dropped, and [linkLoss] decides what follows. Default 5000: ICAO Doc 9303 Part 10, appendix
 *   B.11, lets a chip take up to 5 seconds per block, and Core NFC itself may wait far longer.
 *   The SDK's own command time limit counts as a lost link, so the chip may still answer the
 *   command after the limit, and that late answer is dropped unread: when the late answer was a
 *   refusal of the key, the read does not know it, and [linkLoss] may start again with the same
 *   key, within its restarts.
 * @property waitAfterSystemEndMillis How long a new read waits before it begins a system session
 *   when the previous one was ended by the system (its time limit, the app leaving the screen) or
 *   refused because the reader was busy. Default 1000. A read ended by the app or the person
 *   needs no wait.
 * @property telemetry Where the read's step events go (step, protocol, status word, file id, byte
 *   count, time): the sink you give here. With `null`, the default, the sink registered in
 *   `TelemetrySinkRegistry` when the read starts, which is a sink that drops everything when none
 *   is registered. The events never hold document or chip data.
 * @property observerOn The thread the observer is called on: [ObserverThread.MAIN], the main
 *   queue, by default, so the updates reach a screen without a hop of your own; [ObserverThread.CALLER]
 *   for the read's own threads. Either way the calls come one at a time, in order, with the ending
 *   last, exactly once, and none comes before `start` returned.
 * @property readTimeoutMillis The longest wait for the chip's answer to one ordinary command after
 *   access: selecting a file and reading it, under secure messaging or not, and the other commands
 *   that need no public-key work on the chip. Default 2000: on a device a protected read of 223
 *   bytes took 45 to 65 milliseconds, while a chip that went silent in the middle of a file kept
 *   the read waiting for the full limit each time. ISO/IEC 14443-4 lets a chip take up to about
 *   4.95 seconds for one frame, so a slow but legal chip may need more: raise it then. A command
 *   not answered in time is a lost link, as with [commandTimeoutMillis]: [linkLoss] decides what
 *   follows. When it is longer than [commandTimeoutMillis], [commandTimeoutMillis] applies.
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
 *   costs no restart ([io.lightine.tessera.emrtd.ChipReadState.NoAnswerYet]); the tries go on in
 *   the same system session while at least 10 of its 60 seconds are left; when they stop and no
 *   try got past these commands, the read ends with [io.lightine.tessera.emrtd.ChipReadEnding.Failed]
 *   and [io.lightine.tessera.types.errors.NfcNoAnswer].
 * @property searchRestartMillis How often the read asks the system to search for the document
 *   again (Core NFC's `restartPolling`) while it waits for one and the system has reported none.
 *   On an iPhone, when the first contact between phone and chip happened in a weak position, the
 *   system reported no document at all, and ended the session about 22 seconds later; moving the
 *   document did not help. A new search gives that first contact another try, with the sheet still
 *   up. Default 4000, so the first new search comes well before that end and a person moving the
 *   document gets several. No new search is asked for once a document was reported or the read is
 *   ending. [SEARCH_RESTART_OFF] (0) turns it off; any other value must be at least
 *   [MIN_SEARCH_RESTART_MILLIS] (2000). The read still counts the system session's 60 seconds
 *   from its start: Core NFC's header does not say that a new search changes them. There is no such setting on Android, where the system reports a document that
 *   does not answer.
 * @property polling What the system session polls for: see [CoreNfcPolling]. Default
 *   [CoreNfcPolling.AUTOMATIC], today ISO 14443 and PACE together from iOS 26.4 and ISO 14443
 *   alone before. [CoreNfcPolling.ISO14443] polls ISO 14443 alone on every version and misses
 *   chips that answer only after PACE polling. There is no such setting on Android, where the
 *   system finds the tag.
 * @property maxSessionRenewals How many times one read may close its system session and open a
 *   new one while no document was reported
 *   ([io.lightine.tessera.emrtd.ChipReadState.RenewingSession]), counting both causes: the system
 *   ended the session with Core NFC's code 202 ("ended unexpectedly") before any document was
 *   reported, or [sessionRenewMillis] passed with no document reported. On an iPhone about one
 *   read in four, with the session started before the phone was on the document, never reported
 *   the document and was ended by the system with code 202 about 22 seconds after the document
 *   came near; a session started with the phone already on the document found it at once. A
 *   renewal keeps the key, the attempt and the restarts after a lost link; the sheet closes and
 *   opens again. Never after a document was reported in the read, after the read's ending began,
 *   or after the person tapped Cancel (code 200) or the session's 60 seconds passed (code 201):
 *   those end the read as before. Default 2. [SESSION_RENEWALS_OFF] (0) turns renewals off: a 202
 *   then ends the read with [io.lightine.tessera.emrtd.ChipReadEnding.EndedBySystem]. When the
 *   renewals are used up, the next 202 ends the read that way too. There is no such setting on
 *   Android, where the system has no such session.
 * @property sessionRenewMillis How long a system session may go with no document reported before
 *   the read ends it itself and opens a new one, counted from the session's begin, within
 *   [maxSessionRenewals]. Default 10000, well before the system's own end at about 22 seconds,
 *   after two new searches ([searchRestartMillis]). [SESSION_RENEW_OFF] (0) turns it off; the
 *   renewal after the system's code 202 still happens. Any other value must be at least
 *   [MIN_SESSION_RENEW_MILLIS] (5000): a shorter one would close the sheet before a person had
 *   time to bring the phone to the document.
 * @property busyRetryMillis How long the read keeps trying to begin a system session that the
 *   system refused as busy (Core NFC's code 203), counted from the first refusal: it tries again
 *   after 0.5, 1, 2, then every 3 seconds, the last try at this bound. On an iPhone the system may
 *   refuse a new session for a short time after one ended. It applies to the first session (the
 *   read stays in [io.lightine.tessera.emrtd.ChipReadState.Starting]; when the bound passes the read
 *   ends with [io.lightine.tessera.emrtd.ChipReadEnding.NotStarted] and
 *   [io.lightine.tessera.types.errors.NfcUnavailable.Reason.BUSY]) and to a renewed one (when the
 *   bound passes the read ends with [io.lightine.tessera.emrtd.ChipReadEnding.EndedBySystem] and
 *   that reason: the read had started). A refusal costs no renewal. Default 6000.
 *   [BUSY_RETRY_OFF] (0) turns it off: the first refusal ends the read.
 * @throws IllegalArgumentException when [commandTimeoutMillis], [readTimeoutMillis] or
 *   [commandTimeoutBeforeAccessMillis] is not positive, [settleDelayMillis],
 *   [waitAfterSystemEndMillis], [maxSessionRenewals] or [busyRetryMillis] is negative,
 *   [searchRestartMillis] is neither [SEARCH_RESTART_OFF] nor at least
 *   [MIN_SEARCH_RESTART_MILLIS], or [sessionRenewMillis] is neither [SESSION_RENEW_OFF] nor at
 *   least [MIN_SESSION_RENEW_MILLIS].
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class CoreNfcReadSettings
    @Throws(IllegalArgumentException::class)
    constructor(
        public val linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS),
        public val settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS,
        public val commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS,
        public val waitAfterSystemEndMillis: Long = DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS,
        public val telemetry: TelemetrySink? = null,
        public val observerOn: ObserverThread = ObserverThread.MAIN,
        public val commandTimeoutBeforeAccessMillis: Long = DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS,
        public val readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS,
        public val searchRestartMillis: Long = DEFAULT_SEARCH_RESTART_MILLIS,
        public val polling: CoreNfcPolling = CoreNfcPolling.AUTOMATIC,
        public val maxSessionRenewals: Int = DEFAULT_MAX_SESSION_RENEWALS,
        public val sessionRenewMillis: Long = DEFAULT_SESSION_RENEW_MILLIS,
        public val busyRetryMillis: Long = DEFAULT_BUSY_RETRY_MILLIS,
    ) {
        /**
         * The defaults: [LinkLossPolicy.Resume] with 2 restarts, 400, 5000, 1000, 1500, 2000 and
         * 4000 milliseconds, [CoreNfcPolling.AUTOMATIC], 2 session renewals, 10000 and 6000
         * milliseconds.
         */
        public constructor() : this(LinkLossPolicy.Resume(DEFAULT_RESTARTS))

        init {
            require(settleDelayMillis >= 0) { "settleDelayMillis must not be negative" }
            require(commandTimeoutMillis > 0) { "commandTimeoutMillis must be positive" }
            require(commandTimeoutBeforeAccessMillis > 0) { "commandTimeoutBeforeAccessMillis must be positive" }
            require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
            require(waitAfterSystemEndMillis >= 0) { "waitAfterSystemEndMillis must not be negative" }
            require(searchRestartMillis == SEARCH_RESTART_OFF || searchRestartMillis >= MIN_SEARCH_RESTART_MILLIS) {
                "searchRestartMillis must be 0 (off) or at least $MIN_SEARCH_RESTART_MILLIS"
            }
            require(maxSessionRenewals >= 0) { "maxSessionRenewals must not be negative" }
            require(sessionRenewMillis == SESSION_RENEW_OFF || sessionRenewMillis >= MIN_SESSION_RENEW_MILLIS) {
                "sessionRenewMillis must be 0 (off) or at least $MIN_SESSION_RENEW_MILLIS"
            }
            require(busyRetryMillis >= 0) { "busyRetryMillis must not be negative" }
        }

        /** The values, never document data. */
        override fun toString(): String =
            "CoreNfcReadSettings(linkLoss=$linkLoss, settleDelayMillis=$settleDelayMillis, " +
                "commandTimeoutMillis=$commandTimeoutMillis, readTimeoutMillis=$readTimeoutMillis, " +
                "commandTimeoutBeforeAccessMillis=$commandTimeoutBeforeAccessMillis, " +
                "waitAfterSystemEndMillis=$waitAfterSystemEndMillis, searchRestartMillis=$searchRestartMillis, " +
                "observerOn=$observerOn, polling=$polling, maxSessionRenewals=$maxSessionRenewals, " +
                "sessionRenewMillis=$sessionRenewMillis, busyRetryMillis=$busyRetryMillis)"

        /**
         * A builder that starts from these values, so one value can change without naming the
         * others.
         *
         * @since 1.0.0
         */
        public fun toBuilder(): Builder =
            Builder()
                .linkLoss(linkLoss)
                .settleDelayMillis(settleDelayMillis)
                .commandTimeoutMillis(commandTimeoutMillis)
                .waitAfterSystemEndMillis(waitAfterSystemEndMillis)
                .telemetry(telemetry)
                .observerOn(observerOn)
                .commandTimeoutBeforeAccessMillis(commandTimeoutBeforeAccessMillis)
                .readTimeoutMillis(readTimeoutMillis)
                .searchRestartMillis(searchRestartMillis)
                .polling(polling)
                .maxSessionRenewals(maxSessionRenewals)
                .sessionRenewMillis(sessionRenewMillis)
                .busyRetryMillis(busyRetryMillis)

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
         * Builds [CoreNfcReadSettings] step by step, with the constructor's defaults.
         *
         * @since 1.0.0
         */
        @ExperimentalTesseraChipApi
        public class Builder {
            private var linkLoss: LinkLossPolicy = LinkLossPolicy.Resume(DEFAULT_RESTARTS)
            private var settleDelayMillis: Long = DEFAULT_SETTLE_DELAY_MILLIS
            private var commandTimeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MILLIS
            private var waitAfterSystemEndMillis: Long = DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS
            private var telemetry: TelemetrySink? = null
            private var observerOn: ObserverThread = ObserverThread.MAIN
            private var commandTimeoutBeforeAccessMillis: Long = DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS
            private var readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS
            private var searchRestartMillis: Long = DEFAULT_SEARCH_RESTART_MILLIS
            private var polling: CoreNfcPolling = CoreNfcPolling.AUTOMATIC
            private var maxSessionRenewals: Int = DEFAULT_MAX_SESSION_RENEWALS
            private var sessionRenewMillis: Long = DEFAULT_SESSION_RENEW_MILLIS
            private var busyRetryMillis: Long = DEFAULT_BUSY_RETRY_MILLIS

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

            /** How often to ask the system to search again while no document was reported; [SEARCH_RESTART_OFF] for never. */
            public fun searchRestartMillis(millis: Long): Builder = apply { searchRestartMillis = millis }

            /** How long a new read waits after the system ended the previous session. */
            public fun waitAfterSystemEndMillis(millis: Long): Builder = apply { waitAfterSystemEndMillis = millis }

            /** Where the read's step events go; `null` uses the sink registered when the read starts. */
            public fun telemetry(sink: TelemetrySink?): Builder = apply { telemetry = sink }

            /** The thread the observer is called on. */
            public fun observerOn(thread: ObserverThread): Builder = apply { observerOn = thread }

            /** What the system session polls for. */
            public fun polling(choice: CoreNfcPolling): Builder = apply { polling = choice }

            /** How many times one read may open a new system session while no document was reported; [SESSION_RENEWALS_OFF] for never. */
            public fun maxSessionRenewals(count: Int): Builder = apply { maxSessionRenewals = count }

            /** How long a session may go with no document reported before the read opens a new one; [SESSION_RENEW_OFF] for never. */
            public fun sessionRenewMillis(millis: Long): Builder = apply { sessionRenewMillis = millis }

            /** How long the read keeps trying to begin a session the system refused as busy; [BUSY_RETRY_OFF] for not at all. */
            public fun busyRetryMillis(millis: Long): Builder = apply { busyRetryMillis = millis }

            /**
             * The settings.
             *
             * @throws IllegalArgumentException as the [CoreNfcReadSettings] constructor does.
             */
            @Throws(IllegalArgumentException::class)
            public fun build(): CoreNfcReadSettings =
                CoreNfcReadSettings(
                    linkLoss,
                    settleDelayMillis,
                    commandTimeoutMillis,
                    waitAfterSystemEndMillis,
                    telemetry,
                    observerOn,
                    commandTimeoutBeforeAccessMillis,
                    readTimeoutMillis,
                    searchRestartMillis,
                    polling,
                    maxSessionRenewals,
                    sessionRenewMillis,
                    busyRetryMillis,
                )
        }

        /** The default values. */
        public companion object {
            /** The restarts of the default [linkLoss]: 2. */
            public const val DEFAULT_RESTARTS: Int = 2

            /** The default [settleDelayMillis]: 400. */
            public const val DEFAULT_SETTLE_DELAY_MILLIS: Long = ChipReadCoordinator.DEFAULT_SETTLE_DELAY_MILLIS

            /** The default [commandTimeoutMillis]: 5000. */
            public const val DEFAULT_COMMAND_TIMEOUT_MILLIS: Long = 5_000

            /** The default [commandTimeoutBeforeAccessMillis]: 1500. */
            public const val DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS: Long =
                ChipReadCoordinator.DEFAULT_COMMAND_TIMEOUT_BEFORE_ACCESS_MILLIS

            /** The default [readTimeoutMillis]: 2000. */
            public const val DEFAULT_READ_TIMEOUT_MILLIS: Long = ChipReadCoordinator.DEFAULT_READ_TIMEOUT_MILLIS

            /** The default [waitAfterSystemEndMillis]: 1000. */
            public const val DEFAULT_WAIT_AFTER_SYSTEM_END_MILLIS: Long = 1_000

            /** The default [searchRestartMillis]: 4000. */
            public const val DEFAULT_SEARCH_RESTART_MILLIS: Long = 4_000

            /** The [searchRestartMillis] that turns new searches off: 0. */
            public const val SEARCH_RESTART_OFF: Long = 0

            /** The shortest [searchRestartMillis] other than [SEARCH_RESTART_OFF]: 2000. */
            public const val MIN_SEARCH_RESTART_MILLIS: Long = 2_000

            /** The default [maxSessionRenewals]: 2. */
            public const val DEFAULT_MAX_SESSION_RENEWALS: Int = 2

            /** The [maxSessionRenewals] that turns renewals off: 0. */
            public const val SESSION_RENEWALS_OFF: Int = 0

            /** The default [sessionRenewMillis]: 10000. */
            public const val DEFAULT_SESSION_RENEW_MILLIS: Long = 10_000

            /** The [sessionRenewMillis] that turns the renewal on a timer off: 0. */
            public const val SESSION_RENEW_OFF: Long = 0

            /** The shortest [sessionRenewMillis] other than [SESSION_RENEW_OFF]: 5000. */
            public const val MIN_SESSION_RENEW_MILLIS: Long = 5_000

            /** The default [busyRetryMillis]: 6000. */
            public const val DEFAULT_BUSY_RETRY_MILLIS: Long = 6_000

            /** The [busyRetryMillis] that turns the tries after a busy refusal off: 0. */
            public const val BUSY_RETRY_OFF: Long = 0
        }
    }
