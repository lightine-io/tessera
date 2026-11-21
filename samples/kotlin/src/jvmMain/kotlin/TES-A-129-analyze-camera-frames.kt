// Sample for the page TES-A-129, Analyze your own camera frames in Kotlin: https://lightine.youtrack.cloud/articles/TES-A-129. Region text matches the page; code outside regions only makes it compile.
package samples.tesa129

// region TES-A-129 block 2
import io.lightine.tessera.mrz.camera.CameraError
import io.lightine.tessera.mrz.camera.ConsensusVerdict
import io.lightine.tessera.mrz.camera.MrzDecodeConsensus
import io.lightine.tessera.mrz.camera.MrzFrameAnalyzer
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.MrzTextRecognizer
import io.lightine.tessera.mrz.camera.ParsingMode
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.scan
import io.lightine.tessera.mrz.parsing.ParseResult
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant
// endregion

fun main() {
    // region TES-A-129 block 3
    class Frame(val lines: List<String>)

    val recognizer = MrzTextRecognizer<Frame> { frame ->
        RecognizedText(frame.lines.map { RecognizedLine(it, null) })
    }
    // endregion

    // region TES-A-129 block 4
    val analyzer = MrzFrameAnalyzer(
        recognizer,
        referenceTimeProvider = { Instant.parse("2010-06-01T00:00:00Z") },
    )
    // endregion

    // region TES-A-129 block 5
    val mrzFrame = Frame(
        listOf(
            "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<",
            "L898902C36UTO7408122F1204159ZE184226B<<<<<10",
        ),
    )
    runBlocking {
        when (val result = analyzer.analyse(mrzFrame)) {
            is MrzScanResult.Decoded -> println("Decoded ${result.parse::class.simpleName}")
            is MrzScanResult.NoMrzFound -> println("No MRZ in this frame")
            is MrzScanResult.CaptureError -> println("Capture error ${result.error.code}")
        }
    }
    // endregion

    // region TES-A-129 block 6
    runBlocking { println(analyzer.analyse(Frame(listOf("PRINTED TEXT ONLY"))).quality) }
    // endregion

    // region TES-A-129 block 7
    val frames = flowOf(mrzFrame, Frame(listOf("PRINTED TEXT ONLY")), mrzFrame)
    val consensus = MrzDecodeConsensus()
    runBlocking {
        analyzer.scan(frames).collect { result ->
            if (result is MrzScanResult.Decoded && result.parse !is ParseResult.Failure) {
                when (val verdict = consensus.offer(result)) {
                    is ConsensusVerdict.Gathering -> println("Gathering ${verdict.agreement} of ${verdict.threshold}")
                    is ConsensusVerdict.Confirmed -> println("Confirmed")
                }
            } else {
                println("No usable MRZ in this frame")
            }
        }
    }
    // endregion

    // region TES-A-129 block 8
    val broken = flow<Frame> { throw IllegalStateException("device unplugged") }
    runBlocking {
        analyzer.scan(broken, captureErrorFor = { CameraError.CameraUnavailable(it.message ?: "no detail") })
            .collect { result ->
                if (result is MrzScanResult.CaptureError) println(result.error.code)
            }
    }
    // endregion

    // region TES-A-129 block 9
    val lenient = MrzFrameAnalyzer(recognizer, mode = ParsingMode.LENIENT)
    // endregion
}
