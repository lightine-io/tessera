package io.lightine.tessera.mrz.generation

import io.lightine.tessera.mrz.Specimens
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class MrzGeneratorPolymorphicTest {
    private val ref2026 = Instant.parse("2026-05-04T12:00:00Z")

    // One of the project's specimens per format. The polymorphic `MrzGenerator.generate(MrzDocument)` overload
    // must dispatch to the right per-format path for each variant — the sealed-`when` in the
    // dispatcher is the same shape the validator and parsers already use.
    private val td1Lines = Specimens.TD1
    private val td2Lines = Specimens.TD2
    private val td3Lines = Specimens.TD3
    private val mrvALines = Specimens.MRV_A
    private val mrvBLines = Specimens.MRV_B

    @Test
    fun polymorphic_generate_round_trips_every_format() {
        val cases = listOf(td1Lines, td2Lines, td3Lines, mrvALines, mrvBLines)
        for (lines in cases) {
            val parsed = assertIs<ParseResult.Success>(MrzParser.parse(lines, referenceTime = ref2026))
            val document: MrzDocument = parsed.document

            // Dispatch through the polymorphic overload, NOT the format-specific one.
            val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(document))
            assertEquals(lines, regenerated.mrz, "Polymorphic dispatch must round-trip ${lines.size}x${lines.first().length}")
        }
    }

    @Test
    fun polymorphic_generate_can_pair_with_auto_detect_parser_for_end_to_end_round_trip() {
        // The friendly default API: consumer holds a `MrzDocument` from `MrzParser.parse`
        // (auto-detect) and round-trips through `MrzGenerator.generate(MrzDocument)`. No
        // narrowing required at the call site.
        for (lines in listOf(td1Lines, td2Lines, td3Lines, mrvALines, mrvBLines)) {
            val asString = lines.joinToString("\n")
            val parsed = assertIs<ParseResult.Success>(MrzParser.parse(asString, referenceTime = ref2026))
            val regenerated = assertIs<GenerationResult.Success>(MrzGenerator.generate(parsed.document))
            assertEquals(lines, regenerated.mrz)
        }
    }
}
