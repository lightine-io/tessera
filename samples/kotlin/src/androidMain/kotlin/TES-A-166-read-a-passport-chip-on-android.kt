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
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.MrzKeyResult
import io.lightine.tessera.emrtd.nfc.NfcChipRead
import io.lightine.tessera.emrtd.nfc.NfcReadSettings
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.NfcNoAnswer
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

// For values a person typed: three strings, with the dates as YYMMDD.
fun accessKeyFromTyped(documentNumber: String, dateOfBirth: String, dateOfExpiry: String): ChipAccessKey.Mrz? {
    // First normalise: it trims spaces at both ends and turns a-z into A-Z, and changes nothing else.
    val fields = ChipAccessKey.Mrz.normalizeTypedFields(documentNumber, dateOfBirth, dateOfExpiry)
    // Show the person the three values and fields.changedFields, and let them confirm before you go on.
    show("Check: ${fields.documentNumber} ${fields.dateOfBirth} ${fields.dateOfExpiry}, changed: ${fields.changedFields}")
    // Then build the key from the normalised values. A typing mistake still builds a key.
    return when (val result = ChipAccessKey.Mrz.fromTypedFields(fields)) {
        is MrzKeyResult.Built -> result.key
        is MrzKeyResult.NotBuilt -> {
            show("The values cannot be used: ${result.field} ${result.problem}") // names the field, never the value
            null
        }
    }
}
// endregion

// region TES-A-166 block 4
fun startRead(activity: Activity, key: ChipAccessKey): ChipReadHandle {
    // Call this from the person's action, such as a button, and not from onCreate.
    val options = ChipReadOptions(accessKey = key, checkTime = Clock.System.now())
    // Turns reader mode on for this screen, runs the read, turns it off at the ending.
    return NfcChipRead.start(activity, options, ReadScreen)
}
// endregion

// region TES-A-166 block 5
object ReadScreen : ChipReadObserver {
    // Both calls come on the main thread, in order, and nothing comes before start returns.
    override fun onUpdate(update: ChipReadUpdate) = show("${describe(update.state)} ${update.progress.percent}%")

    override fun onEnding(ending: ChipReadEnding) = handle(ending) // exactly once, and last
}

// Five states are objects, matched by name. Six are classes with values, matched with `is`.
// With no `else`, the compiler tells you when a later release adds a state.
fun describe(state: ChipReadState): String = when (state) {
    ChipReadState.Starting -> "Starting"
    is ChipReadState.WaitingForDocument -> "Hold the document against the phone (${state.waitedMillis} ms)"
    ChipReadState.DocumentFound -> "Found, hold still"
    ChipReadState.Connecting -> "Connecting"
    is ChipReadState.OpeningAccess -> "Opening access with ${state.protocol}" // protocol can be null
    is ChipReadState.ReadingFile -> "Reading a file: ${state.bytesRead} of ${state.bytesTotal} bytes"
    is ChipReadState.AuthenticatingChip -> "Checking the chip with ${state.method}"
    ChipReadState.CheckingFiles -> "Checking the files"
    is ChipReadState.NoAnswerYet -> "Found, no answer yet: move the document a little"
    is ChipReadState.LinkLost -> "Connection lost, hold the document again (${state.restartsLeft} tries left)"
    ChipReadState.Stopping -> "Stopping"
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
    is NfcNoAnswer -> "No answer from the chip. Try another position."
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

// The manifest entry of this screen has
// android:configChanges="orientation|screenSize|screenLayout|keyboardHidden",
// so a rotation does not destroy it and does not end the read.
class ReadActivity : Activity() {
    private var read: ChipReadHandle? = null

    // Called by the person's button. A key is for one read.
    fun onReadButton(key: ChipAccessKey) {
        if (read == null) read = startRead(this, key)
    }

    override fun onDestroy() {
        read?.let(::leaveScreen)
        super.onDestroy()
    }
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
