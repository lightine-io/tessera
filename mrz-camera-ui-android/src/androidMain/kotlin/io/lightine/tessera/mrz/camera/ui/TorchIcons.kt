package io.lightine.tessera.mrz.camera.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Hand-rolled torch glyphs for TorchButton (TES-84) — deliberately NOT pulled from
// `material-icons-extended`: this module stays free of that dependency for a single icon pair, the same
// stance behind the top-bar ✕ and the privacy/explainer ⓘ being plain glyphs elsewhere in this module.
//
// On-device QA (TES-84) flagged the original hand-drawn flashlight-device silhouette as reading wrong — it
// didn't match what any camera app actually shows for a torch toggle. The fix is the camera-app-standard
// convention instead: the Material Symbols `flash_on` lightning bolt for ON, and `flash_off` (the same bolt
// with a diagonal strike-through) for OFF — the same on/off slash convention this file used before. The path
// data below is the standard Material glyph geometry on a 24dp viewport, parsed from its SVG path string via
// [PathParser] rather than hand-walked as moveTo/lineTo calls, so it stays a faithful, easily-diffable copy of
// the published glyph.

private const val VIEWPORT_SIZE = 24f
private val GLYPH_FILL = SolidColor(Color.Black)

/** Parses a Material-style SVG path-data string into this builder as a single filled path. */
private fun ImageVector.Builder.svgPath(pathData: String) {
    addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = GLYPH_FILL)
}

/** Flash ON: the Material Symbols `flash_on` lightning-bolt glyph. */
internal val FlashlightOnIcon: ImageVector
    get() =
        ImageVector
            .Builder(
                name = "FlashOn",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = VIEWPORT_SIZE,
                viewportHeight = VIEWPORT_SIZE,
            ).apply {
                svgPath("M7 2v11h3v9l7-12h-4l4-8z")
            }.build()

/** Flash OFF: the Material Symbols `flash_off` glyph — the same bolt with a diagonal strike-through. */
internal val FlashlightOffIcon: ImageVector
    get() =
        ImageVector
            .Builder(
                name = "FlashOff",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = VIEWPORT_SIZE,
                viewportHeight = VIEWPORT_SIZE,
            ).apply {
                svgPath(
                    "M3.27 3L2 4.27l5 5V13h3v9l3.58-6.14L17.73 20 19 18.73 3.27 3zM17 10h-4l4-8H7v2.18l8.46 8.46L17 10z",
                )
            }.build()
