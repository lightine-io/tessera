package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.tlv.TlvWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The secure-messaging codec on its own: counter, Le encoding, and answers it must refuse. */
class SecureMessagingTest {
    // The 3DES session of Part 11, Appendix D.3.
    private val keys = { SessionKeys(SmCipher.DESEDE, hex("979EC13B1CBFE9DCD01AB0FED307EAE5"), hex("F1CB1F1FB5ADF208806B89DC579DC1F8")) }
    private val ssc = hex("887022120C06C226")

    @Test
    fun theCounterIsUnsignedAndWraps() {
        val a = hex("00000000000000FF")
        SecureMessaging.increment(a)
        assertEquals("0000000000000100", a.toHex())
        val b = hex("FFFFFFFFFFFFFFFF")
        SecureMessaging.increment(b)
        assertEquals("0000000000000000", b.toHex())
    }

    @Test
    fun leDataObjectIsOneByteShortAndTwoBytesExtended() {
        assertEquals("04", SecureMessaging.leBytes(4).toHex())
        assertEquals("00", SecureMessaging.leBytes(256).toHex())
        assertEquals("0101", SecureMessaging.leBytes(257).toHex())
        assertEquals("0000", SecureMessaging.leBytes(65_536).toHex())
    }

    @Test
    fun anExtendedLeGivesAnExtendedProtectedCommand() =
        withCrypto { c ->
            val sm = SecureMessaging(c, keys(), ssc)
            val p = sm.protect(CommandApdu(0x00, 0xB0, 0, 0, ne = 1000))
            assertEquals(CommandApdu.MAX_EXTENDED_NE, p.ne)
            assertEquals("970203E8", p.data.copyOf(4).toHex())
            // Extended Le "00 00" after an extended Lc (Figure 5).
            assertEquals(
                "0000",
                p
                    .encode(true)
                    .takeLast(2)
                    .toByteArray()
                    .toHex(),
            )
        }

    @Test
    fun anOddInsCarriesItsDataInDataObject85() =
        withCrypto { c ->
            val sm = SecureMessaging(c, keys(), ssc)
            val p = sm.protect(CommandApdu(0x00, 0xB1, 0, 0, hex("54020100"), ne = 0xDF))
            assertEquals(0x85, p.data[0].toInt() and 0xFF)
            assertEquals(0x0C, p.cla)
        }

    @Test
    fun anAlreadyProtectedCommandIsRefused() =
        withCrypto { c ->
            val sm = SecureMessaging(c, keys(), ssc)
            assertFailsWith<IllegalArgumentException> { sm.protect(CommandApdu(0x0C, 0xB0, 0, 0, ne = 4)) }
        }

    @Test
    fun answersThatCannotBeAuthenticatedAreRefused() =
        withCrypto { c ->
            fun failure(answer: String): SmFailure {
                val sm = SecureMessaging(c, keys(), ssc)
                sm.protect(CommandApdu(0x00, 0xB0, 0, 0, ne = 4))
                return assertFailsWith<SecureMessagingException> { sm.unprotect(ResponseApdu.parse(hex(answer))) }.failure
            }
            assertEquals(SmFailure.MISSING_MAC, failure("9000"))
            assertEquals(SmFailure.MISSING_MAC, failure("990290009000"))
            assertEquals(SmFailure.CHIP_ENDED_SESSION, failure("6987"))
            assertEquals(SmFailure.CHIP_ENDED_SESSION, failure("6988"))
            assertEquals(SmFailure.MALFORMED_RESPONSE, failure("8E08000000000000000099"))
            // DO'99' before DO'87': wrong order.
            assertEquals(SmFailure.MALFORMED_RESPONSE, failure("99029000" + "870901FFFFFFFFFFFFFFFF" + "8E080000000000000000" + "9000"))
            assertEquals(SmFailure.WRONG_MAC, failure("99029000" + "8E080000000000000000" + "9000"))
        }

    @Test
    fun theAnswerOfAppendixD4IsReadAndAnAlteredOneIsNot() =
        withCrypto { c ->
            // D.4 step 1: SELECT EF.COM, then the chip's answer.
            val sm = SecureMessaging(c, keys(), ssc)
            sm.protect(CommandApdu(0x00, 0xA4, 0x02, 0x0C, hex("011E")))
            val r = sm.unprotect(ResponseApdu.parse(hex("990290008E08FA855A5D4C50A8ED9000")))
            assertEquals(StatusWord.SUCCESS, r.sw)
            assertContentEquals(ByteArray(0), r.data)

            val again = SecureMessaging(c, keys(), ssc)
            again.protect(CommandApdu(0x00, 0xA4, 0x02, 0x0C, hex("011E")))
            val e =
                assertFailsWith<SecureMessagingException> { again.unprotect(ResponseApdu.parse(hex("990262828E08FA855A5D4C50A8ED9000"))) }
            assertEquals(SmFailure.WRONG_MAC, e.failure)
        }

    @Test
    fun withoutDataObject99TheOuterStatusIsTrustedOnlyForAnEmpty9000() =
        withCrypto { c ->
            // An answer whose MAC is right but which has no DO'99': the MAC is computed here over
            // the counter the session will use (two increments: the command, then the answer).
            fun answer(
                covered: String,
                outerSw: String,
            ): ResponseApdu {
                val counter = ssc.copyOf()
                SecureMessaging.increment(counter)
                SecureMessaging.increment(counter)
                val mac = c.retailMac(keys().macKey, CryptoBytes.pad(counter + hex(covered), 8))
                return ResponseApdu.parse(hex(covered + "8E08" + mac.toHex() + outerSw))
            }

            fun session() = SecureMessaging(c, keys(), ssc).also { it.protect(CommandApdu(0x00, 0xB0, 0, 0, ne = 4)) }

            val ok = session().unprotect(answer("", "9000"))
            assertEquals(StatusWord.SUCCESS, ok.sw)
            assertContentEquals(ByteArray(0), ok.data)

            // A plain warning or error outside the MAC is never taken as the answer's status.
            val warning = assertFailsWith<SecureMessagingException> { session().unprotect(answer("", "6282")) }
            assertEquals(SmFailure.UNAUTHENTICATED_STATUS, warning.failure)
            assertEquals(0x6282, warning.sw)
            // Data with no DO'99' is refused even when the outer status says 9000.
            val withData = assertFailsWith<SecureMessagingException> { session().unprotect(answer("870901" + "00".repeat(8), "9000")) }
            assertEquals(SmFailure.UNAUTHENTICATED_STATUS, withData.failure)
        }

    @Test
    fun wrongPaddingOnTheDecryptedDataIsTyped() =
        withCrypto { c ->
            val k = keys()
            val sm = SecureMessaging(c, k, ssc)
            sm.protect(CommandApdu(0x00, 0xB0, 0, 0, ne = 8))
            // The answer's counter is the start value incremented twice: once by protect, once by unprotect.
            val answerSsc =
                ssc.copyOf().also {
                    SecureMessaging.increment(it)
                    SecureMessaging.increment(it)
                }
            // Eight bytes with no ISO 9797-1 method 2 padding terminator, encrypted and MAC'd correctly.
            val padded = ByteArray(8) { (it + 1).toByte() }
            val encrypted = c.encrypt(BlockCipher.DESEDE, CipherMode.CBC, k.encKey, ByteArray(8), padded)
            val covered =
                TlvWriter.encode(0x87, byteArrayOf(0x01) + encrypted) + TlvWriter.encode(0x99, byteArrayOf(0x90.toByte(), 0x00.toByte()))
            val mac = c.retailMac(k.macKey, CryptoBytes.pad(answerSsc + covered, 8))
            val e =
                assertFailsWith<SecureMessagingException> {
                    sm.unprotect(ResponseApdu.parse(covered + TlvWriter.encode(0x8E, mac) + hex("9000")))
                }
            assertEquals(SmFailure.WRONG_PADDING, e.failure)
        }

    @Test
    fun errorTextCarriesNoBytes() {
        val e = SecureMessagingException(SmFailure.CHIP_ENDED_SESSION, 0x6988)
        assertEquals("Secure messaging ended: CHIP_ENDED_SESSION (status 6988)", e.message)
    }
}
