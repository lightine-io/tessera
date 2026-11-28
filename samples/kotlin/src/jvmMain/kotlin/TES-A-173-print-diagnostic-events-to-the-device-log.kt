// Sample for the page TES-A-173, Print diagnostic events to the device log in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-173. Region text matches the page; code outside regions only makes it compile.
package samples.tesa173

// region TES-A-173 block 2
import io.lightine.tessera.logging.LoggingTelemetrySink
import io.lightine.tessera.mrz.camera.MrzFrameAnalyzer
import io.lightine.tessera.mrz.camera.MrzTextRecognizer
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import kotlinx.coroutines.runBlocking
// endregion

fun main() {
    // region TES-A-173 block 3
    TelemetrySinkRegistry.register(LoggingTelemetrySink())
    // endregion

    // region TES-A-173 block 4
    class Frame

    val recognizer = MrzTextRecognizer<Frame> {
        RecognizedText(
            listOf(
                RecognizedLine("P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<", null),
                RecognizedLine("T220001293UTO9003152F3403141S98765432<<<<<20", null),
            ),
        )
    }
    runBlocking { MrzFrameAnalyzer(recognizer).analyse(Frame()) }
    // endregion
}

fun withOwnTag() {
    // region TES-A-173 block 6
    TelemetrySinkRegistry.register(LoggingTelemetrySink(tag = "MyScanner"))
    // endregion
}
