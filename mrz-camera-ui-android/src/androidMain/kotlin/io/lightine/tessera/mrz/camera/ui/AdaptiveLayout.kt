// @Composable functions are PascalCase by Compose convention — see the note in MrzScannerScreen.kt.
@file:Suppress("ktlint:standard:function-naming")

package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The adaptive-layout foundation (TES-78). The default scanner UI is a phone UI first, but a consumer can drop
// MrzScannerScreen onto a tablet or an unfolded foldable, where an unconstrained content column would stretch
// its rows and full-width buttons edge-to-edge into an unreadably wide band. This is the *foundation* only —
// a single content-width cap that keeps the single-pane phone UI readable on a wide screen (a centred column
// about a phone's width). It is NOT a multi-pane / list-detail / two-column tablet build; those are future
// work per the 0.5.0 scope decision (TES-77: "build the phone UI adaptive → tablet/foldable near-free
// (foundation now); full tablet/desktop/AR multi-pane builds are future").
//
// Dependency-free by design: a plain widthIn + centering, NOT a window-size-class library
// (androidx.window / material3.adaptive). The current Android adaptive guidance (developer.android.com,
// "Support different screen sizes" / adaptive layouts, checked 2026-07-07) pushes window-size-classes toward
// *choosing between layouts* (single- vs multi-pane) — which is exactly the deferred work — while BoxWith
// Constraints / widthIn is its dependency-free primitive for constraining nested content. A centred
// max-width column is the established readable-measure foundation, so no new dependency is warranted here.

/**
 * The content-width cap for the single-pane scanner screens (TES-78). About a comfortable phone's width, so on
 * a wide screen (tablet / unfolded foldable) the review / manual / permission / status content sits in a
 * centred column of roughly this width rather than spanning the whole display. On a phone the screen is always
 * narrower than this, so the cap never bites and the phone appearance is unchanged.
 */
internal val ContentMaxWidth: Dp = 480.dp

/**
 * Caps a content column's width at [ContentMaxWidth] and centres it horizontally within its parent — the
 * adaptive-layout foundation for the single-pane scanner screens (TES-78).
 *
 * Applied to the **content** screens (review / expanded, read-failed, manual entry, the permission faces, the
 * saved-image empty / analyzing / await-pick prompts, the camera-status notices, and the camera-initializing
 * state) so their column stays a readable width on a tablet or unfolded foldable instead
 * of stretching its rows and full-width buttons edge-to-edge. The live camera viewfinder is deliberately NOT
 * capped — it stays full-bleed for immersive scanning; only its overlaid controls use this so they remain
 * reachable and not stretched.
 *
 * The modifier chain is `fillMaxWidth().wrapContentWidth(CenterHorizontally).widthIn(max = ContentMaxWidth)`:
 * `fillMaxWidth` claims the parent's width, `wrapContentWidth` re-centres the (soon-to-be-narrower) content in
 * it, and `widthIn` caps the measured width. The order matters — the cap must come after the centring so the
 * centred region is the capped one. On any screen narrower than the cap this is a no-op (the content already
 * fills less than the max), so the phone layout is byte-for-byte the same; the cap only takes effect once the
 * available width exceeds [ContentMaxWidth].
 *
 * Pure layout: it adds no state and reads no configuration, so it composes with each screen's existing
 * `padding` / scroll / pinned-button modifiers without disturbing them (apply it before the padding so the
 * padding sits inside the capped column).
 */
internal fun Modifier.contentMaxWidth(): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth)
