package io.lightine.tessera.photobench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

// An experiment, not SDK code: would a general two-pass rescue (the iOS design, TES-130) help on
// Android? Pass 1 reads the whole photo with ML Kit. Rows that look like MRZ text are grouped by
// height, cropped tightly (with a margin), enlarged when small, and read again. For every image the
// experiment writes the first-pass lines ("p1") and the re-read rows ("rr"), as ML Kit reports them,
// to `experiment.tsv`: image <TAB> kind <TAB> text. score.py compares them with the ground truth.
class RescueExperiment(
    private val files: List<File>,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun run(out: File) {
        val rows = StringBuilder()
        for (file in files) {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            val lines = read(bitmap).textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
            for (line in lines) rows.append("${file.name}\tp1\t${line.text}\n")
            for (group in mrzRows(lines)) {
                val union = Rect(group.first().boundingBox!!).also { r -> group.forEach { r.union(it.boundingBox!!) } }
                val padX = (0.03 * union.width()).toInt()
                val padY = (0.6 * union.height()).toInt()
                val crop =
                    Rect(
                        (union.left - padX).coerceAtLeast(0),
                        (union.top - padY).coerceAtLeast(0),
                        (union.right + padX).coerceAtMost(bitmap.width),
                        (union.bottom + padY).coerceAtMost(bitmap.height),
                    )
                val cut = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height())
                for (scale in listOf(1, 2)) {
                    val scaled = if (scale == 1) cut else Bitmap.createScaledBitmap(cut, cut.width * scale, cut.height * scale, true)
                    val reread = read(scaled).textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
                    // Join the fragments of the row left to right: one row, one string.
                    val joined = reread.sortedBy { it.boundingBox!!.left }.joinToString("") { it.text }
                    rows.append("${file.name}\trr$scale\t$joined\n")
                }
            }
        }
        out.writeText(rows.toString())
        recognizer.close()
    }

    private fun read(bitmap: Bitmap): Text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))

    // MRZ-like: mostly the MRZ alphabet and either a filler or a digit. Fragments on one row are grouped.
    private fun mrzRows(lines: List<Text.Line>): List<List<Text.Line>> {
        val alphabet = { c: Char -> c in 'A'..'Z' || c in '0'..'9' || c == '<' || c == '«' }
        val candidates =
            lines.filter { line ->
                val t = line.text.replace(" ", "")
                t.length >= 5 && t.count(alphabet) >= 0.9 * t.length && t.any { it == '<' || it == '«' || it.isDigit() }
            }
        val groups = mutableListOf<MutableList<Text.Line>>()
        for (line in candidates.sortedBy { it.boundingBox!!.top }) {
            val b = line.boundingBox!!
            val group =
                groups.firstOrNull { g ->
                    val gb = g.first().boundingBox!!
                    minOf(b.bottom, gb.bottom) - maxOf(b.top, gb.top) >= 0.5 * minOf(b.height(), gb.height())
                }
            if (group != null) group.add(line) else groups.add(mutableListOf(line))
        }
        return groups.filter { g -> g.sumOf { it.text.length } >= 20 }
    }
}
