// Sample for the page TES-A-96, Restrict OCR to the MRZ band on Android: https://lightine.youtrack.cloud/articles/TES-A-96. Region text matches the page; code outside regions only makes it compile.
package samples.tesa96

import androidx.activity.ComponentActivity

// region TES-A-96 block 2
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
import io.lightine.tessera.mrz.camera.MlKitMrzTextRecognizer
// endregion

internal class MainActivity : ComponentActivity() {
    private fun startScanner() {
        // region TES-A-96 block 3
        val recognizer = MlKitMrzTextRecognizer().apply { restrictToMrzBand() }
        // endregion

        // region TES-A-96 block 4
        val scanner = CameraXMrzScanner(applicationContext, this, recognizer = recognizer)
        // endregion
    }

    private fun configure() {
        // region TES-A-96 block 5
        val recognizer = MlKitMrzTextRecognizer()   // default: whole frame
        // endregion
    }
}
