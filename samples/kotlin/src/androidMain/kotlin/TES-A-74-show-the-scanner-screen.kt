// Sample for the page TES-A-74, Show the scanner screen on Android: https://lightine.youtrack.cloud/articles/TES-A-74. Region text matches the page; code outside regions only makes it compile.
package samples.tesa74

// region TES-A-74 block 2
import androidx.compose.runtime.Composable
import io.lightine.tessera.mrz.camera.ui.DismissReason
import io.lightine.tessera.mrz.camera.ui.MrzScannerConfig
import io.lightine.tessera.mrz.camera.ui.MrzScannerResult
import io.lightine.tessera.mrz.camera.ui.MrzScannerScreen
import io.lightine.tessera.mrz.parsing.ParseResult
// endregion

// region TES-A-74 block 6 part 1
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
// endregion

// region TES-A-74 block 3
@Composable
fun ScannerRoute(onConfirmed: (ParseResult) -> Unit, onClosed: (DismissReason) -> Unit) {
    MrzScannerScreen(
        config = MrzScannerConfig(),
// endregion
// region TES-A-74 block 4
        onResult = { result ->
            when (result) {
                is MrzScannerResult.Confirmed -> onConfirmed(result.result.parse)
                is MrzScannerResult.Cancelled -> onClosed(result.reason)
            }
        },
    )
}
// endregion

// region TES-A-74 block 5
fun notesOf(parse: ParseResult): List<String> {
    val metadata = parse.metadata
    return metadata.validationFailures.map { "Validation failure: ${it.description}" } +
        metadata.warnings.map { "Warning: ${it.description}" }
}
// endregion

@Composable
internal fun MainScreen() {
    // region TES-A-74 block 6 part 2
    var showScanner by rememberSaveable { mutableStateOf(true) }
    if (showScanner) {
        ScannerRoute(onConfirmed = { showScanner = false }, onClosed = { showScanner = false })
    }
    // endregion
}

@Composable
internal fun configure() {
    ScannerRoute(
        onConfirmed = {},
        // region TES-A-74 block 7
        onClosed = { reason ->
            when (reason) {
                DismissReason.TIMED_OUT -> { /* tell the user time ran out */ }
                else -> { /* go back */ }
            }
        }
        // endregion
    )
}
