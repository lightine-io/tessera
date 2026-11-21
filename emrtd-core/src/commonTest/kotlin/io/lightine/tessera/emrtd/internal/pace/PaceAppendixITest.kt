package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import io.lightine.tessera.emrtd.internal.bac.ScriptedTransport
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EllipticCurves
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.DomainParameters
import io.lightine.tessera.emrtd.internal.securityinfo.PaceMapping
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoParser
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PACE with the chip authentication mapping against the worked example of Doc 9303 Part 11,
 * Appendix I, I.1 (ECDH, BrainpoolP256r1, AES-128, MRZ password), byte for byte: the APDUs as
 * printed, the session keys, the IV E(KSEnc, -1), the decrypted chip authentication data CA_IC, and
 * the check PK_Map,IC = KA(CA_IC, PK_IC, D_IC) with the printed ChipAuthenticationPublicKeyInfo.
 *
 * One note on the print: the IV is printed as "F6A3B75A1 E933941 DD7A13E2 520779DF", a spacing slip
 * of the 16 bytes F6A3B75A 1E933941 DD7A13E2 520779DF; the test computes exactly those bytes.
 */
class PaceAppendixITest {
    // The appendix's MRZ: document number C11T002JM with check digit 4, birth 960812 (2), expiry 231031 (4).
    private fun key() = ChipAccessKey.Mrz("C11T002JM", '4', "960812", '2', "231031", '4')

    private fun scripted(vararg keys: String): (PaceGroup) -> ByteArray {
        val queue = keys.map { hex(it) }.toMutableList()
        return { queue.removeAt(0) }
    }

    private fun suite() = PaceSuite.of(OID, 13)

    @Test
    fun passwordEncodingKeyAndSetAt() =
        withCrypto { c ->
            assertEquals("894D03F148C6265E89845B218856EA34D00EF8E8", PacePassword.encode(c, key()).toHex())
            assertEquals("4E6F6FBF7BE748B932C7B74161BBA9DF", PacePassword.derive(c, key(), SmCipher.AES128).toHex())
            assertEquals(PaceMapping.CHIP_AUTHENTICATION, suite().mapping)
            assertEquals(SCRIPT[0].first, Pace.setAuthenticationTemplate(suite(), PacePasswordType.MRZ, false).encode(false).toHex())
            assertIs<ChipAuthMapping>(Mappings.forKind(PaceMapping.CHIP_AUTHENTICATION))
        }

    @Test
    fun mappingKeyAgreementSessionKeysAndIv() =
        runTest {
            withCrypto { c ->
                val suite = suite()
                val params = (suite.group as PaceGroup.Ec).parameters
                val h = EllipticCurves.sharedPoint(c, params, hex(TERMINAL_MAP_PRIVATE), EcPointCodec.decode(c, params, hex(CHIP_MAP_KEY)))
                assertEquals(H, h.x.toHex() + h.y.toHex())
                val mapping = ChipAuthMapping()
                val mapped =
                    mapping.map(MappingContext(c, suite, scripted(TERMINAL_MAP_PRIVATE), c::randomBytes), hex(NONCE)) {
                        assertEquals(TERMINAL_MAP_KEY, it.toHex())
                        hex(CHIP_MAP_KEY)
                    }
                val g = (mapped as PaceGroup.Ec).parameters.generator
                assertEquals(MAPPED_G, g.x.toHex() + g.y.toHex())
                assertEquals(CHIP_MAP_KEY, mapping.chipMappingKey!!.toHex())

                val secret = mapped.sharedSecret(c, hex(TERMINAL_KA_PRIVATE), mapped.validatePublicKey(c, hex(CHIP_KA_KEY)))
                assertEquals("67950559D0C06B4D4B86972D14460837461087F8419FDBC36AAF6CEAAC462832", secret.toHex())
                val keys = SessionKeys.derive(c, secret, SmCipher.AES128)
                assertEquals(KS_ENC, keys.encKey.toHex())
                assertEquals("4B1C06491ED5140CA2B537D344C6C0B1", keys.macKey.toHex())
                assertEquals("F6A3B75A1E933941DD7A13E2520779DF", ChipAuthMapping.iv(c, keys.encKey).toHex())
            }
        }

    @Test
    fun decryptionGivesThePrintedChipAuthenticationData() =
        withCrypto { c ->
            val ca = ChipAuthMapping.decrypt(c, suite(), hex(KS_ENC), hex(ENCRYPTED_CA)).getOrThrow()
            assertEquals(CA, ca.toHex())
        }

    @Test
    fun fullRunByteForByteAndTheKeyCheck() =
        runTest {
            withCrypto { c ->
                val transport = ScriptedTransport(SCRIPT)
                val result =
                    Pace.open(
                        PlainApduSender(transport),
                        c,
                        key(),
                        suite(),
                        newPrivateKey = scripted(TERMINAL_MAP_PRIVATE, TERMINAL_KA_PRIVATE),
                    )
                transport.assertDone()
                assertEquals(SmCipher.AES128, result.channel.cipher)
                val evidence = assertIs<ChipAuthMappingEvidence>(result.mappingEvidence)
                assertNull(evidence.dataFailure)
                assertEquals(ENCRYPTED_CA, evidence.encryptedData!!.toHex())
                assertEquals(CA, evidence.chipAuthenticationData!!.toHex())
                assertEquals(CHIP_MAP_KEY, evidence.chipMappingKey.toHex())
                assertEquals(13, evidence.keyId)

                // The printed ChipAuthenticationPublicKeyInfo: id-PK-ECDH, BrainpoolP256r1, keyId 13.
                val info = SecurityInfoParser.parse(DerWriter.set(hex(CA_PUBLIC_KEY_INFO))).chipAuthenticationPublicKey(13)!!
                assertEquals(DomainParameters.Standardized(13), info.key.parameters)
                assertEquals("04$PK_IC", info.key.publicValue.toHex())
                assertEquals(CamOutcome.Passed, ChipAuthMappingCheck.keyMatches(c, evidence, evidence.chipAuthenticationData!!, info))
            }
        }

    @Test
    fun theStaticKeyTimesCaIsTheMappingKey() =
        withCrypto { c ->
            // KA(CA_IC, PK_IC, D_IC) as a plain multiplication, independent of the check's code path.
            val params = (suite().group as PaceGroup.Ec).parameters
            val point = c.ecMultiply(params, EcPointCodec.decode(c, params, hex("04$PK_IC")), hex(CA))!!
            assertContentEquals(hex(CHIP_MAP_KEY), EcPointCodec.encode(params, point))
            assertTrue(EllipticCurves.isValidPublicKey(c, params, point))
        }

    internal companion object {
        const val OID = "0.4.0.127.0.7.2.2.4.6.2"

        // I.1, "Encrypted Nonce", "Map Nonce" and "Perform Key Agreement" tables (App I-4 to I-8).
        const val NONCE = "658B860BC94DF6F044FCE6D5C82CF8E5"
        const val TERMINAL_MAP_PRIVATE = "5D8BB87BD74D985A4B7D4325B9F7B976FE835122773400798914AA22738135CC"
        const val TERMINAL_MAP_KEY =
            "04" +
                "7F1D410ADB7DDB3B84BF1030800981A9105D7457B4A3ADE002384F3086C67EDE" +
                "1AB889104A27DB6D842B019020FBF3CEACB0DC627F7BDCAC29969E19D0E553C1"
        const val CHIP_MAP_KEY =
            "04" +
                "A234236AA9B9621E8EFB73B5245C0E09D2576E5277183C1208BDD55280CAE8B3" +
                "04F365713A356E65A451E165ECC9AC0AC46E3771342C8FE5AEDD092685338E23"
        const val H =
            "2C1DCC1773346492C6636A36EE4B965E292E9AAE7EE37736EF58B9D0A043F348" +
                "403A8CF33CA7DC0D9DF61D0889CE24424FF97C1AAD48A5CA2A554B071EF7638D"
        const val MAPPED_G =
            "89F0B5EABF3BE293C75903A3986131925C9F5B515CA95AF485DC7E886F03245D" +
                "44BEFB2DD3A0DBD71CB5E618971CF4747F12B79E548379A40E45963BAAF3E829"
        const val TERMINAL_KA_PRIVATE = "76ECFDAA9841C323A3F5FC5E88B88DB3EFF7E35EBF57A7E6946CB630006C2120"
        const val TERMINAL_KA_KEY =
            "04" +
                "446C934084D9DAB863944F219520076C29EE3F7AE6722B11FF319EC1C7728F95" +
                "5483400BFF60BF0C5929270009277DC2A515E12575010AD9BA916CF1BF86FEFC"
        const val CHIP_KA_KEY =
            "04" +
                "02AD566F3C6EC7F9324509AD50A51FA52030782A4968FCFEDF737DAEA9933331" +
                "11C3B9B4C2287789BD137E7F8AA882E2A3C633CCD6ECC2C63C57AD401A09C2E1"
        const val KS_ENC = "0A9DA4DB03BDDE39FC5202BC44B2E89E"

        // "Chip Authentication" (App I-10 and I-11).
        const val ENCRYPTED_CA =
            "1EEA964DAAE372AC990E3EFDE6333353BFC89A6704D93DA8798CF77F5B7A54BD" +
                "10CBA372B42BE0B9B5F28AA8DE2F4F92"
        const val CA = "85DC3FA93D0952BFA82F5FD189EE75BD82F11D1F0B8ED4BF5319AC9B53C426B3"
        const val PK_IC =
            "1872709494399E7470A6431BE25E83EEE24FEA568C2ED28DB48E05DB3A610DC8" +
                "84D256A40E35EFCB59BF6753D3A489D28C7A4D973C2DA138A6E7A4A08F68E16F"
        const val CA_PUBLIC_KEY_INFO =
            "30620609 04007F00 07020201 02305230 0C060704 007F0007 01020201 0D034200" +
                "04187270 9494399E 7470A643 1BE25E83 EEE24FEA 568C2ED2 8DB48E05 DB3A610D" +
                "C884D256 A40E35EF CB59BF67 53D3A489 D28C7A4D 973C2DA1 38A6E7A4 A08F68E1" +
                "6F02010D"

        // I.1, the APDUs as printed (App I-3 to I-9).
        val SCRIPT =
            listOf(
                "0022C1A40F800A04007F00070202040602830101" to "9000",
                "10860000027C0000" to "7C128010CB60E8E0D85B76A9BD304747C2AD42E29000",
                "10860000457C438141${TERMINAL_MAP_KEY}00" to "7C438241${CHIP_MAP_KEY}9000",
                "10860000457C438341${TERMINAL_KA_KEY}00" to "7C438441${CHIP_KA_KEY}9000",
                "008600000C7C0A8508E86BD06018A1CD3B00" to "7C3C86088596CF055C67C1A38A30${ENCRYPTED_CA}9000",
            )
    }
}
