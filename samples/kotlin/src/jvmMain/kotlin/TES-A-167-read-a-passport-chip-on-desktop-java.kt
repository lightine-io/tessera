// Sample for the page TES-A-167, Read a passport chip on desktop Java: https://lightine.youtrack.cloud/articles/TES-A-167. Region text matches the page; code outside regions only makes it compile.
package samples.tesa167

// region TES-A-167 block 2
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.pcsc.PcscReaders
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
// endregion

// region TES-A-167 block 3
fun firstReaderName(): String? = PcscReaders.listReaders().firstOrNull()
// endregion

// region TES-A-167 block 4
// Replace the values with the ones printed in your document's MRZ.
val key = ChipAccessKey.Mrz(
    documentNumber = "T22000129",
    documentNumberCheckDigit = '3',
    dateOfBirth = "900315",
    dateOfBirthCheckDigit = '2',
    dateOfExpiry = "340314",
    dateOfExpiryCheckDigit = '1',
)
// endregion

// region TES-A-167 block 5
suspend fun readChip(readerName: String, key: ChipAccessKey): ChipReadResult {
    val options = ChipReadOptions(accessKey = key, checkTime = Clock.System.now())
    // connect waits up to 30 seconds for a document on the reader
    return PcscReaders.connect(readerName).use { transport ->
        ChipReader().read(transport, options)
    }
}
// endregion

// region TES-A-167 block 6
fun main() {
    try {
        val reader = firstReaderName() ?: return println("No reader found")
        println("Put the document on $reader")
        when (val result = runBlocking { readChip(reader, key) }) {
            is ChipReadResult.Complete -> println("Read ${result.read.files.size} files, ${result.read.checks.size} checks")
            is ChipReadResult.Partial -> println("Stopped: ${result.stoppedBy.description}")
            is ChipReadResult.Failed -> println("Not read: ${result.error.description}")
        }
    } catch (e: ApduTransportException) {
        println("Reader problem: ${e.error.description}")
    } finally {
        key.clear()
    }
}
// endregion

// region TES-A-167 block 7
val canKey = ChipAccessKey.Can("123456") // the card access number printed on your document
val options = ChipReadOptions(
    accessKey = canKey,
    dataGroups = setOf(1, 2, 11),
    checkTime = Clock.System.now(),
    allowExtendedLength = false,
)
// endregion
