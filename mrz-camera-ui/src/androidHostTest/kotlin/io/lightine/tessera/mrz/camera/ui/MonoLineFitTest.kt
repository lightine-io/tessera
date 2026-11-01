package io.lightine.tessera.mrz.camera.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TES-112: the raw MRZ line fit ladder ([MonoLineFit], [monoLineFit], [MonoLine]) — the Android match of
 * iOS's `MonoLineFit.ladder` (TES-150). A line keeps the largest style that fits on one row, steps down, and
 * only scrolls (with a visible indicator) when nothing fits; the text itself is never changed.
 *
 * The pure-decision tests model a monospaced line's width as characters x 0.6 em x font size x font scale x
 * density, the same shape a real measurement has, so phone and tablet columns at default and large font sizes
 * can be stated as plain numbers. The rendered tests measure with the real [rememberTextMeasurer] and size
 * the column from that measurement, so they do not depend on the host's exact font metrics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonoLineFitTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val td3Line = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"

    // Material 3 default sizes of each rung's style: bodyMedium 14sp, bodySmall 12sp, labelSmall 11sp.
    private fun modelWidthPx(
        fit: MonoLineFit,
        fontScale: Float,
        density: Float = 2.625f,
        chars: Int = 44,
    ): Int {
        val sp =
            when (fit) {
                MonoLineFit.BODY, MonoLineFit.SCROLL -> 14f
                MonoLineFit.SMALL -> 12f
                MonoLineFit.SMALLEST -> 11f
            }
        return (chars * 0.6f * sp * fontScale * density).roundToInt()
    }

    private fun columnPx(
        dp: Int,
        density: Float = 2.625f,
    ): Int = (dp * density).roundToInt()

    @Test
    fun ladder_is_widest_first_then_scroll_last_and_covers_every_rung_once() {
        assertEquals(listOf(MonoLineFit.BODY, MonoLineFit.SMALL, MonoLineFit.SMALLEST, MonoLineFit.SCROLL), MonoLineFit.ladder)
        assertEquals(MonoLineFit.entries.toSet(), MonoLineFit.ladder.toSet())
        assertEquals(MonoLineFit.entries.size, MonoLineFit.ladder.size)
    }

    @Test
    fun a_wide_column_keeps_the_body_style() {
        // A tablet column (600dp) at the default font size: a 44-character line fits at body size.
        assertEquals(MonoLineFit.BODY, monoLineFit(columnPx(600)) { modelWidthPx(it, fontScale = 1f) })
    }

    @Test
    fun a_phone_column_steps_down_instead_of_scrolling() {
        // 328dp (a 360dp phone less 16dp padding each side): body is 369.6dp wide, small is 316.8dp.
        assertEquals(MonoLineFit.SMALL, monoLineFit(columnPx(328)) { modelWidthPx(it, fontScale = 1f) })
        // 300dp: small no longer fits, smallest (290.4dp) does.
        assertEquals(MonoLineFit.SMALLEST, monoLineFit(columnPx(300)) { modelWidthPx(it, fontScale = 1f) })
    }

    @Test
    fun a_large_font_scale_on_a_phone_falls_back_to_scroll() {
        // At 200% even the smallest rung is 580.8dp wide, far past a 328dp column.
        assertEquals(MonoLineFit.SCROLL, monoLineFit(columnPx(328)) { modelWidthPx(it, fontScale = 2f) })
    }

    @Test
    fun a_large_font_scale_on_a_tablet_still_fits_without_scrolling() {
        // 130% in a 600dp column: body is 480.5dp wide and fits.
        assertEquals(MonoLineFit.BODY, monoLineFit(columnPx(600)) { modelWidthPx(it, fontScale = 1.3f) })
        // 200% in a 600dp column: body (739.2dp) and small (633.6dp) do not fit; smallest (580.8dp) does.
        assertEquals(MonoLineFit.SMALLEST, monoLineFit(columnPx(600)) { modelWidthPx(it, fontScale = 2f) })
    }

    @Test
    fun a_line_exactly_as_wide_as_the_column_fits() {
        assertEquals(MonoLineFit.BODY, monoLineFit(500) { 500 })
        assertEquals(MonoLineFit.SCROLL, monoLineFit(499) { 500 })
    }

    @Test
    fun rendered_line_in_a_roomy_column_uses_the_body_rung_verbatim() {
        composeRule.setContent {
            TesseraScannerTheme(MrzScannerConfig().theme) {
                Box(modifier = Modifier.requiredWidth(2000.dp)) { MonoLine(td3Line) }
            }
        }

        composeRule.onNodeWithTag(monoLineTestTag(MonoLineFit.BODY)).assertTextEquals(td3Line)
    }

    @Test
    fun rendered_line_just_too_wide_for_body_steps_down_to_the_small_rung() {
        var bodyPx = 0
        var smallPx = 0
        composeRule.setContent {
            val bumped = Density(density = LocalDensity.current.density, fontScale = 1.5f)
            CompositionLocalProvider(LocalDensity provides bumped) {
                TesseraScannerTheme(MrzScannerConfig().theme) {
                    val measurer = rememberTextMeasurer()
                    val typography = MaterialTheme.typography
                    bodyPx = measurer.measure(td3Line, typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)).size.width
                    smallPx = measurer.measure(td3Line, typography.bodySmall.copy(fontFamily = FontFamily.Monospace)).size.width
                    // A column a few pixels narrower than the body-size line: body no longer fits, small does.
                    val column = with(LocalDensity.current) { (bodyPx - 3).toDp() }
                    Box(modifier = Modifier.requiredWidth(column)) { MonoLine(td3Line) }
                }
            }
        }

        assertTrue(smallPx < bodyPx - 6, "premise: the small style must be clearly narrower ($smallPx vs $bodyPx)")
        composeRule.onNodeWithTag(monoLineTestTag(MonoLineFit.SMALL)).assertTextEquals(td3Line)
    }

    @Test
    fun rendered_line_that_fits_no_style_scrolls_and_stays_verbatim() {
        composeRule.setContent {
            val bumped = Density(density = LocalDensity.current.density, fontScale = 2f)
            CompositionLocalProvider(LocalDensity provides bumped) {
                TesseraScannerTheme(MrzScannerConfig().theme) {
                    Box(modifier = Modifier.requiredWidth(40.dp)) { MonoLine(td3Line) }
                }
            }
        }

        composeRule.onNodeWithTag(monoLineTestTag(MonoLineFit.SCROLL)).assertTextEquals(td3Line)
    }
}
