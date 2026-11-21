package io.lightine.tessera.emrtd.internal.securityinfo

import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(s: String): ByteArray =
    s
        .filter { !it.isWhitespace() }
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

private fun der(
    tag: Int,
    vararg parts: ByteArray,
): ByteArray {
    var v = ByteArray(0)
    parts.forEach { v += it }
    return byteArrayOf(tag.toByte()) + TlvWriter.length(v.size) + v
}

private fun set(vararg entries: ByteArray): ByteArray = der(0x31, *entries)

private fun seq(vararg parts: ByteArray): ByteArray = der(0x30, *parts)

private fun oid(dotted: String): ByteArray {
    val arcs = dotted.split('.').map { it.toLong() }
    val body = ArrayList<Byte>()
    body.add((arcs[0] * 40 + arcs[1]).toByte())
    for (arc in arcs.drop(2)) {
        val groups = ArrayList<Int>()
        var v = arc
        do {
            groups.add((v and 0x7F).toInt())
            v = v shr 7
        } while (v > 0)
        groups.reversed().forEachIndexed { i, g -> body.add((if (i < groups.size - 1) g or 0x80 else g).toByte()) }
    }
    return der(0x06, body.toByteArray())
}

private fun int(value: Int): ByteArray = der(0x02, if (value < 0x80) byteArrayOf(value.toByte()) else byteArrayOf(0, value.toByte()))

private fun intBytes(unsigned: ByteArray): ByteArray = der(0x02, if (unsigned[0] < 0) byteArrayOf(0) + unsigned else unsigned)

/** The SecurityInfos bytes printed in Doc 9303 Part 11 Appendix G.1 (PACEInfo, ECDH, generic mapping, AES-128, Brainpool P256r1). */
private const val G1_PACE_INFO = "3012060A 04007F00 07020204 02020201 0202010D"

/** App G.2 (PACEInfo, DH, generic mapping, AES-128, the 1024-bit group). */
private const val G2_PACE_INFO = "3012060A 04007F00 07020204 01020201 02020100"

/** App I.1 (PACEInfo, ECDH, chip authentication mapping, AES-128, Brainpool P256r1). */
private const val I1_PACE_INFO = "3012060A 04007F00 07020204 06020201 0202010D"

/** App I.1: the ChipAuthenticationPublicKeyInfo read from EF.CardSecurity. */
private const val I1_CAPK_INFO =
    "30620609 04007F00 07020201 02305230 0C060704 007F0007 01020201 0D034200" +
        "04187270 9494399E 7470A643 1BE25E83 EEE24FEA 568C2ED2 8DB48E05 DB3A610D" +
        "C884D256 A40E35EF CB59BF67 53D3A489 D28C7A4D 973C2DA1 38A6E7A4 A08F68E1" +
        "6F02010D"

/** App G.1: the explicit ASN.1 encoding of the Brainpool P256r1 domain parameters, as an AlgorithmIdentifier. */
private const val G1_EXPLICIT_EC =
    "3081EC 06072A8648CE3D0201 3081E0 020101 302C 06072A8648CE3D0101" +
        "022100A9FB57DBA1EEA9BC3E660A909D838D726E3BF623D52620282013481D1F6E5377" +
        "3044 0420 7D5A0975FC2C3057EEF67530417AFFE7FB8055C126DC5C6CE94A4B44F330B5D9" +
        "0420 26DC5C6CE94A4B44F330B5D9BBD77CBF958416295CF7E1CE6BCCDC18FF8C07B6" +
        "0441 048BD2AEB9CB7E57CB2C4B482FFC81B7AFB9DE27E1E3BD23C23A4453BD9ACE3262" +
        "547EF835C3DAC4FD97F8461A14611DC9C27745132DED8E545C1D54C72F046997" +
        "022100A9FB57DBA1EEA9BC3E660A909D838D718C397AA3B561A6F7901E0E82974856A7" +
        "020101"

private val PACE_ECDH_GM_AES128 = oid("0.4.0.127.0.7.2.2.4.2.2")

class SecurityInfoTest {
    private fun parse(vararg entries: ByteArray): SecurityInfos = SecurityInfoParser.parse(set(*entries))

    // ---- Doc 9303 Part 11 worked examples -------------------------------------------------

    @Test
    fun g1_pace_info_reads() {
        val infos = parse(hex(G1_PACE_INFO))
        val pace = infos.paceInfos.single()
        assertEquals("0.4.0.127.0.7.2.2.4.2.2", pace.protocol)
        assertEquals(PaceMapping.GENERIC, pace.mapping)
        assertEquals(KeyAgreement.ECDH, pace.agreement)
        assertEquals(SessionCipher.AES_CBC_CMAC_128, pace.cipher)
        assertEquals(2, pace.version)
        assertEquals(13, pace.parameterId)
        assertEquals(ParameterIdKind.STANDARDIZED, pace.parameterKind)
        assertEquals("BrainpoolP256r1", pace.standardizedParameter?.name)
        assertTrue(pace.usable)
        assertTrue(infos.rejected.isEmpty())
    }

    @Test
    fun g2_pace_info_reads() {
        val pace = parse(hex(G2_PACE_INFO)).paceInfos.single()
        assertEquals("0.4.0.127.0.7.2.2.4.1.2", pace.protocol)
        assertEquals(KeyAgreement.DH, pace.agreement)
        assertEquals(PaceMapping.GENERIC, pace.mapping)
        assertEquals(0, pace.parameterId)
        assertEquals(1024, pace.standardizedParameter?.sizeBits)
        assertTrue(pace.usable)
    }

    @Test
    fun i1_pace_info_and_chip_authentication_public_key_read_and_link_by_key_id() {
        val infos = parse(hex(I1_PACE_INFO), hex(I1_CAPK_INFO))
        val pace = infos.paceInfos.single()
        assertEquals(PaceMapping.CHIP_AUTHENTICATION, pace.mapping)
        assertEquals(13, pace.caKeyId)
        val capk = infos.chipAuthenticationPublicKeys.single()
        assertEquals("0.4.0.127.0.7.2.2.1.2", capk.protocol)
        assertEquals(KeyAgreement.ECDH, capk.agreement)
        assertEquals(13, capk.keyId)
        assertEquals(DomainParameters.Standardized(13), capk.key.parameters)
        assertEquals(65, capk.key.publicValue.size)
        assertEquals(0x04, capk.key.publicValue[0].toInt())
        assertContentEquals(
            hex("18727094 94399E74 70A6431B E25E83EE E24FEA56 8C2ED28D B48E05DB 3A610DC8"),
            capk.key.publicValue.copyOfRange(1, 33),
        )
        assertContentEquals(
            hex("84D256A4 0E35EFCB 59BF6753 D3A489D2 8C7A4D97 3C2DA138 A6E7A4A0 8F68E16F"),
            capk.key.publicValue.copyOfRange(33, 65),
        )
        assertTrue(infos.caKeyFor(pace) === capk)
    }

    @Test
    fun g1_explicit_ec_domain_parameters_read() {
        val reader = DerReader(hex(G1_EXPLICIT_EC))
        val parameters = assertIs<DomainParameters.ExplicitEc>(DomainParameterReader.readAlgorithmIdentifier(reader))
        assertEquals(32, parameters.p.size)
        assertEquals(0xA9, parameters.p[0].toInt() and 0xFF)
        assertEquals(65, parameters.generator.size)
        assertContentEquals(byteArrayOf(1), parameters.cofactor)
        assertEquals(32, parameters.order.size)
    }

    // ---- unknown and unusable entries -----------------------------------------------------

    @Test
    fun unknown_entries_are_ignored_and_the_known_ones_still_read() {
        val unknown = seq(oid("1.2.3.4.5"), int(7), der(0x04, byteArrayOf(1, 2, 3)))
        val unknownUnderPace = seq(oid("0.4.0.127.0.7.2.2.4.9.9"), int(2))
        val infos = parse(unknown, hex(G1_PACE_INFO), unknownUnderPace)
        assertEquals(2, infos.unknown.size)
        assertEquals("1.2.3.4.5", infos.unknown[0].protocol)
        assertEquals(1, infos.paceInfos.size)
        assertTrue(infos.rejected.isEmpty())
    }

    @Test
    fun explicit_domain_parameters_are_flagged_and_never_usable() {
        val paceExplicit = seq(PACE_ECDH_GM_AES128, int(2), int(32))
        val domain = seq(oid("0.4.0.127.0.7.2.2.4.2"), hex(G1_EXPLICIT_EC), int(32))
        val infos = parse(hex(G1_PACE_INFO), paceExplicit, domain)
        val explicit = infos.explicitDomainParameters.single()
        assertIs<DomainParameters.ExplicitEc>(explicit.parameters)
        assertEquals(32, explicit.parameterId)
        assertEquals(2, infos.paceInfos.size)
        assertEquals(1, infos.usablePaceInfos.size)
        val flagged = infos.unusablePaceInfos.single()
        assertEquals(ParameterIdKind.EXPLICIT, flagged.parameterKind)
        assertFalse(flagged.usable)
        assertNull(flagged.standardizedParameter)
    }

    @Test
    fun reserved_mismatched_and_absent_parameter_ids_are_not_usable() {
        val reserved = seq(PACE_ECDH_GM_AES128, int(2), int(5))
        val mismatch = seq(PACE_ECDH_GM_AES128, int(2), int(1)) // a DH set with ECDH
        val absent = seq(PACE_ECDH_GM_AES128, int(2))
        val kinds = parse(reserved, mismatch, absent).paceInfos.map { it.parameterKind }
        assertEquals(listOf(ParameterIdKind.RESERVED, ParameterIdKind.KEY_AGREEMENT_MISMATCH, ParameterIdKind.ABSENT), kinds)
        assertTrue(parse(reserved, mismatch, absent).usablePaceInfos.isEmpty())
    }

    @Test
    fun a_known_entry_with_the_wrong_version_is_rejected_and_the_rest_survive() {
        val infos = parse(seq(PACE_ECDH_GM_AES128, int(3), int(13)), hex(G2_PACE_INFO))
        assertEquals(1, infos.paceInfos.size)
        val rejected = infos.rejected.single()
        assertEquals(SecurityInfoFailure.UNSUPPORTED_VERSION, rejected.failure)
        assertEquals("0.4.0.127.0.7.2.2.4.2.2", rejected.protocol)
    }

    @Test
    fun pace_with_chip_authentication_mapping_needs_a_parameter_id() {
        val infos = parse(seq(oid("0.4.0.127.0.7.2.2.4.6.2"), int(2)))
        assertEquals(SecurityInfoFailure.INVALID_VALUE, infos.rejected.single().failure)
    }

    @Test
    fun a_malformed_entry_and_a_non_sequence_entry_are_rejected_not_thrown() {
        val truncated = seq(PACE_ECDH_GM_AES128, der(0x02, ByteArray(0)))
        val notSequence = int(5)
        val infos = parse(truncated, notSequence, hex(G1_PACE_INFO))
        assertEquals(1, infos.paceInfos.size)
        assertEquals(2, infos.rejected.size)
        assertEquals(SecurityInfoFailure.MALFORMED_DER, infos.rejected[0].failure)
        assertEquals(SecurityInfoFailure.UNEXPECTED_STRUCTURE, infos.rejected[1].failure)
        assertNull(infos.rejected[1].protocol)
    }

    // ---- other protocols ------------------------------------------------------------------

    @Test
    fun chip_authentication_info_reads_with_and_without_key_id() {
        val a = seq(oid("0.4.0.127.0.7.2.2.3.2.2"), int(1), int(13))
        val b = seq(oid("0.4.0.127.0.7.2.2.3.1.1"), int(1))
        val infos = parse(a, b)
        val (first, second) = infos.chipAuthenticationInfos
        assertEquals(KeyAgreement.ECDH, first.agreement)
        assertEquals(SessionCipher.AES_CBC_CMAC_128, first.cipher)
        assertEquals(13, first.keyId)
        assertEquals(KeyAgreement.DH, second.agreement)
        assertEquals(SessionCipher.TDES_CBC_CBC, second.cipher)
        assertNull(second.keyId)
        assertTrue(infos.chipAuthenticationInfo(13) === first)
        assertNull(infos.chipAuthenticationInfo(null)) // two infos: never guess
    }

    @Test
    fun chip_authentication_with_version_two_is_rejected() {
        val infos = parse(seq(oid("0.4.0.127.0.7.2.2.3.2.2"), int(2)))
        assertEquals(SecurityInfoFailure.UNSUPPORTED_VERSION, infos.rejected.single().failure)
    }

    @Test
    fun x942_dh_chip_authentication_key_reads() {
        val p = ByteArray(128) { (it + 1).toByte() }
        val g = byteArrayOf(2)
        val q = ByteArray(20) { (0x80 + it).toByte() }
        val y = ByteArray(127) { (0x10 + it).toByte() }
        val algorithm = seq(oid("1.2.840.10046.2.1"), seq(intBytes(p), intBytes(g), intBytes(q)))
        val spki = seq(algorithm, der(0x03, byteArrayOf(0) + intBytes(y)))
        val infos = parse(seq(oid("0.4.0.127.0.7.2.2.1.1"), spki, int(1)))
        val key = infos.chipAuthenticationPublicKeys.single()
        assertEquals(KeyAgreement.DH, key.agreement)
        val parameters = assertIs<DomainParameters.ExplicitDh>(key.key.parameters)
        assertEquals(DhFormat.X942, parameters.format)
        assertContentEquals(p, parameters.p)
        assertContentEquals(q, parameters.q)
        assertContentEquals(y, key.key.publicValue)
    }

    @Test
    fun pkcs3_dh_chip_authentication_key_reads_without_a_subgroup_order() {
        val p = ByteArray(64) { (it + 1).toByte() }
        val algorithm = seq(oid("1.2.840.113549.1.3.1"), seq(intBytes(p), intBytes(byteArrayOf(2))))
        val spki = seq(algorithm, der(0x03, byteArrayOf(0) + intBytes(byteArrayOf(5, 6, 7))))
        val key = parse(seq(oid("0.4.0.127.0.7.2.2.1.1"), spki)).chipAuthenticationPublicKeys.single()
        val parameters = assertIs<DomainParameters.ExplicitDh>(key.key.parameters)
        assertEquals(DhFormat.PKCS3, parameters.format)
        assertNull(parameters.q)
        assertContentEquals(byteArrayOf(5, 6, 7), key.key.publicValue)
        assertNull(key.keyId)
    }

    @Test
    fun a_named_curve_key_is_kept_as_unsupported_not_read() {
        val algorithm = seq(oid("1.2.840.10045.2.1"), oid("1.2.840.10045.3.1.7"))
        val spki = seq(algorithm, der(0x03, byteArrayOf(0, 4, 1, 2)))
        val key = parse(seq(oid("0.4.0.127.0.7.2.2.1.2"), spki)).chipAuthenticationPublicKeys.single()
        assertEquals(DomainParameters.Unsupported("1.2.840.10045.2.1"), key.key.parameters)
    }

    @Test
    fun active_authentication_info_reads() {
        val infos = parse(seq(oid("2.23.136.1.1.5"), int(1), oid("0.4.0.127.0.7.1.1.4.1.3")))
        val aa = infos.activeAuthenticationInfos.single()
        assertEquals(1, aa.version)
        assertEquals("0.4.0.127.0.7.1.1.4.1.3", aa.signatureAlgorithm)
    }

    @Test
    fun terminal_authentication_is_recognized_and_ignored() {
        val infos = parse(seq(oid("0.4.0.127.0.7.2.2.2"), int(1)), seq(oid("0.4.0.127.0.7.2.2.2.2.3"), int(1)))
        assertEquals(2, infos.terminalAuthenticationInfos.size)
        assertTrue(infos.unknown.isEmpty())
        assertTrue(infos.rejected.isEmpty())
    }

    @Test
    fun ef_dir_info_reads_its_applications() {
        val efDir = hex("61 09 4F 07 A0 00 00 02 47 10 01 61 09 4F 07 A0 00 00 02 47 20 01")
        val infos = parse(seq(oid("2.23.136.1.1.13"), der(0x04, efDir)))
        val info = assertNotNull(infos.efDirInfo)
        assertEquals(listOf(LdsApplication.LDS1_EMRTD, LdsApplication.TRAVEL_RECORDS), info.applications.map { it.application })
        assertContentEquals(efDir, info.encodedEfDir)
    }

    // ---- files ----------------------------------------------------------------------------

    @Test
    fun ef_card_access_is_the_bare_set() {
        val infos = ChipSecurityFiles.parseCardAccess(set(hex(G1_PACE_INFO), hex(G2_PACE_INFO)))
        assertEquals(2, infos.paceInfos.size)
    }

    @Test
    fun ef_dg14_wraps_the_set_in_tag_6e() {
        val file = der(0x6E, set(hex(G1_PACE_INFO), seq(oid("0.4.0.127.0.7.2.2.3.2.2"), int(1), int(13))))
        val infos = ChipSecurityFiles.parseDg14(file)
        assertEquals(1, infos.paceInfos.size)
        assertEquals(1, infos.chipAuthenticationInfos.size)
        assertFailsWith<SecurityInfoException> { ChipSecurityFiles.parseDg14(set(hex(G1_PACE_INFO))) }
    }

    private fun cardSecurity(
        contentType: String,
        content: ByteArray,
    ): ByteArray {
        val encap = seq(oid(contentType), der(0xA0, der(0x04, content)))
        val signedData =
            seq(
                int(3),
                set(seq(oid("2.16.840.1.101.3.4.2.1"))),
                encap,
                der(0xA0, seq(int(1))), // a certificate set (stand-in: not read)
                set(seq(int(1))), // signerInfos (stand-in: not read)
            )
        return seq(oid("1.2.840.113549.1.7.2"), der(0xA0, signedData))
    }

    @Test
    fun ef_card_security_content_reads_from_signed_data_and_i1_entries_are_found() {
        val content = set(hex(I1_PACE_INFO), hex(I1_CAPK_INFO))
        val infos = ChipSecurityFiles.parseCardSecurityContent(cardSecurity("0.4.0.127.0.7.3.2.1", content))
        assertEquals(1, infos.paceInfos.size)
        assertEquals(1, infos.chipAuthenticationPublicKeys.size)
    }

    @Test
    fun ef_card_security_with_another_content_type_fails() {
        val e =
            assertFailsWith<SecurityInfoException> {
                ChipSecurityFiles.parseCardSecurityContent(cardSecurity("2.23.136.1.1.1", set(hex(G1_PACE_INFO))))
            }
        assertEquals(SecurityInfoFailure.WRONG_CONTENT_TYPE, e.failure)
    }

    // ---- EF.ATR/INFO, EF.DIR --------------------------------------------------------------

    @Test
    fun ef_atr_info_reads_capabilities_and_extended_length_sizes() {
        // Tag 47: byte 1 short EF ids (bit 3), byte 3 chaining + extended Lc/Le + info in file.
        val file = hex("47 03 04 01 E0") + hex("7F 66 08 02 02 04 00 02 02 08 00")
        val atr = AtrInfoParser.parse(file)
        assertTrue(atr.shortEfIdentifiers)
        assertTrue(atr.commandChaining)
        assertTrue(atr.extendedLcLe)
        assertTrue(atr.extendedLengthInfoInFile)
        assertEquals(1024, atr.maxCommandBytes)
        assertEquals(2048, atr.maxResponseBytes)
    }

    @Test
    fun ef_atr_info_without_extended_length_says_nothing_about_it() {
        val atr = AtrInfoParser.parse(hex("47 03 00 00 00"))
        assertFalse(atr.extendedLcLe)
        assertNull(atr.maxCommandBytes)
        val empty = AtrInfoParser.parse(ByteArray(0))
        assertNull(empty.cardCapabilities)
        assertFalse(empty.extendedLcLe)
        assertFalse(empty.commandChaining)
    }

    @Test
    fun ef_atr_info_ignores_other_objects_and_rejects_bad_sizes() {
        val atr = AtrInfoParser.parse(hex("5F 52 02 AA BB 47 03 00 00 40"))
        assertTrue(atr.extendedLcLe)
        assertFailsWith<SecurityInfoException> { AtrInfoParser.parse(hex("47 02 00 00")) }
        assertFailsWith<SecurityInfoException> { AtrInfoParser.parse(hex("7F 66 03 02 01 05")) }
        assertFailsWith<SecurityInfoException> { AtrInfoParser.parse(hex("7F 66 0C 02 05 01 00 00 00 00 02 01 05")) }
        assertFailsWith<SecurityInfoException> { AtrInfoParser.parse(hex("47 03 00")) }
    }

    @Test
    fun ef_dir_table_31_reads_all_four_applications() {
        val file =
            hex(
                "61 09 4F 07 A0 00 00 02 47 10 01 61 09 4F 07 A0 00 00 02 47 20 01" +
                    "61 09 4F 07 A0 00 00 02 47 20 02 61 09 4F 07 A0 00 00 02 47 20 03",
            )
        val entries = EfDirParser.parse(file)
        assertEquals(
            listOf(
                LdsApplication.LDS1_EMRTD,
                LdsApplication.TRAVEL_RECORDS,
                LdsApplication.VISA_RECORDS,
                LdsApplication.ADDITIONAL_BIOMETRICS,
            ),
            entries.map { it.application },
        )
    }

    @Test
    fun ef_dir_keeps_an_unknown_aid_and_rejects_a_hostile_one() {
        val entries = EfDirParser.parse(hex("61 07 4F 05 A0 00 00 99 01"))
        assertNull(entries.single().application)
        assertFailsWith<SecurityInfoException> { guardedParse { EfDirParser.parse(hex("61 05 4F 03 A0 00 00")) } }
        assertFailsWith<SecurityInfoException> { guardedParse { EfDirParser.parse(hex("61 09 4F 07 A0")) } }
    }

    // ---- public key data objects ----------------------------------------------------------

    private val pkOid = hex("06 09 04 00 7F 00 07 02 02 01 02")

    @Test
    fun rsa_public_key_data_object_reads() {
        val bytes = template(pkOid, der(0x81, ByteArray(4) { 0x9A.toByte() }), der(0x82, byteArrayOf(1, 0, 1)))
        val key = assertIs<PublicKeyDataObject.Rsa>(PublicKeyDataObjectParser.parse(bytes))
        assertEquals("0.4.0.127.0.7.2.2.1.2", key.oid)
        assertContentEquals(byteArrayOf(1, 0, 1), key.exponent)
        assertEquals(4, key.modulus.size)
    }

    private fun template(vararg children: ByteArray): ByteArray {
        var body = ByteArray(0)
        children.forEach { body += it }
        return hex("7F49") + TlvWriter.length(body.size) + body
    }

    @Test
    fun dh_public_key_data_object_reads_and_may_be_shorter_than_the_prime() {
        val key =
            assertIs<PublicKeyDataObject.Dh>(
                PublicKeyDataObjectParser.parse(
                    template(
                        pkOid,
                        der(0x81, ByteArray(8) { 0xF1.toByte() }),
                        der(0x82, byteArrayOf(0x7F, 1)),
                        der(0x83, byteArrayOf(2)),
                        der(0x84, byteArrayOf(5, 6, 7)),
                    ),
                ),
            )
        assertEquals(8, key.p.size)
        assertContentEquals(byteArrayOf(5, 6, 7), key.y)
    }

    @Test
    fun ec_public_key_data_object_with_and_without_domain_parameters() {
        val point = ByteArray(9) { it.toByte() }
        val bare = assertIs<PublicKeyDataObject.Ec>(PublicKeyDataObjectParser.parse(template(pkOid, der(0x86, point))))
        assertNull(bare.parameters)
        assertContentEquals(point, bare.publicPoint)
        val full =
            assertIs<PublicKeyDataObject.Ec>(
                PublicKeyDataObjectParser.parse(
                    template(
                        pkOid,
                        der(0x81, byteArrayOf(0x97.toByte())),
                        der(0x82, byteArrayOf(1)),
                        der(0x83, byteArrayOf(2)),
                        der(0x84, byteArrayOf(4, 3, 3)),
                        der(0x85, byteArrayOf(0x61)),
                        der(0x86, point),
                        der(0x87, byteArrayOf(1)),
                    ),
                ),
            )
        assertContentEquals(byteArrayOf(0x61), assertNotNull(full.parameters).order)
        assertContentEquals(byteArrayOf(1), full.parameters?.cofactor)
    }

    @Test
    fun public_key_data_object_with_partial_parameters_or_wrong_order_fails() {
        val e =
            assertFailsWith<SecurityInfoException> {
                PublicKeyDataObjectParser.parse(template(pkOid, der(0x81, byteArrayOf(5)), der(0x86, byteArrayOf(4, 1))))
            }
        assertEquals(SecurityInfoFailure.UNEXPECTED_STRUCTURE, e.failure)
        assertFailsWith<SecurityInfoException> {
            PublicKeyDataObjectParser.parse(template(pkOid, der(0x82, byteArrayOf(1)), der(0x81, byteArrayOf(5))))
        }
        assertFailsWith<SecurityInfoException> { PublicKeyDataObjectParser.parse(template(der(0x81, byteArrayOf(5)))) }
        assertFailsWith<SecurityInfoException> { PublicKeyDataObjectParser.parse(hex("7F4A 00")) }
    }

    // ---- hostile input --------------------------------------------------------------------

    @Test
    fun hostile_bytes_fail_with_a_typed_exception_only() {
        val random = Random(315)
        val valid = set(hex(G1_PACE_INFO), hex(I1_CAPK_INFO), seq(oid("0.4.0.127.0.7.2.2.3.2.2"), int(1), int(13)))
        repeat(2000) {
            val bytes =
                if (it % 2 == 0) {
                    random.nextBytes(random.nextInt(0, 80))
                } else {
                    valid.copyOf().also { copy ->
                        repeat(random.nextInt(1, 4)) { copy[random.nextInt(copy.size)] = random.nextInt().toByte() }
                    }
                }
            for (parse in listOf<(ByteArray) -> Any>(
                ChipSecurityFiles::parseCardAccess,
                ChipSecurityFiles::parseDg14,
                ChipSecurityFiles::parseCardSecurityContent,
                AtrInfoParser::parse,
                { b -> guardedParse { EfDirParser.parse(b) } },
                PublicKeyDataObjectParser::parse,
            )) {
                try {
                    parse(bytes)
                } catch (_: SecurityInfoException) {
                    // typed failure: fine
                }
            }
        }
    }
}
