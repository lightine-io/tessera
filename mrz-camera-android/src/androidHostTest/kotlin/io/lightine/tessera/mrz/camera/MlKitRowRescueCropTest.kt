package io.lightine.tessera.mrz.camera

import io.lightine.tessera.mrz.camera.MrzRowRescue.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Host test (JVM, no device) for the pixel geometry of the Android short-row rescue: the planned crop
 * (fractional pixels) becomes a whole-pixel bitmap crop, and boxes ML Kit reports on the upscaled crop map
 * back onto the first pass's image. The ML Kit calls themselves are checked on the emulator.
 */
class MlKitRowRescueCropTest {
    @Test
    fun a_fractional_crop_rounds_outwards() {
        val crop = MlKitRowRescue.pixelCrop(Box(73.6, 387.4, 999.2, 433.6), 1000, 600)
        assertEquals(MlKitRowRescue.PixelCrop(left = 73, top = 387, width = 927, height = 47), crop)
    }

    @Test
    fun a_crop_is_clamped_to_the_bitmap() {
        val crop = MlKitRowRescue.pixelCrop(Box(-5.0, -3.0, 1200.0, 40.0), 1000, 600)
        assertEquals(MlKitRowRescue.PixelCrop(left = 0, top = 0, width = 1000, height = 40), crop)
    }

    @Test
    fun a_crop_outside_the_bitmap_is_null() {
        assertNull(MlKitRowRescue.pixelCrop(Box(1000.0, 10.0, 1100.0, 40.0), 1000, 600))
        assertNull(MlKitRowRescue.pixelCrop(Box(10.0, 10.0, 10.0, 40.0), 1000, 600), "zero width")
    }

    @Test
    fun a_box_on_the_scaled_crop_maps_back_to_the_bitmap() {
        val crop = MlKitRowRescue.PixelCrop(left = 73, top = 387, width = 927, height = 47)
        // ML Kit read the 2x crop; a line box at (54, 28)-(1814, 66) there is (100, 401)-(980, 420) in the bitmap.
        val mapped = MrzRowRescue.fromCropSpace(Box(54.0, 28.0, 1814.0, 66.0), crop.toBox(), scale = 2)
        assertEquals(Box(100.0, 401.0, 980.0, 420.0), mapped)
    }
}
