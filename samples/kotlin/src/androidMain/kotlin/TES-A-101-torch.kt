// Sample for the page TES-A-101, Turn the torch on and off on Android: https://lightine.youtrack.cloud/articles/TES-A-101. Region text matches the page; code outside regions only makes it compile.
package samples.tesa101

// region TES-A-101 block 2
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
// endregion

// region TES-A-101 block 3
@Composable
fun TorchButton(scanner: CameraXMrzScanner) {
    val request by scanner.surfaceRequest.collectAsStateWithLifecycle()
    val hasTorch = remember(request) { scanner.hasTorch() }
    var torchOn by remember { mutableStateOf(false) }
    if (hasTorch) {
// endregion
// region TES-A-101 block 4
        Button(onClick = {
            torchOn = !torchOn
            scanner.setTorchEnabled(torchOn)
        }) { Text(if (torchOn) "Torch off" else "Torch on") }
    }
}
// endregion

internal fun configure(scanner: CameraXMrzScanner) {
    // region TES-A-101 block 5
    scanner.setTorchEnabled(true)   // torch on as soon as the camera opens
    // endregion
}
