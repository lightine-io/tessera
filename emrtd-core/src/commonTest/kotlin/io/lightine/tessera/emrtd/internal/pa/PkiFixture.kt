package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.fakechip.FakePki
import io.lightine.tessera.emrtd.fakechip.TestKeyPair
import io.lightine.tessera.emrtd.fakechip.TestKeySpec
import io.lightine.tessera.emrtd.fakechip.TestSigner
import io.lightine.tessera.emrtd.fakechip.testSigner
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.cms.CmsParser
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.x509.CertTime
import io.lightine.tessera.emrtd.internal.x509.X509Certificate
import io.lightine.tessera.emrtd.internal.x509.X509Parser

/** Keys made once per test run (RSA generation is the slow part), for the platforms that have a [TestSigner]. */
internal class TestKeys(
    val signer: TestSigner,
) {
    val rsaCsca by lazy { signer.generate(TestKeySpec.Rsa(2048)) }
    val rsaDs by lazy { signer.generate(TestKeySpec.Rsa(2048)) }
    val rsaOther by lazy { signer.generate(TestKeySpec.Rsa(2048)) }
    val brainpool256 by lazy { signer.generate(TestKeySpec.Ec(StandardDomainParameterValues.forId(13) as EcDomainParameters)) }
    val brainpool256b by lazy { signer.generate(TestKeySpec.Ec(StandardDomainParameterValues.forId(13) as EcDomainParameters)) }
    val p256 by lazy { signer.generate(TestKeySpec.Ec(StandardDomainParameterValues.forId(12) as EcDomainParameters)) }
    val p384 by lazy { signer.generate(TestKeySpec.Ec(StandardDomainParameterValues.forId(15) as EcDomainParameters)) }

    /** DSA on the RFC 5114 2048-bit group with its 256-bit subgroup (a valid DSA domain; no slow parameter generation). */
    val dsaCsca by lazy { signer.generate(TestKeySpec.Dsa(StandardDomainParameterValues.forId(2) as DhDomainParameters)) }
    val dsaDs by lazy { signer.generate(TestKeySpec.Dsa(StandardDomainParameterValues.forId(2) as DhDomainParameters)) }

    companion object {
        private var cached: TestKeys? = null

        /** The keys, or `null` on a platform with no test signer (iOS until its crypto backend lands). */
        fun get(): TestKeys? {
            val s = testSigner() ?: return null
            return cached ?: TestKeys(s).also { cached = it }
        }
    }
}

/** Runs [block] with the test keys; returns early where there is no signer (iOS). */
internal inline fun withKeys(block: (TestKeys) -> Unit) {
    val keys = TestKeys.get() ?: return
    block(keys)
}

/** A synthetic CSCA and Document Signer pair, really signed, for the fictional state Utopia. */
internal class Pki(
    val csca: ByteArray,
    val ds: ByteArray,
    val dsKey: TestKeyPair,
    val dsAlgorithm: FakePki.SignatureAlgorithm,
    val signer: TestSigner,
    val dsIssuerName: ByteArray = CSCA_NAME,
) {
    val cscaCert: X509Certificate get() = X509Parser.parse(csca)
    val dsSid: ByteArray get() = FakePki.sidIssuerAndSerial(dsIssuerName, DS_SERIAL)

    /** An EF.SOD over [dataGroups], signed by the DS key, carrying the DS certificate. */
    fun sod(
        dataGroups: Map<Int, ByteArray>,
        hash: HashAlgorithm = HashAlgorithm.SHA256,
        hashOverrides: Map<Int, ByteArray> = emptyMap(),
        certificates: List<ByteArray> = listOf(ds),
        signing: FakePki.Signing = FakePki.Signing(signer, dsKey, dsAlgorithm),
        signedAttributes: Boolean = true,
        contentTypeAttribute: String = CmsParser.OID_LDS_SECURITY_OBJECT,
        messageDigestOverride: ByteArray? = null,
    ): ByteArray {
        val crypto =
            io.lightine.tessera.emrtd.internal.crypto
                .chipCrypto()
        val groups = dataGroups.entries.sortedBy { it.key }.map { (n, bytes) -> n to (hashOverrides[n] ?: crypto.digest(hash, bytes)) }
        val lds = FakePki.ldsSecurityObject(version = 0, hashOid = FakePki.hashOid(hash), groups = groups)
        return FakePki.signedData(
            signing,
            hash,
            CmsParser.OID_LDS_SECURITY_OBJECT,
            lds,
            certificates,
            dsSid,
            signedAttributes = signedAttributes,
            contentTypeAttribute = contentTypeAttribute,
            messageDigestOverride = messageDigestOverride,
            fileTag = true,
        )
    }

    /** EF.CardSecurity: [securityInfos] (a DER SET) signed by the DS key. */
    fun cardSecurity(securityInfos: ByteArray): ByteArray =
        FakePki.signedData(
            FakePki.Signing(signer, dsKey, dsAlgorithm),
            HashAlgorithm.SHA256,
            FakePki.OID_ID_SECURITY_OBJECT,
            securityInfos,
            listOf(ds),
            dsSid,
        )

    companion object {
        val CSCA_NAME = FakePki.name("UT", "Utopia CSCA")
        val DS_NAME = FakePki.name("UT", "Utopia Document Signer 01")
        val CSCA_SKI = ByteArray(20) { 0x11 }
        val DS_SKI = ByteArray(20) { 0x22 }
        const val DS_SERIAL = 0x2001L

        /** Default validity: 2024-01-01 to 2034-01-01. */
        val IN_VALIDITY = CertTime(2026, 6, 1, 12, 0, 0)

        fun dsExtensions(
            aki: ByteArray = CSCA_SKI,
            documentTypes: List<String>? = listOf("P"),
            extra: List<FakePki.Ext> = emptyList(),
            keyUsage: FakePki.Ext = FakePki.keyUsage(0),
        ): List<FakePki.Ext> =
            listOfNotNull(
                FakePki.authorityKeyIdentifier(aki),
                FakePki.subjectKeyIdentifier(DS_SKI),
                keyUsage,
                FakePki.privateKeyUsagePeriod("20240101000000Z", "20250101000000Z"),
                documentTypes?.let { FakePki.documentTypeList(*it.toTypedArray()) },
            ) + extra

        /** A self-signed CSCA with [key], named [name], with SubjectKeyIdentifier [ski]. */
        fun csca(
            signer: TestSigner,
            key: TestKeyPair,
            algorithm: FakePki.SignatureAlgorithm,
            name: ByteArray = CSCA_NAME,
            ski: ByteArray = CSCA_SKI,
            ec: FakePki.EcParameters = FakePki.EcParameters.EXPLICIT,
            namedCurveOid: String? = null,
        ): ByteArray =
            FakePki.certificate(
                subject = name,
                issuer = name,
                serial = 0x1001,
                extensions =
                    listOf(
                        FakePki.subjectKeyIdentifier(ski),
                        FakePki.keyUsage(5, 6),
                        FakePki.basicConstraints(true, 0),
                    ),
                publicKeyInfo = FakePki.publicKeyInfo(key.publicKey, ec, namedCurveOid),
                signing = FakePki.Signing(signer, key, algorithm),
            )

        /**
         * A CSCA and a DS. [inheritDsParameters] writes the DS key without parameters (they come
         * from the CSCA key, Part 12 App D.1.1.3 c).
         */
        fun build(
            keys: TestKeys,
            cscaKey: TestKeyPair,
            cscaAlgorithm: FakePki.SignatureAlgorithm,
            dsKey: TestKeyPair,
            dsAlgorithm: FakePki.SignatureAlgorithm,
            ec: FakePki.EcParameters = FakePki.EcParameters.EXPLICIT,
            namedCurveOid: String? = null,
            inheritDsParameters: Boolean = false,
            dsExtensions: List<FakePki.Ext> = dsExtensions(),
            dsSubject: ByteArray = DS_NAME,
            dsIssuer: ByteArray = CSCA_NAME,
            notBefore: ByteArray = DerWriter.utcTime("240101000000Z"),
            notAfter: ByteArray = DerWriter.utcTime("340101000000Z"),
        ): Pki {
            val csca = csca(keys.signer, cscaKey, cscaAlgorithm, ec = ec, namedCurveOid = namedCurveOid)
            val dsEc = if (inheritDsParameters) FakePki.EcParameters.ABSENT else ec
            val ds =
                FakePki.certificate(
                    subject = dsSubject,
                    issuer = dsIssuer,
                    serial = DS_SERIAL,
                    notBefore = notBefore,
                    notAfter = notAfter,
                    extensions = dsExtensions,
                    publicKeyInfo = FakePki.publicKeyInfo(dsKey.publicKey, dsEc, namedCurveOid, inheritParameters = inheritDsParameters),
                    signing = FakePki.Signing(keys.signer, cscaKey, cscaAlgorithm),
                )
            return Pki(csca, ds, dsKey, dsAlgorithm, keys.signer, dsIssuer)
        }

        /** RSA CSCA and DS, PKCS #1 v1.5 with SHA-256. */
        fun rsa(
            keys: TestKeys,
            dsExtensions: List<FakePki.Ext> = dsExtensions(),
            dsSubject: ByteArray = DS_NAME,
            dsIssuer: ByteArray = CSCA_NAME,
            notBefore: ByteArray = DerWriter.utcTime("240101000000Z"),
        ): Pki =
            build(
                keys,
                keys.rsaCsca,
                FakePki.rsaPkcs1(HashAlgorithm.SHA256),
                keys.rsaDs,
                FakePki.rsaPkcs1(HashAlgorithm.SHA256),
                dsExtensions = dsExtensions,
                dsSubject = dsSubject,
                dsIssuer = dsIssuer,
                notBefore = notBefore,
            )
    }
}

/** Synthetic data groups: tagged filler, not any document's data. */
internal object FakeGroups {
    fun group(
        tag: Int,
        seed: Int,
        length: Int = 40,
    ): ByteArray = DerWriter.tlv(tag, ByteArray(length) { (seed + it).toByte() })

    val dg1 = group(0x61, 1)
    val dg2 = group(0x75, 2, 300)

    /** A PACEInfo (id-PACE-ECDH-GM-AES-CBC-CMAC-128, version 2, BrainpoolP256r1). */
    val paceInfo = DerWriter.sequence(DerWriter.oid("0.4.0.127.0.7.2.2.4.2.2"), DerWriter.integer(2), DerWriter.integer(13))

    /** An ActiveAuthenticationInfo (version 1, ecdsa-plain-SHA256). */
    val aaInfo = DerWriter.sequence(DerWriter.oid("2.23.136.1.1.5"), DerWriter.integer(1), DerWriter.oid("0.4.0.127.0.7.1.1.4.1.3"))

    /** A second PACEInfo (integrated mapping), for a CardAccess that lists more than the signed copy. */
    val otherPaceInfo = DerWriter.sequence(DerWriter.oid("0.4.0.127.0.7.2.2.4.4.2"), DerWriter.integer(2), DerWriter.integer(13))

    fun securityInfos(vararg entries: ByteArray): ByteArray = DerWriter.set(*entries)

    fun dg14(vararg entries: ByteArray): ByteArray = DerWriter.tlv(0x6E, securityInfos(*entries))
}
