// Sample for the page TES-A-92, Hand the camera permission to your app from the Android scanner screen: https://lightine.youtrack.cloud/articles/TES-A-92. Region text matches the page; code outside regions only makes it compile.
package samples.tesa92

import androidx.compose.runtime.Composable
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen

// region TES-A-92 block 2
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
// endregion

@Composable
internal fun ScannerRoute() {
    // region TES-A-92 block 3
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the screen checks the permission again when your app resumes */ }
    // endregion

    // region TES-A-92 block 4
    val config = MrzScannerConfig {
        onRequestPermission = { requestCamera.launch(Manifest.permission.CAMERA) }
    }
    // endregion

    // region TES-A-92 block 5
    MrzScannerScreen(config = config, onResult = { /* handle the result */ })
    // endregion
}

internal fun configure() {
    // region TES-A-92 block 6
    val config = MrzScannerConfig()   // no handler: ask for the permission before you show the screen
    // endregion
}
