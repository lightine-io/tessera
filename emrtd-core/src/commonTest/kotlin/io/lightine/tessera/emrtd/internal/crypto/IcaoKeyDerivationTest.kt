package io.lightine.tessera.emrtd.internal.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The key derivation function (Doc 9303 Part 11, section 9.7.1) and the 3DES primitives of Basic
 * Access Control, against the worked examples of Part 11, Appendix D.
 */
class IcaoKeyDerivationTest {
    // Appendix D.1: Kseed.
    private val kSeed = hex("239AB9CB282DAF66231DC5A4DF6BFBAE")

    @Test
    fun appendixD1KeysFromKeySeed() =
        withCrypto { c ->
            // D.1 step 2: the SHA-1 hash of Kseed || c, before the cut and the parity adjustment.
            assertEquals(
                "AB94FCEDF2664EDFB9B291F85D7F77F27F2F4A9D",
                c.digest(HashAlgorithm.SHA1, kSeed + hex("00000001")).toHex(),
            )
            assertEquals(
                "7862D9ECE03C1BCD4D77089DCF131442814EA70A",
                c.digest(HashAlgorithm.SHA1, kSeed + hex("00000002")).toHex(),
            )
            // D.1 step 4: Ka || Kb after the parity adjustment.
            val kEnc = KeyDerivation.derive(c, kSeed, KeyDerivation.COUNTER_ENC, DerivedKeyType.DESEDE)
            val kMac = KeyDerivation.derive(c, kSeed, KeyDerivation.COUNTER_MAC, DerivedKeyType.DESEDE)
            assertEquals("AB94FDECF2674FDF" + "B9B391F85D7F76F2", kEnc.toHex())
            assertEquals("7962D9ECE03D1ACD" + "4C76089DCE131543", kMac.toHex())
        }

    @Test
    fun appendixD3SessionKeys() =
        withCrypto { c ->
            // D.3, chip side step 3-4: Kseed = KIFD xor KIC, then KSEnc and KSMAC.
            val seed = hex("0036D272F5C350ACAC50C3F572D23600")
            assertEquals(
                "979EC13B1CBFE9DCD01AB0FED307EAE5",
                KeyDerivation.derive(c, seed, KeyDerivation.COUNTER_ENC, DerivedKeyType.DESEDE).toHex(),
            )
            assertEquals(
                "F1CB1F1FB5ADF208806B89DC579DC1F8",
                KeyDerivation.derive(c, seed, KeyDerivation.COUNTER_MAC, DerivedKeyType.DESEDE).toHex(),
            )
        }

    @Test
    fun appendixD3TripleDesAndRetailMac() =
        withCrypto { c ->
            val kEnc = hex("AB94FDECF2674FDFB9B391F85D7F76F2")
            val kMac = hex("7962D9ECE03D1ACD4C76089DCE131543")
            // D.3, inspection system step 3-4: S encrypted with 3DES CBC, zero IV (section 9.8.6.1).
            val s = hex("781723860C06C2264608F919887022120B795240CB7049B01C19B33E32804F0B")
            val eIfd = c.encrypt(BlockCipher.DESEDE, CipherMode.CBC, kEnc, ByteArray(8), s)
            assertEquals("72C29C2371CC9BDB65B779B8E8D37B29ECC154AA56A8799FAE2F498F76ED92F2", eIfd.toHex())
            assertEquals(s.toHex(), c.decrypt(BlockCipher.DESEDE, CipherMode.CBC, kEnc, ByteArray(8), eIfd).toHex())
            // D.3 step 5: the retail MAC over EIFD, padded with ISO/IEC 9797-1 method 2.
            assertEquals("5F1448EEA8AD90A7", c.retailMac(kMac, CryptoBytes.pad(eIfd, 8)).toHex())
        }

    @Test
    fun aesKeysUseTheHashAndLengthOfSection9712() =
        withCrypto { c ->
            val secret = hex("0102030405060708")

            fun keydata(
                alg: HashAlgorithm,
                counter: String,
            ) = c.digest(alg, secret + hex(counter))
            assertEquals(
                keydata(HashAlgorithm.SHA1, "00000001").copyOf(16).toHex(),
                KeyDerivation.derive(c, secret, 1, DerivedKeyType.AES128).toHex(),
            )
            assertEquals(
                keydata(HashAlgorithm.SHA256, "00000002").copyOf(24).toHex(),
                KeyDerivation.derive(c, secret, 2, DerivedKeyType.AES192).toHex(),
            )
            assertEquals(
                keydata(HashAlgorithm.SHA256, "00000003").toHex(),
                KeyDerivation.derive(c, secret, KeyDerivation.COUNTER_PACE, DerivedKeyType.AES256).toHex(),
            )
        }

    @Test
    fun desParityAdjustmentGivesOddParity() {
        val key = ByteArray(256) { it.toByte() }
        KeyDerivation.adjustDesParity(key)
        for (i in key.indices) {
            assertEquals(1, (key[i].toInt() and 0xFF).countOneBits() % 2)
            assertEquals(i and 0xFE, key[i].toInt() and 0xFE)
        }
    }
}
