package io.lightine.tessera.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals

class RedactionTest {
    @Test
    fun masks_uppercase_letters() {
        assertEquals("****", Redaction.redactMrzLine("ABCD"))
    }

    @Test
    fun masks_lowercase_letters() {
        assertEquals("****", Redaction.redactMrzLine("abcd"))
    }

    @Test
    fun masks_digits() {
        assertEquals("****", Redaction.redactMrzLine("1234"))
    }

    @Test
    fun preserves_filler_and_length() {
        val input = "P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"
        val redacted = Redaction.redactMrzLine(input)
        assertEquals(input.length, redacted.length)
        assertEquals("*<***********<<****<******<<<<<<<<<<<<<<<<<<", redacted)
    }

    @Test
    fun handles_empty_string() {
        assertEquals("", Redaction.redactMrzLine(""))
    }

    @Test
    fun redacts_realistic_td3_line_2() {
        // The project's TD3 specimen, line 2: 37 alphanumerics, 5 fillers, then a 2-digit composite check.
        val input = "T220001293UTO9003152F3403141S98765432<<<<<20"
        val redacted = Redaction.redactMrzLine(input)
        assertEquals(input.length, redacted.length)
        assertEquals("*************************************<<<<<**", redacted)
    }

    @Test
    fun preserves_non_alphanumeric_non_filler_characters_verbatim() {
        // Defensive behavior: the redactor does not validate input, so unusual characters
        // pass through unchanged. Documented in the KDoc.
        val input = "AB<C 1!2"
        val redacted = Redaction.redactMrzLine(input)
        assertEquals("**<* *!*", redacted)
    }
}
