// Sample for the page TES-A-159, Send Tessera diagnostic events to your own crash reporter in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-159. Region text matches the page; code outside regions only makes it compile.
package samples.tesa159

// region TES-A-159 block 2
import io.lightine.tessera.mrz.camera.CameraFrameEvent
import io.lightine.tessera.mrz.camera.CameraFrameOutcome
import io.lightine.tessera.mrz.camera.MrzCameraScanner
import io.lightine.tessera.mrz.camera.MrzFrameAnalyzer
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.MrzTextRecognizer
import io.lightine.tessera.mrz.parsing.MrzParser
import io.lightine.tessera.mrz.parsing.ParseResult
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.telemetry.TelemetrySinkRegistry
import kotlinx.coroutines.runBlocking
// endregion

// region TES-A-159 block 3
interface CrashReporter {
    fun breadcrumb(category: String, message: String, data: Map<String, String>)
}

object PrintReporter : CrashReporter {
    override fun breadcrumb(category: String, message: String, data: Map<String, String>) {
        // your crash reporter's breadcrumb call goes here
        println("$category | $message | $data")
    }
}
// endregion

// region TES-A-159 block 4
class CrashReporterSink(private val reporter: CrashReporter) : TelemetrySink {
    // Frames can arrive on any thread. A rare race adds one extra breadcrumb and nothing worse.
    @Volatile private var lastOutcome: CameraFrameOutcome? = null

    override fun record(event: TelemetryEvent) {
        if (event !is CameraFrameEvent) return
        if (event.outcome == lastOutcome) return
        lastOutcome = event.outcome
        reporter.breadcrumb(
            category = event.name,
            message = event.outcome.name,
            data = mapOf(
                "recognizedLineCount" to event.recognizedLineCount.toString(),
                "mrzRegionFound" to event.mrzRegionFound.toString(),
                "ocrConfidence" to (event.ocrConfidence?.toString() ?: "none"),
                "detectedFormat" to (event.detectedFormat?.name ?: "none"),
            ),
        )
    }
}
// endregion

// region TES-A-159 block 6
fun forwardParse(reporter: CrashReporter, result: ParseResult) {
    if (result is ParseResult.Failure) {
        reporter.breadcrumb(
            category = "tessera.parse",
            message = result.error.description,
            data = mapOf("readMethod" to result.metadata.readMethod.name),
        )
    }
}
// endregion

// region TES-A-159 block 8
fun forwardScan(reporter: CrashReporter, result: MrzScanResult) {
    when (result) {
        is MrzScanResult.CaptureError ->
            reporter.breadcrumb("tessera.camera", result.error.code, emptyMap())
        is MrzScanResult.Decoded -> forwardParse(reporter, result.parse)
        is MrzScanResult.NoMrzFound -> Unit
    }
}

suspend fun forwardAll(scanner: MrzCameraScanner, reporter: CrashReporter) {
    scanner.results.collect { forwardScan(reporter, it) }
}
// endregion

fun main() {
    // region TES-A-159 block 5
    TelemetrySinkRegistry.register(CrashReporterSink(PrintReporter))
    // endregion

    // region TES-A-159 block 7
    // in main
    forwardParse(PrintReporter, MrzParser.parse("ABC"))
    // endregion

    // region TES-A-159 block 9
    // in main
    class Frame

    val failing = MrzTextRecognizer<Frame> { error("engine failed") }
    val result = runBlocking { MrzFrameAnalyzer(failing).analyse(Frame()) }
    forwardScan(PrintReporter, result)
    // endregion

    // region TES-A-159 block 10
    val analyzer = MrzFrameAnalyzer(failing, telemetry = CrashReporterSink(PrintReporter))
    // endregion
}
