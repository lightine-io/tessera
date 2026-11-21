package io.lightine.tessera.emrtd.internal.aa

import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.hex
import io.lightine.tessera.emrtd.internal.crypto.toHex
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The worked example of ICAO Doc 9303 Part 11, Appendix F (RSA, 1024-bit modulus, SHA-1), byte for
 * byte, the hex copied from the document.
 *
 * The appendix prints no key pair - it names the mechanism, modulus length and hash only - so the
 * signature S of its step 10 cannot be run through the RSA operation by any reader, here or on a
 * device. Every printed value a reader can check is checked: the command of step 2, the message and
 * its digest of steps 7 and 8, and the recovery of the message representative F of step 9.2
 * (steps 13 to 18). The RSA operation itself runs on a synthetic key in
 * [ActiveAuthenticationFakeChipTest].
 */
class ActiveAuthenticationAppendixFTest {
    // Step 1: the reader's nonce RND.IFD.
    private val rndIfd = "F173589974BF40C6"

    // Step 6: the chip's recoverable nonce M1, 848 bits (c = 852, LM1 = c - 4).
    private val m1 =
        "9D2784A67F8E7C659973EA1AEA25D95B" +
            "6C8F91E5002F369F0FBDCE8A3CEC1991" +
            "B543F1696546C5524CF23A5303CD6C98" +
            "599F40B79F377B5F3A1406B3B4D8F967" +
            "84D23AA88DB7E1032A405E69325FA91A" +
            "6E86F5C71AEA978264C4A207446DAD4E" +
            "7292E2DCDA3024B47DA8"

    // Step 8 (and step 17): H = SHA-1(M) = D* = SHA-1(M*), M and M* being the same bytes here.
    private val digest = "C063AA1E6D22FBD976AB0FE73D94D2D9C6D88127"

    // Step 9.2: F = '6A' | M1 | H | T, as printed.
    private val f =
        "6A9D2784A67F8E7C659973EA1AEA25D9" +
            "5B6C8F91E5002F369F0FBDCE8A3CEC19" +
            "91B543F1696546C5524CF23A5303CD6C" +
            "98599F40B79F377B5F3A1406B3B4D8F9" +
            "6784D23AA88DB7E1032A405E69325FA9" +
            "1A6E86F5C71AEA978264C4A207446DAD" +
            "4E7292E2DCDA3024B47DA8C063AA1E6D" +
            "22FBD976AB0FE73D94D2D9C6D88127BC"

    @Test
    fun step2TheCommandIsBuiltByteForByte() {
        // Command APDU: CLA 00, INS 88, P1 P2 00 00, Lc 08, data RND.IFD, Le 00.
        assertEquals("0088000008F173589974BF40C600", ActiveAuthentication.command(hex(rndIfd)).encode(false).toHex())
    }

    @Test
    fun steps7And8TheMessageDigestMatches() =
        withCrypto { c ->
            assertEquals(digest, c.digest(HashAlgorithm.SHA1, hex(m1) + hex(rndIfd)).toHex())
        }

    @Test
    fun steps12To18TheRepresentativeRecoversAndVerifies() =
        withCrypto { c ->
            // The printed F is the header, the printed M1, the printed digest and the trailer `BC`.
            assertEquals(f, ("6A" + m1 + digest + "BC"))

            // Step 13: the trailer names the hash.
            val recovered = assertNotNull(RsaMessageRecovery.parse(hex(f)))
            assertEquals(HashAlgorithm.SHA1, recovered.hash)
            // Step 15: the recoverable part M1 comes back out.
            assertEquals(m1, recovered.recoverablePart.toHex())
            // Step 14: the digest comes back out.
            assertEquals(digest, recovered.digest.toHex())
            // Steps 16 to 18: M* = M1 | RND.IFD, and D* equals D.
            assertTrue(RsaMessageRecovery.matches(c, recovered, hex(rndIfd)))

            // A recovered message changed in M1 no longer matches its digest.
            val tampered = hex(f).also { it[5] = (it[5].toInt() xor 1).toByte() }
            val tamperedRecovery = assertNotNull(RsaMessageRecovery.parse(tampered))
            assertFalse(RsaMessageRecovery.matches(c, tamperedRecovery, hex(rndIfd)))
        }

    @Test
    fun aTrailerThatNamesNoHashIsNotRead() {
        // Section 6.1.2.2 supports SHA-1 and the four SHA-2 hashes only: a one-byte trailer that is
        // not `BC`, and a two-byte one whose identifier is none of the table's, are not read.
        assertNull(RsaMessageRecovery.parse(hex(f.dropLast(2) + "BD")))
        assertNull(RsaMessageRecovery.parse(hex(f + "37CC")))
    }
}
