// Sample for the page TES-A-99, Swap the OCR engine on Android: https://lightine.youtrack.cloud/articles/TES-A-99. Region text matches the page; code outside regions only makes it compile.
package samples.tesa99

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import io.lightine.tessera.mrz.camera.CameraXMrzScanner

// region TES-A-99 block 2
import androidx.camera.core.ImageProxy
import io.lightine.tessera.mrz.camera.MrzTextRecognizer
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
// endregion

/** Stands in for your OCR engine. */
interface MyOcrEngine : AutoCloseable {
    fun readLines(bitmap: Bitmap): List<String>
}

// region TES-A-99 block 3
class MyRecognizer(private val engine: MyOcrEngine) : MrzTextRecognizer<ImageProxy>, AutoCloseable {
    override suspend fun recognize(frame: ImageProxy): RecognizedText {
        val bitmap = frame.toBitmap()   // sensor orientation: rotate by frame.imageInfo.rotationDegrees
        val lines = engine.readLines(bitmap)   // your engine call
        return RecognizedText(lines.map { RecognizedLine(text = it, confidence = null) })
    }

    override fun close() = engine.close()
}
// endregion

internal class MainActivity : ComponentActivity() {
    private fun startScanner(engine: MyOcrEngine) {
        // region TES-A-99 block 4
        val scanner = CameraXMrzScanner(applicationContext, this, recognizer = MyRecognizer(engine))
        // endregion
    }

    private fun configure() {
        // region TES-A-99 block 5
        val scanner = CameraXMrzScanner(applicationContext, this)   // default: bundled ML Kit
        // endregion
    }
}
