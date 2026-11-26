package io.lightine.tessera.mrz.camera.ui

import androidx.camera.core.ViewPort
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * TES-342: in a short camera area (a phone in landscape, split screen) nothing was read although the guide box
 * showed the MRZ. The live recognizer reads only the centred [OCR_BAND_HEIGHT_FRACTION] of the analysis frame, and
 * the analysis frame is the viewport's crop. The viewport used to be the preview area itself, so the band was 18%
 * of the area's height while the guide box is a fixed [GuideBandHeight]: in a 220dp tall area the band was about
 * 40dp of a 96dp box, and it cut through the middle of the MRZ lines.
 *
 * This checks the geometry the viewport gives, for many preview sizes: the viewfinder fills the area and centres
 * the crop (CameraXViewfinder's defaults, ContentScale.Crop and Alignment.Center), so in preview units the crop is
 * the area's width wide and `width / aspectRatio` tall, centred on the area, and the band is its centred fraction.
 * The band must cover the whole guide box. What CameraX and ML Kit do with a real frame is checked on a device.
 */
@RunWith(RobolectricTestRunner::class)
// A screen large enough for every preview size the tests set (Robolectric's default screen is smaller).
@Config(sdk = [34], qualifiers = "w1300dp-h1100dp")
class OcrBandCoversGuideTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var size by mutableStateOf(DpSize(100.dp, 200.dp))
    private var viewport: ViewPort? = null

    @Test
    fun the_ocr_band_covers_the_guide_box_at_every_preview_height() {
        composeRule.setContent {
            Box(Modifier.size(size)) {
                BoxWithConstraints { viewport = rememberPreviewViewport(constraints, 0) }
            }
        }
        val failures = mutableListOf<String>()
        // Landscape phones (about 180 to 250dp of camera area), split screen, tablets, portrait phones.
        for (width in listOf(360, 412, 800, 900)) {
            for (height in 120..1000 step 10) {
                size = DpSize(width.dp, height.dp)
                composeRule.waitForIdle()
                val aspect = viewport!!.aspectRatio.toFloat()
                // The viewfinder scales the crop to fill the area. A crop at least as tall (relative to its
                // width) as the area fills the width; a wider one fills the height.
                val cropHeight = if (aspect <= width.toFloat() / height) width / aspect else height.toFloat()
                val band = cropHeight * OCR_BAND_FRACTION
                // Half a dp of rounding (pixels in the area, integer pixels in the analysis frame).
                if (band < GuideBandHeight.value - 0.5f) failures += "${width}x$height dp: band ${band}dp"
            }
        }
        assertTrue(failures.isEmpty(), "the OCR band is shorter than the $GuideBandHeight guide box at: $failures")
    }

    private companion object {
        // The live recognizer's band, MlKitMrzTextRecognizer.MRZ_BAND_HEIGHT_FRACTION in mrz-camera (internal
        // there). Written out here so this test fails if the screen's own copy drifts from the recognizer's.
        const val OCR_BAND_FRACTION = 0.18f
    }
}
