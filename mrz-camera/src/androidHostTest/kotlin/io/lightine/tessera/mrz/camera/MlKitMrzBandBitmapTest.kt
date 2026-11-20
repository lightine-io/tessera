package io.lightine.tessera.mrz.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Host test (Robolectric, native graphics) for [MlKitMrzTextRecognizer.mrzBand]: the band bitmap ML Kit reads
 * is the CameraX crop rect, turned upright by the frame's rotation, cut to the centred band (TES-86, TES-118).
 *
 * Every source pixel carries its own position (red = x, green = y), so each band pixel names the sensor
 * pixel it came from. The expectation is written from the upright picture, not from the code: crop, then
 * rotate clockwise by `rotationDegrees`, then take the centred band. It cannot show the YUV-to-RGB step
 * (`ImageProxy.toBitmap()`) or real memory use; those need a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MlKitMrzBandBitmapTest {
    @Test
    fun band_is_upright_and_from_the_crop_rect_for_every_rotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            assertBand(CROP, rotation)
        }
    }

    @Test
    fun a_crop_rect_covering_the_whole_frame_reads_the_whole_frame() {
        for (rotation in listOf(0, 90, 180, 270)) {
            assertBand(Rect(0, 0, WIDTH, HEIGHT), rotation)
        }
    }

    @Test
    fun a_crop_rect_outside_the_frame_is_clamped() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val band = MlKitMrzTextRecognizer.mrzBand(positionBitmap(), Rect(-5, -5, WIDTH + 5, HEIGHT + 5), rotation)
            val uprightHeight = if (rotation % 180 == 0) HEIGHT else WIDTH
            assertEquals(if (rotation % 180 == 0) WIDTH else HEIGHT, band.width, "width at $rotation")
            assertEquals(MlKitMrzTextRecognizer.mrzBandRect(uprightHeight).second, band.height, "height at $rotation")
        }
    }

    private fun assertBand(
        crop: Rect,
        rotation: Int,
    ) {
        val band = MlKitMrzTextRecognizer.mrzBand(positionBitmap(), crop, rotation)
        val cropWidth = crop.width()
        val cropHeight = crop.height()
        val uprightWidth = if (rotation % 180 == 0) cropWidth else cropHeight
        val uprightHeight = if (rotation % 180 == 0) cropHeight else cropWidth
        val (bandTop, bandHeight) = MlKitMrzTextRecognizer.mrzBandRect(uprightHeight)

        assertEquals(uprightWidth, band.width, "band width at rotation $rotation")
        assertEquals(bandHeight, band.height, "band height at rotation $rotation")
        for (y in 0 until band.height) {
            for (x in 0 until band.width) {
                val uprightY = bandTop + y
                // Where upright pixel (x, uprightY) sits in the crop, for a clockwise rotation.
                val (cropX, cropY) =
                    when (rotation) {
                        0 -> x to uprightY
                        90 -> uprightY to (cropHeight - 1 - x)
                        180 -> (cropWidth - 1 - x) to (cropHeight - 1 - uprightY)
                        else -> (cropWidth - 1 - uprightY) to x
                    }
                val pixel = band.getPixel(x, y)
                assertEquals(
                    (crop.left + cropX) to (crop.top + cropY),
                    Color.red(pixel) to Color.green(pixel),
                    "band pixel ($x, $y) at rotation $rotation",
                )
            }
        }
    }

    // A sensor-orientation frame where pixel (x, y) is the colour (x, y, 0).
    private fun positionBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                bitmap.setPixel(x, y, Color.argb(255, x, y, 0))
            }
        }
        return bitmap
    }

    private companion object {
        // Small enough that each position fits one colour channel; 4:3 like the analysis frame.
        const val WIDTH = 64
        const val HEIGHT = 48

        // An off-centre crop rect (what a ViewPort gives), so a crop that is ignored or misplaced shows.
        val CROP = Rect(6, 3, 58, 44)
    }
}
