package io.lightine.tessera.ocrbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil

// Reads every MRZ line crop in the app's private files folder `lines/` (`<id>__<condition>__<n>.png`,
// cut by render.swift from the known line geometry) with a PaddleOCR PP-OCRv5 English recognizer
// run through ONNX Runtime, and writes `onnx.tsv` beside it in the benchmark's fragment format:
// image <TAB> variant <TAB> centre-x <TAB> centre-y <TAB> width <TAB> height <TAB> angle <TAB> text
// One row per line and variant: `raw` is the decoder's text as is; `filtered` is upper-cased and
// keeps only A-Z, 0-9 and `<` (a post-filter the vendor engines do not get). The line number sets
// the row's y so score.py reassembles the lines in order. The model is `files/model.onnx`.
// Preprocessing as PaddleOCR: height 48, width by aspect (at least 320), BGR, (x / 255 - 0.5) / 0.5,
// NCHW; CTC greedy decode, blank is index 0. Logs "OCRBENCH DONE <rows> ..." when finished.
class OnnxBenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { run() }.start()
    }

    private fun run() {
        val root = filesDir
        val crops = File(root, "lines").listFiles { f -> f.name.endsWith(".png") }.orEmpty().sortedBy { it.name }
        val env = OrtEnvironment.getEnvironment()
        val loadStart = System.nanoTime()
        val session = env.createSession(File(root, "model.onnx").path, OrtSession.SessionOptions())
        val loadMillis = (System.nanoTime() - loadStart) / 1_000_000
        // PaddleOCR keeps the character list in the model's metadata, one character per line;
        // class 0 is the CTC blank, and the last class is the space.
        val chars = listOf("") + session.metadata.customMetadata.getValue("character").split("\n") + listOf(" ")
        val inputName = session.inputNames.first()
        val rows = StringBuilder()
        var count = 0
        var inferNanos = 0L
        for (file in crops) {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: continue
            val image = file.nameWithoutExtension.substringBeforeLast("__")
            val n = file.nameWithoutExtension.substringAfterLast("__").toInt()
            val h = 48
            val scaledWidth = ceil(h.toDouble() * bitmap.width / bitmap.height).toInt()
            val w = maxOf(320, scaledWidth)
            val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, h, true)
            val pixels = IntArray(scaledWidth * h)
            scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, h)
            val data = FloatArray(3 * h * w) // right padding stays 0, the normalized mid-grey
            for (y in 0 until h) for (x in 0 until scaledWidth) {
                val p = pixels[y * scaledWidth + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                data[0 * h * w + y * w + x] = (b / 255f - 0.5f) / 0.5f // BGR
                data[1 * h * w + y * w + x] = (g / 255f - 0.5f) / 0.5f
                data[2 * h * w + y * w + x] = (r / 255f - 0.5f) / 0.5f
            }
            val started = System.nanoTime()
            val raw = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong())).use { input ->
                session.run(mapOf(inputName to input)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (out[0].value as Array<Array<FloatArray>>)[0]
                    val text = StringBuilder()
                    var previous = 0
                    for (step in logits) {
                        var best = 0
                        for (i in step.indices) if (step[i] > step[best]) best = i
                        if (best != 0 && best != previous) text.append(chars[best])
                        previous = best
                    }
                    text.toString()
                }
            }
            inferNanos += System.nanoTime() - started
            val filtered = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
            for ((variant, text) in listOf("raw" to raw, "filtered" to filtered)) {
                rows.append("$image.png\t$variant\t500\t${n * 100 + 20}\t1000\t40\t0\t$text\n")
            }
            count++
        }
        File(root, "onnx.tsv").writeText(rows.toString())
        session.close()
        Log.i(
            "OCRBENCH",
            "OCRBENCH DONE $count crops, load $loadMillis ms, infer ${inferNanos / 1_000_000} ms total, " +
                "${inferNanos / 1_000_000 / maxOf(1, count)} ms per line, classes ${chars.size}",
        )
    }
}
