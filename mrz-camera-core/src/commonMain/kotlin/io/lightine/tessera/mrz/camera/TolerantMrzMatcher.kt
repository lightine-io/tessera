package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.formats.MrvAFormatSpec
import io.lightine.tessera.mrz.formats.MrvBFormatSpec
import io.lightine.tessera.mrz.formats.Td1FormatSpec
import io.lightine.tessera.mrz.formats.Td2FormatSpec
import io.lightine.tessera.mrz.formats.Td3FormatSpec
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.ReadMethod
import kotlin.time.Instant

/**
 * Produces the candidate MRZ reconstructions for tolerant saved-image reading: it disambiguates the
 * genuinely ambiguous OCR glyphs in a recognized MRZ and surfaces every distinct, structurally-parseable
 * reconstruction with its own parse verdict (see [MrzCandidate]; [ADR-023]).
 *
 * **v1 scope — character disambiguation only.** The matcher works on a run of lines that already matches a
 * known MRZ shape (the same exact-shape line detection the strict analyse-frame core uses), and varies its
 * ambiguous glyphs. The harder half — **length-tolerant / collapsed-filler recovery** (ML Kit collapses long
 * `<` filler runs, so line 1 never reaches its nominal length — see TES-18) — is a deferred refinement and
 * is NOT attempted here: a frame whose lines do not reach a known shape yields no candidates, exactly as
 * strict yields no decode. The refinement is tracked separately.
 */
internal class TolerantMrzMatcher(
    private val mode: ParsingMode,
    private val referenceTimeProvider: () -> Instant,
) {
    /** All distinct, parseable reconstructions of the MRZ in [text]; empty when there is no shape-matched run or nothing ambiguous to resolve. */
    fun candidates(text: RecognizedText): List<MrzCandidate> {
        val run = findShapeRun(text.lines.map { normalizeLine(it.text) }) ?: return emptyList()

        val ambiguous =
            buildList {
                run.forEachIndexed { lineIndex, line ->
                    line.forEachIndexed { columnIndex, glyph ->
                        if (glyph in CONFUSABLES) add(AmbiguousPosition(lineIndex, columnIndex, glyph))
                    }
                }
            }
        if (ambiguous.isEmpty()) return emptyList() // nothing to disambiguate; the strict read already has the answer

        // Bound enumeration cost: vary at most MAX_VARIED_POSITIONS glyphs (<= 2^N reconstructions); any
        // excess ambiguous positions stay as-recognized. NOTE: positions are taken in reading order (line then
        // column), so on a real document the budget can be spent on the name line and leave the data line's
        // check-digit-bearing glyphs unexplored — field-aware selection is a tracked refinement (see the
        // MrzCandidate "bounded enumeration" note). Surfaced count stays small because structurally implausible
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

    private fun normalizeLine(raw: String): String =
        when (mode) {
            ParsingMode.STRICT -> raw.trim().uppercase()
            ParsingMode.LENIENT -> raw.filterNot(Char::isWhitespace).uppercase()
        }

    // First run of consecutive lines whose (count, length) matches a known ICAO shape — the strict core's rule.
    private fun findShapeRun(normalized: List<String>): List<String>? {
        var start = 0
        while (start < normalized.size) {
            val length = normalized[start].length
            var end = start + 1
            while (end < normalized.size && normalized[end].length == length) end++
            if (length > 0 && MrzLineShape(end - start, length) in MRZ_SHAPES) {
                return normalized.subList(start, end).toList()
            }
            start = end
        }
        return null
    }

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

    private data class MrzLineShape(
        val lineCount: Int,
        val lineLength: Int,
    )

    private companion object {
        // <= 2^6 = 64 reconstructions parsed per frame; the check-digit-consistency filter + cap below keep the
        // SURFACED set small and usable even when many reconstructions parse.
        private const val MAX_VARIED_POSITIONS = 6

        // Upper bound on candidates surfaced to "Choose the reading", so the screen stays usable. Genuine glyph
        // ambiguity resolves a couple of ways; more than a handful means OCR noise, not real alternatives.
        private const val MAX_SURFACED_CANDIDATES = 8

        private val MRZ_SHAPES: Set<MrzLineShape> =
            listOf(Td1FormatSpec, Td2FormatSpec, Td3FormatSpec, MrvAFormatSpec, MrvBFormatSpec)
                .map { MrzLineShape(it.lineCount, it.lineLength) }
                .toSet()

        // Conservative, bidirectional OCR glyph confusions within the MRZ alphabet (A–Z, 0–9, <).
        private val CONFUSABLES: Map<Char, List<Char>> =
            buildMap {
                listOf('0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '8' to 'B', '6' to 'G').forEach { (a, b) ->
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
