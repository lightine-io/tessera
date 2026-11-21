package io.lightine.tessera.mrz.transliteration

// The Arabic-script table of ICAO Doc 9303 Part 3 (8th edition, 2021), Section 6.C
// "Transliteration of Arabic Script", transcribed from the standard's own text, keyed by the
// Unicode value the table prints. Two letters come from the informative Appendix B, B.5.4 ("commonly
// used for foreign names"), which Section 6.C does not list: veh (06A4) and feh with 3 dots below
// (06A5).
//
// Entries marked "(Not encoded)" in Section 6.C produce no character: the short vowels, sukun,
// superscript alef and tatweel (Appendix B, B.5.5.8, B.5.5.10, B.5.5.12, B.5.5.13), and the four
// Maghrib letters 069C, 06A2, 06A7, 06A8 (B.5.8: obsolete and not transliterated).
//
// Two entries depend on context and are handled in [mapArabic]:
// - teh marbuta (0629) is XTA, or XAH at the end of a name part (Section 6.C, note 2; B.5.5.6);
// - shadda (0651) repeats the Latin text of the letter it doubles (Section 6.C, note 3; B.5.5.11).
//
// Letters the table does not list become the filler, like every other unmapped character.

internal const val TEH_MARBUTA: Char = 'ة'
internal const val SHADDA: Char = 'ّ'

private val ARABIC_LETTERS: Map<Char, String> =
    mapOf(
        'ء' to "XE", // hamza
        'آ' to "XAA", // alef with madda above
        'أ' to "XAE", // alef with hamza above
        'ؤ' to "U", // waw with hamza above
        'إ' to "I", // alef with hamza below
        'ئ' to "XI", // yeh with hamza above
        'ا' to "A", // alef
        'ب' to "B", // beh
        'ت' to "T", // teh
        'ث' to "XTH", // theh
        'ج' to "J", // jeem
        'ح' to "XH", // hah
        'خ' to "XKH", // khah
        'د' to "D", // dal
        'ذ' to "XDH", // thal
        'ر' to "R", // reh
        'ز' to "Z", // zain
        'س' to "S", // seen
        'ش' to "XSH", // sheen
        'ص' to "XSS", // sad
        'ض' to "XDZ", // dad
        'ط' to "XTT", // tah
        'ظ' to "XZZ", // zah
        'ع' to "E", // ain
        'غ' to "G", // ghain
        'ف' to "F", // feh
        'ق' to "Q", // qaf
        'ك' to "K", // kaf
        'ل' to "L", // lam
        'م' to "M", // meem
        'ن' to "N", // noon
        'ه' to "H", // heh
        'و' to "W", // waw
        'ى' to "XAY", // alef maksura
        'ي' to "Y", // yeh
        'ٱ' to "XXA", // alef wasla
        'ٹ' to "XXT", // tteh
        'ټ' to "XRT", // teh with ring
        'پ' to "P", // peh
        'ځ' to "XKE", // hah with hamza above
        'څ' to "XXH", // hah with 3 dots above
        'چ' to "XC", // tcheh
        'ڈ' to "XXD", // ddal
        'ډ' to "XDR", // dal with ring
        'ڑ' to "XXR", // rreh
        'ړ' to "XRR", // reh with ring
        'ږ' to "XRX", // reh with dot below and dot above
        'ژ' to "XJ", // jeh
        'ښ' to "XXS", // seen with dot below and dot above
        'ڤ' to "V", // veh (Appendix B, B.5.4)
        'ڥ' to "XF", // feh with 3 dots below (Appendix B, B.5.4)
        'ک' to "XKK", // keheh
        'ګ' to "XXK", // kaf with ring
        'ڭ' to "XNG", // ng
        'گ' to "XGG", // gaf
        'ں' to "XNN", // noon ghunna
        'ڼ' to "XXN", // noon with ring
        'ھ' to "XDO", // heh doachashmee
        'ۀ' to "XYH", // heh with yeh above
        'ہ' to "XXG", // heh goal
        'ۂ' to "XGE", // heh goal with hamza above
        'ۃ' to "XTG", // teh marbuta goal
        'ی' to "XYA", // farsi yeh
        'ۍ' to "XXY", // yeh with tail
        'ې' to "Y", // yeh (Pashto)
        'ے' to "XYB", // yeh barree
        'ۓ' to "XBE", // yeh barree with hamza above
    )

/** The entries Section 6.C marks "(Not encoded)": they produce no character. */
private val ARABIC_NOT_ENCODED: Set<Char> =
    setOf(
        'ـ', // tatweel
        'ً', // fathatan
        'ٌ', // dammatan
        'ٍ', // kasratan
        'َ', // fatha
        'ُ', // damma
        'ِ', // kasra
        'ْ', // sukun
        'ٰ', // superscript alef
        'ڜ', // seen with 3 dots below and 3 dots above
        'ڢ', // feh with dot moved below
        'ڧ', // qaf with dot above
        'ڨ', // qaf with 3 dots above
    )

/** Marks that sit between a letter and its shadda, or after a final teh marbuta, and are skipped. */
private val ARABIC_MARKS: Set<Char> =
    setOf('ـ', 'ً', 'ٌ', 'ٍ', 'َ', 'ُ', 'ِ', 'ّ', 'ْ', 'ٰ')

/**
 * Maps the Arabic-script character at [index], or returns `null` when Section 6.C does not list
 * it.
 *
 * @param lookup Maps the character at an index with the caller's full rules; shadda repeats what
 *   it returns for the doubled letter.
 */
internal fun mapArabic(
    text: String,
    index: Int,
    lookup: (Int) -> String,
): String? {
    val char = text[index]
    return when {
        char == TEH_MARBUTA -> if (endsNamePart(text, index)) "XAH" else "XTA"
        char == SHADDA -> doubledLetter(text, index, lookup)
        char in ARABIC_NOT_ENCODED -> ""
        else -> ARABIC_LETTERS[char]
    }
}

// The letter a shadda doubles is the one before it. In NFC the short vowels sort before shadda,
// so marks are skipped on the way back. A shadda with no letter before it doubles nothing.
private fun doubledLetter(
    text: String,
    index: Int,
    lookup: (Int) -> String,
): String {
    var i = index - 1
    while (i >= 0 && text[i] in ARABIC_MARKS) i--
    if (i < 0 || !text[i].isLetter()) return ""
    return lookup(i)
}

// A name part ends at the end of the text or before a character that is not a letter (a space, a
// hyphen, a comma). Marks after the letter are skipped.
private fun endsNamePart(
    text: String,
    index: Int,
): Boolean {
    var i = index + 1
    while (i < text.length && text[i] in ARABIC_MARKS) i++
    return i >= text.length || !text[i].isLetter()
}
