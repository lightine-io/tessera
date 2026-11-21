package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.bac.ScriptedTransport
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.EllipticCurves
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.sm.PlainExchange
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PACE with the generic mapping against the worked examples of Doc 9303 Part 11, Appendix G (eighth
 * edition), byte for byte: G.1 (ECDH, BrainpoolP256r1, AES-128) and G.2 (DH, the 1024-bit group of
 * RFC 5114, AES-128), both with the MRZ password. These are the only ICAO values for PACE-GM; every
 * other suite is checked against the fake chip only (PaceFakeChipTest), not against ICAO values.
 *
 * The APDUs are the "T>C" / "C>T" lines of the appendix as printed. One note: the last answer of
 * G.2 is printed without its status word; 9000 is added here, as on every other answer. That answer
 * also carries a certification authority reference (0x87), which the reader ignores.
 */
class PaceAppendixGTest {
    // The appendix's MRZ: document number T22000129 with check digit 3, birth 640812 (5), expiry 101031 (8).
    private fun key() = ChipAccessKey.Mrz("T22000129", '3', "640812", '5', "101031", '8')

    private fun scripted(vararg keys: String): (PaceGroup) -> ByteArray {
        val queue = keys.map { hex(it) }.toMutableList()
        return { queue.removeAt(0) }
    }

    @Test
    fun passwordEncodingAndKeyOfTheIntroduction() =
        withCrypto { c ->
            assertEquals("7E2D2A41C74EA0B38CD36F863939BFA8E9032AAD", PacePassword.encode(c, key()).toHex())
            assertEquals("89DED1B26624EC1E634C1989302849DD", PacePassword.derive(c, key(), SmCipher.AES128).toHex())
        }

    @Test
    fun suitesAndSetAtOfThePrintedPaceInfos() {
        val g1 = PaceSuite.of(G1_OID, 13)
        assertEquals("BrainpoolP256r1", g1.parameter.name)
        assertEquals(SmCipher.AES128, g1.smCipher)
        val g2 = PaceSuite.of(G2_OID, 0)
        assertTrue(g2.group is PaceGroup.Dh)
        assertEquals(
            "0022C1A40F800A04007F00070202040202830101",
            Pace.setAuthenticationTemplate(g1, PacePasswordType.MRZ, false).encode(false).toHex(),
        )
        assertEquals(
            "0022C1A40F800A04007F00070202040102830101",
            Pace.setAuthenticationTemplate(g2, PacePasswordType.MRZ, false).encode(false).toHex(),
        )
    }

    @Test
    fun g1MappingKeyAgreementAndSessionKeys() =
        runTest {
            withCrypto { c ->
                val suite = PaceSuite.of(G1_OID, 13)
                val params = (suite.group as PaceGroup.Ec).parameters
                val chipMappingKey = EcPointCodec.decode(c, params, hex(G1_CHIP_MAP_KEY))
                val h = EllipticCurves.sharedPoint(c, params, hex(G1_TERMINAL_MAP_PRIVATE), chipMappingKey)
                assertEquals(G1_H, h.x.toHex() + h.y.toHex())

                val mapped =
                    GenericMapping().map(MappingContext(c, suite, scripted(G1_TERMINAL_MAP_PRIVATE), c::randomBytes), hex(G1_NONCE)) {
                        assertEquals(G1_TERMINAL_MAP_KEY, it.toHex())
                        hex(G1_CHIP_MAP_KEY)
                    }
                val g = (mapped as PaceGroup.Ec).parameters.generator
                assertEquals(G1_MAPPED_G, g.x.toHex() + g.y.toHex())

                assertEquals(G1_TERMINAL_KA_KEY, mapped.publicKey(c, hex(G1_TERMINAL_KA_PRIVATE)).toHex())
                val chipKey = mapped.validatePublicKey(c, hex(G1_CHIP_KA_KEY))
                val secret = mapped.sharedSecret(c, hex(G1_TERMINAL_KA_PRIVATE), chipKey)
                assertEquals("28768D20701247DAE81804C9E780EDE582A9996DB4A315020B2733197DB84925", secret.toHex())
                val keys = SessionKeys.derive(c, secret, SmCipher.AES128)
                assertEquals("F5F0E35C0D7161EE6724EE513A0D9A7F", keys.encKey.toHex())
                assertEquals("FE251C7858B356B24514B3BD5F4297D1", keys.macKey.toHex())
                // Input data for T_IFD (over the chip's key) and T_IC (over the terminal's), as printed.
                assertEquals(
                    "7F494F060A04007F000702020402028641$G1_CHIP_KA_KEY",
                    mapped.tokenInput(Pace.oidContent(G1_OID), chipKey).toHex(),
                )
                assertEquals(
                    "7F494F060A04007F000702020402028641$G1_TERMINAL_KA_KEY",
                    mapped.tokenInput(Pace.oidContent(G1_OID), hex(G1_TERMINAL_KA_KEY)).toHex(),
                )
            }
        }

    @Test
    fun g1FullRunByteForByte() =
        runTest {
            withCrypto { c ->
                val transport = ScriptedTransport(G1_SCRIPT)
                val result =
                    Pace.open(
                        PlainExchange(transport),
                        c,
                        key(),
                        PaceSuite.of(G1_OID, 13),
                        newPrivateKey = scripted(G1_TERMINAL_MAP_PRIVATE, G1_TERMINAL_KA_PRIVATE),
                    )
                transport.assertDone()
                assertEquals(SmCipher.AES128, result.channel.cipher)
                assertContentEquals(ByteArray(16), result.channel.sscCopy())
                assertEquals(PacePasswordType.MRZ, result.passwordType)
                assertNull(result.mappingEvidence)
            }
        }

    @Test
    fun g2MappingKeyAgreementAndSessionKeys() =
        runTest {
            withCrypto { c ->
                val suite = PaceSuite.of(G2_OID, 0)
                val mapped =
                    GenericMapping().map(MappingContext(c, suite, scripted(G2_TERMINAL_MAP_PRIVATE), c::randomBytes), hex(G2_NONCE)) {
                        assertEquals(G2_TERMINAL_MAP_KEY, it.toHex())
                        hex(G2_CHIP_MAP_KEY)
                    }
                assertEquals(G2_MAPPED_G, (mapped as PaceGroup.Dh).parameters.g.toHex())

                assertEquals(G2_TERMINAL_KA_KEY, mapped.publicKey(c, hex(G2_TERMINAL_KA_PRIVATE)).toHex())
                val chipKey = mapped.validatePublicKey(c, hex(G2_CHIP_KA_KEY))
                val secret = mapped.sharedSecret(c, hex(G2_TERMINAL_KA_PRIVATE), chipKey)
                assertEquals(G2_SHARED_SECRET, secret.toHex())
                val keys = SessionKeys.derive(c, secret, SmCipher.AES128)
                assertEquals("2F7F46ADCC9E7E521B45D192FAFA9126", keys.encKey.toHex())
                assertEquals("805A1D27D45A5116F73C54469462B7D8", keys.macKey.toHex())
                assertEquals(
                    "7F49818F060A04007F00070202040102848180$G2_CHIP_KA_KEY",
                    mapped.tokenInput(Pace.oidContent(G2_OID), chipKey).toHex(),
                )
            }
        }

    @Test
    fun g2FullRunByteForByte() =
        runTest {
            withCrypto { c ->
                val transport = ScriptedTransport(G2_SCRIPT)
                val result =
                    Pace.open(
                        PlainExchange(transport),
                        c,
                        key(),
                        PaceSuite.of(G2_OID, 0),
                        newPrivateKey = scripted(G2_TERMINAL_MAP_PRIVATE, G2_TERMINAL_KA_PRIVATE),
                    )
                transport.assertDone()
                assertEquals(SmCipher.AES128, result.channel.cipher)
                assertContentEquals(ByteArray(16), result.channel.sscCopy())
            }
        }

    private companion object {
        const val G1_OID = "0.4.0.127.0.7.2.2.4.2.2"
        const val G2_OID = "0.4.0.127.0.7.2.2.4.1.2"

        // G.1, "Encrypted Nonce", "Map Nonce" and "Perform Key Agreement" tables (App G-4 to G-7).
        const val G1_NONCE = "3F00C4D39D153F2B2A214A078D899B22"
        const val G1_TERMINAL_MAP_PRIVATE = "7F4EF07B9EA82FD78AD689B38D0BC78CF21F249D953BC46F4C6E19259C010F99"
        const val G1_TERMINAL_MAP_KEY =
            "04" +
                "7ACF3EFC982EC45565A4B155129EFBC74650DCBFA6362D896FC70262E0C2CC5E" +
                "544552DCB6725218799115B55C9BAA6D9F6BC3A9618E70C25AF71777A9C4922D"
        const val G1_CHIP_MAP_KEY =
            "04" +
                "824FBA91C9CBE26BEF53A0EBE7342A3BF178CEA9F45DE0B70AA601651FBA3F57" +
                "30D8C879AAA9C9F73991E61B58F4D52EB87A0A0C709A49DC63719363CCD13C54"
        const val G1_H =
            "60332EF2450B5D247EF6D3868397D398852ED6E8CAF6FFEEF6BF85CA57057FD5" +
                "0840CA7415BAF3E43BD414D35AA4608B93A2CAF3A4E3EA4E82C9C13D03EB7181"
        const val G1_MAPPED_G =
            "8CED63C91426D4F0EB1435E7CB1D74A46723A0AF21C89634F65A9AE87A9265E2" +
                "8C879506743F8611AC33645C5B985C80B5F09A0B83407C1B6A4D857AE76FE522"
        const val G1_TERMINAL_KA_PRIVATE = "A73FB703AC1436A18E0CFA5ABB3F7BEC7A070E7A6788486BEE230C4A22762595"
        const val G1_TERMINAL_KA_KEY =
            "04" +
                "2DB7A64C0355044EC9DF190514C625CBA2CEA48754887122F3A5EF0D5EDD301C" +
                "3556F3B3B186DF10B857B58F6A7EB80F20BA5DC7BE1D43D9BF850149FBB36462"
        const val G1_CHIP_KA_KEY =
            "04" +
                "9E880F842905B8B3181F7AF7CAA9F0EFB743847F44A306D2D28C1D9EC65DF6DB" +
                "7764B22277A2EDDC3C265A9F018F9CB852E111B768B326904B59A0193776F094"

        // G.1, the APDUs as printed (App G-3 to G-9).
        val G1_SCRIPT =
            listOf(
                "0022C1A40F800A04007F00070202040202830101" to "9000",
                "10860000027C0000" to "7C12801095A3A016522EE98D01E76CB6B98B42C39000",
                "10860000457C438141${G1_TERMINAL_MAP_KEY}00" to "7C438241${G1_CHIP_MAP_KEY}9000",
                "10860000457C438341${G1_TERMINAL_KA_KEY}00" to "7C438441${G1_CHIP_KA_KEY}9000",
                "008600000C7C0A8508C2B0BD78D94BA86600" to "7C0A86083ABB9674BCE93C089000",
            )

        // G.2 tables (App G-11 to G-17).
        const val G2_NONCE = "FA5B7E3E49753A0DB9178B7B9BD898C8"
        const val G2_TERMINAL_MAP_PRIVATE = "5265030F751F4AD18B08AC565FC7AC952E41618D"
        const val G2_TERMINAL_MAP_KEY =
            "23FB3749EA030D2A25B278D2A562047ADE3F01B74F17A15402CB7352CA7D2B3E" +
                "B71C343DB13D1DEBCE9A3666DBCFC920B49174A602CB47965CAA73DC702489A4" +
                "4D41DB914DE9613DC5E98C94160551C0DF86274B9359BC0490D01B03AD54022D" +
                "CB4F57FAD6322497D7A1E28D46710F461AFE710FBBBC5F8BA166F4311975EC6C"
        const val G2_CHIP_MAP_KEY =
            "78879F57225AA8080D52ED0FC890A4B25336F699AA89A2D3A189654AF70729E6" +
                "23EA5738B26381E4DA19E004706FACE7B235C2DBF2F38748312F3C98C2DD4882" +
                "A41947B324AA1259AC22579DB93F7085655AF30889DBB845D9E6783FE42C9F24" +
                "49400306254C8AE8EE9DD812A804C0B66E8CAFC14F84D8258950A91B44126EE6"
        const val G2_MAPPED_G =
            "7C9CBFE98F9FBDDA8D143506FA7D9306F4CB17E3C71707AFF5E1C1A123702496" +
                "84D64EE37AF44B8DBD9D45BF6023919CBAA027AB97ACC771666C8E98FF483301" +
                "BFA4872DEDE9034EDFACB70814166B7F360676829B826BEA57291B5AD69FBC84" +
                "EF1E779032A305803F74341793E869742D401325B37EE8565FFCDEE618342DC5"
        const val G2_TERMINAL_KA_PRIVATE = "89CCD99B0E8D3B1F11E1296DCA68EC53411CF2CA"

        // Printed as 129 bytes with a leading 00; on the wire as an unsigned integer without it.
        const val G2_TERMINAL_KA_KEY =
            "907D89E2D425A178AA81AF4A7774EC8E388C115CAE67031E85EECE520BD91155" +
                "1B9AE4D04369F29A02626C86FBC6747CC7BC352645B6161A2A42D44EDA80A08F" +
                "A8D61B76D3A154AD8A5A51786B0BC07147057871A922212C5F67F43173172236" +
                "B7747D1671E6D692A3C7D40A0C3C5CE397545D015C175EB5130551EDBC2EE5D4"
        const val G2_CHIP_KA_KEY =
            "075693D9AE941877573E634B6E644F8E60AF17A0076B8B123D9201074D36152B" +
                "D8B3A213F53820C42ADC79AB5D0AEEC3AEFB91394DA476BD97B9B14D0A65C1FC" +
                "71A0E019CB08AF55E1F729005FBA7E3FA5DC41899238A250767A6D46DB974064" +
                "386CD456743585F8E5D90CC8B4004B1F6D866C79CE0584E49687FF61BC29AEA1"
        const val G2_SHARED_SECRET =
            "6BABC7B3A72BCD7EA385E4C62DB2625BD8613B24149E146A629311C4CA6698E3" +
                "8B834B6A9E9CD7184BA8834AFF5043D436950C4C1E7832367C10CB8C314D40E5" +
                "990B0DF7013E64B4549E2270923D06F08CFF6BD3E977DDE6ABE4C31D55C0FA2E" +
                "465E553E77BDF75E3193D3834FC26E8EB1EE2FA1E4FC97C18C3F6CFFFE2607FD"

        // G.2, the APDUs as printed (App G-11 to G-18).
        val G2_SCRIPT =
            listOf(
                "0022C1A40F800A04007F00070202040102830101" to "9000",
                "10860000027C0000" to "7C128010854D8DF5827FA6852D1A4FA701CDDDCA9000",
                "10860000867C8183818180${G2_TERMINAL_MAP_KEY}00" to "7C8183828180${G2_CHIP_MAP_KEY}9000",
                "10860000867C8183838180${G2_TERMINAL_KA_KEY}00" to "7C8183848180${G2_CHIP_KA_KEY}9000",
                "008600000C7C0A8508B46DD9BD4D98381F00" to "7C1B8608917F37B5C0E6D8D1870F4445544553544356434130303030339000",
            )
    }
}
