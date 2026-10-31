package io.lightine.tessera.mrz.camera

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import io.lightine.tessera.mrz.camera.MrzRowRescue.Box
import io.lightine.tessera.mrz.camera.MrzRowRescue.BoxedLine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The ML Kit side of the short-row rescue ([MrzRowRescue]). ML Kit collapses long `<` filler runs, so an MRZ
 * row (typically TD3 line 1) comes back short while the other row reads at exact width. After the first
 * pass, each short MRZ-like row that has an exact-width neighbour is cropped again across the neighbour's
 * width, upscaled, and read again; only an exact-width re-read takes the short row's place. When nothing
 * is planned or kept, the result is exactly the first pass's lines.
 */
internal object MlKitRowRescue {
    /**
     * Turns the first pass's [lines] into [RecognizedText], re-reading near-miss rows. [imageWidth] and
     * [imageHeight] are the size of the image the line boxes refer to; [image] supplies that same image
     * (called only when a re-read is planned; a bitmap of another size skips the rescue, since the boxes
     * would not match it). At most [maxRereads] extra ML Kit calls are made. A failure while getting the
     * image or in a re-read only skips the rescue; the first pass's lines still return.
     */
    suspend fun recognizedText(
        recognizer: TextRecognizer,
        lines: List<Text.Line>,
        imageWidth: Int,
        imageHeight: Int,
        maxRereads: Int,
        image: suspend () -> Bitmap?,
    ): RecognizedText {
        val firstPass = lines.map { RecognizedLine(text = it.text, confidence = it.confidence) }
        val boxedIndices = lines.indices.filter { lines[it].boundingBox != null }
        val boxed = boxedIndices.map { lines[it].toBoxedLine() }
        val plans = MrzRowRescue.planRereads(boxed, imageWidth.toDouble(), imageHeight.toDouble()).take(maxRereads)
        if (plans.isEmpty()) return RecognizedText(firstPass)

        val bitmap =
            try {
                image()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return RecognizedText(firstPass)
        if (bitmap.width != imageWidth || bitmap.height != imageHeight) return RecognizedText(firstPass)

        val replacements = mutableMapOf<List<Int>, List<RecognizedLine>>()
        for (plan in plans) {
            val kept =
                try {
                    reread(recognizer, bitmap, plan)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            replacements[plan.row.map { boxedIndices[it] }] = kept
        }
        return RecognizedText(MrzRowRescue.splice(firstPass, replacements))
    }

    private suspend fun reread(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        plan: MrzRowRescue.RereadPlan,
    ): List<RecognizedLine> {
        val crop = pixelCrop(plan.crop, bitmap.width, bitmap.height) ?: return emptyList()
        val scale = MrzRowRescue.rereadScale(plan.rowBox.height, maxOf(crop.width, crop.height).toDouble())
        val cut = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        val scaled = if (scale == 1) cut else Bitmap.createScaledBitmap(cut, crop.width * scale, crop.height * scale, true)
        val result = recognizer.process(InputImage.fromBitmap(scaled, 0)).await()
        val cropBox = crop.toBox()
        val reread =
            result.textBlocks
                .flatMap { it.lines }
                .filter { it.boundingBox != null }
                .map { it.toBoxedLine().let { line -> line.copy(box = MrzRowRescue.fromCropSpace(line.box, cropBox, scale)) } }
        return MrzRowRescue.keptLines(reread, plan)
    }

    private fun Text.Line.toBoxedLine(): BoxedLine {
        val rect = checkNotNull(boundingBox)
        return BoxedLine(
            text = text,
            confidence = confidence,
            box = Box(rect.left.toDouble(), rect.top.toDouble(), rect.right.toDouble(), rect.bottom.toDouble()),
        )
    }

    /** An integer pixel crop inside a bitmap. */
    internal data class PixelCrop(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    ) {
        fun toBox(): Box = Box(left.toDouble(), top.toDouble(), (left + width).toDouble(), (top + height).toDouble())
    }

    /**
     * The whole-pixel crop covering [box] (rounded outwards) inside a [bitmapWidth] x [bitmapHeight]
     * bitmap, clamped to it; null when nothing of it is inside.
     */
    internal fun pixelCrop(
        box: Box,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): PixelCrop? {
        val left = floor(box.left).toInt().coerceIn(0, bitmapWidth)
        val top = floor(box.top).toInt().coerceIn(0, bitmapHeight)
        val right = ceil(box.right).toInt().coerceIn(left, bitmapWidth)
        val bottom = ceil(box.bottom).toInt().coerceIn(top, bitmapHeight)
        if (right - left <= 0 || bottom - top <= 0) return null
        return PixelCrop(left, top, right - left, bottom - top)
    }
}
