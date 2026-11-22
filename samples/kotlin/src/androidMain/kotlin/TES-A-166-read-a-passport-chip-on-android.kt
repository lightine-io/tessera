// Sample for the page TES-A-166, Read a passport chip on Android: https://lightine.youtrack.cloud/articles/TES-A-166. Region text matches the page; code outside regions only makes it compile.
package samples.tesa166

// region TES-A-166 block 2
import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipMrzComparison
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadResult
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.nfc.IsoDepTransport
import io.lightine.tessera.mrz.model.MrzDocument
import io.lightine.tessera.mrz.model.TD1
import io.lightine.tessera.mrz.model.TD2
import io.lightine.tessera.types.errors.ChipAccessDenied
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
// endregion

// Stands in for the app's own screen code.
internal fun show(text: String) {
    println(text)
}

// region TES-A-166 block 3
fun accessKeyFrom(document: MrzDocument): ChipAccessKey.Mrz {
    val common = document.commonFields
    val birth = common.dateOfBirth
    val expiry = common.dateOfExpiry
    val optionalData = when (document) {
        is TD1 -> document.optionalData1
        is TD2 -> document.optionalData
        else -> ""
    }
    return ChipAccessKey.Mrz.fromPrintedFields(
        documentNumberField = common.documentNumber,
        documentNumberCheckDigit = common.checkDigits.documentNumber,
        optionalData = optionalData,
        dateOfBirth = birth.rawYear + birth.rawMonth + birth.rawDay,
        dateOfBirthCheckDigit = common.checkDigits.dateOfBirth,
        dateOfExpiry = expiry.rawYear + expiry.rawMonth + expiry.rawDay,
        dateOfExpiryCheckDigit = common.checkDigits.dateOfExpiry,
    )
}
// endregion

// region TES-A-166 block 4
fun startListening(activity: Activity, scope: CoroutineScope, key: ChipAccessKey) {
    val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return // this device has no NFC reader
    val flags = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    adapter.enableReaderMode(activity, { tag ->
        scope.launch {
            try {
                handle(readChip(tag, key))
            } catch (e: ApduTransportException) {
                show(describe(e.error))
            }
        }
    }, flags, null)
}

fun stopListening(activity: Activity) {
    NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
}
// endregion

// region TES-A-166 block 5
suspend fun readChip(tag: Tag, key: ChipAccessKey): ChipReadResult {
    val options = ChipReadOptions(accessKey = key, checkTime = Clock.System.now())
    return IsoDepTransport.open(tag).use { transport ->
        ChipReader().read(transport, options)
    }
}
// endregion

// region TES-A-166 block 6
fun handle(result: ChipReadResult) {
    when (result) {
        is ChipReadResult.Complete -> showFacts(result.read)
        is ChipReadResult.Partial -> {
            showFacts(result.read) // what was read before the stop
            show(describe(result.stoppedBy))
        }
        is ChipReadResult.Failed -> show(describe(result.error))
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
fun compareWithPrinted(printed: MrzDocument, read: ChipRead) {
    val combined = ChipMrzComparison.combine(printed, read)
    if (combined == null) {
        show("The chip's DG1 was not read")
    } else {
        show("Differing fields: ${combined.differingFields}")
    }
}
// endregion

// region TES-A-166 block 8
fun finish(activity: Activity, key: ChipAccessKey) {
    stopListening(activity)
    key.clear()
}
// endregion

// region TES-A-166 block 9
suspend fun readMore(tag: Tag, key: ChipAccessKey): ChipReadResult {
    val options = ChipReadOptions.Builder(key)
        .dataGroups(setOf(1, 2, 11, 12))
        .checkTime(Clock.System.now())
        .allowExtendedLength(false)
        .build()
    return IsoDepTransport.open(tag, timeoutMillis = 10_000).use { transport ->
        ChipReader().read(transport, options)
    }
}
// endregion
