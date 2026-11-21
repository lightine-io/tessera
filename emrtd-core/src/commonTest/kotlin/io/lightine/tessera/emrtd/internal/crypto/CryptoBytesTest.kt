package io.lightine.tessera.emrtd.internal.crypto

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoBytesTest {
    @Test
    fun constantTimeEqualsComparesContentAndLength() {
        assertTrue(CryptoBytes.constantTimeEquals(hex("0102"), hex("0102")))
        assertFalse(CryptoBytes.constantTimeEquals(hex("0102"), hex("0103")))
        assertFalse(CryptoBytes.constantTimeEquals(hex("0102"), hex("010200")))
        assertTrue(CryptoBytes.constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun wipeZeroesEveryArray() {
        val a = hex("FFEE")
        val b = hex("01")
        CryptoBytes.wipe(a, null, b)
        assertContentEquals(ByteArray(2), a)
        assertContentEquals(ByteArray(1), b)
    }

    @Test
    fun paddingMethod2() {
        // Part 11, section 9.8.3: 80 then zeros; a full block of padding when the data fills a block.
        assertEquals("0102800000000000", CryptoBytes.pad(hex("0102"), 8).toHex())
        assertEquals("01020304050607088000000000000000", CryptoBytes.pad(hex("0102030405060708"), 8).toHex())
        assertNull(CryptoBytes.unpad(hex("0102000000000000")))
        assertNull(CryptoBytes.unpad(ByteArray(8)))
    }

    @Test
    fun padThenUnpadIsTheIdentity() {
        val random = RandomSource.seeded(9303)
        val arb = Arb.byteArray(Arb.int(0..40), Arb.byte())
        repeat(500) {
            val data = arb.next(random)
            assertContentEquals(data, CryptoBytes.unpad(CryptoBytes.pad(data, 16)))
        }
    }

    @Test
    fun unsignedIntegers() {
        assertEquals("000102", CryptoBytes.toFixedLength(hex("0102"), 3).toHex())
        assertEquals("0102", CryptoBytes.toFixedLength(hex("000102"), 2).toHex())
        assertFailsWith<ChipCryptoException> { CryptoBytes.toFixedLength(hex("010203"), 2) }
        assertEquals(0, CryptoBytes.compareUnsigned(hex("0001"), hex("01")))
        assertTrue(CryptoBytes.compareUnsigned(hex("FF"), hex("0100")) < 0)
        assertTrue(CryptoBytes.compareUnsigned(hex("0180"), hex("017F")) > 0)
        assertEquals("01000000", CryptoBytes.ofInt(0x01000000).toHex())
        assertTrue(CryptoBytes.isZero(CryptoBytes.ofInt(0)))
    }
}
