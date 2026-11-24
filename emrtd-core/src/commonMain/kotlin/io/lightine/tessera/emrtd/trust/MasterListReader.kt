package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.asn1.DerTag
import io.lightine.tessera.emrtd.internal.cms.CmsException
import io.lightine.tessera.emrtd.internal.cms.CmsFailure
import io.lightine.tessera.emrtd.internal.cms.CmsParser
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoUnavailableException
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.pa.PaCheckKind
import io.lightine.tessera.emrtd.internal.pa.PaOutcome
import io.lightine.tessera.emrtd.internal.pa.SignedObject
import io.lightine.tessera.emrtd.internal.pa.SignedObjectChecks
import io.lightine.tessera.emrtd.internal.x509.X509Exception
import io.lightine.tessera.emrtd.internal.x509.X509Parser
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.CheckReason

/**
 * Reads a CSCA master list (Doc 9303 Part 12, 9) that the app obtained itself, for example a
 * national master list a state publishes.
 *
 * The SDK never downloads or bundles a master list, and it does not decide whether a list or any
 * root in it is to be trusted: that is the app's choice. It reads the certificates the list holds
 * and reports, as facts, whether the list's own signature verifies and whether the list's signer
 * is signed by a root the app supplies.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public object MasterListReader {
    /** id-icao-cscaMasterList (Part 12, 9.2). */
    private const val OID_CSCA_MASTER_LIST = "2.23.136.1.1.2"

    /**
     * Reads [file], a master list as CMS SignedData in DER (or BER), and checks its signature.
     *
     * @param roots roots to check the list's signer against; with none, that check is
     * [CheckOutcome.NotRun] with [MasterListCheckReason.NO_ROOTS_SUPPLIED].
     * @return [MasterListRead.Read] with the list, or [MasterListRead.Unreadable]. Never throws for
     * bad data.
     */
    public fun read(
        file: ByteArray,
        roots: List<TrustAnchor> = emptyList(),
    ): MasterListRead = read(file, roots, ::chipCrypto)

    internal fun read(
        file: ByteArray,
        roots: List<TrustAnchor>,
        cryptoProvider: () -> ChipCrypto,
    ): MasterListRead {
        val bytes = file.copyOf()
        val sd =
            try {
                CmsParser.parse(bytes, OID_CSCA_MASTER_LIST)
            } catch (e: Asn1Exception) {
                return MasterListRead.Unreadable(MasterListProblem.MALFORMED_ENCODING, e.offset)
            } catch (e: CmsException) {
                val problem =
                    if (e.failure ==
                        CmsFailure.NOT_SIGNED_DATA
                    ) {
                        MasterListProblem.NOT_SIGNED_DATA
                    } else {
                        MasterListProblem.NOT_A_MASTER_LIST
                    }
                return MasterListRead.Unreadable(problem, 0)
            }
        val anchors = ArrayList<TrustAnchor>()
        var unreadable = 0
        val version: Int
        try {
            val top = DerReader(sd.eContent)
            val list = top.readSequence()
            top.finish()
            version = list.readInt()
            val certs = list.readSet()
            list.finish()
            while (certs.hasMore) {
                val e = certs.read()
                if (e.tag != DerTag.universal(DerTag.SEQUENCE, constructed = true)) {
                    unreadable++
                    continue
                }
                try {
                    anchors.add(TrustAnchor(X509Parser.parse(e.encoded())))
                } catch (x: Asn1Exception) {
                    unreadable++
                } catch (x: X509Exception) {
                    unreadable++
                }
            }
        } catch (e: Asn1Exception) {
            return MasterListRead.Unreadable(MasterListProblem.MALFORMED_CONTENT, e.offset)
        }

        val crypto =
            try {
                cryptoProvider()
            } catch (e: ChipCryptoUnavailableException) {
                null
            }
        val result =
            SignedObjectChecks.run(SignedObject.MASTER_LIST, bytes, OID_CSCA_MASTER_LIST, roots.map { it.certificate }, null, crypto)
        val checks =
            result.checks.mapNotNull { c ->
                val kind =
                    when (c.kind) {
                        is PaCheckKind.SignatureVerifies -> MasterListCheckKind.SIGNATURE_VERIFIES
                        is PaCheckKind.SignerChainsToSuppliedRoot -> MasterListCheckKind.SIGNER_CHAINS_TO_SUPPLIED_ROOT
                        is PaCheckKind.SignerCriticalExtensionsRecognized -> MasterListCheckKind.SIGNER_CRITICAL_EXTENSIONS_RECOGNIZED
                        else -> null
                    }
                kind?.let { MasterListCheck(it, outcomeOf(c.outcome)) }
            }
        return MasterListRead.Read(MasterList(version, anchors, unreadable, checks))
    }

    // Every reason a signed-object check can give has a public name of the same spelling (TrustTest
    // checks the list), so the lookup cannot miss on any input bytes.
    private fun reason(name: String): MasterListCheckReason =
        MasterListCheckReason.entries.firstOrNull { it.name == name } ?: throw IllegalStateException("no public reason for $name")

    private fun outcomeOf(o: PaOutcome): CheckOutcome =
        when (o) {
            PaOutcome.Passed -> CheckOutcome.Passed
            is PaOutcome.Failed -> CheckOutcome.Failed(reason(o.reason.name))
            is PaOutcome.NotApplicable -> CheckOutcome.NotApplicable(reason(o.reason.name))
            is PaOutcome.NotRun -> CheckOutcome.NotRun(reason(o.reason.name))
        }
}

/**
 * A master list as read: its certificates as [TrustAnchor]s and the facts about its signature.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class MasterList internal constructor(
    /** The CscaMasterList version as stored (0 in Part 12, 9.2). */
    public val version: Int,
    /** Every certificate in the list that could be read, in order. None has been checked. */
    public val anchors: List<TrustAnchor>,
    /** How many listed certificates could not be read. */
    public val unreadableCertificateCount: Int,
    /** The facts about the list's own signature, in a fixed order: one per [MasterListCheckKind]. */
    public val checks: List<MasterListCheck>,
) {
    /** The outcome of [kind]. */
    public fun outcomeOf(kind: MasterListCheckKind): CheckOutcome = checks.first { it.kind == kind }.outcome

    override fun toString(): String = "MasterList(version=$version, anchors=${anchors.size}, unreadable=$unreadableCertificateCount)"
}

/**
 * One fact about a master list's signature. The chip-read result reports its facts in the same
 * shape (a kind and an outcome).
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class MasterListCheck internal constructor(
    public val kind: MasterListCheckKind,
    public val outcome: CheckOutcome,
) {
    override fun toString(): String = "MasterListCheck($kind, $outcome)"
}

/**
 * What a master-list fact is about.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class MasterListCheckKind {
    /** The list's signature verifies under the signer certificate the list carries, and its signed attributes agree with its content. */
    SIGNATURE_VERIFIES,

    /** The list's signer certificate is signed by one of the roots the app supplied, the one it names. */
    SIGNER_CHAINS_TO_SUPPLIED_ROOT,

    /** The list's signer certificate has no critical extension the SDK does not know. */
    SIGNER_CRITICAL_EXTENSIONS_RECOGNIZED,
}

/**
 * Why a master-list fact failed or did not run. Each value is a fact about the inputs, never a
 * verdict on the list.
 *
 * Implements [CheckReason], the shared reason type of [CheckOutcome], so a master-list check's
 * outcome carries a typed reason like every other module's checks.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class MasterListCheckReason : CheckReason {
    /** The signature does not verify under the key. */
    SIGNATURE_DOES_NOT_VERIFY,

    /** The message-digest attribute is not the hash of the list's content. */
    MESSAGE_DIGEST_DIFFERS,

    /** The signed attributes have no message digest. */
    MESSAGE_DIGEST_ATTRIBUTE_MISSING,

    /** The content-type attribute does not name the master list type. */
    CONTENT_TYPE_ATTRIBUTE_DIFFERS,

    /** The key's type is not the one the signature algorithm needs. */
    KEY_DOES_NOT_FIT_SIGNATURE_ALGORITHM,

    /** No supplied root has the key or the name the signer certificate names as its issuer. */
    NO_SUPPLIED_ROOT_MATCHES,

    /** The root whose key verifies the signer certificate has a subject name other than the certificate's issuer name. */
    ISSUER_NAME_DIFFERS,

    /** The signer certificate has a critical extension the SDK does not know. */
    UNRECOGNIZED_CRITICAL_EXTENSION,

    /** The list does not carry the certificate its signer information names. */
    NO_SIGNER_CERTIFICATE,

    /** The certificate the signer information names could not be read. */
    UNREADABLE_SIGNER_CERTIFICATE,

    /** The app supplied no roots. */
    NO_ROOTS_SUPPLIED,

    /** An algorithm (signature, hash or key) the SDK does not read. */
    UNSUPPORTED_ALGORITHM,

    /** A public key that could not be read. */
    UNREADABLE_KEY,

    /** This platform has no cryptography backend yet. */
    CRYPTO_UNAVAILABLE,
}

/**
 * The outcome of reading a master list.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class MasterListRead {
    /** The list was read; its signature facts are in [MasterList.checks]. */
    public class Read internal constructor(
        public val masterList: MasterList,
    ) : MasterListRead()

    /** The bytes are not a master list the SDK can read. */
    public class Unreadable internal constructor(
        public val problem: MasterListProblem,
        /** The byte offset where the problem was found (0 when it is about the whole file). */
        public val offset: Int,
    ) : MasterListRead()
}

/**
 * Why a master list could not be read.
 *
 * May gain members in a later release: a `when` over it needs an `else` branch.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class MasterListProblem {
    /** The bytes are not well-formed DER or BER. */
    MALFORMED_ENCODING,

    /** The bytes are not a CMS SignedData. */
    NOT_SIGNED_DATA,

    /** The signed content is not a CSCA master list (another content type, or none). */
    NOT_A_MASTER_LIST,

    /** The signed content is not shaped as a CscaMasterList. */
    MALFORMED_CONTENT,
}
