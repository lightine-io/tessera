// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The shared navigation scaffold (TES-71) that ties every reading method together: a Material 3 Scaffold with
// a TopAppBar whose ✕ owns cancel on EVERY screen (closing the earlier gap where the review / read-failed /
// expanded screens had no reachable cancel), and — on the capture & entry screens only — a segmented method
// switcher (Camera / Photo / Type) that flips between the enabled reading methods. Both are pure chrome; the
// screen-specific content renders in the Scaffold body underneath, keeping each screen's own scroll / pinned
// buttons intact.
//
// Reader, not oracle (Principle 1): the switcher only chooses HOW to read (which method), never what a
// reading means. The scaffold adds no judgement.

/** Semantics anchor for the top-bar ✕ close control (the global cancel present on every screen). Not user-facing. */
internal const val CLOSE_TEST_TAG: String = "tessera-mrz-close"

/** Semantics anchor for the method-switcher row (Camera / Photo / Type). Not user-facing. */
internal const val SWITCHER_TEST_TAG: String = "tessera-mrz-method-switcher"

/** Semantics anchor for the await-saved-image-pick prompt. Not user-facing. */
internal const val AWAITING_SAVED_IMAGE_PICK_TEST_TAG: String = "tessera-mrz-awaiting-saved-image-pick"

/**
 * Where the flow starts, derived purely from the consumer's [enabledMethods]. Pure and Compose-free so every
 * combination is host-unit-testable off-device (mirrors [routeDecode]).
 *
 * Preference order — camera first (the default primary method), then manual entry (the always-available
 * fallback), then saved image:
 *  * [`CAMERA`][ScanMethod.CAMERA] present → [ScannerUiState.Scanning];
 *  * else [`MANUAL_ENTRY`][ScanMethod.MANUAL_ENTRY] present → [ScannerUiState.ManualRaw];
 *  * else [`SAVED_IMAGE`][ScanMethod.SAVED_IMAGE] present → [ScannerUiState.AwaitingSavedImagePick] (the flow
 *    triggers the photo picker on entering it);
 *  * empty set → treated as camera (defensive — an empty [enabledMethods] is not a valid consumer config, but
 *    the UI must still show something rather than nothing).
 */
internal fun initialState(enabledMethods: Set<ScanMethod>): ScannerUiState =
    when {
        ScanMethod.CAMERA in enabledMethods -> ScannerUiState.Scanning()
        ScanMethod.MANUAL_ENTRY in enabledMethods -> ScannerUiState.ManualRaw()
        ScanMethod.SAVED_IMAGE in enabledMethods -> ScannerUiState.AwaitingSavedImagePick
        else -> ScannerUiState.Scanning()
    }

/**
 * The reading methods the switcher offers, decided purely from [enabledMethods] — only the enabled ones, in
 * the fixed display order camera, photo, type ([ScanMethod.CAMERA], [ScanMethod.SAVED_IMAGE],
 * [ScanMethod.MANUAL_ENTRY]). Pure and Compose-free so [MethodSwitcher]'s visibility rule is host-testable.
 */
internal fun switcherMethods(enabledMethods: Set<ScanMethod>): List<ScanMethod> =
    listOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY).filter { it in enabledMethods }

/**
 * Whether the current [ScannerUiState] is a capture / entry screen — the only screens the method switcher
 * appears on. Those are the ones a user is actively *reading from* and might want to switch away from:
 * [`Scanning`][ScannerUiState.Scanning], [`ManualRaw`][ScannerUiState.ManualRaw], and the saved-image entry
 * points ([`AwaitingSavedImagePick`][ScannerUiState.AwaitingSavedImagePick] /
 * [`SavedImageEmpty`][ScannerUiState.SavedImageEmpty]). It is deliberately NOT shown on outcome / gate screens
 * (review, expanded, read-failed, the camera-error notices, analyzing, candidates, and the permission faces),
 * where switching method mid-outcome would be a confusing detour. Pure so the rule is host-testable.
 */
internal fun showsMethodSwitcher(state: ScannerUiState): Boolean =
    when (state) {
        is ScannerUiState.Scanning,
        is ScannerUiState.ManualRaw,
        ScannerUiState.AwaitingSavedImagePick,
        ScannerUiState.SavedImageEmpty,
        -> true

        else -> false
    }

/**
 * The shared chrome (TES-71): a [Scaffold] with a [TopAppBar] whose leading ✕ fires [onClose] (the global
 * cancel, reachable on every screen), a stable title, and — on the capture / entry screens only, and only
 * when at least two methods are enabled — the [MethodSwitcher] beneath the bar. [content] renders in the
 * Scaffold body underneath, so each screen keeps its own scroll / pinned-button behaviour.
 *
 * @param enabledMethods the consumer's enabled reading methods; the switcher shows only these, and hides when
 *   fewer than two are enabled (nothing to switch between).
 * @param currentState the flow's current state — decides whether the switcher shows ([showsMethodSwitcher])
 *   and which tab is highlighted.
 * @param onClose the global cancel (the flow reports `Cancelled(USER_DISMISSED)`).
 * @param onSelectMethod invoked with the tapped method when the user switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScannerScaffold(
    enabledMethods: Set<ScanMethod>,
    currentState: ScannerUiState,
    onClose: () -> Unit,
    onSelectMethod: (ScanMethod) -> Unit,
    content: @Composable () -> Unit,
) {
    val closeLabel = stringResource(R.string.tessera_scanner_close)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.tessera_scanner_title)) },
                navigationIcon = {
                    // A text ✕ rather than a Material icon vector, to avoid pulling in the material-icons
                    // dependency for a single glyph. The IconButton carries the Button role; the spoken
                    // label lives on the glyph's contentDescription (the ✕ character is not a label).
                    IconButton(onClick = onClose, modifier = Modifier.testTag(CLOSE_TEST_TAG)) {
                        Text(
                            text = "✕",
                            modifier = Modifier.semantics { contentDescription = closeLabel },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (showsMethodSwitcher(currentState)) {
                MethodSwitcher(
                    enabledMethods = enabledMethods,
                    currentState = currentState,
                    onSelectMethod = onSelectMethod,
                )
            }
            content()
        }
    }
}

/**
 * The segmented method switcher (TES-71): one chip per enabled reading method — Camera / Photo / Type, in the
 * order camera, photo, type — with the method matching [currentState] highlighted (selected). Tapping a chip
 * fires [onSelectMethod]. When fewer than two methods are enabled there is nothing to switch between, so the
 * whole row is hidden (renders nothing).
 *
 * `internal` (not `private`) so it can be host-tested through this entry point directly, per the
 * testing-layers rule.
 */
@Composable
internal fun MethodSwitcher(
    enabledMethods: Set<ScanMethod>,
    currentState: ScannerUiState,
    onSelectMethod: (ScanMethod) -> Unit,
) {
    val methods = switcherMethods(enabledMethods)
    // Fewer than two enabled methods → nothing to switch between; hide the switcher entirely.
    if (methods.size < 2) return

    val active = activeMethod(currentState)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag(SWITCHER_TEST_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        methods.forEach { method ->
            FilterChip(
                selected = method == active,
                onClick = { onSelectMethod(method) },
                label = { Text(text = methodLabel(method)) },
            )
        }
    }
}

/** The visible label for a method chip. */
@Composable
private fun methodLabel(method: ScanMethod): String =
    stringResource(
        when (method) {
            ScanMethod.CAMERA -> R.string.tessera_scanner_method_camera
            ScanMethod.SAVED_IMAGE -> R.string.tessera_scanner_method_photo
            ScanMethod.MANUAL_ENTRY -> R.string.tessera_scanner_method_keyboard
        },
    )

/**
 * Which method the current capture / entry state belongs to, so the matching chip is highlighted. Only the
 * switcher-bearing states have a method; anything else has none (the switcher is not shown there anyway).
 */
internal fun activeMethod(state: ScannerUiState): ScanMethod? =
    when (state) {
        is ScannerUiState.Scanning -> ScanMethod.CAMERA
        is ScannerUiState.ManualRaw -> ScanMethod.MANUAL_ENTRY
        ScannerUiState.AwaitingSavedImagePick, ScannerUiState.SavedImageEmpty -> ScanMethod.SAVED_IMAGE
        else -> null
    }
