package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.cms.CmsSignedData
import io.lightine.tessera.emrtd.internal.cms.CmsSignerInfo
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.KeyLimits
import io.lightine.tessera.emrtd.internal.crypto.SignatureScheme
import io.lightine.tessera.emrtd.internal.crypto.VerificationKey
import io.lightine.tessera.emrtd.internal.x509.CertTime
import io.lightine.tessera.emrtd.internal.x509.X509Certificate
import io.lightine.tessera.emrtd.internal.x509.X509Exception
import io.lightine.tessera.emrtd.internal.x509.X509Parser

/** The signer certificate of a SignedData, or why there is none to use. */
internal class SignerLookup(
    val signerInfo: CmsSignerInfo?,
    val certificate: X509Certificate?,
    val notRun: PaNotRun?,
)

/** What the signer-to-root check found: the outcome and, when a root's key verified the signer, that root and its key. */
internal class RootMatch(
    val outcome: PaOutcome,
    val root: X509Certificate?,
    val rootKey: VerificationKey?,
)

/**
 * The signature checks shared by EF.SOD, EF.CardSecurity and master lists: finding the signer
 * certificate, verifying a CMS signature (RFC 5652, 5.4 to 5.6), verifying a certificate under a
 * root, and the single-certificate path of Doc 9303 Part 12, App D.1.1. Every function returns an
 * outcome; none says the object is genuine.
 */
internal object SignatureChecks {
    /**
     * The certificate the first SignerInfo names (by issuer and serial number, or by subject key
     * identifier) among the certificates the SignedData carries. A certificate is never guessed: with
     * no match the outcome is [PaNotRun.NO_SIGNER_CERTIFICATE].
     */
    fun findSigner(sd: CmsSignedData): SignerLookup {
        val info = sd.signerInfos.firstOrNull() ?: return SignerLookup(null, null, PaNotRun.NO_SIGNER_CERTIFICATE)
        var unreadable = false
        for (der in sd.certificates) {
            val cert =
                try {
                    X509Parser.parse(der)
                } catch (e: Asn1Exception) {
                    unreadable = true
                    continue
                } catch (e: X509Exception) {
                    unreadable = true
                    continue
                }
            val sid = info.sid
            val matches =
                if (sid.subjectKeyIdentifier != null) {
                    cert.decoded.subjectKeyIdentifier?.contentEquals(sid.subjectKeyIdentifier) == true
                } else {
                    sid.issuer?.contentEquals(cert.issuer.encoded) == true && sid.serialNumber?.contentEquals(cert.serialNumber) == true
                }
            if (matches) return SignerLookup(info, cert, null)
        }
        return SignerLookup(info, null, if (unreadable) PaNotRun.UNREADABLE_SIGNER_CERTIFICATE else PaNotRun.NO_SIGNER_CERTIFICATE)
    }

    /** Whether [key]'s type is the one [scheme] needs. */
    private fun fits(
        scheme: SignatureScheme,
        key: VerificationKey,
    ): Boolean =
        when (scheme) {
            is SignatureScheme.RsaPkcs1V15, is SignatureScheme.RsaPss -> key is VerificationKey.Rsa
            is SignatureScheme.Dsa -> key is VerificationKey.Dsa
            is SignatureScheme.Ecdsa -> key is VerificationKey.Ec
        }

    fun verifySignature(
        crypto: ChipCrypto,
        scheme: SignatureScheme,
        key: VerificationKey,
        message: ByteArray,
        signature: ByteArray,
    ): PaOutcome {
        if (!fits(scheme, key)) return PaOutcome.Failed(PaFailure.KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM)
        // A PSS salt longer than the key can hold (RFC 8017, 9.1.1 step 3) is a parameter set no
        // signer could have used; it never reaches the backend.
        if (scheme is SignatureScheme.RsaPss &&
            key is VerificationKey.Rsa &&
            scheme.saltLength > KeyLimits.maxPssSaltLength(key.modulus, scheme.hash)
        ) {
            return PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM)
        }
        return try {
            if (crypto.verify(scheme, key, message, signature)) PaOutcome.Passed else PaOutcome.Failed(PaFailure.SIGNATURE_DOES_NOT_VERIFY)
        } catch (e: ChipCryptoException) {
            PaOutcome.NotRun(PaNotRun.UNREADABLE_KEY)
        }
    }

    /**
     * The CMS signature of [info] over [sd]'s content under [key]. With signed attributes, the
     * content-type attribute must name the content type and the message-digest attribute must equal
     * the content's hash (RFC 5652, 5.4 and 11); the signature covers the attributes' DER. Without
     * them, it covers the content itself.
     */
    fun verifyCms(
        crypto: ChipCrypto,
        sd: CmsSignedData,
        info: CmsSignerInfo,
        key: VerificationKey,
    ): PaOutcome {
        val scheme =
            try {
                SignatureAlgorithms.scheme(info.signatureAlgorithm, info.digestAlgorithm)
            } catch (e: UnsupportedAlgorithmException) {
                return PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM)
            }
        val signedAttributes = info.signedAttributesDer ?: return verifySignature(crypto, scheme, key, sd.eContent, info.signature)
        val digestAlgorithm =
            try {
                SignatureAlgorithms.hash(info.digestAlgorithm.oid)
            } catch (e: UnsupportedAlgorithmException) {
                return PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM)
            }
        val stated =
            try {
                info.contentTypeAttribute
            } catch (e: Asn1Exception) {
                null
            }
        if (stated != sd.eContentType) return PaOutcome.Failed(PaFailure.CONTENT_TYPE_ATTRIBUTE_DIFFERS)
        val digest =
            try {
                info.messageDigest
            } catch (e: Asn1Exception) {
                null
            } ?: return PaOutcome.Failed(PaFailure.MESSAGE_DIGEST_ATTRIBUTE_MISSING)
        if (!crypto.digest(digestAlgorithm, sd.eContent).contentEquals(digest)) return PaOutcome.Failed(PaFailure.MESSAGE_DIGEST_DIFFERS)
        return verifySignature(crypto, scheme, key, signedAttributes, info.signature)
    }

    /** The signature on [certificate] under [issuerKey] (RFC 5280, 6.1.3 a 1). */
    fun verifyCertificate(
        crypto: ChipCrypto,
        certificate: X509Certificate,
        issuerKey: VerificationKey,
    ): PaOutcome {
        val scheme =
            try {
                SignatureAlgorithms.scheme(certificate.signatureAlgorithm)
            } catch (e: UnsupportedAlgorithmException) {
                return PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM)
            }
        if (certificate.signature.unusedBits != 0) return PaOutcome.Failed(PaFailure.SIGNATURE_DOES_NOT_VERIFY)
        return verifySignature(crypto, scheme, issuerKey, certificate.tbsEncoded, certificate.signature.bytes)
    }

    /**
     * The single-certificate path from [signer] to one of [roots] (Part 12, App D.1.1; paths have one
     * certificate, CHIP-22). The candidate roots are those whose SubjectKeyIdentifier equals the
     * signer's AuthorityKeyIdentifier (App D.1.1.1); if none does, or the signer has no AKI, those
     * whose subject name equals the signer's issuer name. Passed when a candidate's key verifies the
     * signer's signature and its subject is the signer's issuer name (compared as DER bytes).
     * Validity, critical extensions and revocation are separate checks.
     */
    fun chainToRoot(
        crypto: ChipCrypto,
        signer: X509Certificate,
        roots: List<X509Certificate>,
    ): RootMatch {
        if (roots.isEmpty()) return RootMatch(PaOutcome.NotRun(PaNotRun.NO_ROOTS_SUPPLIED), null, null)
        val aki = signer.decoded.authorityKeyIdentifier?.keyIdentifier
        val byKey = if (aki == null) emptyList() else roots.filter { it.decoded.subjectKeyIdentifier?.contentEquals(aki) == true }
        val candidates = byKey.ifEmpty { roots.filter { it.subject.encoded.contentEquals(signer.issuer.encoded) } }
        if (candidates.isEmpty()) return RootMatch(PaOutcome.Failed(PaFailure.NO_SUPPLIED_ROOT_MATCHES), null, null)
        var notRun: PaOutcome? = null
        var nameDiffers: RootMatch? = null
        var anyRan = false
        for (root in candidates) {
            val key =
                try {
                    PublicKeys.of(root, crypto)
                } catch (e: UnsupportedAlgorithmException) {
                    notRun = PaOutcome.NotRun(PaNotRun.UNSUPPORTED_ALGORITHM)
                    continue
                } catch (e: UnreadableKeyException) {
                    notRun = PaOutcome.NotRun(PaNotRun.UNREADABLE_KEY)
                    continue
                }
            val outcome = verifyCertificate(crypto, signer, key)
            if (outcome is PaOutcome.NotRun) {
                notRun = outcome
                continue
            }
            anyRan = true
            if (outcome == PaOutcome.Passed) {
                if (root.subject.encoded.contentEquals(signer.issuer.encoded)) return RootMatch(PaOutcome.Passed, root, key)
                nameDiffers = RootMatch(PaOutcome.Failed(PaFailure.ISSUER_NAME_DIFFERS), root, key)
            }
        }
        return nameDiffers
            ?: if (anyRan || notRun == null) {
                RootMatch(PaOutcome.Failed(PaFailure.SIGNATURE_DOES_NOT_VERIFY), null, null)
            } else {
                RootMatch(notRun, null, null)
            }
    }

    /** Whether [time] is inside [certificate]'s validity period, both ends included (RFC 5280, 4.1.2.5). */
    fun validAt(
        certificate: X509Certificate,
        time: CertTime?,
    ): PaOutcome =
        when {
            time == null -> PaOutcome.NotRun(PaNotRun.NO_TIME_GIVEN)
            time >= certificate.notBefore && time <= certificate.notAfter -> PaOutcome.Passed
            else -> PaOutcome.Failed(PaFailure.OUTSIDE_VALIDITY)
        }

    /** Whether [certificate] has no critical extension the SDK does not know (App D.1.1.3: such a certificate cannot be processed). */
    fun criticalExtensionsRecognized(certificate: X509Certificate): PaOutcome =
        if (certificate.unrecognizedCriticalExtensions.isEmpty()) {
            PaOutcome.Passed
        } else {
            PaOutcome.Failed(PaFailure.UNRECOGNIZED_CRITICAL_EXTENSION)
        }
}
