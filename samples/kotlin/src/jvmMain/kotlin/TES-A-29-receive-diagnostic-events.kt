// Sample for the page TES-A-29, Receive diagnostic events in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-29. Region text matches the page; code outside regions only makes it compile.
package samples.tesa29

// region TES-A-29 block 2
import io.lightine.tessera.mrz.camera.CameraFrameEvent
import io.lightine.tessera.mrz.camera.MrzFrameAnalyzer
import io.lightine.tessera.mrz.camera.MrzTextRecognizer
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.telemetry.Redaction
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import kotlinx.coroutines.runBlocking
// endregion

// region TES-A-29 block 3
object PrintSink : TelemetrySink {
    override fun record(event: TelemetryEvent) {
        if (event is CameraFrameEvent) {
            println(
                "${event.name} ${event.outcome} lines=${event.recognizedLineCount} " +
                    "region=${event.mrzRegionFound} confidence=${event.ocrConfidence} format=${event.detectedFormat}",
            )
        }
    }
}
// endregion

fun main() {
    // region TES-A-29 block 4
    TelemetrySinkRegistry.register(PrintSink)
    // endregion

    // region TES-A-29 block 5
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

    // region TES-A-29 block 6
    println(Redaction.redactMrzLine("P<UTOSPECIMEN<<ALEX<MORGAN<<<<<<<<<<<<<<<<<<"))
    // endregion

    // region TES-A-29 block 7
    val analyzer = MrzFrameAnalyzer(recognizer, telemetry = PrintSink)
    // endregion
}
