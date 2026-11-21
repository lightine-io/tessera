// Sample for the page TES-A-90, Accept a read after N matching frames on Android: https://lightine.youtrack.cloud/articles/TES-A-90. Region text matches the page; code outside regions only makes it compile.
package samples.tesa90

import androidx.activity.ComponentActivity
import io.lightine.tessera.mrz.camera.CameraXMrzScanner

// region TES-A-90 block 2
import io.lightine.tessera.mrz.camera.ConsensusVerdict
import io.lightine.tessera.mrz.camera.MrzDecodeConsensus
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.parsing.ParseResult
// endregion

// region TES-A-90 block 6 part 1
import io.lightine.tessera.mrz.camera.ParsingMode
// endregion

internal class MainActivity : ComponentActivity() {
    private lateinit var scanner: CameraXMrzScanner

    private fun onAgreed(decoded: MrzScanResult.Decoded) {}

    private suspend fun readUntilAgreed() {
        // region TES-A-90 block 3
        val consensus = MrzDecodeConsensus(threshold = 2)
        // endregion

        // region TES-A-90 block 4
        scanner.results.collect { result ->
            if (result is MrzScanResult.Decoded && result.parse !is ParseResult.Failure) {
                when (val verdict = consensus.offer(result)) {
                    is ConsensusVerdict.Confirmed -> onAgreed(verdict.decoded)   // your handler
                    is ConsensusVerdict.Gathering -> { /* verdict.agreement of verdict.threshold so far */ }
                }
            }
        }
        // endregion

        // region TES-A-90 block 5
        consensus.reset()
        // endregion
    }

    private fun configure() {
        // region TES-A-90 block 6 part 2
        val consensus = MrzDecodeConsensus(threshold = 3)
        val scanner = CameraXMrzScanner(applicationContext, this, mode = ParsingMode.LENIENT)
        // endregion
    }
}
