package io.lightine.tessera.mrz.conformance

import io.lightine.tessera.types.errors.MrzExpiryDateCenturyNotInferred
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.MrzFormat
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/*
 * The published worked examples of ICAO Doc 9303, Parts 3 to 7, as data. IcaoConformanceTest runs
 * the SDK against them; see its KDoc for the rules. Every MRZ literal below was copied by script
 * from the text layer of the PDF named in its source and checked mechanically: same characters,
 * line length per format, and every check digit recomputed with the 7-3-1 weighting.
 *
 * Literals that are ours, not ICAO's, are marked "derived" or "corrected by us" where they appear.
 */

/** The fixed reference time of every parse and validation here: the day the PDFs were read. */
internal val REFERENCE_TIME: Instant = Instant.parse("2026-11-19T00:00:00Z")

/** The reference time as a UTC date, as the validator compares it. */
internal val REFERENCE_DATE: LocalDate = LocalDate(2026, 11, 19)

/**
 * Where a vector is printed.
 *
 * @property part The Doc 9303 part, 3 to 7.
 * @property where The section, figure, or appendix, as the PDF names it.
 * @property pdfPage The page index in the PDF file (not the printed page number).
 */
internal data class IcaoSource(
    val part: Int,
    val where: String,
    val pdfPage: Int,
)

/** What every vector carries. A non-null [knownFinding] moves the vector to its own ignored test. */
internal interface IcaoVector {
    val id: String
    val source: IcaoSource
    val knownFinding: String?
}

// ---------------------------------------------------------------------------------------------
// Group 1. Check-digit worked examples: Part 3, Informative Appendix A.
// ---------------------------------------------------------------------------------------------

/**
 * One check-digit worked example.
 *
 * @property printed The input as printed: one field, or the MRZ line(s) the composite is taken from.
 * @property positions For a composite, the 1-based character positions of each printed line that
 *   form the input, as Doc 9303 states them. `null` when the whole printed field is the input.
 * @property expected The check digit Doc 9303 states.
 * @property written The field or line as Doc 9303 writes it with the check digit appended.
 */
internal data class CheckDigitVector(
    override val id: String,
    override val source: IcaoSource,
    val printed: List<String>,
    val positions: List<List<IntRange>>?,
    val expected: Char,
    val written: String,
    override val knownFinding: String? = null,
) : IcaoVector

internal val CHECK_DIGIT_VECTORS: List<CheckDigitVector> =
    listOf(
        // A date of birth, 27 July 1952. Products 35, 6, 0, 49, 6, 7; sum 103.
        CheckDigitVector(
            id = "P3-A-1",
            source = IcaoSource(part = 3, where = "Appendix A, Example 1", pdfPage = 51),
            printed = listOf("520727"),
            positions = null,
            expected = '3',
            written = "5207273",
        ),
        // A 9-character document number field. Sum 125.
        CheckDigitVector(
            id = "P3-A-2",
            source = IcaoSource(part = 3, where = "Appendix A, Example 2", pdfPage = 51),
            printed = listOf("AB2134<<<"),
            positions = null,
            expected = '5',
            written = "AB2134<<<5",
        ),
        // TD3 composite: positions 1-10, 14-20 and 22-43 of the lower line. Sum 448.
        // The 43-character input is printed that way on purpose; the written 44-character line is on pdf page 53.
        CheckDigitVector(
            id = "P3-A-3",
            source = IcaoSource(part = 3, where = "Appendix A, Example 3", pdfPage = 52),
            printed = listOf("HA672242<6YTO5802254M9601086<<<<<<<<<<<<<<0"),
            positions = listOf(listOf(1..10, 14..20, 22..43)),
            expected = '8',
            written = "HA672242<6YTO5802254M9601086<<<<<<<<<<<<<<08",
        ),
        // TD1 composite: upper line 6-30, middle line 1-7, 9-15 and 19-29. Sum 392. Written form on pdf page 54.
        CheckDigitVector(
            id = "P3-A-4",
            source = IcaoSource(part = 3, where = "Appendix A, Example 4", pdfPage = 53),
            printed = listOf("I<YTOD231458907<<<<<<<<<<<<<<<", "3407127M9507122YTO<<<<<<<<<<<"),
            positions = listOf(listOf(6..30), listOf(1..7, 9..15, 19..29)),
            expected = '2',
            written = "3407127M9507122YTO<<<<<<<<<<<2",
        ),
        // TD2 composite: positions 1-10, 14-20 and 22-35 of the lower line. Sum 448. Written form on pdf page 55.
        CheckDigitVector(
            id = "P3-A-5",
            source = IcaoSource(part = 3, where = "Appendix A, Example 5", pdfPage = 54),
            printed = listOf("HA672242<6YTO5802254M9601086<<<<<<<"),
            positions = listOf(listOf(1..10, 14..20, 22..35)),
            expected = '8',
            written = "HA672242<6YTO5802254M9601086<<<<<<<8",
        ),
    )

// ---------------------------------------------------------------------------------------------
// Group 2. Full specimens: an MRZ printed with its visual zone (VIZ).
// ---------------------------------------------------------------------------------------------

/**
 * One full specimen and what its visual zone states.
 *
 * @property alsoPrintedAt Other figures that print the same MRZ.
 * @property lines The MRZ lines exactly as printed.
 * @property documentNumber The document number as the MRZ prints it, filler included.
 * @property dateOfBirth The date of birth the VIZ states.
 * @property dateOfExpiry The date of expiry the VIZ states.
 * @property expiryCenturyInferred Whether the SDK places the expiry year in a century at
 *   [REFERENCE_TIME]. Its documented window is 10 years back to 50 years ahead of the reference
 *   year, so expiry years before 2016 stay raw only, and the validator reports
 *   `MrzExpiryDateCenturyNotInferred` for them.
 * @property optionalData The optional data as printed, in the format's order: the personal number
 *   (TD3), optional data 1 and 2 (TD1), or the one optional-data field (TD2, MRV-A, MRV-B).
 * @property warnings The exact warning list expected at [REFERENCE_TIME].
 */
internal data class SpecimenVector(
    override val id: String,
    override val source: IcaoSource,
    val alsoPrintedAt: String,
    val format: MrzFormat,
    val lines: List<String>,
    val documentType: String,
    val issuingState: String,
    val primaryIdentifier: String,
    val secondaryIdentifier: String,
    val documentNumber: String,
    val nationality: String,
    val sex: Sex,
    val dateOfBirth: LocalDate,
    val dateOfExpiry: LocalDate,
    val expiryCenturyInferred: Boolean,
    val optionalData: List<String>,
    val warnings: List<MrzWarning>,
    override val knownFinding: String? = null,
) : IcaoVector

// VIZ: PP, UTO, L898902C3, ERIKSSON, ANNA MARIA, UTOPIAN, 12 AUG 74, personal no. ZE184226B, F,
// expiry 15 APR 12.
internal val P3_F_1: SpecimenVector =
    SpecimenVector(
        id = "P3-F-1",
        source = IcaoSource(part = 3, where = "Figure 1", pdfPage = 11),
        alsoPrintedAt = "none",
        format = MrzFormat.TD3,
        lines = listOf("PPUTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", "L898902C36UTO7408122F1204159ZE184226B<<<<<10"),
        documentType = "PP",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "L898902C3",
        nationality = "UTO",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1974, 8, 12),
        dateOfExpiry = LocalDate(2012, 4, 15),
        expiryCenturyInferred = false,
        optionalData = listOf("ZE184226B<<<<<"),
        warnings = listOf(MrzExpiryDateCenturyNotInferred("12", "04", "15", REFERENCE_DATE)),
    )

// VIZ: PP, UTO, L898902C3, ERIKSSON, ANNA MARIA, UTOPIAN, 12 AUG 1974, personal no. ZE184226B, F,
// expiry 15 APR 2034. Same holder as P3-F-1; only the expiry differs.
internal val P4_F_1: SpecimenVector =
    SpecimenVector(
        id = "P4-F-1",
        source = IcaoSource(part = 4, where = "Appendix A, Figure A-1", pdfPage = 35),
        alsoPrintedAt = "Figures A-3 and A-4 (pdf page 36), Figure B-1 (pdf page 37)",
        format = MrzFormat.TD3,
        lines = listOf("PPUTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", "L898902C36UTO7408122F3404159ZE184226B<<<<<16"),
        documentType = "PP",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "L898902C3",
        nationality = "UTO",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1974, 8, 12),
        dateOfExpiry = LocalDate(2034, 4, 15),
        expiryCenturyInferred = true,
        optionalData = listOf("ZE184226B<<<<<"),
        warnings = emptyList(),
    )

// VIZ: ERIKSSON, ANNA MARIA; F; UTO; 12 AUG 1974; D23145890; expiry 15 APR 2012.
internal val P5_F_1: SpecimenVector =
    SpecimenVector(
        id = "P5-F-1",
        source = IcaoSource(part = 5, where = "Appendix A, Figure A-2", pdfPage = 29),
        alsoPrintedAt = "Figure A-4 (pdf page 30); Figure B-1 (pdf page 31) prints it split by labels",
        format = MrzFormat.TD1,
        lines =
            listOf(
                "I<UTOD231458907<<<<<<<<<<<<<<<",
                "7408122F1204159UTO<<<<<<<<<<<6",
                "ERIKSSON<<ANNA<MARIA<<<<<<<<<<",
            ),
        documentType = "I",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "D23145890",
        nationality = "UTO",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1974, 8, 12),
        dateOfExpiry = LocalDate(2012, 4, 15),
        expiryCenturyInferred = false,
        optionalData = listOf("<".repeat(15), "<".repeat(11)),
        warnings = listOf(MrzExpiryDateCenturyNotInferred("12", "04", "15", REFERENCE_DATE)),
    )

// VIZ: ERIKSSON; ANNA MARIA; UTO; 12 AUG 1974; F; D23145890; valid until 15 APR 2012.
internal val P6_F_1: SpecimenVector =
    SpecimenVector(
        id = "P6-F-1",
        source = IcaoSource(part = 6, where = "Appendix A, Figure A-1", pdfPage = 27),
        alsoPrintedAt = "Figures A-2 to A-4 (pdf pages 27-28), Figure B-1 (pdf page 29)",
        format = MrzFormat.TD2,
        lines = listOf("I<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<", "D231458907UTO7408122F1204159<<<<<<<6"),
        documentType = "I",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "D23145890",
        nationality = "UTO",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1974, 8, 12),
        dateOfExpiry = LocalDate(2012, 4, 15),
        expiryCenturyInferred = false,
        optionalData = listOf("<".repeat(7)),
        warnings = listOf(MrzExpiryDateCenturyNotInferred("12", "04", "15", REFERENCE_DATE)),
    )

// VIZ: visa document no. M123889546 (not in the MRZ); passport no. L8988901C, which the MRZ
// carries as its document number; ERIKSSON ANNA MARIA; F; 12 AUG 74; nationality XXX; valid until
// 10 DEC 96. XXX means unspecified nationality (Doc 9303 Part 3) and is in CountryCodeTable, so
// it raises no warning.
internal val P7_F_1: SpecimenVector =
    SpecimenVector(
        id = "P7-F-1",
        source = IcaoSource(part = 7, where = "Appendix A, Figure A-1 (MRV-A)", pdfPage = 53),
        alsoPrintedAt = "Figure 11 (pdf page 45), Figure A-2 (pdf page 54)",
        format = MrzFormat.MRV_A,
        lines = listOf("V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", "L8988901C4XXX7408122F96121096ZE184226B<<<<<<"),
        documentType = "V",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "L8988901C",
        nationality = "XXX",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1974, 8, 12),
        dateOfExpiry = LocalDate(1996, 12, 10),
        expiryCenturyInferred = false,
        // As printed: the optional data starts with 6. A specimen oddity, read verbatim.
        optionalData = listOf("6ZE184226B<<<<<<"),
        warnings = listOf(MrzExpiryDateCenturyNotInferred("96", "12", "10", REFERENCE_DATE)),
    )

// VIZ: as P7-F-1.
internal val P7_F_2: SpecimenVector =
    SpecimenVector(
        id = "P7-F-2",
        source = IcaoSource(part = 7, where = "Appendix A, Figure A-3 (MRV-B)", pdfPage = 55),
        alsoPrintedAt = "Figure 12 (pdf page 46), Figure A-4 (pdf page 55)",
        format = MrzFormat.MRV_B,
        lines = listOf("V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<", "L8988901C4XXX7408122F9612109<<<<<<<<"),
        documentType = "V",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "L8988901C",
        nationality = "XXX",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1974, 8, 12),
        dateOfExpiry = LocalDate(1996, 12, 10),
        expiryCenturyInferred = false,
        optionalData = listOf("<".repeat(8)),
        warnings = listOf(MrzExpiryDateCenturyNotInferred("96", "12", "10", REFERENCE_DATE)),
    )

// A construction figure with no VIZ: the values are what the printed MRZ says.
internal val P7_F_3: SpecimenVector =
    SpecimenVector(
        id = "P7-F-3",
        source = IcaoSource(part = 7, where = "Appendix B, Figure B-1 (MRV-A construction)", pdfPage = 57),
        alsoPrintedAt = "none",
        format = MrzFormat.MRV_A,
        lines = listOf("V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", "L898902C<3UTO6908061F9406236ZE184226B<<<<<<<"),
        documentType = "V",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "L898902C<",
        nationality = "UTO",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1969, 8, 6),
        dateOfExpiry = LocalDate(1994, 6, 23),
        expiryCenturyInferred = false,
        optionalData = listOf("ZE184226B<<<<<<<"),
        warnings = listOf(MrzExpiryDateCenturyNotInferred("94", "06", "23", REFERENCE_DATE)),
    )

// A construction figure with no VIZ. Line 2 ends in optional data with no filler.
internal val P7_F_4: SpecimenVector =
    SpecimenVector(
        id = "P7-F-4",
        source = IcaoSource(part = 7, where = "Appendix B, Figure B-2 (MRV-B construction)", pdfPage = 58),
        alsoPrintedAt = "none",
        format = MrzFormat.MRV_B,
        lines = listOf("V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<", "L898902C<3UTO6908061F9406236ZE184226"),
        documentType = "V",
        issuingState = "UTO",
        primaryIdentifier = "ERIKSSON",
        secondaryIdentifier = "ANNA MARIA",
        documentNumber = "L898902C<",
        nationality = "UTO",
        sex = Sex.FEMALE,
        dateOfBirth = LocalDate(1969, 8, 6),
        dateOfExpiry = LocalDate(1994, 6, 23),
        expiryCenturyInferred = false,
        optionalData = listOf("ZE184226"),
        warnings = listOf(MrzExpiryDateCenturyNotInferred("94", "06", "23", REFERENCE_DATE)),
    )

internal val SPECIMEN_VECTORS: List<SpecimenVector> =
    listOf(P3_F_1, P4_F_1, P5_F_1, P6_F_1, P7_F_1, P7_F_2, P7_F_3, P7_F_4)

// ---------------------------------------------------------------------------------------------
// Group 3. Name examples.
// ---------------------------------------------------------------------------------------------

/**
 * Part 3, section 4.6: how punctuation in the VIZ name becomes the MRZ name. These are name
 * fragments, not whole fields.
 *
 * @property viz The VIZ text as printed.
 * @property printed The MRZ text as printed.
 * @property splitAtComma `true` when the comma separates the primary and secondary identifiers
 *   (the SDK takes them as two arguments); `false` when the whole VIZ text goes through the
 *   transliteration profile.
 */
internal data class ConventionVector(
    override val id: String,
    override val source: IcaoSource,
    val viz: String,
    val printed: String,
    val splitAtComma: Boolean,
    override val knownFinding: String? = null,
) : IcaoVector

internal val CONVENTION_VECTORS: List<ConventionVector> =
    listOf(
        // Apostrophe: omitted, no filler. Also printed in Part 7 (pdf pages 17 and 35).
        ConventionVector(
            id = "P3-N-1",
            source = IcaoSource(part = 3, where = "4.6, apostrophe", pdfPage = 26),
            viz = "D’ARTAGNAN",
            printed = "DARTAGNAN",
            splitAtComma = false,
        ),
        // Hyphen: one filler. Also printed in Part 7 (pdf pages 17 and 35).
        ConventionVector(
            id = "P3-N-2",
            source = IcaoSource(part = 3, where = "4.6, hyphen", pdfPage = 26),
            viz = "MARIE-ELISE",
            printed = "MARIE<ELISE",
            splitAtComma = false,
        ),
        // Comma between primary and secondary: two fillers.
        ConventionVector(
            id = "P3-N-3",
            source = IcaoSource(part = 3, where = "4.6, comma between identifiers", pdfPage = 26),
            viz = "ERIKSSON, ANNA MARIA",
            printed = "ERIKSSON<<ANNA<MARIA",
            splitAtComma = true,
        ),
        // Comma between two components: one filler, with or without a space after the comma.
        ConventionVector(
            id = "P3-N-4",
            source = IcaoSource(part = 3, where = "4.6, comma between components", pdfPage = 26),
            viz = "ANNA, MARIA",
            printed = "ANNA<MARIA",
            splitAtComma = false,
        ),
    )

/** How the generator is expected to handle a name example's VIZ name. */
internal enum class NameGeneration {
    /** The full VIZ name fits the field: generating from it reproduces the printed lines. */
    REPRODUCES_PRINTED_LINE,

    /** The full VIZ name does not fit: the SDK reports an overflow and never truncates. */
    REPORTS_OVERFLOW,

    /** Not asserted: the VIZ name is in a script the SDK has no profile for. */
    NOT_ASSERTED,
}

/**
 * One printed name example.
 *
 * Doc 9303 prints only the name line. The test combines it with the other line(s) of [host], the
 * same part's full specimen, to make a parseable MRZ. The combination is ours; the name line is
 * ICAO's. In TD3, TD2 and the visas the name is on line 1, which no composite check digit covers;
 * in TD1 it is line 3, which the composite does not cover either.
 *
 * @property printed The name line as printed, or the 39-character name field when
 *   [printedIsNameFieldOnly] is `true` (Part 3 Appendix B); the test then prefixes the first 5
 *   characters of the host's line 1.
 * @property viz The VIZ name as printed: primary identifier, comma, secondary identifier.
 * @property primaryIdentifier What the printed name field says under the Doc 9303 rule: the text
 *   before the first `<<`, with each single `<` read as a space.
 * @property secondaryIdentifier The text after the first `<<`, read the same way.
 * @property sdkReportsTruncation The SDK's documented rule (CommonFields.nameTruncated,
 *   MrzNameTruncated): the field fills its width with no trailing `<`. Doc 9303 says such a field
 *   "must be treated as though truncation had occurred" (Part 4, 4.2.3), so the SDK reports the
 *   names that just fit too. That is by design, not a finding.
 * @property doc9303Says What Doc 9303 says about truncation for this example.
 */
internal data class NameVector(
    override val id: String,
    override val source: IcaoSource,
    val host: SpecimenVector,
    val printed: String,
    val printedIsNameFieldOnly: Boolean = false,
    val viz: String?,
    val primaryIdentifier: String,
    val secondaryIdentifier: String,
    val sdkReportsTruncation: Boolean,
    val doc9303Says: String,
    val generation: NameGeneration,
    override val knownFinding: String? = null,
) : IcaoVector

// Generated from the text dump in page order; the "where" text names the Doc 9303 case.
// Part 4 (TD3, host P4-F-1), Part 5 (TD1 line 3, host P5-F-1), Part 6 (TD2, host P6-F-1), Part 7
// MRV-A (host P7-F-1) and MRV-B (host P7-F-2). P7-A-1 and P7-A-4 are printed with the wrong length
// and are in the anomalies section instead. Then Part 3 Appendix B, Arabic names, host P3-F-1.
// Part 6 spells the name JONATHOON in P6-N-6, in the VIZ and the MRZ alike; it is kept as printed.
internal val NAME_VECTORS: List<NameVector> =
    listOf(
        NameVector(
            id = "P4-N-1",
            source = IcaoSource(part = 4, where = "4.2.3, usual", pdfPage = 30),
            host = P4_F_1,
            printed = "PPUTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
            viz = "ERIKSSON, ANNA MARIA",
            primaryIdentifier = "ERIKSSON",
            secondaryIdentifier = "ANNA MARIA",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-2",
            source = IcaoSource(part = 4, where = "4.2.3, central primary", pdfPage = 30),
            host = P4_F_1,
            printed = "PPUTOHENG<<DEBORAH<MING<LO<<<<<<<<<<<<<<<<<<",
            viz = "HENG, DEBORAH MING LO",
            primaryIdentifier = "HENG",
            secondaryIdentifier = "DEBORAH MING LO",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-3",
            source = IcaoSource(part = 4, where = "4.2.3, hyphen", pdfPage = 30),
            host = P4_F_1,
            printed = "PPUTOSMITH<JONES<<SUSIE<MARGARET<<<<<<<<<<<<",
            viz = "SMITH-JONES, SUSIE MARGARET",
            primaryIdentifier = "SMITH JONES",
            secondaryIdentifier = "SUSIE MARGARET",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-4",
            source = IcaoSource(part = 4, where = "4.2.3, apostrophe", pdfPage = 30),
            host = P4_F_1,
            printed = "PPUTOOCONNOR<<ENYA<SIOBHAN<<<<<<<<<<<<<<<<<<",
            viz = "O’CONNOR, ENYA SIOBHAN",
            primaryIdentifier = "OCONNOR",
            secondaryIdentifier = "ENYA SIOBHAN",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-5",
            source = IcaoSource(part = 4, where = "4.2.3, multiple components", pdfPage = 30),
            host = P4_F_1,
            printed = "PPUTOVAN<DER<MUELLEN<<MARTIN<<<<<<<<<<<<<<<<",
            viz = "VAN DER MUELLEN, MARTIN",
            primaryIdentifier = "VAN DER MUELLEN",
            secondaryIdentifier = "MARTIN",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-6",
            source = IcaoSource(part = 4, where = "4.2.3, multiple components", pdfPage = 30),
            host = P4_F_1,
            printed = "PPUTOAL<BASRI<<HUDA<MUHAMMAD<JAWAD<<<<<<<<<<",
            viz = "AL-BASRI, HUDA MUHAMMAD JAWAD",
            primaryIdentifier = "AL BASRI",
            secondaryIdentifier = "HUDA MUHAMMAD JAWAD",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-7",
            source = IcaoSource(part = 4, where = "4.2.3, multiple components", pdfPage = 30),
            host = P4_F_1,
            printed = "PPUTOVILARCHAO<FERNANDEZ<<JOSE<RAMON<<<<<<<<",
            viz = "VILARCHAO FERNANDEZ, JOSE RAMON",
            primaryIdentifier = "VILARCHAO FERNANDEZ",
            secondaryIdentifier = "JOSE RAMON",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-8",
            source = IcaoSource(part = 4, where = "4.2.3, no secondary", pdfPage = 31),
            host = P4_F_1,
            printed = "PPUTOARKFREITH<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<",
            viz = "ARKFREITH",
            primaryIdentifier = "ARKFREITH",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-9",
            source = IcaoSource(part = 4, where = "4.2.3, no secondary", pdfPage = 31),
            host = P4_F_1,
            printed = "PPUTOSATRIYA<SUDARPA<<<<<<<<<<<<<<<<<<<<<<<<",
            viz = "SATRIYA SUDARPA",
            primaryIdentifier = "SATRIYA SUDARPA",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P4-N-10",
            source = IcaoSource(part = 4, where = "4.2.3, secondary to initials", pdfPage = 31),
            host = P4_F_1,
            printed = "PPUTONILAVADHANANANDA<<CHAYAPA<DEJTHAMRONG<K",
            viz = "NILAVADHANANANDA, CHAYAPA DEJTHAMRONG KRASUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "CHAYAPA DEJTHAMRONG K",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P4-N-11",
            source = IcaoSource(part = 4, where = "4.2.3, secondary truncated", pdfPage = 31),
            host = P4_F_1,
            printed = "PPUTONILAVADHANANANDA<<ARNPOL<PETCH<CHARONGU",
            viz = "NILAVADHANANANDA, ARNPOL PETCH CHARONGUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "ARNPOL PETCH CHARONGU",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P4-N-12",
            source = IcaoSource(part = 4, where = "4.2.3, primary to initials", pdfPage = 31),
            host = P4_F_1,
            printed = "PPUTOBENNELONG<WOOLOOMOOLOO<WARRANDYTE<W<<DI",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOMOOLOO WARRANDYTE W",
            secondaryIdentifier = "DI",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P4-N-13",
            source = IcaoSource(part = 4, where = "4.2.3, primary truncated", pdfPage = 31),
            host = P4_F_1,
            printed = "PPUTOBENNELONG<WOOLOOM<WARRAND<WARNAM<<DINGO",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOM WARRAND WARNAM",
            secondaryIdentifier = "DINGO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P4-N-14",
            source = IcaoSource(part = 4, where = "4.2.3, primary fixed length", pdfPage = 31),
            host = P4_F_1,
            printed = "PPUTOBENNEL<WOOLOO<WARRAN<WARNAM<<DINGO<POTO",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNEL WOOLOO WARRAN WARNAM",
            secondaryIdentifier = "DINGO POTO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P4-N-15",
            source = IcaoSource(part = 4, where = "4.2.3, just fits", pdfPage = 32),
            host = P4_F_1,
            printed = "PPUTOPAPANDROPOULOUS<<JONATHON<WARREN<TREVOR",
            viz = "PAPANDROPOULOUS, JONATHON WARREN TREVOR",
            primaryIdentifier = "PAPANDROPOULOUS",
            secondaryIdentifier = "JONATHON WARREN TREVOR",
            sdkReportsTruncation = true,
            doc9303Says = "not truncated, but a reader must assume it was truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P5-N-1",
            source = IcaoSource(part = 5, where = "4.2.3, secondary to initials", pdfPage = 25),
            host = P5_F_1,
            printed = "NILAVADHANANANDA<<CHAYAPA<DE<K",
            viz = "NILAVADHANANANDA, CHAYAPA DEJTHAMRONG KRASUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "CHAYAPA DE K",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P5-N-2",
            source = IcaoSource(part = 5, where = "4.2.3, secondary truncated", pdfPage = 25),
            host = P5_F_1,
            printed = "NILAVADHANANANDA<<ARNPOL<PE<CH",
            viz = "NILAVADHANANANDA, ARNPOL PETCH CHARONGUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "ARNPOL PE CH",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P5-N-3",
            source = IcaoSource(part = 5, where = "4.2.3, primary to initials", pdfPage = 25),
            host = P5_F_1,
            printed = "BENNELONG<WOOLOOMOOLOO<W<W<<DI",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOMOOLOO W W",
            secondaryIdentifier = "DI",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P5-N-4",
            source = IcaoSource(part = 5, where = "4.2.3, primary truncated", pdfPage = 26),
            host = P5_F_1,
            printed = "BENNELONG<WOOLOOM<WA<WARN<<D<P",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOM WA WARN",
            secondaryIdentifier = "D P",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P5-N-5",
            source = IcaoSource(part = 5, where = "4.2.3, primary fixed length", pdfPage = 26),
            host = P5_F_1,
            printed = "BENNE<WOOLO<WARRA<WARNA<<DIN<P",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNE WOOLO WARRA WARNA",
            secondaryIdentifier = "DIN P",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P5-N-6",
            source = IcaoSource(part = 5, where = "4.2.3, just fits", pdfPage = 26),
            host = P5_F_1,
            printed = "PAPANDROPOULOUS<<JONATHON<ALEC",
            viz = "PAPANDROPOULOUS, JONATHON ALEC",
            primaryIdentifier = "PAPANDROPOULOUS",
            secondaryIdentifier = "JONATHON ALEC",
            sdkReportsTruncation = true,
            doc9303Says = "not truncated, but a reader must assume it was truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P5-N-7",
            source = IcaoSource(part = 5, where = "4.2.3, multiple components", pdfPage = 26),
            host = P5_F_1,
            printed = "VAN<DER<MUELLEN<<MARTIN<<<<<<<",
            viz = "VAN DER MUELLEN, MARTIN",
            primaryIdentifier = "VAN DER MUELLEN",
            secondaryIdentifier = "MARTIN",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P5-N-8",
            source = IcaoSource(part = 5, where = "4.2.3, multiple components", pdfPage = 26),
            host = P5_F_1,
            printed = "AL<BASRI<<HUDA<MUHAMMAD<JAWAD<",
            viz = "AL-BASRI, HUDA MUHAMMAD JAWAD",
            primaryIdentifier = "AL BASRI",
            secondaryIdentifier = "HUDA MUHAMMAD JAWAD",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P5-N-9",
            source = IcaoSource(part = 5, where = "4.2.3, multiple components", pdfPage = 26),
            host = P5_F_1,
            printed = "VILARCHAO<FERNANDEZ<<JOSE<RAMO",
            viz = "VILARCHAO FERNANDEZ, JOSE RAMON",
            primaryIdentifier = "VILARCHAO FERNANDEZ",
            secondaryIdentifier = "JOSE RAMO",
            sdkReportsTruncation = true,
            doc9303Says = "shortened (RAMON to RAMO) in a multiple-components example that does not call it truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P5-N-10",
            source = IcaoSource(part = 5, where = "4.2.3, no secondary", pdfPage = 26),
            host = P5_F_1,
            printed = "ARKFREITH<<<<<<<<<<<<<<<<<<<<<",
            viz = "ARKFREITH",
            primaryIdentifier = "ARKFREITH",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P5-N-11",
            source = IcaoSource(part = 5, where = "4.2.3, no secondary", pdfPage = 26),
            host = P5_F_1,
            printed = "SATRIYA<SUDARPA<<<<<<<<<<<<<<<",
            viz = "SATRIYA SUDARPA",
            primaryIdentifier = "SATRIYA SUDARPA",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P6-N-1",
            source = IcaoSource(part = 6, where = "4.2.3, secondary to initials", pdfPage = 24),
            host = P6_F_1,
            printed = "I<UTONILAVADHANANANDA<<CHAYAPA<DEJ<K",
            viz = "NILAVADHANANANDA, CHAYAPA DEJTHAMRONG KRASUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "CHAYAPA DEJ K",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P6-N-2",
            source = IcaoSource(part = 6, where = "4.2.3, secondary truncated", pdfPage = 24),
            host = P6_F_1,
            printed = "I<UTONILAVADHANANANDA<<ARN<PET<CHARO",
            viz = "NILAVADHANANANDA, ARNPOL PETCH CHARONGUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "ARN PET CHARO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P6-N-3",
            source = IcaoSource(part = 6, where = "4.2.3, primary to initials", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOBENNELONG<WOOLOOMOOLOO<W<W<<D<P",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOMOOLOO W W",
            secondaryIdentifier = "D P",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P6-N-4",
            source = IcaoSource(part = 6, where = "4.2.3, primary truncated", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOBENNELONG<WOOLOOM<WAR<WARN<<D<P",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOM WAR WARN",
            secondaryIdentifier = "D P",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P6-N-5",
            source = IcaoSource(part = 6, where = "4.2.3, primary fixed length", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOBENNEL<WOOLO<WARRA<WARNA<<DIN<P",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNEL WOOLO WARRA WARNA",
            secondaryIdentifier = "DIN P",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P6-N-6",
            source = IcaoSource(part = 6, where = "4.2.3, just fits", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOPAPANDROPOULOUS<<JONATHOON<ALEC",
            viz = "PAPANDROPOULOUS, JONATHOON ALEC",
            primaryIdentifier = "PAPANDROPOULOUS",
            secondaryIdentifier = "JONATHOON ALEC",
            sdkReportsTruncation = true,
            doc9303Says = "not truncated, but a reader must assume it was truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P6-N-7",
            source = IcaoSource(part = 6, where = "4.2.3, multiple components", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOVAN<DER<MUELLEN<<MARTIN<<<<<<<<",
            viz = "VAN DER MUELLEN, MARTIN",
            primaryIdentifier = "VAN DER MUELLEN",
            secondaryIdentifier = "MARTIN",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P6-N-8",
            source = IcaoSource(part = 6, where = "4.2.3, multiple components", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOAL<BASRI<<HUDA<MUHAMMAD<JAWAD<<",
            viz = "AL-BASRI, HUDA MUHAMMAD JAWAD",
            primaryIdentifier = "AL BASRI",
            secondaryIdentifier = "HUDA MUHAMMAD JAWAD",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P6-N-9",
            source = IcaoSource(part = 6, where = "4.2.3, multiple components", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOVILARCHAO<FERNANDEZ<<JOSE<RAMON",
            viz = "VILARCHAO FERNANDEZ, JOSE RAMON",
            primaryIdentifier = "VILARCHAO FERNANDEZ",
            secondaryIdentifier = "JOSE RAMON",
            sdkReportsTruncation = true,
            doc9303Says = "not truncated (the full name fits the field exactly)",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P6-N-10",
            source = IcaoSource(part = 6, where = "4.2.3, no secondary", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOARKFREITH<<<<<<<<<<<<<<<<<<<<<<",
            viz = "ARKFREITH",
            primaryIdentifier = "ARKFREITH",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P6-N-11",
            source = IcaoSource(part = 6, where = "4.2.3, no secondary", pdfPage = 25),
            host = P6_F_1,
            printed = "I<UTOSATRIYA<SUDARPA<<<<<<<<<<<<<<<<",
            viz = "SATRIYA SUDARPA",
            primaryIdentifier = "SATRIYA SUDARPA",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-A-2",
            source = IcaoSource(part = 7, where = "4.2.3, central primary", pdfPage = 20),
            host = P7_F_1,
            printed = "V<UTOHENG<<DEBORAH<MING<LO<<<<<<<<<<<<<<<<<<",
            viz = "HENG, DEBORAH MING LO",
            primaryIdentifier = "HENG",
            secondaryIdentifier = "DEBORAH MING LO",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-A-3",
            source = IcaoSource(part = 7, where = "4.2.3, hyphen", pdfPage = 20),
            host = P7_F_1,
            printed = "V<UTOSMITH<JONES<<SUSIE<MARGARET<<<<<<<<<<<<",
            viz = "SMITH-JONES, SUSIE MARGARET",
            primaryIdentifier = "SMITH JONES",
            secondaryIdentifier = "SUSIE MARGARET",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-A-5",
            source = IcaoSource(part = 7, where = "4.2.3, multiple components", pdfPage = 21),
            host = P7_F_1,
            printed = "V<UTOVAN<DER<MUELLEN<<MARTIN<<<<<<<<<<<<<<<<",
            viz = "VAN DER MUELLEN, MARTIN",
            primaryIdentifier = "VAN DER MUELLEN",
            secondaryIdentifier = "MARTIN",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-A-6",
            source = IcaoSource(part = 7, where = "4.2.3, no secondary", pdfPage = 21),
            host = P7_F_1,
            printed = "V<UTOARKFREITH<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<",
            viz = "ARKFREITH",
            primaryIdentifier = "ARKFREITH",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-A-7",
            source = IcaoSource(part = 7, where = "4.2.3, secondary to initials", pdfPage = 21),
            host = P7_F_1,
            printed = "V<UTONILAVADHANANANDA<<CHAYAPA<DEJTHAMRONG<K",
            viz = "NILAVADHANANANDA, CHAYAPA DEJTHAMRONG KRASUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "CHAYAPA DEJTHAMRONG K",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-A-8",
            source = IcaoSource(part = 7, where = "4.2.3, secondary truncated", pdfPage = 21),
            host = P7_F_1,
            printed = "V<UTONILAVADHANANANDA<<ARNPOL<PETCH<CHARONGU",
            viz = "NILAVADHANANANDA, ARNPOL PETCH CHARONGUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "ARNPOL PETCH CHARONGU",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-A-9",
            source = IcaoSource(part = 7, where = "4.2.3, primary to initials", pdfPage = 21),
            host = P7_F_1,
            printed = "V<UTOBENNELONG<WOOLOOMOOLOO<WARRANDYTE<W<<DI",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOMOOLOO WARRANDYTE W",
            secondaryIdentifier = "DI",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-A-10",
            source = IcaoSource(part = 7, where = "4.2.3, primary truncated", pdfPage = 21),
            host = P7_F_1,
            printed = "V<UTOBENNELONG<WOOLOOM<WARRAND<WARNAM<<DINGO",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOM WARRAND WARNAM",
            secondaryIdentifier = "DINGO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-A-11",
            source = IcaoSource(part = 7, where = "4.2.3, primary fixed length", pdfPage = 22),
            host = P7_F_1,
            printed = "V<UTOBENNEL<WOOLOO<WARRAN<WARNAM<<DINGO<POTO",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNEL WOOLOO WARRAN WARNAM",
            secondaryIdentifier = "DINGO POTO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-A-12",
            source = IcaoSource(part = 7, where = "4.2.3, just fits", pdfPage = 22),
            host = P7_F_1,
            printed = "V<UTOPAPANDROPOULOUS<<JONATHON<WARREN<TREVOR",
            viz = "PAPANDROPOULOUS, JONATHON WARREN TREVOR",
            primaryIdentifier = "PAPANDROPOULOUS",
            secondaryIdentifier = "JONATHON WARREN TREVOR",
            sdkReportsTruncation = true,
            doc9303Says = "not truncated, but a reader must assume it was truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-B-1",
            source = IcaoSource(part = 7, where = "7.2.3, usual", pdfPage = 38),
            host = P7_F_2,
            printed = "V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<",
            viz = "ERIKSSON, ANNA MARIA",
            primaryIdentifier = "ERIKSSON",
            secondaryIdentifier = "ANNA MARIA",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-B-2",
            source = IcaoSource(part = 7, where = "7.2.3, central primary", pdfPage = 38),
            host = P7_F_2,
            printed = "V<UTOHENG<<DEBORAH<MING<LO<<<<<<<<<<",
            viz = "HENG, DEBORAH MING LO",
            primaryIdentifier = "HENG",
            secondaryIdentifier = "DEBORAH MING LO",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-B-3",
            source = IcaoSource(part = 7, where = "7.2.3, hyphen", pdfPage = 39),
            host = P7_F_2,
            printed = "V<UTOSMITH<JONES<<SUSIE<MARGARET<<<<",
            viz = "SMITH-JONES, SUSIE MARGARET",
            primaryIdentifier = "SMITH JONES",
            secondaryIdentifier = "SUSIE MARGARET",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-B-4",
            source = IcaoSource(part = 7, where = "7.2.3, apostrophe", pdfPage = 39),
            host = P7_F_2,
            printed = "V<UTOOCONNOR<<ENYA<SIOBHAN<<<<<<<<<<",
            viz = "O’CONNOR, ENYA SIOBHAN",
            primaryIdentifier = "OCONNOR",
            secondaryIdentifier = "ENYA SIOBHAN",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-B-5",
            source = IcaoSource(part = 7, where = "7.2.3, multiple components", pdfPage = 39),
            host = P7_F_2,
            printed = "V<UTOVAN<DER<MUELLEN<<MARTIN<<<<<<<<",
            viz = "VAN DER MUELLEN, MARTIN",
            primaryIdentifier = "VAN DER MUELLEN",
            secondaryIdentifier = "MARTIN",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-B-6",
            source = IcaoSource(part = 7, where = "7.2.3, no secondary", pdfPage = 39),
            host = P7_F_2,
            printed = "V<UTOARKFREITH<<<<<<<<<<<<<<<<<<<<<<",
            viz = "ARKFREITH",
            primaryIdentifier = "ARKFREITH",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P7-B-7",
            source = IcaoSource(part = 7, where = "7.2.3, secondary to initials", pdfPage = 39),
            host = P7_F_2,
            printed = "V<UTONILAVADHANANANDA<<CHAYAPA<DEJ<K",
            viz = "NILAVADHANANANDA, CHAYAPA DEJTHAMRONG KRASUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "CHAYAPA DEJ K",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-B-8",
            source = IcaoSource(part = 7, where = "7.2.3, secondary truncated", pdfPage = 39),
            host = P7_F_2,
            printed = "V<UTONILAVADHANANANDA<<ARNP<PE<CHARO",
            viz = "NILAVADHANANANDA, ARNPOL PETCH CHARONGUANG",
            primaryIdentifier = "NILAVADHANANANDA",
            secondaryIdentifier = "ARNP PE CHARO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-B-9",
            source = IcaoSource(part = 7, where = "7.2.3, primary to initials", pdfPage = 39),
            host = P7_F_2,
            printed = "V<UTOBENNELONG<WOOLOOMOOLOO<WAR<W<<D",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOMOOLOO WAR W",
            secondaryIdentifier = "D",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-B-10",
            source = IcaoSource(part = 7, where = "7.2.3, primary truncated", pdfPage = 40),
            host = P7_F_2,
            printed = "V<UTOBENNELONG<WOOLOOM<WAR<WA<<DINGO",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENNELONG WOOLOOM WAR WA",
            secondaryIdentifier = "DINGO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-B-11",
            source = IcaoSource(part = 7, where = "7.2.3, primary fixed length", pdfPage = 40),
            host = P7_F_2,
            printed = "V<UTOBENN<WOOL<WARR<WARN<<DINGO<POTO",
            viz = "BENNELONG WOOLOOMOOLOO WARRANDYTE WARNAMBOOL, DINGO POTOROO",
            primaryIdentifier = "BENN WOOL WARR WARN",
            secondaryIdentifier = "DINGO POTO",
            sdkReportsTruncation = true,
            doc9303Says = "truncated",
            generation = NameGeneration.REPORTS_OVERFLOW,
        ),
        NameVector(
            id = "P7-B-12",
            source = IcaoSource(part = 7, where = "7.2.3, just fits", pdfPage = 40),
            host = P7_F_2,
            printed = "V<UTOPAPANDROPOULOUS<<STEPHEN<TREVOR",
            viz = "PAPANDROPOULOUS, STEPHEN TREVOR",
            primaryIdentifier = "PAPANDROPOULOUS",
            secondaryIdentifier = "STEPHEN TREVOR",
            sdkReportsTruncation = true,
            doc9303Says = "not truncated, but a reader must assume it was truncated",
            generation = NameGeneration.REPRODUCES_PRINTED_LINE,
        ),
        NameVector(
            id = "P3-AR-4",
            source = IcaoSource(part = 3, where = "B.5.9, further examples", pdfPage = 70),
            host = P3_F_1,
            printed = "HARY<ALXSHMAE<<<<<<<<<<<<<<<<<<<<<<<<<<",
            printedIsNameFieldOnly = true,
            viz = "Hari Al-Schamma",
            primaryIdentifier = "HARY ALXSHMAE",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-5",
            source = IcaoSource(part = 3, where = "B.5.9, further examples", pdfPage = 70),
            host = P3_F_1,
            printed = "SMYR<BADMKDWXDHYL<<<<<<<<<<<<<<<<<<<<<<",
            printedIsNameFieldOnly = true,
            viz = "Samir Badmakduthal",
            primaryIdentifier = "SMYR BADMKDWXDHYL",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-7",
            source = IcaoSource(part = 3, where = "B.5.9, further examples", pdfPage = 70),
            host = P3_F_1,
            printed = "ALEBAS<EBD<ALLXH<BN<MXHMD<ALSFAXH<<<<<<",
            printedIsNameFieldOnly = true,
            viz = "al-'Abbās 'Abdu'llāh ibn Muhammad as-Saffāh",
            primaryIdentifier = "ALEBAS EBD ALLXH BN MXHMD ALSFAXH",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-8",
            source = IcaoSource(part = 3, where = "B.5.9, further examples", pdfPage = 70),
            host = P3_F_1,
            printed = "EBD<ALLXH<MXHMD<BN<EMR<BN<ALXHSYN<FXKHR",
            printedIsNameFieldOnly = true,
            viz = "Abdullah Muhammad ibn Umar ibn al-Husayn Fakhr al-Din al-Razi",
            primaryIdentifier = "EBD ALLXH MXHMD BN EMR BN ALXHSYN FXKHR",
            secondaryIdentifier = "",
            sdkReportsTruncation = true,
            doc9303Says = "truncated (footnote 6)",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-9",
            source = IcaoSource(part = 3, where = "B.5.9, further examples", pdfPage = 70),
            host = P3_F_1,
            printed = "EBD<ALEZYZ<BN<MTEB<<<<<<<<<<<<<<<<<<<<<",
            printedIsNameFieldOnly = true,
            viz = "Abdul Aziz bin Mithab",
            primaryIdentifier = "EBD ALEZYZ BN MTEB",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-10",
            source = IcaoSource(part = 3, where = "B.5.9, further examples", pdfPage = 70),
            host = P3_F_1,
            printed = "ISMAEYL<EZZ<ALDYN<<<<<<<<<<<<<<<<<<<<<<",
            printedIsNameFieldOnly = true,
            viz = "Isma’il Izz-ud-din",
            primaryIdentifier = "ISMAEYL EZZ ALDYN",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-11",
            source = IcaoSource(part = 3, where = "B.5.9, further examples", pdfPage = 70),
            host = P3_F_1,
            printed = "JMYLXAH<NEYMXAH<<<<<<<<<<<<<<<<<<<<<<<<",
            printedIsNameFieldOnly = true,
            viz = "Jamillah Na'ima",
            primaryIdentifier = "JMYLXAH NEYMXAH",
            secondaryIdentifier = "",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-12",
            source = IcaoSource(part = 3, where = "B.5.10, order of names option 1", pdfPage = 71),
            host = P3_F_1,
            printed = "BN<ZKRYA<ALRAZY<<ABW<BKR<MXHMD<<<<<<<<<",
            printedIsNameFieldOnly = true,
            viz = null,
            primaryIdentifier = "BN ZKRYA ALRAZY",
            secondaryIdentifier = "ABW BKR MXHMD",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
        NameVector(
            id = "P3-AR-13",
            source = IcaoSource(part = 3, where = "B.5.10, order of names option 2", pdfPage = 71),
            host = P3_F_1,
            printed = "ALRAZY<<ABW<BKR<MXHMD<BN<ZKRYA<<<<<<<<<",
            printedIsNameFieldOnly = true,
            viz = null,
            primaryIdentifier = "ALRAZY",
            secondaryIdentifier = "ABW BKR MXHMD BN ZKRYA",
            sdkReportsTruncation = false,
            doc9303Says = "not truncated",
            generation = NameGeneration.NOT_ASSERTED,
        ),
    )

/**
 * Part 3 Appendix B.4.1: a European name written with the expansion `Ñ` to `NXX`.
 *
 * Doc 9303's Latin table allows `N` or `NXX` for `Ñ`. The SDK's ICAO profile documents the
 * no-expansion choice (accented letters lose their accent), so the expected value here is the
 * SDK's: `CAÑON` gives `CANON`. The printed `CANXXON` is the other allowed choice, not asserted.
 */
internal val P3_T_1: ConventionVector =
    ConventionVector(
        id = "P3-T-1",
        source = IcaoSource(part = 3, where = "Appendix B.4.1", pdfPage = 62),
        viz = "Térèsa CAÑON",
        printed = "CANXXON<<TERESA",
        splitAtComma = false,
    )

// ---------------------------------------------------------------------------------------------
// Listed, not asserted.
// ---------------------------------------------------------------------------------------------

/** A published example the SDK has no operation for. Kept so the list is complete. */
internal data class ListedVector(
    override val id: String,
    override val source: IcaoSource,
    val printed: String,
    val reason: String,
    override val knownFinding: String? = null,
) : IcaoVector

internal val LISTED_VECTORS: List<ListedVector> =
    listOf(
        ListedVector(
            id = "P3-AR-1",
            source = IcaoSource(part = 3, where = "Appendix B.2.2", pdfPage = 59),
            printed = "ABU<BAKR<MOHAMMED<IBN<ZAKARIA<AL<RAZI",
            reason = "A free transcription of an Arabic-script name, not a whole name field; the SDK has no Arabic profile.",
        ),
        ListedVector(
            id = "P3-AR-2",
            source = IcaoSource(part = 3, where = "Appendix B.2.2, Buckwalter", pdfPage = 59),
            printed = "Abw<bAkr<mHmd<bn<zkryAY<AlrAzY",
            reason = "Shown by Doc 9303 as unsuitable for the MRZ (lower case).",
        ),
        ListedVector(
            id = "P3-AR-3",
            source = IcaoSource(part = 3, where = "Appendix B.5.7", pdfPage = 69),
            printed = "ABW<BKR<MXHMD<BN<ZKRYA<ALRAZY",
            reason = "Output of the recommended Arabic table, not a whole name field; the SDK has no Arabic profile.",
        ),
    )

// ---------------------------------------------------------------------------------------------
// Group 4. Source anomalies: examples that are inconsistent in Doc 9303 itself.
// ---------------------------------------------------------------------------------------------

/**
 * A printed example that does not fit its own format. Kept exactly as printed; nothing here is
 * fixed silently. A form corrected by us, if any, is built in the test and named so.
 *
 * @property printed The line(s) as printed. For a name line, only the name line.
 * @property whatIsWrong What is inconsistent, in Doc 9303's own terms.
 */
internal data class AnomalyVector(
    override val id: String,
    override val source: IcaoSource,
    val printed: List<String>,
    val whatIsWrong: String,
    override val knownFinding: String? = null,
) : IcaoVector

internal val P4_F_2: AnomalyVector =
    AnomalyVector(
        id = "P4-F-2",
        source = IcaoSource(part = 4, where = "Appendix A, Figure A-2", pdfPage = 35),
        printed = listOf("PPUTOIVANOVA<<ANNA<<<<<<<<<<<<<<<<<<<<<<<<<<<", "L898902C36UTO7408122F2504155184226<<<<<<<<<18"),
        whatIsWrong =
            "Both lines are 45 characters; TD3 lines are 44. With one filler fewer in each, every " +
                "check digit agrees with the VIZ (IVANOVA, ANNA; personal no. 184226; expiry 15.04.2025).",
    )

internal val P3_AR_6: AnomalyVector =
    AnomalyVector(
        id = "P3-AR-6",
        source = IcaoSource(part = 3, where = "Appendix B.5.9, further examples", pdfPage = 70),
        printed = listOf("JMAL<EBD<ALNAXSSR<<<<<<<<<<<<<<<<<<<<<<<"),
        whatIsWrong = "The name field is 40 characters; its seven siblings and the TD3 name field are 39.",
    )

internal val P7_A_1: AnomalyVector =
    AnomalyVector(
        id = "P7-A-1",
        source = IcaoSource(part = 7, where = "4.2.3 a), usual representation (MRV-A)", pdfPage = 20),
        printed = listOf("V<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<<"),
        whatIsWrong = "The MRV-A name line is 45 characters; MRV-A lines are 44. Figure A-1 prints the 44-character line.",
    )

internal val P7_A_4: AnomalyVector =
    AnomalyVector(
        id = "P7-A-4",
        source = IcaoSource(part = 7, where = "4.2.3 d), apostrophe (MRV-A)", pdfPage = 21),
        printed = listOf("V<UTOOCONNOR<<ENYA<SIOBHAN<<<<<<<<<<"),
        whatIsWrong = "The MRV-A name line is 36 characters, the MRV-B length; it is the same text as P7-B-4.",
    )

internal val P7_VDS_1: AnomalyVector =
    AnomalyVector(
        id = "P7-VDS-1",
        source = IcaoSource(part = 7, where = "Appendix E, Figure E-1 (visible digital seal)", pdfPage = 64),
        printed = listOf("VCD<<DENT<<ARTHUR<PHILIP<<<<<<<<<<<", "1234567XY7GBR5203116M2105253<<<<<<<<"),
        whatIsWrong =
            "Line 1 is 35 characters; the MRV-B shape needs 36. Line 2 is 36 and its three check digits " +
                "agree. Part 10 prints the same name over two cells adding up to 36.",
    )

internal val ANOMALY_VECTORS: List<AnomalyVector> = listOf(P4_F_2, P3_AR_6, P7_A_1, P7_A_4, P7_VDS_1)
