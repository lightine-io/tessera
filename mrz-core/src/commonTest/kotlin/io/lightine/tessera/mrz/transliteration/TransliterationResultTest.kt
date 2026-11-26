package io.lightine.tessera.mrz.transliteration

import io.lightine.tessera.types.vocabulary.UnmappedCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TransliterationResultTest {
    @Test
    fun success_equality_includes_replacement_details() {
        assertEquals(TransliterationResult.Success("FOO"), TransliterationResult.Success("FOO"))
        assertNotEquals(TransliterationResult.Success("FOO"), TransliterationResult.Success("BAR"))
        assertNotEquals(
            TransliterationResult.Success("F<", listOf(UnmappedCharacter('Ω', 1))),
            TransliterationResult.Success("F<"),
        )
    }

    @Test
    fun shipped_profiles_report_only_characters_without_a_mapping() {
        val result = IcaoDefaultTransliterationProfile.toMrzAlphabet("ЬAΩƏ")
        assertEquals(
            listOf(
                UnmappedCharacter('Ь', 0),
                UnmappedCharacter('Ω', 2),
                UnmappedCharacter('Ə', 3),
            ),
            (result as TransliterationResult.Success).replacedCharacters,
        )

        assertEquals(
            emptyList(),
            (IcaoDefaultTransliterationProfile.toMrzAlphabet("MÜLLER") as TransliterationResult.Success)
                .replacedCharacters,
        )
        assertEquals(
            emptyList(),
            (IcaoDefaultTransliterationProfile.toMrzAlphabet("A B-C") as TransliterationResult.Success)
                .replacedCharacters,
        )
    }

    @Test
    fun overridden_profiles_report_unmapped_characters_after_applying_overrides() {
        val profile = IcaoDefaultTransliterationProfile.withOverrides("OVERRIDDEN", mapOf('Ω' to "O"))

        val result = profile.toMrzAlphabet("AΩΔ")

        assertEquals(
            listOf(UnmappedCharacter('Δ', 2)),
            (result as TransliterationResult.Success).replacedCharacters,
        )
    }

    @Test
    fun the_one_argument_success_constructor_and_copy_remain_available() {
        val success = TransliterationResult.Success("FOO")

        assertEquals(emptyList(), success.replacedCharacters)
        assertEquals(TransliterationResult.Success("BAR"), success.copy(output = "BAR"))
        val (output) = success
        assertEquals("FOO", output)
    }

    @Test
    fun failure_equality_by_unmapped_list() {
        val a = TransliterationResult.Failure(listOf(UnmappedCharacter('x', 0)))
        val b = TransliterationResult.Failure(listOf(UnmappedCharacter('x', 0)))
        val c = TransliterationResult.Failure(listOf(UnmappedCharacter('x', 1)))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun a_profile_can_surface_unmapped_characters_via_failure() {
        // A failing profile that only knows about the letter 'A'; everything else is unmapped.
        val profile =
            object : TransliterationProfile {
                override val identifier: String = "TEST-A-ONLY"

                override fun toMrzAlphabet(normalizedInput: String): TransliterationResult {
                    val unmapped = mutableListOf<UnmappedCharacter>()
                    for ((position, char) in normalizedInput.withIndex()) {
                        if (char != 'A') unmapped += UnmappedCharacter(char, position)
                    }
                    return if (unmapped.isEmpty()) {
                        TransliterationResult.Success(normalizedInput)
                    } else {
                        TransliterationResult.Failure(unmapped)
                    }
                }
            }

        val result = profile.toMrzAlphabet("AxAy")
        assertEquals(
            TransliterationResult.Failure(
                listOf(
                    UnmappedCharacter('x', 1),
                    UnmappedCharacter('y', 3),
                ),
            ),
            result,
        )
    }
}
