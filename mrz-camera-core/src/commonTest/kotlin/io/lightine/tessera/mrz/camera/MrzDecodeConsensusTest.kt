package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Host tests for [MrzDecodeConsensus] — the frame-agreement gate that removes transient OCR misreads by
 * requiring the same parsed document on [threshold][MrzDecodeConsensus.threshold] frames before a reading is
 * confirmed. Synthetic MRZ only (the ICAO Doc 9303 Utopia specimen); the "misread" fixture flips one filler
 * `<` in the name to `K`, reproducing the checksum-invisible `ASGAR<` → `ASGARK` misread the gate exists for.
 */
class MrzDecodeConsensusTest {
    private val referenceTime = Instant.parse("2026-05-04T12:00:00Z")

    // A well-formed TD3, and the same document with one filler in the given-name field misread as a letter.
    // The name is not covered by any check digit, so both parse to Success — indistinguishable except by
    // their (different) documents, which is exactly what consensus keys on.
    private val td3Line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"
    private val goodDecode = decode("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", td3Line2)
    private val misreadDecode = decode("P<UTOERIKSSON<<ANNA<MARIAK<<<<<<<<<<<<<<<<<<", td3Line2)

    private fun decode(
        line1: String,
        line2: String,
    ): MrzScanResult.Decoded =
        MrzScanResult.Decoded(
            parse = MrzParser.parse(listOf(line1, line2), referenceTime),
            recognizedText = RecognizedText(listOf(RecognizedLine(line1, null), RecognizedLine(line2, null))),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
        )

    @Test
    fun a_single_decode_does_not_confirm_under_the_default_threshold() {
        val verdict = MrzDecodeConsensus().offer(goodDecode)

        assertEquals(ConsensusVerdict.Gathering(agreement = 1, threshold = 2), verdict)
    }

    @Test
    fun the_threshold_th_agreeing_frame_confirms_that_reading() {
        val consensus = MrzDecodeConsensus(threshold = 3)

        assertIs<ConsensusVerdict.Gathering>(consensus.offer(goodDecode))
        assertIs<ConsensusVerdict.Gathering>(consensus.offer(goodDecode))
        val third = consensus.offer(goodDecode)

        assertEquals(ConsensusVerdict.Confirmed(goodDecode), third)
    }

    @Test
    fun a_transient_misread_between_correct_reads_never_wins() {
        val consensus = MrzDecodeConsensus(threshold = 3)

        // Correct, a one-off misread, then two more correct: the correct reading reaches 3 while the misread
        // stays a minority of one — the confirmed reading is the correct document, not ASGARK-style noise.
        consensus.offer(goodDecode)
        consensus.offer(misreadDecode)
        consensus.offer(goodDecode)
        val verdict = consensus.offer(goodDecode)

        assertEquals(ConsensusVerdict.Confirmed(goodDecode), verdict)
    }

    @Test
    fun threshold_one_confirms_the_first_decode() {
        val verdict = MrzDecodeConsensus(threshold = 1).offer(goodDecode)

        assertEquals(ConsensusVerdict.Confirmed(goodDecode), verdict)
    }

    @Test
    fun reset_discards_accumulated_votes() {
        val consensus = MrzDecodeConsensus(threshold = 3)
        consensus.offer(goodDecode)
        consensus.offer(goodDecode)

        consensus.reset()
        val afterReset = consensus.offer(goodDecode)

        assertEquals(ConsensusVerdict.Gathering(agreement = 1, threshold = 3), afterReset)
    }

    @Test
    fun once_confirmed_a_later_frame_still_reports_the_settled_reading() {
        val consensus = MrzDecodeConsensus(threshold = 1)
        consensus.offer(goodDecode)

        // A different reading offered after settling does not overturn or re-count it (the caller's own latch
        // normally stops offering, but the gate is idempotent regardless).
        val afterSettle = consensus.offer(misreadDecode)

        assertEquals(ConsensusVerdict.Confirmed(goodDecode), afterSettle)
    }

    @Test
    fun a_failure_decode_carries_no_document_and_does_not_advance() {
        val failure = decode("GARBAGE", "NOT-AN-MRZ")
        assertIs<ParseResult.Failure>(failure.parse)
        val consensus = MrzDecodeConsensus(threshold = 3)

        consensus.offer(goodDecode)
        val verdict = consensus.offer(failure)

        // The failure did not count: the leading reading is still at one vote, not confirmed.
        assertEquals(ConsensusVerdict.Gathering(agreement = 1, threshold = 3), verdict)
    }

    @Test
    fun a_non_positive_threshold_is_rejected() {
        assertFailsWith<IllegalArgumentException> { MrzDecodeConsensus(threshold = 0) }
    }
}
