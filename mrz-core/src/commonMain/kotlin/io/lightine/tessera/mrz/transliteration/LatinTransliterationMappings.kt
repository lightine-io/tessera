package io.lightine.tessera.mrz.transliteration

// Shared internal builder for the Latin-script mapping table used by profiles
// whose conventions align with ICAO Doc 9303 Part 3 Section 6 (Annex G).
//
// This is implementation sharing, not profile inheritance: the public API has
// no inheritance mechanism (see the project's issue tracker, "Profile inheritance
// for transliteration"). Profiles that want to diverge from these defaults
// build a copy and apply their own overrides; profiles whose conventions are
// not Latin-aligned do not use this helper at all.
//
// Annex G coverage: this builder ships every codepoint in §6.A (Latin-1
// Supplement U+00C0-00DE plus Latin Extended-A U+0100-017D plus the capital
// sharp s U+1E9E), both cases, verified during the 2026-05-18 pre-tag
// conformance pass (see CONFORMANCE-NOTES-2026-05-18.md, finding F5). Where
// Annex G permits multiple recommended transliterations (Ä → "AE or A",
// Å → "AA or A", Ñ → "N or NXX", Ö → "OE or O", Ü → "UE or UXX or U"),
// the no-expansion variant is chosen and applied consistently — the choice
// is recorded in IcaoDefaultTransliterationProfile's KDoc.
//
// Punctuation handling, following ICAO Doc 9303 Part 3 §4.6 (summarized here in
// our own words): an apostrophe is dropped with no filler; a hyphen becomes one
// filler (handled by the unmapped-character fallback); a comma between two name
// components is one filler (handled by mapToMrzAlphabet); any other punctuation
// is dropped with no filler. Apostrophe and common Western
// punctuation are explicitly mapped to the empty string below so the
// fallback-to-filler behaviour does not apply to them.
//
// Schwa note: ICAO Annex G does NOT include the Latin schwa character (Ə U+018F /
// ə U+0259). Its Unicode range (Latin Extended-B, U+0180-024F) is outside the
// Annex G table, which ends at U+017D plus U+1E9E. Profiles that need to map
// schwa (e.g., AzeTransliterationProfile) apply their own override on top of
// this base; the rationale chain (BGN/PCGN 1993 Agreement → ICAO Annex G
// no-expansion) is documented on the AZE profile.
//
// Eth vs D-stroke: Annex G has both U+00D0 (Ð "Eth", Icelandic) and U+0110
// (Đ "D stroke", Croatian/Vietnamese/Sami) mapping to D. These are
// visually similar but distinct codepoints — both are explicitly handled.
internal fun buildIcaoLatinMappings(): MutableMap<Char, String> {
    val map = mutableMapOf<Char, String>()

    for (lower in 'a'..'z') map[lower] = lower.uppercaseChar().toString()

    addAll(map, "ÀÁÂÃÄÅĀĂĄàáâãäåāăą", "A")
    addAll(map, "ÇĆĈĊČçćĉċč", "C")
    addAll(map, "ĎĐďđ", "D")
    addAll(map, "ÈÉÊËĒĔĖĘĚèéêëēĕėęě", "E")
    addAll(map, "ĜĞĠĢĝğġģ", "G")
    addAll(map, "ĤĦĥħ", "H")
    addAll(map, "ÌÍÎÏĨĪĬĮİìíîïĩīĭįı", "I")
    addAll(map, "Ĵĵ", "J")
    addAll(map, "Ķķ", "K")
    addAll(map, "ĹĻĽĿŁĺļľŀł", "L")
    addAll(map, "ÑŃŅŇŊñńņňŋ", "N")
    addAll(map, "ÒÓÔÕÖØŌŎŐòóôõöøōŏő", "O")
    addAll(map, "ŔŖŘŕŗř", "R")
    addAll(map, "ŚŜŞŠśŝşš", "S")
    addAll(map, "ŢŤŦţťŧ", "T")
    addAll(map, "ÙÚÛÜŨŪŬŮŰŲùúûüũūŭůűų", "U")
    addAll(map, "Ŵŵ", "W")
    addAll(map, "ÝŶŸýÿŷ", "Y")
    addAll(map, "ŹŻŽźżž", "Z")

    addAll(map, "Ææ", "AE")
    addAll(map, "Œœ", "OE")
    addAll(map, "ẞß", "SS")
    addAll(map, "Þþ", "TH")
    addAll(map, "Ðð", "D")
    addAll(map, "Ĳĳ", "IJ")

    // ICAO Doc 9303 Part 3 §4.6: an apostrophe is dropped, with no filler.
    // Covers ASCII apostrophe (U+0027), left/right single quotation marks
    // (U+2018 / U+2019), acute accent (U+00B4), and grave accent (U+0060) —
    // characters commonly typed where an apostrophe is intended.
    addAll(map, "'’‘´`", "")

    // ICAO Doc 9303 Part 3 §4.6: other punctuation is dropped from the MRZ, with no
    // filler in its place.
    // Note: '-' (U+002D) is NOT in this list — per §4.6 hyphens convert to filler,
    // which is the unmapped-character fallback the profile already applies.
    // ',' (U+002C) is NOT in this list either: per §4.6 a comma between two name
    // components is one filler; [mapToMrzAlphabet] handles it.
    addAll(map, "\".;:?!()[]{}*&@#\$%+=~_/\\|^“”", "")

    return map
}

private const val COMMA: Char = ','
private const val FILLER: Char = '<'

/**
 * Maps [input] one character at a time with [mapChar], and applies the comma rule of ICAO
 * Doc 9303 Part 3 §4.6: a comma between two name components is one filler.
 *
 * A comma together with the whitespace around it counts as one separator, so `ANNA, MARIA`
 * and `ANNA,MARIA` both give `ANNA<MARIA`. A comma with nothing before it or nothing after it
 * separates nothing and is dropped.
 */
internal fun mapToMrzAlphabet(
    input: String,
    mapChar: (Char) -> String,
): String {
    val output = StringBuilder(input.length)
    var commaPending = false
    for (char in input) {
        if (char == COMMA) {
            commaPending = output.isNotEmpty()
            continue
        }
        if (commaPending && char.isWhitespace()) continue
        val mapped = mapChar(char)
        if (mapped.isEmpty()) continue
        if (commaPending && output.last() != FILLER) output.append(FILLER)
        commaPending = false
        output.append(mapped)
    }
    return output.toString()
}

private fun addAll(
    map: MutableMap<Char, String>,
    chars: String,
    target: String,
) {
    for (c in chars) map[c] = target
}
