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
// Punctuation handling: per ICAO Doc 9303 Part 3 §4.6, apostrophes shall be
// omitted (no filler), hyphens shall be converted to the filler character
// (handled implicitly via the unmapped-character fallback), and "all other
// punctuation characters shall be omitted from the MRZ (no filler character
// shall be inserted in their place)." Apostrophe and common Western
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

    // Per ICAO Doc 9303 Part 3 §4.6: apostrophes shall be omitted (no filler).
    // Covers ASCII apostrophe (U+0027), left/right single quotation marks
    // (U+2018 / U+2019), acute accent (U+00B4), and grave accent (U+0060) —
    // characters commonly typed where an apostrophe is intended.
    addAll(map, "'’‘´`", "")

    // Per ICAO Doc 9303 Part 3 §4.6: "All other punctuation characters shall be
    // omitted from the MRZ (no filler character shall be inserted in their place)."
    // Note: '-' (U+002D) is NOT in this list — per §4.6 hyphens convert to filler,
    // which is the unmapped-character fallback the profile already applies.
    addAll(map, "\".,;:?!()[]{}*&@#\$%+=~_/\\|^“”", "")

    return map
}

private fun addAll(
    map: MutableMap<Char, String>,
    chars: String,
    target: String,
) {
    for (c in chars) map[c] = target
}
