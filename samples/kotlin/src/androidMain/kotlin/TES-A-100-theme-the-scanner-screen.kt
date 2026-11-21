// Sample for the page TES-A-100, Theme the Android scanner screen: https://lightine.youtrack.cloud/articles/TES-A-100. Region text matches the page; code outside regions only makes it compile.
package samples.tesa100

import androidx.compose.runtime.Composable
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen

// region TES-A-100 block 2
import io.lightine.tessera.mrz.camera.ui.DarkMode
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
// endregion

@Composable
internal fun ScannerRoute() {
    // region TES-A-100 block 3
    val config = MrzScannerConfig {
        theme {
            brandColor = 0xFF00695CL
            darkMode = DarkMode.ON   // always dark
        }
    }
    // endregion

    // region TES-A-100 block 4
    MrzScannerScreen(config = config, onResult = { /* handle the result */ })
    // endregion
}

internal fun configure() {
    // region TES-A-100 block 5
    val config = MrzScannerConfig {
        theme {
            useDynamicColor = true
        }
    }
    // endregion
}
