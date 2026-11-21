// Sample for the page TES-A-98, Show a live camera preview on Android: https://lightine.youtrack.cloud/articles/TES-A-98. Region text matches the page; code outside regions only makes it compile.
package samples.tesa98

// region TES-A-98 block 2
import android.content.Context
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ViewPort
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.lightine.tessera.mrz.camera.CameraXMrzScanner
// endregion

// region TES-A-98 block 3
private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display.rotation
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }
// endregion

// region TES-A-98 block 4
@Composable
fun MrzViewfinder(scanner: CameraXMrzScanner) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val viewPort = remember(constraints) {
            ViewPort.Builder(
                Rational(constraints.maxWidth, constraints.maxHeight),
                displayRotation(context),
            ).build()
        }
// endregion
// region TES-A-98 block 5
        LaunchedEffect(scanner, viewPort) {
            scanner.enablePreview(viewPort)
            scanner.start()
        }
// endregion
// region TES-A-98 block 6
        val request by scanner.surfaceRequest.collectAsStateWithLifecycle()
        request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
    }
}
// endregion

internal fun configure(scanner: CameraXMrzScanner) {
    // region TES-A-98 block 7
    scanner.enablePreview()   // preview only: no alignment promise
    // endregion
}
