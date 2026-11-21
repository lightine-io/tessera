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

fun main() {
    // region TES-A-134 block 3
    val recognizer = MrzTextRecognizer<Path> { image ->
        // Run your OCR engine on the file at "image" and return every line it read.
        RecognizedText(
            listOf(
                RecognizedLine("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", null),
                RecognizedLine("L898902C36UTO7408122F1204159ZE184226B<<<<<10", null),
            ),
        )
    }
    // endregion

    // region TES-A-134 block 4
    val reader = SavedImageMrzReader(
        acknowledgement = SavedImageReadingAcknowledgement(),
        recognizer = recognizer,
        referenceTimeProvider = { Instant.parse("2010-06-01T00:00:00Z") },
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
