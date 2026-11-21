package io.lightine.tessera.photobench

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.SavedImageMrzReader
import io.lightine.tessera.mrz.camera.SavedImageReadingAcknowledgement
import io.lightine.tessera.mrz.camera.mlKitSavedImageRecognizer
import kotlinx.coroutines.runBlocking
import java.io.File

// Reads every benchmark photo in the app's private files folder `images/` through the SDK's own
// saved-image path (the same reader and recognizer an app gets from mlKitSavedImageRecognizer) and
// writes one row per image to `results.tsv` beside it:
// image <TAB> outcome <TAB> parse <TAB> milliseconds <TAB> recognized line 1 | recognized line 2 | ...
// outcome is Decoded, NoMrzFound, or CaptureError; parse is the parse result's text (or "-").
// The images are synthetic, so the recognized text and parse text are written as is.
// Logs "PHOTOBENCH DONE <images>" when finished.
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { run() }.start()
    }

    private fun run() {
        val images = File(filesDir, "images").listFiles { f -> f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }
        val ack = SavedImageReadingAcknowledgement()
        val reader = SavedImageMrzReader(ack, mlKitSavedImageRecognizer(ack, this))
        val rows = StringBuilder()
        val flat = { s: String -> s.replace('\t', ' ').replace('\n', ' ') }
        for (file in images) {
            val started = System.nanoTime()
            val result = runBlocking { reader.read(Uri.fromFile(file)) }
            val millis = (System.nanoTime() - started) / 1_000_000
            val (outcome, parse, text) =
                when (val scan = result.scan) {
                    is MrzScanResult.Decoded -> Triple("Decoded", scan.parse.toString(), scan.recognizedText)
                    is MrzScanResult.NoMrzFound -> Triple("NoMrzFound", "-", scan.recognizedText)
                    is MrzScanResult.CaptureError -> Triple("CaptureError", scan.error.toString(), null)
                }
            val lines = text?.lines?.joinToString(" | ") { it.text }.orEmpty()
            rows.append("${file.name}\t$outcome\t${flat(parse)}\t$millis\t${flat(lines)}\n")
        }
        File(filesDir, "results.tsv").writeText(rows.toString())
        reader.close()
        RescueExperiment(images).run(File(filesDir, "experiment.tsv"))
        Log.i("PHOTOBENCH", "PHOTOBENCH DONE ${images.size}")
    }
}
