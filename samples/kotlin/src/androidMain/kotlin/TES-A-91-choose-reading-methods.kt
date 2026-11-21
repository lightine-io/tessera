// Sample for the page TES-A-91, Choose the reading methods of the scanner screen on Android: https://lightine.youtrack.cloud/articles/TES-A-91. Region text matches the page; code outside regions only makes it compile.
package samples.tesa91

import androidx.compose.runtime.Composable
import io.lightine.tessera.mrz.camera.ui.MrzScannerResult
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen

// region TES-A-91 block 2
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
import io.lightine.tessera.mrz.camera.ui.ScanMethod
// endregion

@Composable
internal fun ScannerRoute() {
    // region TES-A-91 block 3
    val config = MrzScannerConfig {
        enabledMethods = setOf(ScanMethod.CAMERA, ScanMethod.SAVED_IMAGE, ScanMethod.MANUAL_ENTRY)
    }
    // endregion

    // region TES-A-91 block 4
    MrzScannerScreen(config = config, onResult = { /* handle the result */ })
    // endregion
}

internal fun onConfirmed(confirmed: MrzScannerResult.Confirmed) {
    // region TES-A-91 block 5
    val readMethod = confirmed.result.parse.metadata.readMethod
    // endregion
}

internal fun configure() {
    // region TES-A-91 block 6
    val config = MrzScannerConfig {
        enabledMethods = setOf(ScanMethod.CAMERA)   // camera only: no photo, no typing
    }
    // endregion
}
