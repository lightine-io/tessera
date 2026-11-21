package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Hashes, AES and CMAC against published vectors. */
class PrimitiveVectorsTest {
    // RFC 6234, section 8.5: TEST1 = "abc", TEST2_1 (448 bits) and TEST2_2 (896 bits).
    private val test1 = "abc"
    private val test21 = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
    private val test22 =
        "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmn" +
            "hijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"

    @Test
    fun hashesMatchRfc6234() =
        withCrypto { c ->
            fun h(
                alg: HashAlgorithm,
                msg: String,
            ) = c.digest(alg, msg.encodeToByteArray()).toHex()
            assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", h(HashAlgorithm.SHA1, test1))
            assertEquals("84983E441C3BD26EBAAE4AA1F95129E5E54670F1", h(HashAlgorithm.SHA1, test21))
            assertEquals("23097D223405D8228642A477BDA255B32AADBCE4BDA0B3F7E36C9DA7", h(HashAlgorithm.SHA224, test1))
            assertEquals("75388B16512776CC5DBA5DA1FD890150B0C6455CB4F58B1952522525", h(HashAlgorithm.SHA224, test21))
            assertEquals(
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
                h(HashAlgorithm.SHA256, test1),
            )
            assertEquals(
                "248D6A61D20638B8E5C026930C3E6039A33CE45964FF2167F6ECEDD419DB06C1",
                h(HashAlgorithm.SHA256, test21),
            )
            assertEquals(
                "CB00753F45A35E8BB5A03D699AC65007272C32AB0EDED1631A8B605A43FF5BED8086072BA1E7CC2358BAECA134C825A7",
                h(HashAlgorithm.SHA384, test1),
            )
            assertEquals(
                "09330C33F71147E83D192FC782CD1B4753111B173B3B05D22FA08086E3B0F712FCC7C71A557E2DB966C3E9FA91746039",
                h(HashAlgorithm.SHA384, test22),
            )
            assertEquals(
                "DDAF35A193617ABACC417349AE20413112E6FA4E89A97EA20A9EEEE64B55D39A2192992A274FC1A836BA3C23A3FEEBBD" +
                    "454D4423643CE80E2A9AC94FA54CA49F",
                h(HashAlgorithm.SHA512, test1),
            )
            assertEquals(
                "8E959B75DAE313DA8CF4F72814FC143F8F7779C6EB9F7FA17299AEADB6889018501D289E4900F7E4331B99DEC4B5433A" +
                    "C7D329EEB6DD26545E96E55B874BE909",
                h(HashAlgorithm.SHA512, test22),
            )
            HashAlgorithm.entries.forEach { assertEquals(it.outputLength, c.digest(it, ByteArray(0)).size) }
        }

    @Test
    fun aesMatchesSp80038a() =
        withCrypto { c ->
            val vectors =
                with(CryptoVectors.Aes) {
                    listOf(ECB_AES128, ECB_AES192, ECB_AES256, CBC_AES128, CBC_AES192, CBC_AES256)
                }
            for (v in vectors) {
                val mode = if (v.iv == null) CipherMode.ECB else CipherMode.CBC
                val iv = v.iv?.let(::hex)
                assertEquals(v.ciphertext, c.encrypt(BlockCipher.AES, mode, hex(v.key), iv, hex(v.plaintext)).toHex())
                assertEquals(v.plaintext, c.decrypt(BlockCipher.AES, mode, hex(v.key), iv, hex(v.ciphertext)).toHex())
            }
        }

    @Test
    fun cmacMatchesRfc4493AndNistExamples() =
        withCrypto { c ->
            val key128 = hex("2b7e151628aed2a6abf7158809cf4f3c")
            val m =
                hex(
                    "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                        "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
                )
            // RFC 4493, section 4: AES-128(key, 0), then examples 1-4 (lengths 0, 16, 40, 64).
            assertEquals(
                "7DF76B0C1AB899B33E42F047B91B546F",
                c.encrypt(BlockCipher.AES, CipherMode.ECB, key128, null, ByteArray(16)).toHex(),
            )
            assertEquals("BB1D6929E95937287FA37D129B756746", c.cmac(key128, m.copyOf(0)).toHex())
            assertEquals("070A16B46B4D4144F79BDD9DD04A287C", c.cmac(key128, m.copyOf(16)).toHex())
            assertEquals("DFA66747DE9AE63030CA32611497C827", c.cmac(key128, m.copyOf(40)).toHex())
            assertEquals("51F0BEBF7E3B9D92FC49741779363CFE", c.cmac(key128, m).toHex())
            // NIST CSRC "AES_CMAC" examples (SP 800-38B), CMAC-AES256 examples 1-4.
            val key256 = hex("603DEB1015CA71BE2B73AEF0857D77811F352C073B6108D72D9810A30914DFF4")
            assertEquals("028962F61B7BF89EFC6B551F4667D983", c.cmac(key256, m.copyOf(0)).toHex())
            assertEquals("28A7023F452E8F82BD4BF28D8C37C35C", c.cmac(key256, m.copyOf(16)).toHex())
            assertEquals("156727DC0878944A023C1FE03BAD6D93", c.cmac(key256, m.copyOf(20)).toHex())
            assertEquals("E1992190549F6ED5696A2C056C315410", c.cmac(key256, m).toHex())
        }

    @Test
    fun invalidCipherInputThrowsTypedError() =
        withCrypto { c ->
            val key = ByteArray(16)
            assertFailsWith<ChipCryptoException> { c.encrypt(BlockCipher.AES, CipherMode.ECB, key, null, ByteArray(15)) }
            assertFailsWith<ChipCryptoException> { c.encrypt(BlockCipher.AES, CipherMode.ECB, ByteArray(15), null, ByteArray(16)) }
            assertFailsWith<ChipCryptoException> { c.encrypt(BlockCipher.AES, CipherMode.ECB, key, ByteArray(16), ByteArray(16)) }
            assertFailsWith<ChipCryptoException> { c.encrypt(BlockCipher.AES, CipherMode.CBC, key, null, ByteArray(16)) }
            assertFailsWith<ChipCryptoException> { c.encrypt(BlockCipher.AES, CipherMode.CBC, key, ByteArray(8), ByteArray(16)) }
            assertFailsWith<ChipCryptoException> { c.encrypt(BlockCipher.DESEDE, CipherMode.ECB, ByteArray(8), null, ByteArray(8)) }
            assertFailsWith<ChipCryptoException> { c.retailMac(ByteArray(8), ByteArray(8)) }
            assertFailsWith<ChipCryptoException> { c.retailMac(key, ByteArray(7)) }
            assertFailsWith<ChipCryptoException> { c.cmac(ByteArray(10), ByteArray(0)) }
            assertFailsWith<ChipCryptoException> { c.randomBytes(-1) }
        }

    @Test
    fun randomBytesHaveTheLengthAskedFor() =
        withCrypto { c ->
            assertEquals(0, c.randomBytes(0).size)
            assertEquals(32, c.randomBytes(32).size)
        }
}
