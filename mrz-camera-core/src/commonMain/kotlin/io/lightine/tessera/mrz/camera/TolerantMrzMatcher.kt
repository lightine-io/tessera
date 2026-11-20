package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.formats.FieldSpec
import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.MrzFormatSpec
import io.lightine.tessera.mrz.formats.MrzFormatSpecWithComposite
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.formats.extractCharFrom
import io.lightine.tessera.mrz.formats.extractFrom
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.time.Instant

/**
 * Produces the candidate MRZ reconstructions for tolerant saved-image reading: it disambiguates the
 * genuinely ambiguous OCR glyphs in a recognized MRZ and surfaces every distinct, structurally-parseable
 * reconstruction with its own parse verdict (see [MrzCandidate]; [ADR-023]).
 *
 * **v1 scope - character disambiguation only.** The matcher works on a window of lines that already matches a
 * known MRZ shape, found via [MrzLineDetection] - the SAME windowed extraction, MRZ-alphabet guard, and
 * chevron-glyph recovery the strict analyse-frame core uses (shared, not restated, since TES-117), and then
 * varies its ambiguous glyphs in check-digit-covered fields only (TES-265). The harder half - **length-tolerant /
 * collapsed-filler recovery** (ML Kit collapses long `<` filler runs, so line 1 never reaches its nominal length -
 * see TES-18) - is a deferred
 * refinement and is NOT attempted here: a frame whose lines do not reach a known shape yields no candidates,
 * exactly as strict yields no decode. The refinement is tracked separately.
 */
internal class TolerantMrzMatcher(
    private val mode: ParsingMode,
    private val referenceTimeProvider: () -> Instant,
) {
    /** All distinct, parseable reconstructions of the MRZ in [text]; empty when there is no shape-matched run or nothing ambiguous to resolve. */
    fun candidates(text: RecognizedText): List<MrzCandidate> {
        val normalized = text.lines.map { MrzLineDetection.normalizeLine(it.text, mode) }
        val run = MrzLineDetection.findMrzWindow(normalized) ?: return emptyList()

        // Only glyphs in a field a check digit covers are ever varied: a change anywhere else (document code,
        // issuing state, name, nationality, sex, unchecked optional data) can never be confirmed, so it would be a
        // guess, not a reading. Fields whose check digit fails as recognized get the budget first, since only they
        // can need a fix; then the passing ones, each in reading order.
        val ambiguous =
            checkedFieldsOf(run)
                .sortedBy { it.passes } // stable: failing fields first, otherwise reading order
                .flatMap { field -> field.positions.filter { (line, column) -> run[line][column] in CONFUSABLES } }
                .distinct()
                .map { (line, column) -> AmbiguousPosition(line, column, run[line][column]) }
        if (ambiguous.isEmpty()) return emptyList() // nothing to disambiguate; the strict read already has the answer

        // Bound enumeration cost: vary at most MAX_VARIED_POSITIONS glyphs (<= 2^N reconstructions); any excess
        // ambiguous positions stay as-recognized. Surfaced count stays small because structurally implausible
        // reconstructions (a letter in a numeric field, etc.) drop out below.
        val varied = ambiguous.take(MAX_VARIED_POSITIONS)
        val distinct = LinkedHashMap<List<String>, MrzCandidate>()
        cartesian(varied.map { listOf(it.recognized) + CONFUSABLES.getValue(it.recognized) }) { choice ->
            val lineChars = run.map(String::toCharArray)
            val disambiguations = mutableListOf<CharacterDisambiguation>()
            varied.forEachIndexed { k, position ->
                val resolved = choice[k]
                lineChars[position.lineIndex][position.columnIndex] = resolved
                if (resolved != position.recognized) {
                    disambiguations += CharacterDisambiguation(position.lineIndex, position.columnIndex, position.recognized, resolved)
                }
            }
            val mrzLines = lineChars.map(CharArray::concatToString)
            if (mrzLines !in distinct) {
                val parse = MrzParser.parse(mrzLines, referenceTimeProvider()).preCaptured()
                // Drop structurally-unparseable reconstructions (not plausible readings); keep all that parse,
                // including check-digit-failing ones (those are PartialSuccess).
                if (parse !is ParseResult.Failure) {
                    distinct[mrzLines] = MrzCandidate(mrzLines, parse, disambiguations.toList())
                }
            }
        }

        // Surface the genuinely plausible readings, bounded so "Choose the reading" stays usable (ADR-023
        // refinement). Enumerating N ambiguous glyphs yields up to 2^N structurally-parseable reconstructions,
        // most of which differ only in a NON-check-protected field (e.g. a name glyph) and so break the MRZ's
        // check digits — those are OCR noise, not real alternative readings. So: when any reconstruction is
        // check-digit-consistent (a full Success), surface only those; the check-digit self-consistency is what
        // makes a reconstruction plausible, not the SDK judging the document (Principle 1 — the user still picks
        // among the surfaced readings, each with its own verdict). If none are consistent (a genuinely noisy or
        // misprinted MRZ), fall back to every parseable reading. Either way cap the count.
        val all = distinct.values.toList()
        val consistent = all.filter { it.parse is ParseResult.Success }
        return consistent.ifEmpty { all }.take(MAX_SURFACED_CANDIDATES)
    }

    // The check-digit-covered fields of [run], each with its own check digit and whether that digit passes as
    // recognized. The format follows MrzParser's own choice: line count and width, and a leading `V` for a visa.
    // Document code is never varied, so the choice is the same for every reconstruction.
    private fun checkedFieldsOf(run: List<String>): List<CheckedField> {
        val visa = run[0].startsWith('V')
        val spec: MrzFormatSpec =
            when {
                run.size == Td1FormatSpec.lineCount -> Td1FormatSpec
                run[0].length == Td2FormatSpec.lineLength -> if (visa) MrvBFormatSpec else Td2FormatSpec
                else -> if (visa) MrvAFormatSpec else Td3FormatSpec
            }

        fun checked(
            field: FieldSpec,
            checkDigit: FieldSpec,
        ) = CheckedField(
            positions = (field.positionsIn() + checkDigit.positionsIn()),
            passes = computeCheckDigit(field.extractFrom(run)) == checkDigit.extractCharFrom(run),
        )

        return buildList {
            add(checked(spec.documentNumber, spec.documentNumberCheckDigit))
            add(checked(spec.dateOfBirth, spec.dateOfBirthCheckDigit))
            add(checked(spec.dateOfExpiry, spec.dateOfExpiryCheckDigit))
            if (spec is Td3FormatSpec) add(checked(spec.personalNumber, spec.personalNumberCheckDigit))
            if (spec is MrzFormatSpecWithComposite) {
                add(
                    CheckedField(
                        positions = spec.compositeInputFields.flatMap { it.positionsIn() } + spec.compositeCheckDigit.positionsIn(),
                        passes =
                            computeCheckDigit(spec.compositeInputFields.joinToString("") { it.extractFrom(run) }) ==
                                spec.compositeCheckDigit.extractCharFrom(run),
                    ),
                )
            }
        }
    }

    private fun FieldSpec.positionsIn(): List<Pair<Int, Int>> = (startInLine until endInLineExclusive).map { line to it }

    private class CheckedField(
        val positions: List<Pair<Int, Int>>,
        val passes: Boolean,
    )

    // A candidate is a saved-image read, so stamp PRE_CAPTURED_IMAGE (the parser alone reports BACKEND_STRING_INPUT).
    private fun ParseResult.preCaptured(): ParseResult {
        val stamped = metadata.copy(readMethod = ReadMethod.PRE_CAPTURED_IMAGE)
        return when (this) {
            is ParseResult.Success -> copy(metadata = stamped)
            is ParseResult.PartialSuccess -> copy(metadata = stamped)
            is ParseResult.Failure -> copy(metadata = stamped)
        }
    }

    private data class AmbiguousPosition(
        val lineIndex: Int,
        val columnIndex: Int,
        val recognized: Char,
    )

    private companion object {
        // <= 2^6 = 64 reconstructions parsed per frame; the check-digit-consistency filter + cap below keep the
        // SURFACED set small and usable even when many reconstructions parse.
        private const val MAX_VARIED_POSITIONS = 6

        // Upper bound on candidates surfaced to "Choose the reading", so the screen stays usable. Genuine glyph
        // ambiguity resolves a couple of ways; more than a handful means OCR noise, not real alternatives.
        private const val MAX_SURFACED_CANDIDATES = 8

        // Conservative, bidirectional OCR glyph confusions within the MRZ alphabet (A–Z, 0–9, <), kept only when a
        // check digit can tell the two apart. Check-digit weights (7, 3, 1) are coprime with 10, so a swap changes
        // the digit unless the character values differ by a multiple of 10: `6` (6) and `G` (16) never do, so a
        // 6/G guess could never be confirmed and is not tried (TES-265).
        private val CONFUSABLES: Map<Char, List<Char>> =
            buildMap {
                listOf('0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '8' to 'B', '6' to 'G')
                    .filter { (a, b) -> computeCheckDigit(a.toString()) != computeCheckDigit(b.toString()) }
                    .forEach { (a, b) ->
                        put(a, listOf(b))
                        put(b, listOf(a))
                    }
            }

        private fun cartesian(
            options: List<List<Char>>,
            action: (List<Char>) -> Unit,
        ) {
            if (options.isEmpty()) {
                action(emptyList())
                return
            }
            val current = CharArray(options.size)

            fun recurse(index: Int) {
                if (index == options.size) {
                    action(current.toList())
                    return
                }
                for (glyph in options[index]) {
                    current[index] = glyph
                    recurse(index + 1)
                }
            }
            recurse(0)
        }
    }
}
