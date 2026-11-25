@file:OptIn(ExperimentalTesseraChipApi::class)

package io.lightine.tessera.types.errors

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import io.lightine.tessera.types.vocabulary.MrzField

/**
 * Every [ChipError] the SDK can report, one per subclass and reason, each with a status word where
 * the class carries one. [every] is built through an exhaustive `when` over the sealed hierarchy:
 * a new subclass does not compile until it is added here, and `ChipErrorSubclassScanTest` (JVM)
 * checks that each compiled subclass appears.
 */
internal object ChipErrorSamples {
    private const val SW = 0x6A80

    private fun all(of: ChipError): List<ChipError> =
        when (of) {
            is ChipAccessDenied -> {
                ChipAccessDenied.Reason.entries.map { ChipAccessDenied(ChipAccessProtocol.PACE, it, SW) }
            }

            is ChipSecureMessagingLost -> {
                ChipSecureMessagingLost.Reason.entries.map { ChipSecureMessagingLost(it, SW) }
            }

            is ChipUnexpectedAnswer -> {
                ChipUnexpectedAnswer.Reason.entries.map { ChipUnexpectedAnswer(it, SW) }
            }

            is ChipUnsupportedAlgorithm -> {
                ChipUnsupportedAlgorithm.Reason.entries.map {
                    ChipUnsupportedAlgorithm(
                        ChipAccessProtocol.PACE,
                        it,
                        SW,
                    )
                }
            }

            is ChipMalformedFile -> {
                ChipMalformedFile.Reason.entries.map { ChipMalformedFile(0x0101, it, 3) }
            }

            is ChipBlank -> {
                listOf(ChipBlank)
            }

            is ChipFileNotFound -> {
                listOf(ChipFileNotFound(0x0101, true))
            }

            is ChipKeyCheckDigitMismatch -> {
                listOf(ChipKeyCheckDigitMismatch(setOf(MrzField.DOCUMENT_NUMBER)))
            }

            is NfcUnavailable -> {
                NfcUnavailable.Reason.entries.map { NfcUnavailable(it) }
            }

            is NfcTagLost -> {
                listOf(NfcTagLost)
            }

            is NfcTimeout -> {
                listOf(NfcTimeout(1000))
            }
        }

    /** One error of each subclass, to open each branch of [all]. */
    private val oneOfEach: List<ChipError> =
        listOf(
            ChipAccessDenied(ChipAccessProtocol.PACE, ChipAccessDenied.Reason.KEY_NOT_MATCHED),
            ChipSecureMessagingLost(ChipSecureMessagingLost.Reason.WRONG_CHECKSUM),
            ChipUnexpectedAnswer(ChipUnexpectedAnswer.Reason.NO_STATUS_WORD),
            ChipUnsupportedAlgorithm(ChipAccessProtocol.PACE, ChipUnsupportedAlgorithm.Reason.NO_SUPPORTED_SUITE),
            ChipMalformedFile(null, ChipMalformedFile.Reason.NOT_VALID_TLV),
            ChipBlank,
            ChipFileNotFound(1, false),
            ChipKeyCheckDigitMismatch(emptySet()),
            NfcUnavailable(NfcUnavailable.Reason.DISABLED),
            NfcTagLost,
            NfcTimeout(null),
        )

    val every: List<ChipError> = oneOfEach.flatMap(::all)

    /** The classes that carry a status word of their own. */
    fun carriesStatusWord(e: ChipError): Boolean =
        e is ChipAccessDenied || e is ChipSecureMessagingLost || e is ChipUnexpectedAnswer || e is ChipUnsupportedAlgorithm
}
