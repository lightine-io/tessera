package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.lightine.tessera.mrz.camera.CameraError
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.SavedImageScanResult
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.parsing.MrzParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Host-side test for the saved-image (photo) reading screens ([SavedImageEmptyContent]) and the pure result
 * mapping ([mapSavedImageResult]) — the saved-image path of [MrzScannerScreen], run on the JVM via Robolectric
 * (no device), same shape as [ReviewScreenTest].
 *
 * The saved-image reader is used in single-read mode (`tolerant = false`, see
 * `MrzScannerScreen.readPickedImage`), so `SavedImageScanResult.candidates` is always empty in practice — the
 * mapping only ever branches on the primary [`scan`][SavedImageScanResult.scan]. The real photo pick + ML Kit
 * read is device-only (the picker launches the system photo picker and the recognizer runs ML Kit), so it is
 * not exercised here — per the testing-layers rule, the pure mapping and the composables are covered on host.
 *
 * The fixtures use the ICAO TD3 specimen the parser tests use (synthetic; UTO = Utopia), parsed at a fixed
 * [referenceTime] so date inference is deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SavedImageScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Fixed so 2-digit year inference is deterministic; at 1994 the specimen parses cleanly to Success.
    private val referenceTime = Instant.parse("1994-01-01T00:00:00Z")

    private val line1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val line2 = "L898902C<3UTO6908061F9406236ZE184226B<<<<<14"

    private fun scanResult(scan: MrzScanResult): SavedImageScanResult =
        SavedImageScanResult(scan = scan, candidates = emptyList(), captureMetadata = null)

    private fun decoded(): MrzScanResult.Decoded =
        MrzScanResult.Decoded(
            parse = MrzParser.parse(listOf(line1, line2), referenceTime = referenceTime),
            recognizedText = RecognizedText(listOf(RecognizedLine(line1, null), RecognizedLine(line2, null))),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = 2),
        )

    // ----------------------------------------------------------------------------------------------------
    // Pure mapping — mapSavedImageResult
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun a_decoded_scan_maps_to_single_decode() {
        val outcome = mapSavedImageResult(scanResult(decoded()))
        val single = assertIs<SavedImageOutcome.SingleDecode>(outcome, "a Decoded scan maps to SingleDecode")
        assertEquals(decoded(), single.decoded)
    }

    @Test
    fun no_mrz_found_maps_to_empty() {
        val noMrz =
            MrzScanResult.NoMrzFound(
                recognizedText = RecognizedText(emptyList()),
                quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
            )
        assertEquals(
            SavedImageOutcome.Empty,
            mapSavedImageResult(scanResult(noMrz)),
            "NoMrzFound maps to Empty",
        )
    }

    @Test
    fun capture_error_maps_to_empty() {
        val captureError =
            MrzScanResult.CaptureError(
                error = CameraError.OcrFailed("decode failed"),
                quality = ScanQuality(mrzRegionFound = false, ocrConfidence = null, recognizedLineCount = 0),
            )
        assertEquals(
            SavedImageOutcome.Empty,
            mapSavedImageResult(scanResult(captureError)),
            "CaptureError maps to Empty",
        )
    }

    // ----------------------------------------------------------------------------------------------------
    // Empty screen
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun empty_screen_renders_and_its_two_actions_fire() {
        var choseDifferent = false
        var wentManual = false
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    SavedImageEmptyContent(
                        onChooseDifferent = { choseDifferent = true },
                        onManualEntry = { wentManual = true },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(SAVED_IMAGE_EMPTY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No MRZ found in this photo").assertIsDisplayed()

        composeRule.onNodeWithText("Choose different photo").performClick()
        assertTrue(choseDifferent, "Choose different photo must fire onChooseDifferent (re-launches the picker)")

        composeRule.onNodeWithText("Enter details manually").performClick()
        assertTrue(wentManual, "Enter details manually must fire onManualEntry")
    }

    @Test
    fun empty_screen_hides_manual_entry_when_the_method_is_disabled() {
        // A consumer without MANUAL_ENTRY in enabledMethods must not be offered an escape into a screen they
        // cannot reach any other way.
        composeRule.setContent {
            MrzScannerConfig().let { config ->
                TesseraScannerTheme(config.theme) {
                    SavedImageEmptyContent(
                        onChooseDifferent = {},
                        onManualEntry = {},
                        showManualEntry = false,
                    )
                }
            }
        }

        composeRule.onNodeWithTag(SAVED_IMAGE_EMPTY_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Choose different photo").assertIsDisplayed()
        composeRule.onNodeWithText("Enter details manually").assertDoesNotExist()
    }
}
