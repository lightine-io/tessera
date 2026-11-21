// Sample for the page TES-A-14, Read an MRZ from the live camera on Android: https://lightine.youtrack.cloud/articles/TES-A-14. Region text matches the page; code outside regions only makes it compile.
package samples.tesa14

import androidx.activity.ComponentActivity

// region TES-A-14 block 2
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlinx.coroutines.launch
// endregion

// region TES-A-14 block 7 part 1
import androidx.camera.core.CameraSelector
import io.lightine.tessera.mrz.camera.ParsingMode
// endregion

class MainActivity : ComponentActivity() {
    // region TES-A-14 block 3
    private lateinit var scanner: CameraXMrzScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = CameraXMrzScanner(applicationContext, this)
    }
    // endregion

    private fun startReading() {
        // region TES-A-14 block 4
        lifecycleScope.launch {
            scanner.results.collect { result ->
                when (result) {
                    is MrzScanResult.Decoded -> when (val parse = result.parse) {
                        is ParseResult.Success -> { /* parse.document */ }
                        is ParseResult.PartialSuccess -> { /* parse.document, parse.metadata.validationFailures */ }
                        is ParseResult.Failure -> { /* text shaped like an MRZ that the parser rejected */ }
                    }
                    is MrzScanResult.NoMrzFound -> { /* normal between reads */ }
                    is MrzScanResult.CaptureError -> { /* switch on result.error.code */ }
                }
            }
        }
        // endregion

        // region TES-A-14 block 5
        scanner.start()
        // endregion
    }

    // region TES-A-14 block 6
    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
    }
    // endregion

    private fun configure() {
        // region TES-A-14 block 7 part 2
        scanner = CameraXMrzScanner(
            appContext = applicationContext,
            lifecycleOwner = this,
            mode = ParsingMode.LENIENT,
            cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA,
        )
        // endregion
    }
}
