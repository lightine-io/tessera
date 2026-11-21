package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.lightine.tessera.mrz.camera.MrzDecodeConsensus
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.ScanQuality
import io.lightine.tessera.mrz.generation.GenerationResult
import io.lightine.tessera.mrz.generation.MrzGenerator
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.types.vocabulary.DocumentCategory
import io.lightine.tessera.types.vocabulary.Sex
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * TES-103: `MrzScannerConfig.acceptedDocumentTypes`, the app's intake rule, across the three reading methods.
 *
 * The pure seams ([routeDecode], [stepLiveDecode], [notAcceptedStateFor], the saver) are tested directly; the
 * manual-entry path is driven end to end through [MrzScannerScreen] (it needs no camera); the camera notice
 * and the photo screen are rendered through their own composables, because the live preview and the photo
 * picker cannot run under Robolectric.
 *
 * Fixtures: the published ICAO TD3 specimen (UTO, the reserved test issuer) as the passport, and lines built
 * with the SDK's own [MrzGenerator] for an ID card (TD1, code `I`) and for a code with no category (`X`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AcceptedDocumentTypesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val referenceTime = Instant.parse("1994-01-01T00:00:00Z")

    private val passportLines =
        listOf(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
            "L898902C<3UTO6908061F9406236ZE184226B<<<<<14",
        )

    private val idCardLines: List<String> =
        generated(
            MrzGenerator.generateTD1(
                documentType = "I",
                issuingState = "UTO",
                documentNumber = "D23145890",
                primaryIdentifier = "SPECIMEN",
                secondaryIdentifier = "TEST",
                nationality = "UTO",
                dateOfBirth = LocalDate(1974, 8, 12),
                sex = Sex.FEMALE,
                dateOfExpiry = LocalDate(2032, 4, 15),
                optionalData1 = "",
                optionalData2 = "",
            ),
        )

    // A document type code whose first letter is none of P, I, A, C, V: broadCategory is null.
    private val uncategorizedLines: List<String> =
        generated(
            MrzGenerator.generateTD3(
                documentType = "X",
                issuingState = "UTO",
                documentNumber = "L898902C",
                primaryIdentifier = "SPECIMEN",
                secondaryIdentifier = "TEST",
                nationality = "UTO",
                dateOfBirth = LocalDate(1969, 8, 6),
                sex = Sex.FEMALE,
                dateOfExpiry = LocalDate(2032, 6, 23),
                personalNumber = "",
            ),
        )

    private fun generated(result: GenerationResult): List<String> = assertIs<GenerationResult.Success>(result).mrz

    private fun decoded(lines: List<String>): MrzScanResult.Decoded =
        MrzScanResult.Decoded(
            parse = MrzParser.parse(lines, referenceTime = referenceTime),
            recognizedText = RecognizedText(lines.map { RecognizedLine(it, null) }),
            quality = ScanQuality(mrzRegionFound = true, ocrConfidence = null, recognizedLineCount = lines.size),
        )

    private val passport get() = decoded(passportLines)
    private val idCard get() = decoded(idCardLines)
    private val uncategorized get() = decoded(uncategorizedLines)

    private val passportOnly = setOf(DocumentCategory.PASSPORT)
    private val idCardOnly = setOf(DocumentCategory.IDENTITY_CARD)

    // ---------------------------------------------------------------------------------------------------
    // Fixtures and config
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun fixtures_parse_to_the_categories_the_tests_rely_on() {
        fun category(d: MrzScanResult.Decoded): DocumentCategory? {
            val parse = d.parse
            assertTrue(parse !is ParseResult.Failure, "fixture must parse: $parse")
            val document = if (parse is ParseResult.Success) parse.document else (parse as ParseResult.PartialSuccess).document
            return document.commonFields.documentType.broadCategory
        }
        assertEquals(DocumentCategory.PASSPORT, category(passport))
        assertEquals(DocumentCategory.IDENTITY_CARD, category(idCard))
        assertNull(category(uncategorized))
    }

    @Test
    fun default_accepts_every_category_and_an_empty_set_is_refused() {
        assertEquals(DocumentCategory.entries.toSet(), MrzScannerConfig().acceptedDocumentTypes)
        assertEquals(passportOnly, MrzScannerConfig { acceptedDocumentTypes = passportOnly }.acceptedDocumentTypes)
        assertFailsWith<IllegalArgumentException> { MrzScannerConfig { acceptedDocumentTypes = emptySet() } }
    }

    @Test
    fun the_opened_config_keeps_the_accepted_set_across_a_recreation() {
        val original = MrzScannerConfig { acceptedDocumentTypes = setOf(DocumentCategory.VISA, DocumentCategory.PASSPORT) }
        val restored = assertNotNull(decodeOpenedConfig(encodeOpenedConfig(original), null))
        assertEquals(original.acceptedDocumentTypes, restored.acceptedDocumentTypes)
        // An unknown category name or an empty list is unreadable, so the config passed at that moment is used.
        val valid = encodeOpenedConfig(original)
        assertNull(decodeOpenedConfig(valid.toMutableList().also { it[11] = arrayListOf("NOT_A_CATEGORY") }, null))
        assertNull(decodeOpenedConfig(valid.toMutableList().also { it[11] = arrayListOf<String>() }, null))
    }

    // ---------------------------------------------------------------------------------------------------
    // routeDecode: saved image and manual entry route through it
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun default_routing_is_unchanged() {
        val all = DocumentCategory.entries.toSet()
        assertIs<DecodeRoute.ShowReview>(routeDecode(passport, ReviewMode.REVIEW, all))
        assertIs<DecodeRoute.ReturnConfirmed>(routeDecode(passport, ReviewMode.INSTANT_RETURN, all))
        assertIs<DecodeRoute.ShowReview>(routeDecode(idCard, ReviewMode.REVIEW, all))
        assertEquals(routeDecode(passport, ReviewMode.REVIEW), routeDecode(passport, ReviewMode.REVIEW, all))
    }

    @Test
    fun a_reading_the_app_does_not_accept_is_neither_reviewed_nor_returned() {
        assertEquals(DecodeRoute.NotAccepted(DocumentCategory.PASSPORT), routeDecode(passport, ReviewMode.REVIEW, idCardOnly))
        // Instant-return never hands it back either.
        assertEquals(
            DecodeRoute.NotAccepted(DocumentCategory.PASSPORT),
            routeDecode(passport, ReviewMode.INSTANT_RETURN, idCardOnly),
        )
        assertIs<DecodeRoute.ShowReview>(routeDecode(idCard, ReviewMode.REVIEW, idCardOnly))
        assertIs<DecodeRoute.ReturnConfirmed>(routeDecode(idCard, ReviewMode.INSTANT_RETURN, idCardOnly))
    }

    @Test
    fun a_reading_with_no_category_is_not_filtered() {
        assertIs<DecodeRoute.ShowReview>(routeDecode(uncategorized, ReviewMode.REVIEW, passportOnly))
        assertIs<DecodeRoute.ReturnConfirmed>(routeDecode(uncategorized, ReviewMode.INSTANT_RETURN, passportOnly))
    }

    @Test
    fun a_parse_failure_still_shows_read_failed() {
        val garbled = decoded(listOf("P<UTO", "NOT AN MRZ"))
        assertIs<ParseResult.Failure>(garbled.parse)
        assertIs<DecodeRoute.ShowReadFailed>(routeDecode(garbled, ReviewMode.REVIEW, idCardOnly))
    }

    @Test
    fun each_method_lands_on_its_own_not_accepted_state() {
        val struggling = ScannerUiState.Scanning(struggling = true, gathering = true)
        assertEquals(
            ScannerUiState.Scanning(struggling = true, gathering = false, notAccepted = DocumentCategory.PASSPORT),
            notAcceptedStateFor(ScanMethod.CAMERA, DocumentCategory.PASSPORT, struggling, manualDraft = ""),
        )
        assertEquals(
            ScannerUiState.SavedImageNotAccepted(DocumentCategory.PASSPORT),
            notAcceptedStateFor(ScanMethod.SAVED_IMAGE, DocumentCategory.PASSPORT, ScannerUiState.SavedImageAnalyzing, ""),
        )
        assertEquals(
            ScannerUiState.ManualRaw(text = "typed", notAccepted = DocumentCategory.PASSPORT),
            notAcceptedStateFor(ScanMethod.MANUAL_ENTRY, DocumentCategory.PASSPORT, ScannerUiState.ManualRaw("typed"), "typed"),
        )
    }

    // ---------------------------------------------------------------------------------------------------
    // Live camera: consensus and the decode latch
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun live_default_gathers_then_routes_as_before() {
        val consensus = MrzDecodeConsensus(2)
        val all = DocumentCategory.entries.toSet()
        assertEquals(LiveDecodeStep.Gathering, stepLiveDecode(passport, false, consensus, all))
        assertEquals(LiveDecodeStep.Route(passport), stepLiveDecode(passport, false, consensus, all))
        assertEquals(LiveDecodeStep.Ignore, stepLiveDecode(passport, true, consensus, all))
    }

    @Test
    fun live_not_accepted_reading_does_not_latch_and_a_later_accepted_document_routes() {
        val consensus = MrzDecodeConsensus(2)
        var routed = false

        fun step(d: MrzScanResult.Decoded): LiveDecodeStep {
            val s = stepLiveDecode(d, routed, consensus, passportOnly)
            if (s is LiveDecodeStep.Route) routed = true
            return s
        }

        // The ID card gathers (no "hold steady" cue), then the frames agree: a notice, not a route.
        assertEquals(LiveDecodeStep.NotAcceptedGathering(DocumentCategory.IDENTITY_CARD), step(idCard))
        assertEquals(LiveDecodeStep.NotAccepted(DocumentCategory.IDENTITY_CARD), step(idCard))
        assertTrue(!routed, "a not-accepted reading must not latch the session")
        // The consensus was reset: one more ID card frame gathers again rather than confirming at once.
        assertEquals(LiveDecodeStep.NotAcceptedGathering(DocumentCategory.IDENTITY_CARD), step(idCard))
        // A passport now: gathers, then routes.
        assertEquals(LiveDecodeStep.Gathering, step(passport))
        val route = assertIs<LiveDecodeStep.Route>(step(passport))
        assertEquals(passport, route.decoded)
        assertTrue(routed)
    }

    @Test
    fun live_single_frame_consensus_and_unknown_category() {
        val consensus = MrzDecodeConsensus(1)
        assertEquals(LiveDecodeStep.NotAccepted(DocumentCategory.IDENTITY_CARD), stepLiveDecode(idCard, false, consensus, passportOnly))
        assertEquals(LiveDecodeStep.NotAccepted(DocumentCategory.IDENTITY_CARD), stepLiveDecode(idCard, false, consensus, passportOnly))
        assertEquals(LiveDecodeStep.Route(uncategorized), stepLiveDecode(uncategorized, false, consensus, passportOnly))
    }

    @Test
    fun live_notice_keeps_struggling_and_drops_the_gathering_cue() {
        val next = showNotAcceptedNotice(ScannerUiState.Scanning(struggling = true, gathering = true), DocumentCategory.VISA)
        assertEquals(ScannerUiState.Scanning(struggling = true, gathering = false, notAccepted = DocumentCategory.VISA), next)
        // Off the live preview it changes nothing.
        assertEquals(ScannerUiState.CameraInUse, showNotAcceptedNotice(ScannerUiState.CameraInUse, DocumentCategory.VISA))
    }

    // ---------------------------------------------------------------------------------------------------
    // Saved state, back and switcher
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun not_accepted_states_hold_no_document_data_after_process_death_or_recreation() {
        assertEquals(
            ScannerUiState.AwaitingSavedImagePick,
            decodeScannerUiState(encodeScannerUiState(ScannerUiState.SavedImageNotAccepted(DocumentCategory.PASSPORT))),
        )
        assertEquals(
            ScannerUiState.ManualRaw(),
            decodeScannerUiState(encodeScannerUiState(ScannerUiState.ManualRaw("x", notAccepted = DocumentCategory.PASSPORT))),
        )
        assertEquals(
            ScannerUiState.Scanning(),
            settleAfterRecreation(ScannerUiState.Scanning(notAccepted = DocumentCategory.PASSPORT)),
        )
    }

    @Test
    fun the_photo_not_accepted_screen_behaves_like_the_other_photo_prompts() {
        val state = ScannerUiState.SavedImageNotAccepted(DocumentCategory.PASSPORT)
        assertEquals(BackEffect.ReturnToCamera, backEffect(state, cameraEnabled = true))
        assertEquals(BackEffect.Cancel, backEffect(state, cameraEnabled = false))
        assertTrue(showsMethodSwitcher(state))
        assertEquals(ScanMethod.SAVED_IMAGE, activeMethod(state))
        assertEquals(state, gateRestoredState(state, setOf(ScanMethod.SAVED_IMAGE)))
        assertNull(gateRestoredState(state, setOf(ScanMethod.CAMERA)))
    }

    // ---------------------------------------------------------------------------------------------------
    // Manual entry, end to end through MrzScannerScreen
    // ---------------------------------------------------------------------------------------------------

    private fun typeAndRead(lines: List<String>) {
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextClearance()
        composeRule.onNodeWithTag(MANUAL_RAW_FIELD_TEST_TAG).performTextInput(lines.joinToString("\n"))
        composeRule.onNodeWithText("Read").performScrollTo().performClick()
    }

    @Test
    fun manual_entry_stays_on_the_entry_screen_then_accepts_an_accepted_document() {
        val results = mutableListOf<MrzScannerResult>()
        composeRule.setContent {
            MrzScannerScreen(
                config =
                    MrzScannerConfig {
                        enabledMethods = setOf(ScanMethod.MANUAL_ENTRY)
                        acceptedDocumentTypes = idCardOnly
                    },
                onResult = { results += it },
            )
        }

        typeAndRead(passportLines)
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(MANUAL_RAW_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(MANUAL_NOT_ACCEPTED_TEST_TAG).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("This looks like a passport", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("This app accepts: ID card.", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithTag(MANUAL_NOT_ACCEPTED_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        assertTrue(results.isEmpty())

        // An edit clears the note; an ID card then goes to review as usual.
        typeAndRead(idCardLines)
        composeRule.onNodeWithTag(MANUAL_NOT_ACCEPTED_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()
        assertTrue(results.isEmpty())
    }

    @Test
    fun manual_entry_under_instant_return_returns_only_the_accepted_document() {
        val results = mutableListOf<MrzScannerResult>()
        composeRule.setContent {
            MrzScannerScreen(
                config =
                    MrzScannerConfig {
                        enabledMethods = setOf(ScanMethod.MANUAL_ENTRY)
                        reviewMode = ReviewMode.INSTANT_RETURN
                        acceptedDocumentTypes = idCardOnly
                    },
                onResult = { results += it },
            )
        }

        typeAndRead(passportLines)
        composeRule.onNodeWithTag(MANUAL_NOT_ACCEPTED_TEST_TAG).performScrollTo().assertIsDisplayed()
        assertTrue(results.isEmpty(), "a not-accepted reading must not be returned under instant-return")

        typeAndRead(idCardLines)
        val confirmed = assertIs<MrzScannerResult.Confirmed>(results.single())
        assertEquals(
            idCardLines,
            confirmed.result.recognizedText.lines
                .map { it.text },
        )
    }

    @Test
    fun a_not_accepted_reading_leaves_the_session_deadline_running() {
        val results = mutableListOf<MrzScannerResult>()
        composeRule.setContent {
            MrzScannerScreen(
                config =
                    MrzScannerConfig {
                        enabledMethods = setOf(ScanMethod.MANUAL_ENTRY)
                        acceptedDocumentTypes = idCardOnly
                        scanTimeout = 2.seconds
                    },
                onResult = { results += it },
            )
        }

        typeAndRead(passportLines)
        composeRule.onNodeWithTag(MANUAL_NOT_ACCEPTED_TEST_TAG).performScrollTo().assertIsDisplayed()
        assertTrue(results.isEmpty())

        repeat(10) {
            ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))
            composeRule.mainClock.advanceTimeBy(500)
        }
        composeRule.waitForIdle()
        assertEquals(listOf<MrzScannerResult>(MrzScannerResult.Cancelled(DismissReason.TIMED_OUT)), results)
    }

    @Test
    fun manual_entry_with_the_default_and_with_an_unknown_category_goes_to_review() {
        composeRule.setContent {
            MrzScannerScreen(
                config =
                    MrzScannerConfig {
                        enabledMethods = setOf(ScanMethod.MANUAL_ENTRY)
                        acceptedDocumentTypes = passportOnly
                    },
                onResult = {},
            )
        }
        typeAndRead(uncategorizedLines)
        composeRule.onNodeWithTag(MANUAL_NOT_ACCEPTED_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun manual_entry_default_config_reviews_any_category() {
        composeRule.setContent {
            MrzScannerScreen(config = MrzScannerConfig { enabledMethods = setOf(ScanMethod.MANUAL_ENTRY) }, onResult = {})
        }
        typeAndRead(idCardLines)
        composeRule.onNodeWithTag(REVIEW_TEST_TAG).assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------------------------------
    // Camera notice and photo screen, rendered directly
    // ---------------------------------------------------------------------------------------------------

    @Test
    fun camera_notice_states_what_was_read_and_what_is_accepted_and_is_announced() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                MrzGuideOverlay(
                    struggling = true,
                    notAccepted = DocumentCategory.IDENTITY_CARD,
                    acceptedDocumentTypes = setOf(DocumentCategory.VISA, DocumentCategory.PASSPORT),
                )
            }
        }
        composeRule.onNodeWithTag(NOT_ACCEPTED_HINT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("This looks like an ID card", useUnmergedTree = true).assertExists()
        // Listed in DocumentCategory order, whatever the set's order.
        composeRule.onNodeWithText("This app accepts: passport, visa.", useUnmergedTree = true).assertExists()
        composeRule
            .onNodeWithTag(NOT_ACCEPTED_HINT_TEST_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        // The notice ranks above the struggling hint.
        composeRule.onNodeWithTag(STRUGGLING_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun camera_overlay_without_a_notice_is_unchanged() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) { MrzGuideOverlay() }
        }
        composeRule.onNodeWithTag(NOT_ACCEPTED_HINT_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(GUIDE_HINT_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun photo_screen_offers_another_photo_and_manual_entry() {
        var chooseDifferent = 0
        var manual = 0
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                SavedImageNotAcceptedContent(
                    read = DocumentCategory.OTHER,
                    acceptedDocumentTypes = passportOnly,
                    onChooseDifferent = { chooseDifferent++ },
                    onManualEntry = { manual++ },
                )
            }
        }
        composeRule.onNodeWithTag(SAVED_IMAGE_NOT_ACCEPTED_TEST_TAG).assertIsDisplayed()
        composeRule
            .onNodeWithText("This looks like another kind of document")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        composeRule.onNodeWithText("This app accepts: passport.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose different photo").performClick()
        composeRule.onNodeWithText("Enter details manually").performClick()
        assertEquals(1, chooseDifferent)
        assertEquals(1, manual)
    }
}
