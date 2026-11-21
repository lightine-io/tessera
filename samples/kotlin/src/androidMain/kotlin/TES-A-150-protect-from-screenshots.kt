// Sample for the page TES-A-150, Protect the Android scanner screen from screenshots: https://lightine.youtrack.cloud/articles/TES-A-150. Region text matches the page; code outside regions only makes it compile.
package samples.tesa150

// region TES-A-150 block 1
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class ScannerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { /* your scanner screen */ }
    }
}
// endregion

internal fun ScannerActivity.configure() {
    // region TES-A-150 block 2
    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    // endregion
}
