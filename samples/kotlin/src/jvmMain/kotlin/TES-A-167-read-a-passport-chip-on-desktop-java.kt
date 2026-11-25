// Sample for the page TES-A-167, Read a passport chip on desktop Java: https://lightine.youtrack.cloud/articles/TES-A-167. Region text matches the page; code outside regions only makes it compile.
package samples.tesa167

// region TES-A-167 block 2
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReadUpdateListener
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.CanKeyResult
import io.lightine.tessera.emrtd.MrzKeyResult
import io.lightine.tessera.emrtd.pcsc.PcscReaders
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
// endregion

// region TES-A-167 block 3
fun firstReaderName(): String? = PcscReaders.listReaders().firstOrNull()
// endregion

// region TES-A-167 block 4
// Replace the values with the ones printed in your document's MRZ.
fun accessKey(): ChipAccessKey.Mrz? =
    when (val result = ChipAccessKey.Mrz.fromTypedFields(
        documentNumber = "T22000129",
        dateOfBirth = "900315",
        dateOfExpiry = "340314",
    )) {
        is MrzKeyResult.Built -> result.key
        is MrzKeyResult.NotBuilt -> {
            println("No key could be made: ${result.problem}")
            null
        }
    }
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
    val key = accessKey() ?: return
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
        key.clear() // ChipReader.read does not clear the key
    }
}
// endregion

// region TES-A-167 block 7
fun optionsWithCan(): ChipReadOptions? =
    when (val result = ChipAccessKey.Can.from("123456")) { // the card access number printed on your document
        is CanKeyResult.Built -> ChipReadOptions(
            accessKey = result.key,
            dataGroups = setOf(1, 2, 11),
            checkTime = Clock.System.now(),
            allowExtendedLength = false,
        )
        is CanKeyResult.NotBuilt -> {
            println("No key could be made: ${result.problem}")
            null
        }
    }
// endregion

// region TES-A-167 block 8
// The same read with progress: the listener hears each step, one at a time, on the calling coroutine.
suspend fun readChipWithProgress(readerName: String, key: ChipAccessKey): ChipReadResult {
    val options = ChipReadOptions(accessKey = key, checkTime = Clock.System.now())
    val progress = ChipReadUpdateListener { update ->
        println("${update.state} ${update.progress.percent}%") // states and counts, never document data
    }
    return PcscReaders.connect(readerName).use { transport ->
        ChipReader().read(transport, options, progress)
    }
}
// endregion
