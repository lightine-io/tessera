package io.lightine.tessera.mrz.transliteration

// The Cyrillic table of ICAO Doc 9303 Part 3 (8th edition, 2021), Section 6.B "Transliteration of
// Cyrillic Characters", transcribed from the standard's own text, keyed by the Unicode value the
// table prints. Where the printed glyph and the printed Unicode value disagree, the Unicode value
// is used: 0402 is printed with the glyph of 040B, and 040E with a lower-case glyph.
//
// The table lists capital letters only. The small letter of each listed capital maps the same way,
// as the Latin table does; the MRZ has no lower case.
//
// Letters the table does not list (for example 0403, 040B, 040D, 042C, 044C) are not mapped here
// and become the filler, like every other unmapped character, unless a language's exceptions
// below map them (the soft sign in Bulgarian and Ukrainian).
//
// The table's language exceptions ("except Belorussian = IO", "except if Ukrainian first
// character, then = YE", ...) are kept apart in [CyrillicLanguage]; the ICAO default profile uses
// the main values only.

/** The main values of Section 6.B, capital letters, keyed by the printed Unicode value. */
private val CYRILLIC_CAPITALS: Map<Char, String> =
    mapOf(
        'Ё' to "E", // Ё
        'Ђ' to "D", // Ђ
        'Є' to "IE", // Є
        'Ѕ' to "DZ", // Ѕ
        'І' to "I", // І
        'Ї' to "I", // Ї
        'Ј' to "J", // Ј
        'Љ' to "LJ", // Љ
        'Њ' to "NJ", // Њ
        'Ќ' to "K", // Ќ
        'Ў' to "U", // Ў
        'Џ' to "DZ", // Џ
        'А' to "A", // А
        'Б' to "B", // Б
        'В' to "V", // В
        'Г' to "G", // Г
        'Д' to "D", // Д
        'Е' to "E", // Е
        'Ж' to "ZH", // Ж
        'З' to "Z", // З
        'И' to "I", // И
        'Й' to "I", // Й
        'К' to "K", // К
        'Л' to "L", // Л
        'М' to "M", // М
        'Н' to "N", // Н
        'О' to "O", // О
        'П' to "P", // П
        'Р' to "R", // Р
        'С' to "S", // С
        'Т' to "T", // Т
        'У' to "U", // У
        'Ф' to "F", // Ф
        'Х' to "KH", // Х
        'Ц' to "TS", // Ц
        'Ч' to "CH", // Ч
        'Ш' to "SH", // Ш
        'Щ' to "SHCH", // Щ
        'Ъ' to "IE", // Ъ
        'Ы' to "Y", // Ы
        'Э' to "E", // Э
        'Ю' to "IU", // Ю
        'Я' to "IA", // Я
        'Ѫ' to "U", // Ѫ
        'Ѵ' to "Y", // Ѵ
        'Ґ' to "G", // Ґ
        'Ғ' to "G", // Ғ
        'Һ' to "C", // Һ
    )

/**
 * A language the Section 6.B table names in an exception. Each one changes some letters; every
 * other letter keeps its main value.
 *
 * @property exceptions Capital letter to value, anywhere in a name part. An empty value drops the
 *   letter. May also map a letter the table does not list, such as the soft sign.
 * @property firstCharacterExceptions Capital letter to value, only as the first letter of a name
 *   part (the table's "if Ukrainian first character").
 */
internal enum class CyrillicLanguage(
    val exceptions: Map<Char, String>,
    val firstCharacterExceptions: Map<Char, String> = emptyMap(),
) {
    /** "Belorussian" in the table: Ё is IO, Г is H. */
    BELARUSIAN(mapOf('Ё' to "IO", 'Г' to "H")),

    /**
     * Щ is SHT. The soft sign Ь (and ь) is Y: Bulgaria's Transliteration Act (2009) transliterates
     * it as Y; whether that act's values are used for passports is not verified.
     */
    BULGARIAN(mapOf('Щ' to "SHT", 'Ь' to "Y")),

    /**
     * "The language spoken in the former Yugoslav Republic of Macedonia" in the table: Ќ is KJ, Џ is
     * DJ, Х is H, Ц is C, and Ғ (0492, as printed) is GJ.
     */
    MACEDONIAN(
        mapOf('Ќ' to "KJ", 'Џ' to "DJ", 'Х' to "H", 'Ц' to "C", 'Ғ' to "GJ"),
    ),

    /** Г is H (as printed), Ж is Z, Х is H, Ц is C, Ч is C, Ш is S. */
    SERBIAN(
        mapOf(
            'Г' to "H",
            'Ж' to "Z",
            'Х' to "H",
            'Ц' to "C",
            'Ч' to "C",
            'Ш' to "S",
        ),
    ),

    /**
     * Г is H, И is Y; as the first letter of a name part Є is YE, Ї is YI, Й is Y, Ю is YU, Я is
     * YA. The soft sign Ь (and ь) produces nothing: Ukraine's Cabinet of Ministers resolution
     * No. 55 of 27 January 2010 on transliteration of the Ukrainian alphabet into Latin says the
     * soft sign is not reproduced.
     */
    UKRAINIAN(
        mapOf('Г' to "H", 'И' to "Y", 'Ь' to ""),
        mapOf('Є' to "YE", 'Ї' to "YI", 'Й' to "Y", 'Ю' to "YU", 'Я' to "YA"),
    ),
}

/**
 * Maps one Cyrillic letter, or returns `null` when neither Section 6.B nor the language's
 * exceptions list it.
 *
 * @param char The letter, capital or small.
 * @param language The language whose exceptions apply, or `null` for the main values.
 * @param firstOfNamePart Whether the letter starts a name part.
 */
internal fun mapCyrillic(
    char: Char,
    language: CyrillicLanguage?,
    firstOfNamePart: Boolean,
): String? {
    if (char !in 'Ѐ'..'ӿ') return null
    val capital = if (char in CYRILLIC_CAPITALS) char else char.uppercaseChar()
    if (language != null) {
        if (firstOfNamePart) language.firstCharacterExceptions[capital]?.let { return it }
        language.exceptions[capital]?.let { return it }
    }
    if (capital !in CYRILLIC_CAPITALS) return null
    return CYRILLIC_CAPITALS.getValue(capital)
}
