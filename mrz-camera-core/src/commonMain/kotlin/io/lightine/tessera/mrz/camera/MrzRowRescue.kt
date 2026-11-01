package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import kotlin.math.ceil

/**
 * The pure rules both platform recognizers use to read a short MRZ row again (mrz-camera's ML Kit
 * recognizer and mrz-camera-ios's Vision saved-image recognizer), kept here so both platforms follow ONE
 * rule and it is tested once, in this module's commonTest. It is public only so those modules can call it,
 * and marked [InternalTesseraApi]: not consumer API, left out of the ABI dumps, hidden from Swift.
 *
 * **The problem.** OCR engines collapse long runs of the `<` filler, so an MRZ row comes back shorter than
 * its fixed ICAO width and the exact-width shape match downstream never fires. Typical case: TD3 line 1
 * (mostly filler) reads short, while line 2 (mostly characters) reads at exactly 44.
 *
 * **What the rescue does - selection, never invention.** A row that looks like MRZ text but is shorter than
 * an exact-width neighbour row is cropped again across the neighbour's full width and read again. Only a
 * re-read of exact width is kept (an exact multiple is split into its rows); a short re-read is dropped.
 * Nothing is padded, guessed, or corrected: every kept line is a string the recognizer actually produced,
 * and the parser's check digits remain the judge of it.
 */
@InternalTesseraApi
public object MrzRowRescue {
    /** ICAO Doc 9303 MRZ line widths (30, 36, 44), from mrz-core's public format specs, ascending. */
    internal val MRZ_LINE_WIDTHS: List<Int> =
        listOf(Td1FormatSpec, Td2FormatSpec, Td3FormatSpec, MrvAFormatSpec, MrvBFormatSpec)
            .map { it.lineLength }
            .distinct()
            .sorted()

    // How many concatenated rows a single over-merged read may be split into - the deepest ICAO shape has
    // 3 lines, so a longer multiple is noise, not an MRZ block.
    private const val MAX_SPLIT_ROWS: Int = 3

    // A row qualifies as an MRZ candidate when at least this fraction of its characters are MRZ-alphabet
    // (A-Z, 0-9, `<`) - real MRZ rows are 100% but OCR noise bleeds a little - and it contains at least one
    // filler chevron (every MRZ line format has filler somewhere; VIZ text lines have none).
    private const val MRZ_CHARSET_FRACTION: Double = 0.8

    // Minimum stripped length for candidacy - shorter runs (dates, document numbers in the VIZ) match the
    // charset but are not MRZ rows.
    private const val MIN_CANDIDATE_LENGTH: Int = 10

    // Re-read crop paddings. Horizontal: a fraction of the neighbour row's width on each side. Vertical: a
    // fraction of the short row's height above and below. Emulator-verified on the ICAO TD3 specimen.
    internal const val HORIZONTAL_PAD_FRACTION: Double = 0.03
    internal const val VERTICAL_PAD_FRACTION: Double = 0.6

    // Upscaling of the re-read crop. Google's ML Kit guidance asks for at least 16x16 px per character; the
    // row box is about one character tall, so the crop is scaled until the row reaches that height, never
    // less than 2x (the emulator-verified default) and never more than 4x.
    internal const val MIN_CHARACTER_PX: Double = 16.0
    internal const val DEFAULT_SCALE: Int = 2
    internal const val MAX_SCALE: Int = 4

    // The scaled crop's longest side is capped so a large photo cannot make a huge bitmap.
    internal const val MAX_SCALED_SIDE_PX: Double = 4096.0

    /** Whether a row's joined, whitespace-stripped text marks it as a candidate MRZ row. */
    public fun isMrzCandidate(stripped: String): Boolean {
        if (stripped.length < MIN_CANDIDATE_LENGTH) return false
        if ('<' !in stripped) return false
        val mrzChars = stripped.count(::isMrzChar)
        return mrzChars.toDouble() / stripped.length >= MRZ_CHARSET_FRACTION
    }

    /**
     * The lines kept from one re-read: an exact-width read is kept, and a read whose stripped length is an
     * exact multiple (2..[MAX_SPLIT_ROWS]) of one width is split into its rows in the read's own order.
     * Anything else yields nothing. Nothing is added or dropped inside a kept read.
     */
    public fun rescuedLines(stripped: String): List<String> {
        for (width in MRZ_LINE_WIDTHS) {
            if (stripped.length == width) return listOf(stripped)
        }
        for (width in MRZ_LINE_WIDTHS) {
            if (stripped.length % width == 0) {
                val rows = stripped.length / width
                if (rows in 2..MAX_SPLIT_ROWS) return stripped.chunked(width)
            }
        }
        return emptyList()
    }

    /** Folds case and maps chevron look-alikes to `<`, one for one (the length never changes). */
    internal fun recoverGlyphs(stripped: String): String =
        stripped
            .uppercase()
            .map { if (it in MrzLineDetection.MRZ_CHEVRON_GLYPHS) '<' else it }
            .joinToString("")

    /** Whether [stripped] is an exact-width MRZ line: an ICAO width and only MRZ characters after glyph recovery. */
    internal fun isExactMrzLine(stripped: String): Boolean {
        val recovered = recoverGlyphs(stripped)
        return recovered.length in MRZ_LINE_WIDTHS && recovered.all(::isMrzChar)
    }

    /** Whether [stripped] looks like MRZ text (after glyph recovery) but is shorter than [width]. */
    internal fun isNearMiss(
        stripped: String,
        width: Int,
    ): Boolean = stripped.length < width && isMrzCandidate(recoverGlyphs(stripped))

    private fun isMrzChar(c: Char): Boolean = c in 'A'..'Z' || c in '0'..'9' || c == '<'

    /** An axis-aligned rectangle, top-left origin, in whatever pixel space the caller documents. */
    @InternalTesseraApi
    public data class Box(
        public val left: Double,
        public val top: Double,
        public val right: Double,
        public val bottom: Double,
    ) {
        /** The box's horizontal extent. */
        public val width: Double get() = right - left

        /** The box's vertical extent. */
        public val height: Double get() = bottom - top

        /** The vertical centre of the box. */
        public val centerY: Double get() = (top + bottom) / 2.0

        /** The smallest box that holds both this box and [other]. */
        public fun union(other: Box): Box =
            Box(minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom))

        /** Whether this box and [other] overlap vertically by at least half the smaller height. */
        public fun sharesRowWith(other: Box): Boolean {
            val overlap = minOf(bottom, other.bottom) - maxOf(top, other.top)
            return overlap > 0.0 && overlap >= 0.5 * minOf(height, other.height)
        }
    }

    /**
     * One recognized line with its box, as the recognizer reported it.
     *
     * @property text The line's text, verbatim.
     * @property confidence The recognizer's confidence for the line, or null when it gives none.
     * @property box Where the line sits in the image, in pixels.
     */
    @InternalTesseraApi
    public data class BoxedLine(
        public val text: String,
        public val confidence: Float?,
        public val box: Box,
    )

    /**
     * Groups [lines] into visual rows (fragments overlap vertically by at least half the smaller height),
     * top to bottom; each row lists indices into [lines], left to right.
     */
    internal fun groupRows(lines: List<BoxedLine>): List<List<Int>> {
        val rows = mutableListOf<MutableList<Int>>()
        for (index in lines.indices.sortedBy { lines[it].box.centerY }) {
            val row = rows.lastOrNull()
            if (row != null && row.any { lines[it].box.sharesRowWith(lines[index].box) }) row.add(index) else rows.add(mutableListOf(index))
        }
        return rows.map { row -> row.sortedBy { lines[it].box.left } }
    }

    /** A row's fragments joined left to right with whitespace removed; nothing inserted between them. */
    internal fun rowText(
        lines: List<BoxedLine>,
        row: List<Int>,
    ): String = row.joinToString("") { lines[it].text }.filterNot(Char::isWhitespace)

    private fun rowBox(
        lines: List<BoxedLine>,
        row: List<Int>,
    ): Box = row.map { lines[it].box }.reduce(Box::union)

    /**
     * One planned re-read of a short row.
     *
     * @property row The short row, as indices into the recognized lines.
     * @property rowBox The union of the row's fragment boxes.
     * @property crop The area to read again, in the same pixel space.
     * @property width The character count of the exact-width neighbouring row; a re-read is kept only at this length.
     */
    @InternalTesseraApi
    public data class RereadPlan(
        public val row: List<Int>,
        public val rowBox: Box,
        public val crop: Box,
        public val width: Int,
    )

    /**
     * Plans a re-read for every near-miss row that has an exact-width MRZ neighbour row directly above or
     * below (the one above is preferred when both are). The crop spans the neighbour's horizontal extent
     * plus [HORIZONTAL_PAD_FRACTION] of its width on each side, and the short row's vertical extent plus
     * [VERTICAL_PAD_FRACTION] of its height above and below, clamped to the image. A row already of exact
     * width is never planned: re-reading it can only make it worse.
     */
    public fun planRereads(
        lines: List<BoxedLine>,
        imageWidth: Double,
        imageHeight: Double,
    ): List<RereadPlan> {
        val rows = groupRows(lines)
        val texts = rows.map { rowText(lines, it) }
        val plans = mutableListOf<RereadPlan>()
        for ((i, row) in rows.withIndex()) {
            val neighbour =
                listOf(i - 1, i + 1)
                    .filter { it in rows.indices && isExactMrzLine(texts[it]) }
                    .firstOrNull { isNearMiss(texts[i], texts[it].length) }
                    ?: continue
            val shortBox = rowBox(lines, row)
            val neighbourBox = rowBox(lines, rows[neighbour])
            val padX = neighbourBox.width * HORIZONTAL_PAD_FRACTION
            val padY = shortBox.height * VERTICAL_PAD_FRACTION
            val crop =
                Box(
                    left = (neighbourBox.left - padX).coerceIn(0.0, imageWidth),
                    top = (shortBox.top - padY).coerceIn(0.0, imageHeight),
                    right = (neighbourBox.right + padX).coerceIn(0.0, imageWidth),
                    bottom = (shortBox.bottom + padY).coerceIn(0.0, imageHeight),
                )
            if (crop.width <= 0.0 || crop.height <= 0.0) continue
            plans.add(RereadPlan(row, shortBox, crop, texts[neighbour].length))
        }
        return plans
    }

    /**
     * The integer upscale for a re-read crop: enough for the row (about one character tall) to reach
     * [MIN_CHARACTER_PX], at least [DEFAULT_SCALE], at most [MAX_SCALE], and lowered (to no less than 1) so
     * the scaled crop's longest side stays within [MAX_SCALED_SIDE_PX].
     */
    public fun rereadScale(
        rowHeight: Double,
        cropLongestSide: Double,
    ): Int {
        val needed = if (rowHeight > 0.0) ceil(MIN_CHARACTER_PX / rowHeight).toInt() else MAX_SCALE
        val wanted = needed.coerceIn(DEFAULT_SCALE, MAX_SCALE)
        val fits = if (cropLongestSide > 0.0) (MAX_SCALED_SIDE_PX / cropLongestSide).toInt() else wanted
        return minOf(wanted, fits).coerceAtLeast(1)
    }

    /** Maps a box from a re-read crop (scaled by [scale], cut at [crop]'s corner) back to the image's space. */
    public fun fromCropSpace(
        box: Box,
        crop: Box,
        scale: Int,
    ): Box =
        Box(
            left = crop.left + box.left / scale,
            top = crop.top + box.top / scale,
            right = crop.left + box.right / scale,
            bottom = crop.top + box.bottom / scale,
        )

    /**
     * The lines kept from a re-read for [plan]. [reread] boxes must already be in the image's space (see
     * [fromCropSpace]). Only re-read rows whose vertical centre lies inside the short row's own extent count
     * (the padded crop can catch a sliver of the neighbour); each such row's joined text goes through
     * [rescuedLines], and only lines of the neighbour's width are kept. Confidence is the lowest of the
     * row's fragments.
     */
    public fun keptLines(
        reread: List<BoxedLine>,
        plan: RereadPlan,
    ): List<RecognizedLine> {
        val kept = mutableListOf<RecognizedLine>()
        for (row in groupRows(reread)) {
            val centre = row.map { reread[it].box }.reduce(Box::union).centerY
            if (centre < plan.rowBox.top || centre > plan.rowBox.bottom) continue
            val confidence = row.mapNotNull { reread[it].confidence }.minOrNull()
            for (text in rescuedLines(rowText(reread, row))) {
                if (text.length == plan.width) kept.add(RecognizedLine(text = text, confidence = confidence))
            }
        }
        return kept
    }

    /**
     * Puts each rescued row's lines in place of that row's fragments: the replacement goes where the row's
     * first fragment was, its other fragments are dropped, every other item stays as it was. A row with no
     * replacement (or an empty one) is left untouched.
     */
    public fun <T> splice(
        original: List<T>,
        replacements: Map<List<Int>, List<T>>,
    ): List<T> {
        val active = replacements.filterValues { it.isNotEmpty() }
        if (active.isEmpty()) return original
        val dropped = active.keys.flatten().toSet()
        val insertAt = active.entries.associate { (row, lines) -> row.min() to lines }
        val out = mutableListOf<T>()
        for ((index, item) in original.withIndex()) {
            insertAt[index]?.let(out::addAll)
            if (index !in dropped) out.add(item)
        }
        return out
    }
}
