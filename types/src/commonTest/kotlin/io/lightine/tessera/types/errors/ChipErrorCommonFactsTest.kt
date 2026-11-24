@file:OptIn(ExperimentalTesseraChipApi::class)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import io.lightine.tessera.types.vocabulary.MrzField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The facts every [ChipError] offers without a cast: [ChipError.statusWord] and [ChipError.code]. */
class ChipErrorCommonFactsTest {
    private val shape = Regex("[a-z]+(-[a-z]+)*(\\.[a-z]+(-[a-z]+)*)?")

    private val everyError: List<ChipError> =
        buildList {
            ChipAccessDenied.Reason.entries.forEach { add(ChipAccessDenied(ChipAccessProtocol.PACE, it, 0x6300)) }
            ChipSecureMessagingLost.Reason.entries.forEach { add(ChipSecureMessagingLost(it)) }
            ChipUnexpectedAnswer.Reason.entries.forEach { add(ChipUnexpectedAnswer(it)) }
            ChipUnsupportedAlgorithm.Reason.entries.forEach { add(ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, it)) }
            ChipMalformedFile.Reason.entries.forEach { add(ChipMalformedFile(null, it)) }
            NfcUnavailable.Reason.entries.forEach { add(NfcUnavailable(it)) }
            add(ChipBlank)
            add(ChipFileNotFound(0x0101, true))
            add(ChipKeyCheckDigitMismatch(setOf(MrzField.DOCUMENT_NUMBER)))
            add(NfcTagLost)
            add(NfcTimeout(1000))
        }

    @Test
    fun everyErrorHasItsOwnStableCodeOfTheDocumentedShape() {
        val codes = everyError.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "codes must be unique: $codes")
        codes.forEach { assertTrue(shape.matches(it), it) }
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
        assertEquals("timeout", NfcTimeout(null).code)
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
