// Sample for the page TES-A-102, Tune timing and extras of the Android scanner screen: https://lightine.youtrack.cloud/articles/TES-A-102. Region text matches the page; code outside regions only makes it compile.
package samples.tesa102

import androidx.compose.runtime.Composable
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen

// region TES-A-102 block 2
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
import kotlin.time.Duration.Companion.seconds
// endregion

// region TES-A-102 block 5 part 1
import kotlin.time.Duration
// endregion

@Composable
internal fun ScannerRoute() {
    // region TES-A-102 block 3
    val config = MrzScannerConfig {
        struggleTimeout = 20.seconds
        scanTimeout = 90.seconds
        consensusReads = 3
        showTorchButton = false
        hapticFeedback = false
    }
    // endregion

    // region TES-A-102 block 4
    MrzScannerScreen(config = config, onResult = { /* handle the result */ })
    // endregion
}

internal fun configure() {
    // region TES-A-102 block 5 part 2
    val config = MrzScannerConfig {
        struggleTimeout = Duration.INFINITE   // never show the hint
    }
    // endregion
}
