// Sample for the page TES-A-134, Read an MRZ from a saved image on the JVM: https://lightine.youtrack.cloud/articles/TES-A-134. Region text matches the page; code outside regions only makes it compile.
package samples.tesa134

// region TES-A-134 block 2
import io.lightine.tessera.mrz.camera.MrzScanResult
import io.lightine.tessera.mrz.camera.MrzTextRecognizer
import io.lightine.tessera.mrz.camera.ParsingMode
import io.lightine.tessera.mrz.camera.RecognizedLine
import io.lightine.tessera.mrz.camera.RecognizedText
import io.lightine.tessera.mrz.camera.SavedImageMrzReader
import io.lightine.tessera.mrz.camera.SavedImageReadingAcknowledgement
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.time.Instant
// endregion

// region TES-A-134 block 3
class FileOcrEngine : MrzTextRecognizer<Path> {
    override suspend fun recognize(image: Path): RecognizedText {
        // Run your OCR engine on the file at "image" here.
        val engineLines: List<RecognizedLine> = emptyList() // yourEngine.read(image)
        return RecognizedText(engineLines)
    }
}
// endregion

fun main() {
    // region TES-A-134 block 4
    val reader = SavedImageMrzReader(
        acknowledgement = SavedImageReadingAcknowledgement(),
        recognizer = FileOcrEngine(),
        referenceTimeProvider = { Instant.parse("2026-11-21T00:00:00Z") },
    )
    // endregion

    // region TES-A-134 block 5
    try {
        val result = runBlocking { reader.read(Path.of("passport-page.png")) }
        when (val scan = result.scan) {
            is MrzScanResult.Decoded -> println("${scan.parse::class.simpleName} ${scan.parse.metadata.readMethod}")
            is MrzScanResult.NoMrzFound -> println("No MRZ in this image")
            is MrzScanResult.CaptureError -> println("OCR failed: ${scan.error.code}")
        }
    } finally {
        reader.close()
    }
    // endregion
}

internal fun configure(recognizer: MrzTextRecognizer<Path>) {
    // region TES-A-134 block 6
    val reader = SavedImageMrzReader(
        acknowledgement = SavedImageReadingAcknowledgement(),
        recognizer = recognizer,
        mode = ParsingMode.STRICT,
        tolerant = true,
    )
    // endregion
}
