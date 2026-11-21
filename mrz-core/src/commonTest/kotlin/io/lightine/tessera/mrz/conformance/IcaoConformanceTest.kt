package io.lightine.tessera.mrz.conformance

import io.lightine.tessera.mrz.checkdigit.computeCheckDigit
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.model.MrvA
import io.lightine.tessera.mrz.model.MrvB
import io.lightine.tessera.mrz.model.MrzDate
import io.lightine.tessera.mrz.model.MrzDateInferenceMethod
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.mrz.model.TD3
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.mrz.transliteration.IcaoDefaultTransliterationProfile
import io.lightine.tessera.mrz.transliteration.TransliterationResult
import io.lightine.tessera.mrz.transliteration.transliterate
import io.lightine.tessera.mrz.validation.MrzValidator
import io.lightine.tessera.types.errors.MrzExpiryDateCenturyNotInferred
import io.lightine.tessera.types.errors.MrzExpiryDatePast
import io.lightine.tessera.types.errors.MrzFormatNotDetected
import io.lightine.tessera.types.errors.MrzGenerationFieldOverflow
import io.lightine.tessera.types.errors.MrzInvalidLength
import io.lightine.tessera.types.errors.MrzNameTruncated
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.MrzField
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Conformance of the MRZ SDK with the worked examples published in ICAO Doc 9303.
 *
 * Source: Doc 9303, Eighth Edition (2021), the consolidated PDFs with their amendments, read on
 * 2026-11-19. Covered: Parts 3 to 7 (common specifications, TD3, TD1, TD2, visas). Parts 8 to 13
 * are about chip reading and are left out. Their printed inconsistencies (Part 10 A.2.2, Part 11
 * D.2 examples 2 and 4, and the Part 2 Appendix C screen mock-ups) are out of scope here.
 *
 * The vectors are in IcaoConformanceVectors.kt, one data entry each, and that file is the list of
 * the examples. Ids map to the source: `P<part>-<kind>-<n>`, where the kind is `A` for a Part 3
 * Appendix A check-digit example (in Part 7, `A` and `B` are the MRV-A and MRV-B name examples),
 * `F` for a full specimen in figure order, `N` for a name example in print order, `AR` and `T` for
 * Part 3 Appendix B, `S` for a Part 3 Arabic-script example whose script we wrote out, and `VDS`
 * for the visible digital seal. Each entry names the section or
 * figure and the PDF page index.
 *
 * Groups: 1, check digits; 2, full specimens (parse, validate, generate); 3, name examples; 4,
 * source anomalies. All parses and validations use [REFERENCE_TIME].
 *
 * The rule for failures: this test exists to find problems. A published example that the SDK
 * handles differently is a finding. It is never hidden by weakening an expectation or by changing
 * the SDK. The vector keeps its true expected value, gets a `knownFinding` reason, leaves its group
 * loop, and is asserted in its own test behind `@Ignore` with a one-line reason naming the id.
 * Where the SDK differs from Doc 9303 by documented design, the expectation is the SDK's
 * documented behaviour, and a comment says so.
 *
 * All data is the fictional specimen data that ICAO publishes.
 */
class IcaoConformanceTest {
    // --- Group 1: check digits (Part 3 Appendix A) ---

    @Test
    fun check_digit_examples_give_the_stated_digit() =
        checkEach(CHECK_DIGIT_VECTORS) { v ->
            val input =
                if (v.positions == null) {
                    v.printed.single()
                } else {
                    v.printed
                        .zip(v.positions)
                        .joinToString("") { (line, ranges) -> ranges.joinToString("") { line.substring(it.first - 1, it.last) } }
                }
            assertEquals(v.expected, computeCheckDigit(input), "computed check digit")
            assertEquals(v.printed.last() + v.expected, v.written, "the written form is the input plus the digit")
        }

    // --- Group 2: full specimens ---

    @Test
    fun full_specimens_parse_to_the_visual_zone_values() = checkEach(SPECIMEN_VECTORS, ::assertParsesToVisualZone)

    @Test
    fun full_specimens_validate_with_no_failure_and_the_expected_warnings() = checkEach(SPECIMEN_VECTORS, ::assertValidatesClean)

    @Test
    fun full_specimens_generate_the_printed_lines() = checkEach(SPECIMEN_VECTORS, ::assertGeneratesPrintedLines)

    // --- Group 3: name examples ---

    @Test
    fun name_examples_parse_to_the_doc_9303_identifiers() =
        checkEach(NAME_VECTORS) { v ->
            val document = parseSuccess(nameExampleLines(v), v.host.format)
            val fields = document.commonFields
            assertEquals(nameField(v), fields.rawNameField, "raw name field")
            assertEquals(v.primaryIdentifier, fields.primaryIdentifier, "primary identifier")
            assertEquals(v.secondaryIdentifier, fields.secondaryIdentifier, "secondary identifier")
        }

    @Test
    fun name_examples_report_truncation_as_the_sdk_documents() =
        checkEach(NAME_VECTORS) { v ->
            val result = MrzParser.parse(nameExampleLines(v), REFERENCE_TIME)
            val success = assertIs<ParseResult.Success>(result, "parse result")
            assertEquals(v.sdkReportsTruncation, success.document.commonFields.nameTruncated, "nameTruncated")
            // The host's line with the expiry date is kept, so its expiry warning comes first.
            val expiry: List<MrzWarning> = v.host.warnings.filterIsInstance<MrzExpiryDateCenturyNotInferred>()
            val expected: List<MrzWarning> =
                if (v.sdkReportsTruncation) {
                    expiry + MrzNameTruncated(rawNameField = nameField(v), position = nameFieldPosition(v.host.format))
                } else {
                    expiry
                }
            assertEquals(expected, success.metadata.warnings, "warnings")
        }

    // The generator documents that it joins the identifiers with `<<` and replaces spaces with `<`,
    // and the ICAO profile documents that it drops apostrophes and maps hyphens to `<`. The VIZ name
    // is split at its first ", " (Part 3, 4.6: the comma separates the primary and secondary
    // identifiers); the other fields come from the host specimen.
    @Test
    fun fitting_name_examples_generate_the_printed_lines_from_the_visual_zone_name() =
        checkEach(NAME_VECTORS.filter { it.generation == NameGeneration.REPRODUCES_PRINTED_LINE }) { v ->
            val result = generateFromVisualZoneName(v.host, v.viz ?: fail("no VIZ name"))
            val success = assertIs<GenerationResult.Success>(result, "generation result: $result")
            assertEquals(nameExampleLines(v), success.mrz)
        }

    // The SDK never truncates a name: a name longer than its field is an overflow (MrzGenerator KDoc).
    @Test
    fun overlong_name_examples_report_an_overflow_instead_of_truncating() =
        checkEach(NAME_VECTORS.filter { it.generation == NameGeneration.REPORTS_OVERFLOW }) { v ->
            val result = generateFromVisualZoneName(v.host, v.viz ?: fail("no VIZ name"))
            val failure = assertIs<GenerationResult.Failure>(result, "generation result")
            val overflow = assertIs<MrzGenerationFieldOverflow>(failure.error, "generation error")
            assertEquals(MrzField.NAME_FIELD, overflow.field)
            assertEquals(nameField(v).length, overflow.maxLength)
        }

    @Test
    fun part_3_name_convention_examples_give_the_printed_mrz_text() =
        checkEach(CONVENTION_VECTORS) { v ->
            if (v.splitAtComma) {
                val result = generateFromVisualZoneName(P3_F_1, v.viz)
                val line1 = assertIs<GenerationResult.Success>(result, "generation result").mrz.first()
                assertEquals(v.printed.padEnd(39, '<'), line1.substring(5))
            } else {
                assertEquals(v.printed, transliterateWithIcaoProfile(v.viz))
            }
        }

    // By documented design the ICAO profile writes Ñ as N, not NXX (see P3_T_1). This asserts the
    // SDK's documented output for the printed VIZ name, not the printed CANXXON.
    @Test
    fun part_3_european_name_example_uses_the_documented_no_expansion_choice() {
        val (secondary, primary) = P3_T_1.viz.split(" ")
        assertEquals("CANON", transliterateWithIcaoProfile(primary), "P3-T-1 primary (derived: SDK choice)")
        assertEquals("TERESA", transliterateWithIcaoProfile(secondary), "P3-T-1 secondary")
        assertTrue(P3_T_1.printed.endsWith("<<TERESA"), "P3-T-1 printed secondary")
    }

    // Arabic script through the ICAO profile (Section 6.C and Appendix B). Where Doc 9303 prints
    // something else, the vector says why and the SDK's documented output is asserted.
    @Test
    fun part_3_arabic_script_examples_give_the_printed_mrz_text() =
        checkEach(SCRIPT_VECTORS) { v ->
            assertEquals(
                v.whyDifferent == null,
                v.sdkOutput == v.printed.trimEnd('<'),
                "whyDifferent is set exactly when the output differs",
            )
            assertEquals(v.sdkOutput, transliterateWithIcaoProfile(v.script))
        }

    // --- Group 4: source anomalies ---

    @Test
    fun p4_f_2_as_printed_is_not_read_as_a_passport() {
        val failure = assertIs<ParseResult.Failure>(MrzParser.parse(P4_F_2.printed, REFERENCE_TIME))
        assertEquals(MrzFormatNotDetected(observedLineCount = 2, observedLineLengths = listOf(45, 45)), failure.error)
        val td3 = assertIs<ParseResult.Failure>(MrzParser.parseTD3(P4_F_2.printed, REFERENCE_TIME))
        assertEquals(MrzInvalidLength(MrzFormat.TD3, 2, 44, 2, listOf(45, 45)), td3.error)
    }

    // Corrected by us: one filler removed from each printed line. Not an ICAO line.
    @Test
    fun p4_f_2_corrected_by_us_reads_as_its_visual_zone_says() {
        val corrected =
            SpecimenVector(
                id = "P4-F-2 corrected by us",
                source = P4_F_2.source,
                alsoPrintedAt = "none",
                format = MrzFormat.TD3,
                lines = listOf(P4_F_2.printed[0].dropLast(1), P4_F_2.printed[1].replaceFirst("<<", "<")),
                documentType = "PP",
                issuingState = "UTO",
                primaryIdentifier = "IVANOVA",
                secondaryIdentifier = "ANNA",
                documentNumber = "L898902C3",
                nationality = "UTO",
                sex = Sex.FEMALE,
                dateOfBirth = LocalDate(1974, 8, 12),
                dateOfExpiry = LocalDate(2025, 4, 15),
                expiryCenturyInferred = true,
                optionalData = listOf("184226" + "<".repeat(8)),
                warnings = listOf(MrzExpiryDatePast(expiryDate = LocalDate(2025, 4, 15), referenceDate = REFERENCE_DATE)),
            )
        assertEquals(listOf(44, 44), corrected.lines.map { it.length })
        assertParsesToVisualZone(corrected)
        assertValidatesClean(corrected)
        assertGeneratesPrintedLines(corrected)
    }

    // The 40-character field after the host's `PPUTO` makes a 45-character line: not a TD3 line.
    @Test
    fun p3_ar_6_as_printed_is_not_read_as_a_name_field() {
        val lines = listOf(P3_F_1.lines[0].take(5) + P3_AR_6.printed.single(), P3_F_1.lines[1])
        val failure = assertIs<ParseResult.Failure>(MrzParser.parse(lines, REFERENCE_TIME))
        assertEquals(MrzFormatNotDetected(observedLineCount = 2, observedLineLengths = listOf(45, 44)), failure.error)
    }

    @Test
    fun p7_a_1_as_printed_is_not_read_as_a_visa() {
        val lines = listOf(P7_A_1.printed.single(), P7_F_1.lines[1])
        val failure = assertIs<ParseResult.Failure>(MrzParser.parse(lines, REFERENCE_TIME))
        assertEquals(MrzFormatNotDetected(observedLineCount = 2, observedLineLengths = listOf(45, 44)), failure.error)
        val mrvA = assertIs<ParseResult.Failure>(MrzParser.parseMRVA(lines, REFERENCE_TIME))
        assertEquals(MrzInvalidLength(MrzFormat.MRV_A, 2, 44, 2, listOf(45, 44)), mrvA.error)
    }

    @Test
    fun p7_a_4_as_printed_is_not_read_as_an_mrv_a_and_is_the_mrv_b_line() {
        val lines = listOf(P7_A_4.printed.single(), P7_F_1.lines[1])
        val failure = assertIs<ParseResult.Failure>(MrzParser.parse(lines, REFERENCE_TIME))
        assertEquals(MrzFormatNotDetected(observedLineCount = 2, observedLineLengths = listOf(36, 44)), failure.error)
        assertEquals(NAME_VECTORS.single { it.id == "P7-B-4" }.printed, P7_A_4.printed.single())
    }

    @Test
    fun p7_vds_1_as_printed_is_not_read_as_an_mrv_b() {
        val failure = assertIs<ParseResult.Failure>(MrzParser.parse(P7_VDS_1.printed, REFERENCE_TIME))
        assertEquals(MrzFormatNotDetected(observedLineCount = 2, observedLineLengths = listOf(35, 36)), failure.error)
        val mrvB = assertIs<ParseResult.Failure>(MrzParser.parseMRVB(P7_VDS_1.printed, REFERENCE_TIME))
        assertEquals(MrzInvalidLength(MrzFormat.MRV_B, 2, 36, 2, listOf(35, 36)), mrvB.error)
        // Line 2 on its own is consistent: document number, birth and expiry check digits agree.
        val line2 = P7_VDS_1.printed[1]
        assertEquals(line2[9], computeCheckDigit(line2.substring(0, 9)))
        assertEquals(line2[19], computeCheckDigit(line2.substring(13, 19)))
        assertEquals(line2[27], computeCheckDigit(line2.substring(21, 27)))
    }

    // --- The list itself ---

    @Test
    fun vector_list_is_well_formed() {
        val all: List<IcaoVector> =
            listOf(
                CHECK_DIGIT_VECTORS,
                SPECIMEN_VECTORS,
                CONVENTION_VECTORS,
                listOf(P3_T_1),
                SCRIPT_VECTORS,
                NAME_VECTORS,
                LISTED_VECTORS,
                ANOMALY_VECTORS,
            ).flatten()
        val duplicates = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate ids: $duplicates")
        assertTrue(all.all { it.source.part in 3..7 }, "only Parts 3 to 7")
        for (v in SPECIMEN_VECTORS) {
            assertEquals(v.lines.map { lineLength(v.format) }, v.lines.map { it.length }, "${v.id} line lengths")
        }
        for (v in NAME_VECTORS) {
            val expected = if (v.printedIsNameFieldOnly) 39 else lineLength(v.host.format)
            assertEquals(expected, v.printed.length, "${v.id} printed length")
        }
        // The anomalies are exactly the ones whose printed length is wrong.
        assertEquals(listOf(45, 45), P4_F_2.printed.map { it.length })
        assertEquals(40, P3_AR_6.printed.single().length)
        assertEquals(45, P7_A_1.printed.single().length)
        assertEquals(36, P7_A_4.printed.single().length)
        assertEquals(listOf(35, 36), P7_VDS_1.printed.map { it.length })
    }

    // --- Assertions shared by the groups ---

    private fun assertParsesToVisualZone(v: SpecimenVector) {
        val document = parseSuccess(v.lines, v.format)
        val fields = document.commonFields
        assertEquals(v.documentType, fields.documentType.rawCode, "document type")
        assertEquals(v.issuingState, fields.issuingState.rawCode, "issuing state")
        assertEquals(v.primaryIdentifier, fields.primaryIdentifier, "primary identifier")
        assertEquals(v.secondaryIdentifier, fields.secondaryIdentifier, "secondary identifier")
        assertEquals(false, fields.nameTruncated, "nameTruncated")
        assertEquals(v.documentNumber, fields.documentNumber, "document number")
        assertEquals(v.nationality, fields.nationality.rawCode, "nationality")
        assertEquals(v.sex, fields.sex, "sex")
        assertDate(v.dateOfBirth, fields.dateOfBirth, inferred = true, "date of birth")
        assertDate(v.dateOfExpiry, fields.dateOfExpiry, inferred = v.expiryCenturyInferred, "date of expiry")
        assertEquals(v.optionalData, optionalDataOf(document), "optional data")
        assertEquals(v.lines, document.rawLines, "raw lines")
    }

    private fun assertValidatesClean(v: SpecimenVector) {
        val parsed = MrzParser.parse(v.lines, REFERENCE_TIME)
        assertEquals(emptyList(), parsed.metadata.validationFailures, "parse validation failures")
        assertEquals(v.warnings, parsed.metadata.warnings, "parse warnings")
        val fromLines = MrzValidator.validate(v.lines, REFERENCE_TIME)
        assertNull(fromLines.structuralFailure, "structural failure")
        assertEquals(emptyList(), fromLines.validationFailures, "validation failures")
        assertEquals(v.warnings, fromLines.warnings, "validation warnings")
        val fromDocument = MrzValidator.validate(parseSuccess(v.lines, v.format), REFERENCE_TIME)
        assertEquals(fromLines, fromDocument, "validate(document) agrees with validate(lines)")
    }

    private fun assertGeneratesPrintedLines(v: SpecimenVector) {
        val result = MrzGenerator.generate(parseSuccess(v.lines, v.format))
        val success = assertIs<GenerationResult.Success>(result, "generation result: $result")
        assertEquals(v.lines, success.mrz, "generated lines")
    }

    private fun assertDate(
        viz: LocalDate,
        date: MrzDate,
        inferred: Boolean,
        what: String,
    ) {
        // LocalDate.toString() is ISO 8601: yyyy-mm-dd.
        val iso = viz.toString()
        assertEquals(iso.substring(2, 4), date.rawYear, "$what raw year")
        assertEquals(iso.substring(5, 7), date.rawMonth, "$what raw month")
        assertEquals(iso.substring(8, 10), date.rawDay, "$what raw day")
        if (inferred) {
            assertEquals(viz, date.computedDate, "$what computed date")
        } else {
            assertNull(date.computedDate, "$what computed date")
            assertEquals(MrzDateInferenceMethod.RAW_ONLY, date.inferenceMethod, "$what inference method")
        }
    }

    private fun parseSuccess(
        lines: List<String>,
        format: MrzFormat,
    ): MrzDocument {
        val result = MrzParser.parse(lines, REFERENCE_TIME)
        val success = assertIs<ParseResult.Success>(result, "parse result: $result")
        assertEquals(format, success.document.format, "detected format")
        return success.document
    }

    private fun optionalDataOf(document: MrzDocument): List<String> =
        when (document) {
            is TD3 -> listOf(document.personalNumber)
            is TD1 -> listOf(document.optionalData1, document.optionalData2)
            is TD2 -> listOf(document.optionalData)
            is MrvA -> listOf(document.optionalData)
            is MrvB -> listOf(document.optionalData)
        }

    // The name example's MRZ: the printed name line in its place, the rest from the host specimen.
    private fun nameExampleLines(v: NameVector): List<String> {
        val nameLine = if (v.printedIsNameFieldOnly) v.host.lines[0].take(5) + v.printed else v.printed
        return when (v.host.format) {
            MrzFormat.TD1 -> listOf(v.host.lines[0], v.host.lines[1], nameLine)
            else -> listOf(nameLine, v.host.lines[1])
        }
    }

    private fun nameField(v: NameVector): String =
        when {
            v.printedIsNameFieldOnly -> v.printed
            v.host.format == MrzFormat.TD1 -> v.printed
            else -> v.printed.substring(5)
        }

    // Zero-based index of the first name character across the lines joined (MrzNameTruncated KDoc):
    // TD1 line 3 starts after two 30-character lines; the others start at line 1, character 6.
    private fun nameFieldPosition(format: MrzFormat): Int = if (format == MrzFormat.TD1) 60 else 5

    private fun lineLength(format: MrzFormat): Int =
        when (format) {
            MrzFormat.TD1 -> 30
            MrzFormat.TD2, MrzFormat.MRV_B -> 36
            MrzFormat.TD3, MrzFormat.MRV_A -> 44
        }

    private fun transliterateWithIcaoProfile(text: String): String {
        val result = IcaoDefaultTransliterationProfile.transliterate(text).result
        return assertIs<TransliterationResult.Success>(result, "transliteration of $text").output
    }

    private fun generateFromVisualZoneName(
        host: SpecimenVector,
        viz: String,
    ): GenerationResult {
        val comma = viz.indexOf(", ")
        val primary = if (comma < 0) viz else viz.substring(0, comma)
        val secondary = if (comma < 0) "" else viz.substring(comma + 2)
        val profile = IcaoDefaultTransliterationProfile
        return with(host) {
            when (format) {
                MrzFormat.TD3 -> {
                    MrzGenerator.generateTD3(
                        documentType,
                        issuingState,
                        documentNumber,
                        primary,
                        secondary,
                        nationality,
                        dateOfBirth,
                        sex,
                        dateOfExpiry,
                        optionalData[0],
                        profile,
                    )
                }

                MrzFormat.TD1 -> {
                    MrzGenerator.generateTD1(
                        documentType,
                        issuingState,
                        documentNumber,
                        primary,
                        secondary,
                        nationality,
                        dateOfBirth,
                        sex,
                        dateOfExpiry,
                        optionalData[0],
                        optionalData[1],
                        profile,
                    )
                }

                MrzFormat.TD2 -> {
                    MrzGenerator.generateTD2(
                        documentType,
                        issuingState,
                        documentNumber,
                        primary,
                        secondary,
                        nationality,
                        dateOfBirth,
                        sex,
                        dateOfExpiry,
                        optionalData[0],
                        profile,
                    )
                }

                MrzFormat.MRV_A -> {
                    MrzGenerator.generateMrvA(
                        documentType,
                        issuingState,
                        documentNumber,
                        primary,
                        secondary,
                        nationality,
                        dateOfBirth,
                        sex,
                        dateOfExpiry,
                        optionalData[0],
                        profile,
                    )
                }

                MrzFormat.MRV_B -> {
                    MrzGenerator.generateMrvB(
                        documentType,
                        issuingState,
                        documentNumber,
                        primary,
                        secondary,
                        nationality,
                        dateOfBirth,
                        sex,
                        dateOfExpiry,
                        optionalData[0],
                        profile,
                    )
                }
            }
        }
    }

    // Runs [check] on every vector without a known finding and reports every failing id at once,
    // so one run shows all the problems in a group.
    private fun <T : IcaoVector> checkEach(
        vectors: List<T>,
        check: (T) -> Unit,
    ) {
        val active = vectors.filter { it.knownFinding == null }
        assertTrue(active.isNotEmpty(), "no vector to check")
        val problems =
            active.mapNotNull { v ->
                try {
                    check(v)
                    null
                } catch (e: AssertionError) {
                    "${v.id} (${v.source.where}, pdf page ${v.source.pdfPage}): ${e.message}"
                }
            }
        if (problems.isNotEmpty()) fail("${problems.size} of ${active.size} vectors differ:\n" + problems.joinToString("\n"))
    }
}
