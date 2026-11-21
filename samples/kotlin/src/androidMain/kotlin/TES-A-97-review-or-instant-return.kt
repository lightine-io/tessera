// Sample for the page TES-A-97, Choose review or instant return on the Android scanner screen: https://lightine.youtrack.cloud/articles/TES-A-97. Region text matches the page; code outside regions only makes it compile.
package samples.tesa97

import androidx.compose.runtime.Composable
import io.lightine.tessera.mrz.camera.ui.MrzScannerResult
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen

// region TES-A-97 block 2
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
import io.lightine.tessera.mrz.camera.ui.ReviewMode
// endregion

@Composable
internal fun ScannerRoute() {
    // region TES-A-97 block 3
    val config = MrzScannerConfig {
        reviewMode = ReviewMode.INSTANT_RETURN
    }
    // endregion

    // region TES-A-97 block 4
    MrzScannerScreen(config = config, onResult = { /* handle the result */ })
    // endregion
}

internal fun onConfirmed(confirmed: MrzScannerResult.Confirmed) {
    // region TES-A-97 block 5
    val failures = confirmed.result.parse.metadata.validationFailures
    // endregion
}

internal fun configure() {
    // region TES-A-97 block 6
    val config = MrzScannerConfig {
        reviewMode = ReviewMode.REVIEW   // the default, written out
    }
    // endregion
}
