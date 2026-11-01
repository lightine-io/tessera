package io.lightine.tessera.mrz.camera.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TES-143: freezes the `tessera_*` string resource key set of this module.
 *
 * These keys are the consumer override contract: an app overrides the default UI copy by defining the same key in
 * its own `strings.xml`. Removing or renaming a key silently drops that override. The generated `R` class used to
 * freeze the key set as ABI, but it left the ABI baseline at the 2026-10 cadence, so this test takes over.
 *
 * It reads `strings.xml` (the source of truth) and compares every `<string>` and `<plurals>` name with the
 * hard-coded list below. The Gradle test task runs with the module directory as its working directory, so the
 * relative path resolves.
 */
class TesseraStringKeysTest {
    @Test
    fun string_key_set_is_frozen() {
        val actual = readKeys()

        val removed = EXPECTED_KEYS - actual
        val added = actual - EXPECTED_KEYS
        assertTrue(
            removed.isEmpty() && added.isEmpty(),
            buildString {
                appendLine("The tessera_* string key set changed (this is the consumer override contract).")
                appendLine("Removed or renamed (missing from strings.xml): ${removed.sorted()}")
                appendLine("Added (missing from EXPECTED_KEYS): ${added.sorted()}")
                appendLine("An intentional removal or rename is a breaking change under ADR-007: bump MAJOR and note it in the CHANGELOG.")
                append("An added key is non-breaking: append it to EXPECTED_KEYS in TesseraStringKeysTest, keeping the list sorted.")
            },
        )
    }

    @Test
    fun every_key_is_namespaced_with_tessera_prefix() {
        val unprefixed = readKeys().filterNot { it.startsWith("tessera_") }
        assertTrue(
            unprefixed.isEmpty(),
            "Every string resource must start with 'tessera_' so it cannot clash with a consumer key: $unprefixed",
        )
    }

    @Test
    fun strings_xml_has_no_duplicate_names() {
        val names = readNames()
        val duplicates =
            names
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        assertTrue(duplicates.isEmpty(), "strings.xml declares these names more than once: ${duplicates.sorted()}")
    }

    @Test
    fun expected_list_is_sorted_and_unique() {
        assertEquals(EXPECTED_KEYS.sorted(), EXPECTED_KEYS, "Keep EXPECTED_KEYS alphabetically sorted.")
        assertEquals(EXPECTED_KEYS.toSet().size, EXPECTED_KEYS.size, "EXPECTED_KEYS must not contain duplicates.")
    }

    private fun readKeys(): Set<String> = readNames().toSet()

    private fun readNames(): List<String> {
        val file = File(STRINGS_XML)
        assertTrue(
            file.isFile,
            "strings.xml not found at ${file.absolutePath}; the test expects the module directory as the working directory.",
        )
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .documentElement
        val children = root.childNodes
        return (0 until children.length)
            .map { children.item(it) }
            .filter { it.nodeName == "string" || it.nodeName == "plurals" }
            .map { it.attributes.getNamedItem("name").nodeValue }
    }

    private companion object {
        const val STRINGS_XML = "src/androidMain/res/values/strings.xml"

        val EXPECTED_KEYS: List<String> =
            listOf(
                "tessera_scanner_camera_guide",
                "tessera_scanner_camera_in_use_body",
                "tessera_scanner_camera_in_use_title",
                "tessera_scanner_camera_manual",
                "tessera_scanner_camera_reconnecting",
                "tessera_scanner_camera_unavailable_body",
                "tessera_scanner_camera_unavailable_title",
                "tessera_scanner_category_identity_card",
                "tessera_scanner_category_other",
                "tessera_scanner_category_passport",
                "tessera_scanner_category_residence_permit",
                "tessera_scanner_category_visa",
                "tessera_scanner_check_label_composite",
                "tessera_scanner_check_label_date_of_birth",
                "tessera_scanner_check_label_date_of_expiry",
                "tessera_scanner_check_label_document_number",
                "tessera_scanner_check_label_optional_data",
                "tessera_scanner_close",
                "tessera_scanner_date_unresolved_format",
                "tessera_scanner_document_format",
                "tessera_scanner_explainer_action",
                "tessera_scanner_explainer_dismiss",
                "tessera_scanner_explainer_doc_label",
                "tessera_scanner_explainer_intro",
                "tessera_scanner_explainer_title",
                "tessera_scanner_explainer_where_body",
                "tessera_scanner_explainer_where_title",
                "tessera_scanner_explainer_zone_caption",
                "tessera_scanner_field_date_of_birth",
                "tessera_scanner_field_document",
                "tessera_scanner_field_document_type",
                "tessera_scanner_field_expiry",
                "tessera_scanner_field_issuing_state",
                "tessera_scanner_field_name",
                "tessera_scanner_field_nationality",
                "tessera_scanner_field_number",
                "tessera_scanner_field_optional",
                "tessera_scanner_field_optional_data",
                "tessera_scanner_field_sex",
                "tessera_scanner_gathering_hint",
                "tessera_scanner_grant_permission",
                "tessera_scanner_initializing_body",
                "tessera_scanner_initializing_title",
                "tessera_scanner_manual_field_label",
                "tessera_scanner_manual_obs_char_count",
                "tessera_scanner_manual_parse_failed",
                "tessera_scanner_manual_read",
                "tessera_scanner_manual_title",
                "tessera_scanner_method_camera",
                "tessera_scanner_method_keyboard",
                "tessera_scanner_method_photo",
                "tessera_scanner_name_format",
                "tessera_scanner_obs_check_match",
                "tessera_scanner_obs_check_mismatch",
                "tessera_scanner_obs_expiry_well_formed",
                "tessera_scanner_obs_read_by",
                "tessera_scanner_obs_some_mismatch",
                "tessera_scanner_obs_tone_info",
                "tessera_scanner_obs_tone_match",
                "tessera_scanner_obs_tone_mismatch",
                "tessera_scanner_permission_denied_body",
                "tessera_scanner_permission_denied_title",
                "tessera_scanner_permission_grant_action",
                "tessera_scanner_permission_grant_body",
                "tessera_scanner_permission_grant_title",
                "tessera_scanner_permission_open_settings",
                "tessera_scanner_permission_rationale",
                "tessera_scanner_powered_by",
                "tessera_scanner_privacy_action",
                "tessera_scanner_privacy_dialog_body",
                "tessera_scanner_privacy_dialog_dismiss",
                "tessera_scanner_privacy_dialog_title",
                "tessera_scanner_read_failed_body",
                "tessera_scanner_read_failed_captured_header",
                "tessera_scanner_read_failed_manual",
                "tessera_scanner_read_failed_title",
                "tessera_scanner_read_failed_try_again",
                "tessera_scanner_read_method_live_camera",
                "tessera_scanner_read_method_manual",
                "tessera_scanner_read_method_photo",
                "tessera_scanner_review_all_fields_title",
                "tessera_scanner_review_edit_entry",
                "tessera_scanner_review_observations_header",
                "tessera_scanner_review_raw_mrz_header",
                "tessera_scanner_review_rescan",
                "tessera_scanner_review_show_all",
                "tessera_scanner_review_show_less",
                "tessera_scanner_review_title",
                "tessera_scanner_review_try_another_photo",
                "tessera_scanner_review_use",
                "tessera_scanner_saved_image_analyzing_reading",
                "tessera_scanner_saved_image_analyzing_title",
                "tessera_scanner_saved_image_choose_different",
                "tessera_scanner_saved_image_empty_body",
                "tessera_scanner_saved_image_empty_title",
                "tessera_scanner_saved_image_manual",
                "tessera_scanner_saved_image_prompt_action",
                "tessera_scanner_saved_image_prompt_body",
                "tessera_scanner_saved_image_prompt_title",
                "tessera_scanner_struggling_hint",
                "tessera_scanner_struggling_manual",
                "tessera_scanner_time_remaining",
                "tessera_scanner_title",
                "tessera_scanner_torch",
                "tessera_scanner_torch_state_off",
                "tessera_scanner_torch_state_on",
            )
    }
}
