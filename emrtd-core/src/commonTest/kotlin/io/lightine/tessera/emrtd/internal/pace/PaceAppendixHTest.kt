package io.lightine.tessera.emrtd.internal.pace

import io.lightine.tessera.emrtd.fakechip.FakePaceIm
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PACE with the integrated mapping against the worked examples of Doc 9303 Part 11, Appendix H,
 * byte for byte: H.1 (ECDH, BrainpoolP256r1, AES-128) and H.2 (DH, the 1024-bit group with a 160-bit
 * subgroup, AES-128). These are the only ICAO values for PACE-IM; every other suite is checked
 * against the fake chip only (PaceImFakeChipTest), not against ICAO values.
 *
 * The appendix prints Kπ but not the password it comes from (it differs from Appendix G's), and no
 * APDUs, so the run is checked step by step from the encrypted nonce on, not through [Pace.open].
 */
class PaceAppendixHTest {
    private fun single(value: String): (Int) -> ByteArray = { hex(value).also { v -> assertEquals(v.size, it) } }

    private fun scripted(vararg keys: String): (PaceGroup) -> ByteArray {
        val queue = keys.map { hex(it) }.toMutableList()
        return { queue.removeAt(0) }
    }

    private fun cmacToken(
        c: ChipCrypto,
        keys: SessionKeys,
        input: ByteArray,
    ) = c.cmac(keys.macKey, input).copyOf(8)

    @Test
    fun h1EcdhBrainpoolP256r1() =
        runTest {
            withCrypto { c ->
                val suite = PaceSuite.of(H1_OID, 13)
                val params = (suite.group as PaceGroup.Ec).parameters
                // The curve of the table at App H-1 is the standardized BrainpoolP256r1.
                assertEquals(H1_P, params.p.toHex())
                assertEquals(H1_G, params.generator.x.toHex() + params.generator.y.toHex())

                // Encrypted nonce: s = D(Kπ, z).
                assertEquals(H1_S, c.decrypt(suite.smCipher.blockCipher, CipherMode.CBC, hex(H1_KPI), ByteArray(16), hex(H1_Z)).toHex())

                // Map nonce: R(s, t), R_p(s, t), Ĝ.
                val r = IntegratedMapping.pseudoRandom(c, SmCipher.AES128, hex(H1_S), hex(H1_T), IntegratedMapping.bitLength(params.p))
                assertEquals(H1_R, r.toHex())
                assertEquals(H1_RP, IntegratedMapping.pseudoRandomMapping(c, SmCipher.AES128, hex(H1_S), hex(H1_T), params.p).toHex())
                var sent: String? = null
                val mapped =
                    IntegratedMapping().map(MappingContext(c, suite, { error("no mapping key") }, single(H1_T)), hex(H1_S)) {
                        sent = it.toHex()
                        ByteArray(0)
                    }
                assertEquals(H1_T, sent)
                val g = (mapped as PaceGroup.Ec).parameters.generator
                assertEquals(H1_MAPPED_G, g.x.toHex() + g.y.toHex())

                // Key agreement, session keys and tokens.
                assertEquals(H1_CHIP_KEY, mapped.publicKey(c, hex(H1_CHIP_PRIVATE)).toHex())
                assertEquals(H1_TERMINAL_KEY, mapped.publicKey(c, hex(H1_TERMINAL_PRIVATE)).toHex())
                val chipKey = mapped.validatePublicKey(c, hex(H1_CHIP_KEY))
                val secret = mapped.sharedSecret(c, hex(H1_TERMINAL_PRIVATE), chipKey)
                assertEquals("4F150FDE1D4F0E38E95017B891BAE17133A0DF45B0D3E18B60BA7BEAFDC2C713", secret.toHex())
                val keys = SessionKeys.derive(c, secret, SmCipher.AES128)
                assertEquals("0D3FEB33251A6370893D62AE8DAAF51B", keys.encKey.toHex())
                assertEquals("B01E89E3D9E8719E586B50B4A7506E0B", keys.macKey.toHex())
                val inputIc = mapped.tokenInput(Pace.oidContent(H1_OID), hex(H1_TERMINAL_KEY))
                val inputIfd = mapped.tokenInput(Pace.oidContent(H1_OID), chipKey)
                assertEquals("7F494F060A04007F000702020404028641$H1_TERMINAL_KEY", inputIc.toHex())
                assertEquals("7F494F060A04007F000702020404028641$H1_CHIP_KEY", inputIfd.toHex())
                assertEquals("75D4D96E8D5B0308", cmacToken(c, keys, inputIc).toHex())
                assertEquals("450F02B86F6A0909", cmacToken(c, keys, inputIfd).toHex())
            }
        }

    @Test
    fun h2Dh1024BitGroup() =
        runTest {
            withCrypto { c ->
                val suite = PaceSuite.of(H2_OID, 0)
                val params = (suite.group as PaceGroup.Dh).parameters
                assertEquals(H2_P, params.p.toHex())
                assertEquals(H2_G, params.g.toHex())
                assertEquals(H2_Q, params.q!!.toHex())

                assertEquals(H2_S, c.decrypt(suite.smCipher.blockCipher, CipherMode.CBC, hex(H1_KPI), ByteArray(16), hex(H2_Z)).toHex())

                val r = IntegratedMapping.pseudoRandom(c, SmCipher.AES128, hex(H2_S), hex(H2_T), IntegratedMapping.bitLength(params.p))
                assertEquals(H2_R, r.toHex())
                assertEquals(H2_RP, IntegratedMapping.pseudoRandomMapping(c, SmCipher.AES128, hex(H2_S), hex(H2_T), params.p).toHex())
                val mapped =
                    IntegratedMapping().map(MappingContext(c, suite, { error("no mapping key") }, single(H2_T)), hex(H2_S)) {
                        assertEquals(H2_T, it.toHex())
                        ByteArray(0)
                    }
                assertEquals(H2_MAPPED_G, (mapped as PaceGroup.Dh).parameters.g.toHex())

                assertEquals(H2_CHIP_KEY, mapped.publicKey(c, hex(H2_CHIP_PRIVATE)).toHex())
                assertEquals(H2_TERMINAL_KEY, mapped.publicKey(c, hex(H2_TERMINAL_PRIVATE)).toHex())
                val chipKey = mapped.validatePublicKey(c, hex(H2_CHIP_KEY))
                val secret = mapped.sharedSecret(c, hex(H2_TERMINAL_PRIVATE), chipKey)
                assertEquals(H2_SHARED_SECRET, secret.toHex())
                val keys = SessionKeys.derive(c, secret, SmCipher.AES128)
                assertEquals("01AFC10CF87BE36D8179E87370171F07", keys.encKey.toHex())
                assertEquals("23F0FBD05FD6C7B8B88F4C8309669061", keys.macKey.toHex())
                val inputIc = mapped.tokenInput(Pace.oidContent(H2_OID), hex(H2_TERMINAL_KEY))
                val inputIfd = mapped.tokenInput(Pace.oidContent(H2_OID), chipKey)
                assertEquals("7F49818F060A04007F00070202040302848180$H2_TERMINAL_KEY", inputIc.toHex())
                assertEquals("7F49818F060A04007F00070202040302848180$H2_CHIP_KEY", inputIfd.toHex())
                assertEquals("C2F04230187E1525", cmacToken(c, keys, inputIc).toHex())
                assertEquals("55D61977CBF5307E", cmacToken(c, keys, inputIfd).toHex())
            }
        }

    @Test
    fun theFakeChipMapsTheAppendixNoncesToTheSameGenerators() =
        withCrypto { c ->
            // The fake chip's own R_p and Jacobian point encoding (Appendix B.3) against the printed values.
            val curve = PaceSuite.of(H1_OID, 13).group.parameters as EcDomainParameters
            val rp = FakePaceIm.rp(c, SmCipher.AES128, hex(H1_S), hex(H1_T), curve.p)
            assertEquals(H1_RP, rp.toHex())
            val g = FakePaceIm.jacobianEncoding(c, curve, rp)!!
            assertEquals(H1_MAPPED_G, g.x.toHex() + g.y.toHex())
            val dh = (PaceSuite.of(H2_OID, 0).group as PaceGroup.Dh).parameters
            assertEquals(H2_RP, FakePaceIm.rp(c, SmCipher.AES128, hex(H2_S), hex(H2_T), dh.p).toHex())
            val a = FakePaceIm.Big.divide(FakePaceIm.Big.minus(dh.p, byteArrayOf(1)), dh.q!!)
            assertEquals(H2_MAPPED_G, c.modPow(hex(H2_RP), a, dh.p).toHex())
            assertTrue(a.isNotEmpty())
        }

    private companion object {
        const val H1_OID = "0.4.0.127.0.7.2.2.4.4.2"
        const val H2_OID = "0.4.0.127.0.7.2.2.4.3.2"

        // H.1 tables (App H-1 to H-3).
        const val H1_P = "A9FB57DBA1EEA9BC3E660A909D838D726E3BF623D52620282013481D1F6E5377"
        const val H1_G =
            "8BD2AEB9CB7E57CB2C4B482FFC81B7AFB9DE27E1E3BD23C23A4453BD9ACE3262" +
                "547EF835C3DAC4FD97F8461A14611DC9C27745132DED8E545C1D54C72F046997"
        const val H1_KPI = "591468CDA83D65219CCCB8560233600F"
        const val H1_S = "2923BE84E16CD6AE529049F1F1BBE9EB"
        const val H1_Z = "143DC40C08C8E891FBED7DEDB92B64AD"
        const val H1_T = "5DD4CBFC96F5453B130D890A1CDBAE32"
        const val H1_R =
            "E4447E2DFB3586BAC05DDB00156B57FBB2179A3949294C97254189800C517BAA" +
                "8DA0FF397ED8C445D3E421E4FEB57322"
        const val H1_RP = "A2F8FF2DF50E52C6599F386ADCB595D229F6A167ADE2BE5F2C3296ADD5B7430E"
        const val H1_MAPPED_G =
            "8E82D31559ED0FDE92A4D0498ADD3C23BABA94FB77691E31E90AEA77FB17D427" +
                "4C1AE14BD0C3DBAC0C871B7F3608169364437CA30AC243A089D3F266C1E60FAD"
        const val H1_CHIP_PRIVATE = "107CF58696EF6155053340FD633392BA81909DF7B9706F226F32086C7AFF974A"
        const val H1_CHIP_KEY =
            "04" +
                "67F78E5F7F7686082B293E8D087E056916D0F74BC01A5F8957D0DE45691E51E8" +
                "932B69A962B52A0985AD2C0A271EE6A13A8ADDDCD1A3A994B9DED257F4D22753"
        const val H1_TERMINAL_PRIVATE = "A73FB703AC1436A18E0CFA5ABB3F7BEC7A070E7A6788486BEE230C4A22762595"
        const val H1_TERMINAL_KEY =
            "04" +
                "89CBA23FFE96AA18D824627C3E934E54A9FD0B87A95D1471DC1C0ABFDCD640D4" +
                "6755DE9B7B778280B6BEBD57439ADFEB0E21FD4ED6DF42578C13418A59B34C37"

        // H.2 tables (App H-4 to H-7).
        const val H2_P =
            "B10B8F96A080E01DDE92DE5EAE5D54EC52C99FBCFB06A3C69A6A9DCA52D23B61" +
                "6073E28675A23D189838EF1E2EE652C013ECB4AEA906112324975C3CD49B83BF" +
                "ACCBDD7D90C4BD7098488E9C219A73724EFFD6FAE5644738FAA31A4FF55BCCC0" +
                "A151AF5F0DC8B4BD45BF37DF365C1A65E68CFDA76D4DA708DF1FB2BC2E4A4371"
        const val H2_G =
            "A4D1CBD5C3FD34126765A442EFB99905F8104DD258AC507FD6406CFF14266D31" +
                "266FEA1E5C41564B777E690F5504F213160217B4B01B886A5E91547F9E2749F4" +
                "D7FBD7D3B9A92EE1909D0D2263F80A76A6A24C087A091F531DBF0A0169B6A28A" +
                "D662A4D18E73AFA32D779D5918D08BC8858F4DCEF97C2A24855E6EEB22B3B2E5"
        const val H2_Q = "F518AA8781A8DF278ABA4E7D64B7CB9D49462353"
        const val H2_S = "FA5B7E3E49753A0DB9178B7B9BD898C8"
        const val H2_Z = "9ABB8864CA0FF1551E620D1EF4E13510"
        const val H2_T = "B3A6DB3C870C3E99245E0D1C06B747DE"
        const val H2_R =
            "EAB98D13E09052952AA729907C3C946184DEA0FE74AD2B3AF506F0A83018459C" +
                "38099CD1F7FF4EA0A078DB1FAC1365505E3DC85500EF95E20B4EEF2E88489233" +
                "BEE0546B472F994B618D168702406791DEEF3CB4810932EC278F3533FDB860EB" +
                "4835C36FA4F1BF3FA0B828A718C96BDE88FBA38A3E6C35AAA10959251EB5FC71" +
                "0FC187258995944C0F926E249373F485"
        const val H2_RP =
            "A0C7C50C002061A51CC87D254EF38068607417B6EE1B36473CFB800D2D2E5FA2" +
                "B6980F01105D24FAB22ACD1BFA5C8A4C093ECDFAFE6D7125D42A843E33860383" +
                "5CF19AFAFF75EFE21DC5F6AA1F9AE46C25087E7368166FB08C1E4627AFED7D93" +
                "570417B790FF7F747E57F432B04E1236819E0DFEF5B6E77CA4999925328182D2"
        const val H2_MAPPED_G =
            "1D7D767F11E333BCD6DBAEF40E799E7A926B96973550656FF3C830726D118D61" +
                "C276CDCC61D475CF03A98E0C0E79CAEBA5BE25578BD4551D0B10903236F0B0F9" +
                "76852FA78EEA14EA0ACA87D1E91F688FE0DFF897BBE35A472621D343564B262F" +
                "34223AE8FC59B664BFEDFA2BFE7516CA5510A6BBB633D517EC25D4E0BBAA16C2"
        const val H2_CHIP_PRIVATE =
            "020F018C7284B047FA7721A337EFB7ACB1440BB30C5252BD41C97C30C994BB78" +
                "E9F0C5B32744D84017D21FFA6878396A6469CA283EF5C000DAF7D261A39AB886" +
                "0ED4610AB5343390897AAB5A7787E4FAEFA0649C6A94FDF82D991E8E3FC332F5" +
                "142729E7040A3F7D5A4D3CD75CBEE1F043C1CAD2DD484FEB4ED22B597D36688E"
        const val H2_CHIP_KEY =
            "928D9A0F9DBA450F13FC859C6F290D1D36E42431138A4378500BEB4E0401854C" +
                "FF111F71CB6DC1D0335807A11388CC8EAA87B07907AAD9FBA6B169AF6D8C26AF" +
                "8DDDC39ADC3AD2E3FF882B84D23E9768E95A80E4746FB07A9767679FE92133B4" +
                "D379935C771BD7FBED6C7BB4B1708B275EA75679524CDC9C6A91370CC662A2F3"
        const val H2_TERMINAL_PRIVATE =
            "4BD0E54740F9A028E6A515BFDAF967848C4F5F5FFF65AA0915947FFD1A0DF2FA" +
                "6981271BC905F3551457B7E03AC3B8066DE4AA406C1171FB43DD939C4BA16175" +
                "103BA3DEE16419AA248118F90CC36A3D6F4C373652E0C3CCE7F0F1D0C5425B36" +
                "00F0F0D6A67F004C8BBA33F2B4733C7252445C1DFC4F1107203F71D2EFB28161"
        const val H2_TERMINAL_KEY =
            "0F0CC62945A8029251FB7EF3C094E12EC68E4EF07F27CB9D9CD04C5C4250FAE0" +
                "E4F8A951557E929AEB48E5C6DD47F2F5CD7C351A9BD2CD722C07EDE166770F08" +
                "FFCB370262CF308DD7B07F2E0DA9CAAA1492344C852906919538C98A4BA4187E" +
                "76CE9D87832386D319CE2E043C3343AEAE6EDBA1A9894DC5094D22F7FE1351D5"
        const val H2_SHARED_SECRET =
            "419410D6C0A17A4C07C54872CE1CBCEB0A2705C1A434C8A89A4CFE41F1D78124" +
                "CA7EC52BDE7615E5345E48AB1ABB6E7D1D59A57F3174084D3CA4570397C1F622" +
                "28BDFDB2DA191EA2239E2C060DBE3BBC23C2FCD0AF12E0F9E0B99FCF91FF1959" +
                "011D5798B2FCBC1F14FCC24E441F4C8F9B08D977E9498560E63E7FFAB3134EA7"
    }
}
