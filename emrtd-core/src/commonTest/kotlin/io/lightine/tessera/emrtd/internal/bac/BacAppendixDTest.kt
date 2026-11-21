package io.lightine.tessera.emrtd.internal.bac

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.sm.PlainExchange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A transport that expects exactly the commands of a worked example, in order, and answers each
 * with the example's response.
 */
internal class ScriptedTransport(
    private val script: List<Pair<String, String>>,
) : ApduTransport {
    override val maxCommandLength: Int = 261
    override val supportsExtendedLength: Boolean = false
    var step = 0
        private set

    override suspend fun transceive(command: ByteArray): ByteArray {
        val (expected, answer) = script[step++]
        assertEquals(expected.filter { !it.isWhitespace() }, command.toHex(), "command $step")
        return hex(answer)
    }

    fun assertDone() = assertEquals(script.size, step, "not every scripted command was sent")
}

/**
 * Basic Access Control and 3DES secure messaging against the worked example of Doc 9303 Part 11,
 * Appendix D (eighth edition), byte for byte. D.1 (keys from Kseed) is in IcaoKeyDerivationTest.
 */
class BacAppendixDTest {
    // D.2, the "document number 9 characters" examples (TD1 and TD2 give the same values).
    private fun nineCharacterKey() = ChipAccessKey.Mrz("L898902C<", '3', "690806", '1', "940623", '6')

    @Test
    fun appendixD2KeysFromTheMrzInformation() =
        withCrypto { c ->
            val key = nineCharacterKey()
            assertEquals("L898902C<369080619406236", key.secretCopy().decodeToString())
            // D.2 step 3 and 4: the SHA-1 hash and the key seed.
            assertEquals("239AB9CB282DAF66231DC5A4DF6BFBAEDF477565", c.digest(HashAlgorithm.SHA1, key.secretCopy()).toHex())
            assertEquals("239AB9CB282DAF66231DC5A4DF6BFBAE", Bac.keySeed(c, key.secretCopy()).toHex())
            // D.2 step 5.
            val keys = Bac.documentKeys(c, key)
            assertEquals("AB94FDECF2674FDFB9B391F85D7F76F2", keys.encKey.toHex())
            assertEquals("7962D9ECE03D1ACD4C76089DCE131543", keys.macKey.toHex())
        }

    @Test
    fun appendixD2DocumentNumbersLongerThanNineCharacters() {
        // D.2 TD1 example: number field, a filler in the check-digit place, then "7349<<<..." in
        // the 15-character optional data of the upper line.
        val td1 = ChipAccessKey.Mrz.fromPrintedFields("D23145890", '<', "7349<<<<<<<<<<<", "340712", '7', "950712", '2')
        assertEquals("D23145890734934071279507122", td1.secretCopy().decodeToString())
        // D.2 TD2 example: the same number with the 7-character optional data of TD2.
        val td2 = ChipAccessKey.Mrz.fromPrintedFields("D23145890", '<', "7349<<<", "340712", '7', "950712", '2')
        assertEquals("D23145890734934071279507122", td2.secretCopy().decodeToString())
        // The complete number given directly gives the same key.
        val whole = ChipAccessKey.Mrz("D23145890734", '9', "340712", '7', "950712", '2')
        assertEquals(td1.secretCopy().decodeToString(), whole.secretCopy().decodeToString())
        // Appendix D prints no hash for this MRZ information, so no key bytes are checked here.
    }

    @Test
    fun appendixD3AndD4BacThenSecureMessagingOfSelectAndReadBinary() =
        withCrypto { c ->
            runTest {
                val transport =
                    ScriptedTransport(
                        listOf(
                            // D.3 inspection system step 1: GET CHALLENGE.
                            "0084000008" to "4608F91988702212" + "9000",
                            // D.3 step 6: EXTERNAL AUTHENTICATE with cmd_data, and the chip's resp_data.
                            "0082000028" +
                                "72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F25F1448EEA8AD90A7" + "28" to
                                "46B9342A41396CD7386BF5803104D7CEDC122B9132139BAF2EEDC94EE178534F2F2D235D074D7449" + "9000",
                            // D.4 step 1: SELECT EF.COM, protected.
                            "0CA4020C158709016375432908C044F68E08BF8B92D635FF24F800" to
                                "990290008E08FA855A5D4C50A8ED9000",
                            // D.4 step 2: READ BINARY of the first four bytes.
                            "0CB000000D9701048E08ED6705417E96BA5500" to
                                "8709019FF0EC34F992265199029000" + "8E08AD55CC17140B2DED9000",
                            // D.4 step 3: READ BINARY of the remaining 18 bytes from offset 4.
                            "0CB000040D9701128E082EA28A70F3C7B53500" to
                                "871901FB9235F4E4037F2327DCC8964F1F9B8C30F42" + "C8E2FFF224A990290008E08C8B2787EAEA07D749000",
                        ),
                    )
                // D.3 step 2: RND.IFD, then KIFD.
                val randoms = ArrayDeque(listOf(hex("781723860C06C226"), hex("0B795240CB7049B01C19B33E32804F0B")))
                val channel =
                    Bac.open(
                        PlainExchange(transport),
                        c,
                        nineCharacterKey(),
                    ) { n -> randoms.removeFirst().also { assertEquals(n, it.size) } }
                // D.3 inspection system step 4: SSC.
                assertEquals("887022120C06C226", channel.sscCopy().toHex())

                val select = channel.send(ChipCommands.selectFile(LdsIds.EF_COM))
                assertEquals(StatusWord.SUCCESS, select.sw)
                assertEquals(0, select.data.size)
                assertEquals("887022120C06C228", channel.sscCopy().toHex())

                val head = channel.send(ChipCommands.readBinary(0, 4))
                assertEquals("60145F01", head.data.toHex())
                assertEquals("887022120C06C22A", channel.sscCopy().toHex())

                val rest = channel.send(ChipCommands.readBinary(4, 0x12))
                assertEquals("04303130365F36063034303030305C026175", rest.data.toHex())
                assertEquals(StatusWord.SUCCESS, rest.sw)
                assertEquals("887022120C06C22C", channel.sscCopy().toHex())
                // D.4 result: EF.COM.
                assertEquals("60145F0104303130365F36063034303030305C026175", (head.data + rest.data).toHex())
                transport.assertDone()
                assertTrue(channel.isOpen)
            }
        }
}
