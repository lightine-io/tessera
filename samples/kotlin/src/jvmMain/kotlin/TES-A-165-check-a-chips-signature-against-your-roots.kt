// Sample for the page TES-A-165, Check a chip's signature against your own root certificates: https://lightine.youtrack.cloud/articles/TES-A-165. Region text matches the page; code outside regions only makes it compile.
package samples.tesa165

// region TES-A-165 block 2
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipCheckKind
import io.lightine.tessera.emrtd.ChipRead
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipSignedFile
import io.lightine.tessera.emrtd.trust.DocumentSignerCertificate
import io.lightine.tessera.emrtd.trust.DocumentSignerCertificateParse
import io.lightine.tessera.emrtd.trust.MasterListCheckKind
import io.lightine.tessera.emrtd.trust.MasterListRead
import io.lightine.tessera.emrtd.trust.MasterListReader
import io.lightine.tessera.emrtd.trust.TrustAnchor
import io.lightine.tessera.emrtd.trust.TrustAnchorParse
import io.lightine.tessera.types.vocabulary.CheckOutcome
import kotlin.time.Clock
// endregion

// region TES-A-165 block 3
fun rootFromDer(der: ByteArray): TrustAnchor? =
    when (val parsed = TrustAnchor.fromDer(der)) {
        is TrustAnchorParse.Parsed -> parsed.anchor
        is TrustAnchorParse.Unreadable -> null // parsed.problem and parsed.offset say why
    }
// endregion

// region TES-A-165 block 4
fun rootsFromMasterList(file: ByteArray, myRoots: List<TrustAnchor>): List<TrustAnchor> =
    when (val read = MasterListReader.read(file, myRoots)) {
        is MasterListRead.Read -> {
            // Your policy: use the list only when its signer chains to a root you already trust.
            val chains = read.masterList.outcomeOf(MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT)
            if (chains == CheckOutcome.Passed) read.masterList.anchors else emptyList()
        }
        is MasterListRead.Unreadable -> emptyList() // read.problem says why
    }
// endregion

// region TES-A-165 block 5
fun optionsWithRoots(key: ChipAccessKey, roots: List<TrustAnchor>): ChipReadOptions =
    ChipReadOptions(
        accessKey = key,
        trustAnchors = roots,
        checkTime = Clock.System.now(),
    )
// endregion

// region TES-A-165 block 6
fun describe(outcome: CheckOutcome?): String = when (outcome) {
    null -> "no such check in this read"
    CheckOutcome.Passed -> "passed"
    is CheckOutcome.Failed -> "failed: ${outcome.reason}"
    is CheckOutcome.NotApplicable -> "not applicable: ${outcome.reason}"
    is CheckOutcome.NotRun -> "not run: ${outcome.reason}"
}

fun report(read: ChipRead) {
    val file = ChipSignedFile.SECURITY_OBJECT
    println("signature: " + describe(read.outcomeOf(ChipCheckKind.SignatureVerifies(file))))
    println("root: " + describe(read.outcomeOf(ChipCheckKind.SignerChainsToSuppliedRoot(file))))
    println("validity: " + describe(read.outcomeOf(ChipCheckKind.SignerWithinValidityPeriod(file))))
    println("revocation: " + describe(read.outcomeOf(ChipCheckKind.SignerNotRevoked)))
}
// endregion

// region TES-A-165 block 7
fun allChecks(read: ChipRead) {
    for (check in read.checks) {
        println("${check.kind}: ${describe(check.outcome)}")
    }
}
// endregion

// region TES-A-165 block 8
fun optionsWithSigner(key: ChipAccessKey, roots: List<TrustAnchor>, signerDer: ByteArray): ChipReadOptions? =
    when (val parsed = DocumentSignerCertificate.fromDer(signerDer)) {
        is DocumentSignerCertificateParse.Parsed -> ChipReadOptions(
            accessKey = key,
            trustAnchors = roots,
            checkTime = Clock.System.now(),
            signerCertificate = parsed.certificate,
        )
        is DocumentSignerCertificateParse.Unreadable -> null
    }
// endregion
