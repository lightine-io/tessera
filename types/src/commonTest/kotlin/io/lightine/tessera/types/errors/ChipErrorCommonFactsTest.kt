@file:OptIn(ExperimentalTesseraChipApi::class)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The facts every [ChipError] offers without a cast: [ChipError.statusWord] and [ChipError.code]. */
class ChipErrorCommonFactsTest {
    private val shape = Regex("[a-z]+(-[a-z]+)*(\\.[a-z]+(-[a-z]+)*)?")

    private val everyError: List<ChipError> = ChipErrorSamples.every

    @Test
    fun everyErrorHasItsOwnStableCodeOfTheDocumentedShape() {
        val codes = everyError.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "codes must be unique: $codes")
        codes.forEach { assertTrue(shape.matches(it), it) }
    }

    @Test
    fun everySubclassAndEveryReasonIsWalked() {
        val classes = everyError.map { it::class }.toSet()
        assertEquals(12, classes.size, "one sample list per subclass: $classes")
        val reasons =
            ChipAccessDenied.Reason.entries.size + ChipSecureMessagingLost.Reason.entries.size +
                ChipUnexpectedAnswer.Reason.entries.size + ChipUnsupportedAlgorithm.Reason.entries.size +
                ChipMalformedFile.Reason.entries.size + NfcUnavailable.Reason.entries.size + NfcTimeout.Limit.entries.size
        assertEquals(reasons + 5, everyError.size)
    }

    @Test
    fun aStatusWordTheErrorCarriesIsReadFromTheRoot() {
        for (e in everyError) {
            if (ChipErrorSamples.carriesStatusWord(e)) {
                assertEquals(0x6A80, e.statusWord, "$e")
            } else {
                assertNull(e.statusWord, "$e")
            }
            assertTrue(e.code.isNotEmpty(), "$e")
        }
    }

    @Test
    fun theCodesDoNotChange() {
        assertEquals(
            "access-denied.key-not-matched",
            ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED).code,
        )
        assertEquals("secure-messaging-lost.wrong-checksum", ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.WRONG_CHECKSUM).code)
        assertEquals("unexpected-answer.no-status-word", ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.NO_STATUS_WORD).code)
        assertEquals(
            "unsupported-algorithm.no-supported-suite",
            ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.NO_SUPPORTED_SUITE).code,
        )
        assertEquals("malformed-file.not-valid-tlv", ChipMalformedFile(null, ChipMalformedFile.Reason.NOT_VALID_TLV).code)
        assertEquals("unavailable.disabled", NfcUnavailable(NfcUnavailable.Reason.DISABLED).code)
        assertEquals("blank", ChipBlank.code)
        assertEquals("file-not-found", ChipFileNotFound(1, false).code)
        assertEquals("key-check-digit-mismatch", ChipKeyCheckDigitMismatch(emptySet()).code)
        assertEquals("tag-lost", NfcTagLost.code)
        assertEquals("no-answer", NfcNoAnswer.code)
        assertEquals("timeout.command", NfcTimeout(null, NfcTimeout.Limit.COMMAND).code)
        assertEquals("timeout.wait-for-document", NfcTimeout(5_000, NfcTimeout.Limit.WAIT_FOR_DOCUMENT).code)
        assertEquals("timeout.session", NfcTimeout(null, NfcTimeout.Limit.SESSION).code)
        assertEquals("unavailable.tag-reading-disabled", NfcUnavailable(NfcUnavailable.Reason.TAG_READING_DISABLED).code)
        assertEquals("unavailable.command-too-long", NfcUnavailable(NfcUnavailable.Reason.COMMAND_TOO_LONG).code)
        assertEquals("unavailable.not-eligible", NfcUnavailable(NfcUnavailable.Reason.NOT_ELIGIBLE).code)
        assertEquals("unavailable.access-not-answered", NfcUnavailable(NfcUnavailable.Reason.ACCESS_NOT_ANSWERED).code)
        assertEquals("unavailable.unexpected-fault", NfcUnavailable(NfcUnavailable.Reason.UNEXPECTED_FAULT).code)
    }

    // Every code, written out: a code is public contract, so a rename of a reason or a change of the
    // slug rule fails here, value by value, including the reasons no read produces today.
    @Test
    fun everyCodeIsExactlyTheDocumentedString() {
        val expected =
            listOf(
                "access-denied.chip-refused",
                "access-denied.file-protected",
                "access-denied.key-blocked",
                "access-denied.key-cleared",
                "access-denied.key-deactivated",
                "access-denied.key-needs-pace",
                "access-denied.key-not-matched",
                "access-denied.key-suspended",
                "access-denied.key-tries-limited",
                "access-denied.unexpected-chip-answer",
                "blank",
                "file-not-found",
                "key-check-digit-mismatch",
                "malformed-file.invalid-element",
                "malformed-file.missing-element",
                "malformed-file.nesting-too-deep",
                "malformed-file.not-signed-data",
                "malformed-file.not-valid-tlv",
                "malformed-file.unexpected-content-type",
                "malformed-file.unexpected-file-tag",
                "no-answer",
                "secure-messaging-lost.chip-ended-session",
                "secure-messaging-lost.malformed-answer",
                "secure-messaging-lost.missing-checksum",
                "secure-messaging-lost.plain-length-hint",
                "secure-messaging-lost.session-closed",
                "secure-messaging-lost.unauthenticated-status",
                "secure-messaging-lost.wrong-checksum",
                "secure-messaging-lost.wrong-padding",
                "tag-lost",
                "timeout.command",
                "timeout.session",
                "timeout.wait-for-document",
                "unavailable.access-not-answered",
                "unavailable.activity-destroyed",
                "unavailable.busy",
                "unavailable.cancelled-by-user",
                "unavailable.closed",
                "unavailable.command-too-long",
                "unavailable.disabled",
                "unavailable.ended-by-system",
                "unavailable.not-eligible",
                "unavailable.not-permitted",
                "unavailable.not-supported",
                "unavailable.read-in-progress",
                "unavailable.request-refused",
                "unavailable.tag-reading-disabled",
                "unavailable.unexpected-fault",
                "unexpected-answer.no-status-word",
                "unexpected-answer.unexpected-status-word",
                "unexpected-answer.unusable-file-answer",
                "unsupported-algorithm.chip-refused-suite",
                "unsupported-algorithm.cryptography-failed",
                "unsupported-algorithm.extended-length-required",
                "unsupported-algorithm.no-cryptography-on-platform",
                "unsupported-algorithm.no-supported-suite",
            )
        assertEquals(expected, everyError.map { it.code }.sorted())
    }

    @Test
    fun theStatusWordIsReadFromTheRootAndIsNullWhereThereIsNone() {
        val denied: ChipError = ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED, 0x63C2)
        assertEquals(0x63C2, denied.statusWord)
        assertEquals(0x6A82, (ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.UNEXPECTED_STATUS_WORD, 0x6A82) as ChipError).statusWord)
        assertNull((ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.WRONG_PADDING) as ChipError).statusWord)
        assertNull((ChipBlank as ChipError).statusWord)
        assertNull((NfcTagLost as NfcError).statusWord)
        assertNull((ChipMalformedFile(7, ChipMalformedFile.Reason.NOT_VALID_TLV, 3) as ChipError).statusWord)
    }

    @Test
    fun aCodeNeverHoldsADocumentValue() {
        val e = ChipFileNotFound(0x0101, true)
        assertTrue("0101" !in e.code && "257" !in e.code)
    }
}
