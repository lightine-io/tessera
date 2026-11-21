package io.lightine.tessera.ocrbench

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.math.atan2
import kotlin.math.hypot

// Reads every benchmark image in the app's private files folder `images/` with ML Kit (the
// bundled Latin model, default options, as mrz-camera uses it) and writes one row per recognized
// line to `mlkit.tsv` beside it:
// image <TAB> variant <TAB> centre-x <TAB> centre-y <TAB> width <TAB> height <TAB> angle <TAB> text
// (pixels, y growing downwards, baseline angle in radians). The images are synthetic, so the
// recognized text is written as is. Logs "OCRBENCH DONE <rows>" when finished.
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { run() }.start()
    }

    private fun run() {
        val root = filesDir
        val images = File(root, "images").listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val rows = StringBuilder()
        var count = 0
        val started = System.nanoTime()
        for (file in images) {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
            for (line in text.textBlocks.flatMap { it.lines }) {
                val p = line.cornerPoints ?: continue // top-left, top-right, bottom-right, bottom-left
                val cx = p.sumOf { it.x.toDouble() } / 4
                val cy = p.sumOf { it.y.toDouble() } / 4
                val width = hypot((p[1].x - p[0].x).toDouble(), (p[1].y - p[0].y).toDouble())
                val height = hypot((p[3].x - p[0].x).toDouble(), (p[3].y - p[0].y).toDouble())
                val angle = atan2((p[1].y - p[0].y).toDouble(), (p[1].x - p[0].x).toDouble())
                rows.append("${file.name}\tdefault\t$cx\t$cy\t$width\t$height\t$angle\t${line.text}\n")
                count++
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        File(root, "mlkit.tsv").writeText(rows.toString())
        recognizer.close()
        Log.i("OCRBENCH", "OCRBENCH DONE $count rows, ${images.size} images, $millis ms")
    }
}
