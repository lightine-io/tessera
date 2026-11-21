package io.lightine.tessera.emrtd.internal.sm

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * AES secure messaging (Doc 9303 Part 11, sections 9.8.7.1-9.8.7.3) has no ICAO worked example.
 * This test builds one protected command and one protected answer straight from the text, with the
 * JDK's AES (SunJCE) and BouncyCastle's CMAC, and checks the reader's codec against them: "checked
 * against an independent reference, not ICAO values".
 */
class AesSecureMessagingReferenceTest {
    private fun aes(
        mode: String,
        key: ByteArray,
        iv: ByteArray?,
        data: ByteArray,
        encrypt: Boolean = true,
    ): ByteArray {
        val c = Cipher.getInstance("AES/$mode/NoPadding")
        val op = if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (iv == null) c.init(op, SecretKeySpec(key, "AES")) else c.init(op, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    private fun cmac8(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val m = CMac(AESEngine.newInstance())
        m.init(KeyParameter(key))
        m.update(data, 0, data.size)
        val out = ByteArray(16)
        m.doFinal(out, 0)
        return out.copyOf(8)
    }

    private fun pad(d: ByteArray): ByteArray {
        val out = ByteArray((d.size / 16 + 1) * 16)
        d.copyInto(out)
        out[d.size] = 0x80.toByte()
        return out
    }

    private fun counter(n: Int) = ByteArray(16).also { it[15] = n.toByte() }

    @Test
    fun protectedSelectAndItsAnswerMatchAReferenceBuiltFromTheText() {
        for (keyLength in listOf(16, 24, 32)) {
            val rnd = Random(keyLength)
            val kEnc = rnd.nextBytes(keyLength)
            val kMac = rnd.nextBytes(keyLength)
            val cipher = mapOf(16 to SmCipher.AES128, 24 to SmCipher.AES192, 32 to SmCipher.AES256).getValue(keyLength)
            val sm = SecureMessaging(chipCrypto(), SessionKeys(cipher, kEnc, kMac), ByteArray(16))

            // Command: SELECT EF.COM with Le 4 (to have both DO'87' and DO'97'). SSC = 1.
            val plainData = byteArrayOf(0x01, 0x1E)
            val ssc1 = counter(1)
            val iv1 = aes("ECB", kEnc, null, ssc1)
            val do87 = byteArrayOf(0x87.toByte(), 0x11, 0x01) + aes("CBC", kEnc, iv1, pad(plainData))
            val do97 = byteArrayOf(0x97.toByte(), 0x01, 0x04)
            val header = pad(byteArrayOf(0x0C, 0xA4.toByte(), 0x02, 0x0C))
            val cc = cmac8(kMac, pad(ssc1 + header + do87 + do97))
            val expected =
                byteArrayOf(0x0C, 0xA4.toByte(), 0x02, 0x0C, 0x20) + do87 + do97 + byteArrayOf(0x8E.toByte(), 0x08) + cc + byteArrayOf(0x00)

            val protected = sm.protect(CommandApdu(0x00, 0xA4, 0x02, 0x0C, plainData, 4))
            assertContentEquals(expected, protected.encode(false), "command, AES-${keyLength * 8}")

            // Answer: 4 bytes of data and 9000. SSC = 2.
            val answerData = byteArrayOf(0x60, 0x14, 0x5F, 0x01)
            val ssc2 = counter(2)
            val iv2 = aes("ECB", kEnc, null, ssc2)
            val r87 = byteArrayOf(0x87.toByte(), 0x11, 0x01) + aes("CBC", kEnc, iv2, pad(answerData))
            val r99 = byteArrayOf(0x99.toByte(), 0x02, 0x90.toByte(), 0x00)
            val rcc = cmac8(kMac, pad(ssc2 + r87 + r99))
            val answer = r87 + r99 + byteArrayOf(0x8E.toByte(), 0x08) + rcc + byteArrayOf(0x90.toByte(), 0x00)

            val plain = sm.unprotect(ResponseApdu.parse(answer))
            assertContentEquals(answerData, plain.data)
            assertEquals(0x9000, plain.sw)
        }
    }
}
