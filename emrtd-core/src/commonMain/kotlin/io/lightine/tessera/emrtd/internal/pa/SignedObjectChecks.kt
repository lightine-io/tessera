package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.cms.CmsException
import io.lightine.tessera.emrtd.internal.cms.CmsParser
import io.lightine.tessera.emrtd.internal.cms.CmsSignedData
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.x509.CertTime
import io.lightine.tessera.emrtd.internal.x509.X509Certificate

/** The checks on one signed object, with what later checks build on: the parsed SignedData, its signer and the matched root. */
internal class SignedObjectResult(
    val checks: List<PaCheck>,
    val signedData: CmsSignedData?,
    val signer: X509Certificate?,
    val root: X509Certificate?,
)

/**
 * The four signer facts about one CMS signed object (EF.SOD, EF.CardSecurity, a master list): its
 * signature, its signer's path to a supplied root, the signer's validity at the caller's time, and
 * the signer's critical extensions. Each is its own fact; none implies another.
 */
internal object SignedObjectChecks {
    fun run(
        signed: SignedObject,
        file: ByteArray,
        contentType: String,
        roots: List<X509Certificate>,
        at: CertTime?,
        crypto: ChipCrypto?,
    ): SignedObjectResult {
        val sd =
            try {
                CmsParser.parse(file, contentType)
            } catch (e: Asn1Exception) {
                null
            } catch (e: CmsException) {
                null
            } ?: return notRun(signed, at, PaNotRun.UNREADABLE_FILE)
        val lookup = SignatureChecks.findSigner(sd)
        val signer = lookup.certificate
        val info = lookup.signerInfo
        if (signer == null || info == null) {
            return SignedObjectResult(allNotRun(signed, at, lookup.notRun ?: PaNotRun.NO_SIGNER_CERTIFICATE), sd, null, null)
        }
        val validity = PaCheck(PaCheckKind.SignerValidAt(signed, at), SignatureChecks.validAt(signer, at))
        val critical = PaCheck(PaCheckKind.SignerCriticalExtensionsRecognized(signed), SignatureChecks.criticalExtensionsRecognized(signer))
        if (crypto == null) {
            val unavailable = PaOutcome.NotRun(PaNotRun.CRYPTO_UNAVAILABLE)
            return SignedObjectResult(
                listOf(
                    PaCheck(PaCheckKind.SignatureVerifies(signed), unavailable),
                    PaCheck(PaCheckKind.SignerChainsToSuppliedRoot(signed), unavailable),
                    validity,
                    critical,
                ),
                sd,
                signer,
                null,
            )
        }
        val match = SignatureChecks.chainToRoot(crypto, signer, roots)
        val signature =
            try {
                SignatureChecks.verifyCms(crypto, sd, info, PublicKeys.of(signer, crypto, match.rootKey))
            } catch (e: UnsupportedAlgorithmException) {
                PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM)
            } catch (e: UnreadableKeyException) {
                PaOutcome.NotRun(PaNotRun.UNREADABLE_KEY)
            }
        return SignedObjectResult(
            listOf(
                PaCheck(PaCheckKind.SignatureVerifies(signed), signature),
                PaCheck(PaCheckKind.SignerChainsToSuppliedRoot(signed), match.outcome),
                validity,
                critical,
            ),
            sd,
            signer,
            match.root,
        )
    }

    private fun allNotRun(
        signed: SignedObject,
        at: CertTime?,
        reason: PaNotRun,
    ): List<PaCheck> {
        val o = PaOutcome.NotRun(reason)
        return listOf(
            PaCheck(PaCheckKind.SignatureVerifies(signed), o),
            PaCheck(PaCheckKind.SignerChainsToSuppliedRoot(signed), o),
            PaCheck(PaCheckKind.SignerValidAt(signed, at), o),
            PaCheck(PaCheckKind.SignerCriticalExtensionsRecognized(signed), o),
        )
    }

    fun notRun(
        signed: SignedObject,
        at: CertTime?,
        reason: PaNotRun,
    ): SignedObjectResult = SignedObjectResult(allNotRun(signed, at, reason), null, null, null)
}
