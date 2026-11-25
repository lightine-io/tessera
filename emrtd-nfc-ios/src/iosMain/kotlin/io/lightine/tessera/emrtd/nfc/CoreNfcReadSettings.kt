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
 * (`NfcReadSettings.waitForDocumentMillis`): the system session's own 60-second limit and
 * [maxSessionRenewals] bound the wait for a document, and the system ends the read when it passes
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
 * @property maxSessionRenewals How many times one read may open a new system session after a
 *   dead one while no document was reported
 *   ([io.lightine.tessera.emrtd.ChipReadState.RenewingSession]). A dead session is one the system
 *   ends with Core NFC's code 202 ("ended unexpectedly"), or with code 203 ("busy") 2 seconds or
 *   more after its begin; also one the read ends itself after
 *   [sessionRenewMillis], when that is on. On an iPhone about one read in four, with the session
 *   started before the phone was on the document, never reported the document, and the system
 *   ended the session 22 to 36 seconds after it began, mostly with 202, once with 203 after a
 *   renewed session ran 18 seconds; a new session with the phone in place found the document
 *   within 3 seconds in 3 cases of 4. A renewal keeps the key, the attempt and the restarts after
 *   a lost link; the sheet closes and opens again. Never after a document was reported in the
 *   read, after the read's ending began, or after the person tapped Cancel (code 200) or the
 *   session's 60 seconds passed (code 201): those end the read as before. Default 1: one dead
 *   session and one renewed session; when the renewed one dies too, the read ends at once with
 *   [io.lightine.tessera.emrtd.ChipReadEnding.EndedBySystem] and
 *   [io.lightine.tessera.types.errors.NfcUnavailable.Reason.ENDED_BY_SYSTEM], with 0 attempts
 *   when the system never reported the document. On a device that took about 43 seconds; each
 *   session lasts as long as the system keeps it, so it can be longer.
 *   [SESSION_RENEWALS_OFF] (0) turns renewals off: the first dead session then ends the read
 *   that way. There is no such setting on Android, where the system has no such session.
 * @property sessionRenewMillis How long a system session may go with no document reported before
 *   the read ends it itself and opens a new one, counted from the session's begin, within
 *   [maxSessionRenewals]. Default [SESSION_RENEW_OFF] (0): off. On an iPhone a renewal at 10
 *   seconds did not shorten the wait and made it longer: the new session was ended by the system
 *   or refused for about 10 more seconds, and the document was found 24 to 51 seconds after the
 *   start, as if the system stayed tied to a tag it never reported for its full 20 seconds. The
 *   renewal after a dead session still happens with it off. Any other value must be at least
 *   [MIN_SESSION_RENEW_MILLIS] (5000): a shorter one would close the sheet before a person had
 *   time to bring the phone to the document.
 * @property busyRetryMillis How long the read keeps trying to begin a system session that the
 *   system refused as busy (Core NFC's code 203 within 2 seconds of the begin; a later 203 is a
 *   dead session, see [maxSessionRenewals]), counted
 *   from the first refusal: it tries again
 *   after 0.5, 1, 2, then every 3 seconds, the last try at this bound. On an iPhone the system may
 *   refuse a new session for a short time after one ended. It applies to the first session (the
 *   read stays in [io.lightine.tessera.emrtd.ChipReadState.Starting]; when the bound passes the read
 *   ends with [io.lightine.tessera.emrtd.ChipReadEnding.NotStarted] and
 *   [io.lightine.tessera.types.errors.NfcUnavailable.Reason.BUSY]) and to a renewed one (when the
 *   bound passes the read ends with [io.lightine.tessera.emrtd.ChipReadEnding.EndedBySystem] and
 *   that reason: the read had started). A refusal costs no renewal. Default 6000.
 *   [BUSY_RETRY_OFF] (0) turns it off: the first refusal ends the read.
 * @property tagTimeLimitMillis How long the read uses one connection to the document before it
 *   asks the system for a fresh one, counted from the moment the system reported the document.
 *   Engineers of Core NFC's vendor have stated a limit of 20 seconds per connection to a tag and
 *   60 per session: stated by the vendor's engineers, not in the API documentation. The fact the
 *   SDK relies on is what the read does. When this time has passed and the read is not finished,
 *   the read stops between two commands, never while a command is out; from 2 seconds before, it
 *   stops at the next file instead; before the access protocol, chip authentication or active
 *   authentication, it stops already when less than [commandTimeoutMillis] (at most half of this
 *   time) is left, so such a step is never cut in the middle. Then it asks the system to find the
 *   document again, opens the chip again with the same key (BAC or PACE runs again), checks that
 *   EF.SOD is the same byte for byte, and goes on from where it stopped, a cut-off data group from
 *   its byte offset. This uses no restart of [linkLoss] and is no new attempt; the observer gets
 *   no new state: the last state stays for the moment it takes, and the progress and the file
 *   count never go back. The read's telemetry sink gets an
 *   [io.lightine.tessera.emrtd.ChipReadEvent] with step
 *   [io.lightine.tessera.emrtd.ChipReadStep.TAG_HANDOVER]. At most 3 times per read, and never
 *   when less than 10 of the session's 60 seconds are left: the read then keeps the connection,
 *   as without this setting. When the document is not found again within 2 seconds, the lost-link
 *   rules of [linkLoss] apply: [io.lightine.tessera.emrtd.ChipReadState.LinkLost] and a restart
 *   used. Only with [io.lightine.tessera.emrtd.LinkLossPolicy.Resume]: with another policy a new
 *   connection would read everything again or end the read, so the read keeps one connection.
 *   Default 18000, 2 seconds before the stated limit. [TAG_TIME_LIMIT_OFF] (0) turns it off; any
 *   other value must be from [MIN_TAG_TIME_LIMIT_MILLIS] (10000) to 60000. There is no such
 *   setting on Android: Android documents no time limit on one connection to a tag.
 * @throws IllegalArgumentException when [commandTimeoutMillis], [readTimeoutMillis] or
 *   [commandTimeoutBeforeAccessMillis] is not positive, [settleDelayMillis],
 *   [waitAfterSystemEndMillis], [maxSessionRenewals] or [busyRetryMillis] is negative,
 *   [searchRestartMillis] is neither [SEARCH_RESTART_OFF] nor at least
 *   [MIN_SEARCH_RESTART_MILLIS], or [sessionRenewMillis] is neither [SESSION_RENEW_OFF] nor at
 *   least [MIN_SESSION_RENEW_MILLIS], or [tagTimeLimitMillis] is neither [TAG_TIME_LIMIT_OFF]
 *   nor from [MIN_TAG_TIME_LIMIT_MILLIS] to 60000.
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
        public val tagTimeLimitMillis: Long = DEFAULT_TAG_TIME_LIMIT_MILLIS,
    ) {
        /**
         * The defaults: [LinkLossPolicy.Resume] with 2 restarts, 400, 5000, 1000, 1500, 2000 and
         * 4000 milliseconds, [CoreNfcPolling.AUTOMATIC], 1 session renewal, no renewal on a timer
         * (0), 6000 and 18000 milliseconds.
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
            require(
                tagTimeLimitMillis == TAG_TIME_LIMIT_OFF || tagTimeLimitMillis in MIN_TAG_TIME_LIMIT_MILLIS..MAX_TAG_TIME_LIMIT_MILLIS,
            ) {
                "tagTimeLimitMillis must be 0 (off) or $MIN_TAG_TIME_LIMIT_MILLIS to $MAX_TAG_TIME_LIMIT_MILLIS"
            }
        }

        /** The values, never document data. */
        override fun toString(): String =
            "CoreNfcReadSettings(linkLoss=$linkLoss, settleDelayMillis=$settleDelayMillis, " +
                "commandTimeoutMillis=$commandTimeoutMillis, readTimeoutMillis=$readTimeoutMillis, " +
                "commandTimeoutBeforeAccessMillis=$commandTimeoutBeforeAccessMillis, " +
                "waitAfterSystemEndMillis=$waitAfterSystemEndMillis, searchRestartMillis=$searchRestartMillis, " +
                "observerOn=$observerOn, polling=$polling, maxSessionRenewals=$maxSessionRenewals, " +
                "sessionRenewMillis=$sessionRenewMillis, busyRetryMillis=$busyRetryMillis, tagTimeLimitMillis=$tagTimeLimitMillis)"

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
                .tagTimeLimitMillis(tagTimeLimitMillis)

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
            private var tagTimeLimitMillis: Long = DEFAULT_TAG_TIME_LIMIT_MILLIS

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

            /** How long the read uses one connection to the document before it asks for a fresh one; [TAG_TIME_LIMIT_OFF] for never. */
            public fun tagTimeLimitMillis(millis: Long): Builder = apply { tagTimeLimitMillis = millis }

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
                    tagTimeLimitMillis,
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

            /** The default [maxSessionRenewals]: 1. */
            public const val DEFAULT_MAX_SESSION_RENEWALS: Int = 1

            /** The [maxSessionRenewals] that turns renewals off: 0. */
            public const val SESSION_RENEWALS_OFF: Int = 0

            /** The [sessionRenewMillis] that turns the renewal on a timer off: 0. */
            public const val SESSION_RENEW_OFF: Long = 0

            /** The default [sessionRenewMillis]: [SESSION_RENEW_OFF] (0), off. */
            public const val DEFAULT_SESSION_RENEW_MILLIS: Long = SESSION_RENEW_OFF

            /** The shortest [sessionRenewMillis] other than [SESSION_RENEW_OFF]: 5000. */
            public const val MIN_SESSION_RENEW_MILLIS: Long = 5_000

            /** The default [busyRetryMillis]: 6000. */
            public const val DEFAULT_BUSY_RETRY_MILLIS: Long = 6_000

            /** The [busyRetryMillis] that turns the tries after a busy refusal off: 0. */
            public const val BUSY_RETRY_OFF: Long = 0

            /** The default [tagTimeLimitMillis]: 18000. */
            public const val DEFAULT_TAG_TIME_LIMIT_MILLIS: Long = 18_000

            /** The [tagTimeLimitMillis] that turns the planned handover off: 0. */
            public const val TAG_TIME_LIMIT_OFF: Long = 0

            /** The shortest [tagTimeLimitMillis] other than [TAG_TIME_LIMIT_OFF]: 10000. */
            public const val MIN_TAG_TIME_LIMIT_MILLIS: Long = 10_000

            // The longest: the session's own 60 seconds.
            private const val MAX_TAG_TIME_LIMIT_MILLIS: Long = 60_000
        }
    }
