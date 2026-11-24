package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadProgress
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.types.ExperimentalTesseraChipApi

/**
 * The texts a [CoreNfcChipRead] shows on the system NFC sheet. The sheet is the system's, so this
 * text is the one thing your app says while the sheet is up.
 *
 * Pass nothing to get [CoreNfcDefaultSheetTexts], short English lines. An app in another language,
 * or with its own wording, implements this interface; [CoreNfcDefaultSheetTexts.progressBar] gives
 * the same character progress bar for its own texts.
 *
 * Both functions are called on the read's own thread: return at once, and never block. A function
 * that throws gets the default text instead.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public interface CoreNfcSheetTexts {
    /**
     * The sheet's text for [update]. Called on every update; the SDK sets the sheet's text only
     * when it changes, and at most every 100 milliseconds (the latest text always gets shown).
     *
     * @param update Where the read is, how far it got, and which attempt it is.
     * @return The text, in your app's language.
     */
    public fun textFor(update: ChipReadUpdate): String

    /**
     * The sheet's last text, for [ending]. For [ChipReadEnding.Complete] the sheet shows it and
     * closes with success; for every other ending the sheet shows it as the error message and
     * closes. Not shown when the person or the system closed the sheet, or when no sheet was shown
     * (the read did not start).
     *
     * @param ending How the read ended.
     * @return The text, in your app's language.
     */
    public fun textForEnding(ending: ChipReadEnding): String
}

/**
 * The built-in sheet texts: one short English line per state, a character progress bar while the
 * chip is read, and a short line per ending. Used when your app passes no texts.
 *
 * The lines are plain English strings in code. An app in another language supplies its own
 * [CoreNfcSheetTexts], and can reuse [progressBar].
 *
 * Experimental: see [ExperimentalTesseraChipApi]. In Swift: `CoreNfcDefaultSheetTexts.shared`.
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public object CoreNfcDefaultSheetTexts : CoreNfcSheetTexts {
    /** How long the reader waits before the waiting text changes to a hint to move the phone. */
    public const val MOVE_HINT_AFTER_MILLIS: Long = 8_000

    /** The number of characters in the default [progressBar]. */
    public const val BAR_WIDTH: Int = 10

    /**
     * A line per state:
     * - Starting: "Getting ready".
     * - Waiting for the document: "Hold your iPhone near the document"; after
     *   [MOVE_HINT_AFTER_MILLIS], "Move the phone slowly along the document".
     * - Document found: "Found. Hold still."
     * - Connecting: "Connecting. Hold still."
     * - Opening access, reading, checking with the chip, checking the files: a line, then the
     *   [progressBar] on the next line.
     * - Link lost: "Connection lost. Hold the document again." with the attempt the read now
     *   waits for, for example "Try 2 of 3".
     * - Stopping: "Stopping".
     */
    override fun textFor(update: ChipReadUpdate): String {
        val bar by lazy { progressBar(update.progress) }
        return when (val state = update.state) {
            ChipReadState.Starting -> {
                "Getting ready"
            }

            is ChipReadState.WaitingForDocument -> {
                if (state.waitedMillis >= MOVE_HINT_AFTER_MILLIS) {
                    "Move the phone slowly along the document"
                } else {
                    "Hold your iPhone near the document"
                }
            }

            ChipReadState.DocumentFound -> {
                "Found. Hold still."
            }

            ChipReadState.Connecting -> {
                "Connecting. Hold still."
            }

            is ChipReadState.OpeningAccess -> {
                "Opening the chip. Hold still.\n$bar"
            }

            is ChipReadState.ReadingFile -> {
                val what = if (state.file.dataGroup == 2) "the photo" else "the document"
                "Reading $what. Hold still.\n$bar"
            }

            is ChipReadState.AuthenticatingChip -> {
                "Checking the chip. Hold still.\n$bar"
            }

            ChipReadState.CheckingFiles -> {
                "Checking the data\n$bar"
            }

            is ChipReadState.LinkLost -> {
                // The attempt the read waits for: the restarts left count it.
                val next = update.maxAttempts - state.restartsLeft + 1
                "Connection lost. Hold the document again.\nTry $next of ${update.maxAttempts}"
            }

            ChipReadState.Stopping -> {
                "Stopping"
            }

            else -> {
                "Reading. Hold still.\n$bar"
            }
        }
    }

    /** A short line per ending, for example "Done" or "Reading failed. Try again." */
    override fun textForEnding(ending: ChipReadEnding): String =
        when (ending) {
            is ChipReadEnding.Complete -> "Done"
            is ChipReadEnding.Partial -> "Not everything could be read. Try again."
            is ChipReadEnding.Failed -> "Reading failed. Try again."
            is ChipReadEnding.KeyNotAccepted -> "The chip did not accept the details. Check them and try again."
            is ChipReadEnding.KeyRejected -> "The document details do not add up. Check them."
            is ChipReadEnding.Cancelled -> "Cancelled"
            is ChipReadEnding.EndedBySystem -> "Reading stopped. Try again."
            is ChipReadEnding.NotStarted -> "NFC reading is not available"
            else -> "Reading ended"
        }

    /**
     * A progress bar in characters, [BAR_WIDTH] wide: filled blocks for the work done, empty
     * blocks for the rest, then the percent, for example `▓▓▓▓░░░░░░ 45%`. It never goes back,
     * because the progress never does.
     *
     * @param progress The read's progress.
     * @return The bar and the percent.
     */
    public fun progressBar(progress: ChipReadProgress): String = progressBar(progress, BAR_WIDTH)

    /**
     * A progress bar in characters, [width] wide; see the one-argument form.
     *
     * @param progress The read's progress.
     * @param width How many characters the bar has, 1 to 40.
     * @return The bar and the percent.
     * @throws IllegalArgumentException when [width] is outside 1 to 40.
     */
    @Throws(IllegalArgumentException::class)
    public fun progressBar(
        progress: ChipReadProgress,
        width: Int,
    ): String {
        require(width in 1..MAX_BAR_WIDTH) { "width must be 1 to $MAX_BAR_WIDTH" }
        val filled = (progress.fraction.coerceIn(0.0, 1.0) * width).toInt().coerceIn(0, width)
        return FILLED.repeat(filled) + EMPTY.repeat(width - filled) + " " + progress.percent + "%"
    }

    private const val MAX_BAR_WIDTH = 40
    private const val FILLED = "▓"
    private const val EMPTY = "░"
}
