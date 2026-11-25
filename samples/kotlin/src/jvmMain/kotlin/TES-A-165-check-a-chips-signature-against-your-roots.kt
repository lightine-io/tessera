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
import io.lightine.tessera.emrtd.trust.TrustAnchor
import io.lightine.tessera.emrtd.trust.TrustAnchors
import io.lightine.tessera.types.vocabulary.CheckOutcome
import java.io.File
import kotlin.time.Clock
// endregion

// region TES-A-165 block 3
fun loadRoots(file: File): List<TrustAnchor> {
    val loaded = TrustAnchors.load(file)
    if (loaded.problem != null) return emptyList() // loaded.problem says why nothing was loaded
    for (item in loaded.unreadable) {
        println("Skipped item ${item.position}: ${item.problem}")
    }
    return loaded.anchors
}
// endregion

// region TES-A-165 block 4
fun rootsFromMasterList(file: File, myRoots: List<TrustAnchor>): List<TrustAnchor> {
    val loaded = TrustAnchors.load(file, myRoots) // myRoots are used only for the list's own check
    val list = loaded.masterList ?: return emptyList() // not a master list, or nothing loaded
    // Your policy: use the list only when its signer chains to a root you already trust.
    val chains = list.outcomeOf(MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT)
    return if (chains == CheckOutcome.Passed) loaded.anchors else emptyList()
}
// endregion

// region TES-A-165 block 5
fun optionsWithRoots(key: ChipAccessKey, roots: List<TrustAnchor>): ChipReadOptions =
    ChipReadOptions.Builder(key)
        .trustAnchors(roots)
        .checkTime(Clock.System.now())
        .build()
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

// region TES-A-165 block 9
fun allRoots(first: File, second: File): List<TrustAnchor> =
    TrustAnchors.merge(TrustAnchors.load(first), TrustAnchors.load(second)).anchors
// endregion

// region TES-A-165 block 10
fun allRootsWithPlus(first: File, second: File): List<TrustAnchor> =
    (TrustAnchors.load(first) + TrustAnchors.load(second)).anchors
// endregion
