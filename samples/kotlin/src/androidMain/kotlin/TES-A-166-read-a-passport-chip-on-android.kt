// Sample for the page TES-A-166, Read a passport chip on Android: https://lightine.youtrack.cloud/articles/TES-A-166. Region text matches the page; code outside regions only makes it compile.
package samples.tesa166

// region TES-A-166 block 2
import android.app.Activity
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipCheckDetail
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadHandle
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.MrzKeyResult
import io.lightine.tessera.emrtd.nfc.NfcChipRead
import io.lightine.tessera.emrtd.nfc.NfcReadSettings
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import kotlin.time.Clock
// endregion

// Stands in for the app's own screen code.
internal fun show(text: String) {
    println(text)
}

// region TES-A-166 block 3
fun accessKeyFrom(document: MrzDocument): ChipAccessKey.Mrz? =
    when (val result = ChipAccessKey.Mrz.from(document)) {
        is MrzKeyResult.Built -> result.key
        is MrzKeyResult.NotBuilt -> {
            show("No key could be made: ${result.problem}") // for example a visa, which has no chip
            null
        }
    }
// endregion

// region TES-A-166 block 4
fun startRead(activity: Activity, key: ChipAccessKey): ChipReadHandle {
    val options = ChipReadOptions(accessKey = key, checkTime = Clock.System.now())
    // Turns reader mode on for this screen, runs the read, turns it off at the ending.
    return NfcChipRead.start(activity, options, ReadScreen)
}
// endregion

// region TES-A-166 block 5
object ReadScreen : ChipReadObserver {
    // Both calls come on the main thread, in order, and nothing comes before start returns.
    override fun onUpdate(update: ChipReadUpdate) = show("${update.state} ${update.progress.percent}%")

    override fun onEnding(ending: ChipReadEnding) = handle(ending) // exactly once, and last
}
// endregion

// region TES-A-166 block 6
fun handle(ending: ChipReadEnding) {
    when (ending) {
        is ChipReadEnding.Complete -> showFacts(ending.read)
        is ChipReadEnding.Partial -> {
            showFacts(ending.read) // what was read before the stop
            show(describe(ending.stoppedBy))
        }
        is ChipReadEnding.Failed -> show(describe(ending.error))
        is ChipReadEnding.NotStarted -> show("NFC cannot be used: ${ending.reason}")
        else -> show("The read ended: $ending") // cancelled, key rejected, ended by the system
    }
}

fun describe(error: ChipError): String = when (error) {
    is NfcTagLost -> "The document moved. Hold it still against the phone."
    is NfcTimeout -> "The chip did not answer in time. Try again."
    is ChipAccessDenied -> "The chip did not accept the MRZ values."
    else -> error.description
}

fun showFacts(read: ChipRead) {
    val chipMrz = read.dg1?.document // the MRZ as the chip holds it
    val hashOfDg1 = read.outcomeOf(ChipCheckKind.DataGroupHashMatches(1))
    val signature = read.outcomeOf(ChipCheckKind.SignatureVerifies(ChipSignedFile.SECURITY_OBJECT))
    show("DG1 hash: $hashOfDg1, signature: $signature, files: ${read.files.size}")
}
// endregion

// region TES-A-166 block 7
fun optionsWithPrinted(key: ChipAccessKey, printed: MrzDocument): ChipReadOptions =
    ChipReadOptions.Builder(key)
        .printedDocument(printed) // you pass it yourself: no key builder does it for you
        .checkTime(Clock.System.now())
        .build()

fun showComparison(read: ChipRead) {
    val check = read.checks.firstOrNull { it.kind == ChipCheckKind.Dg1MatchesPrintedMrz }
    val fields = check?.detail as? ChipCheckDetail.PrintedMrzFields
    if (fields == null) {
        show("No comparison: the chip's DG1 was not read")
    } else {
        show("Differing fields: ${fields.differingFields}") // field names only, never values
    }
}
// endregion

// region TES-A-166 block 8
fun leaveScreen(read: ChipReadHandle) {
    read.cancel() // safe at any time; reader mode is switched off and the key is cleared by the SDK
}
// endregion

// region TES-A-166 block 9
fun startFullRead(activity: Activity, key: ChipAccessKey, printed: MrzDocument): ChipReadHandle {
    val options = ChipReadOptions.Builder(key)
        .dataGroups(listOf(1, 2, 11, 12))
        .printedDocument(printed)
        .checkTime(Clock.System.now())
        .allowExtendedLength(false)
        .build()
    val settings = NfcReadSettings.Builder()
        .commandTimeoutMillis(10_000)
        .platformSounds(false) // the system sound can make people move the document
        .build()
    return NfcChipRead.start(activity, options, ReadScreen, settings)
}
// endregion
