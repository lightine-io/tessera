package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.ResponseApdu
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.BlockCipher
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.CipherMode
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DerivedKeyType
import io.lightine.tessera.emrtd.internal.crypto.HashAlgorithm
import io.lightine.tessera.emrtd.internal.crypto.KeyDerivation
import io.lightine.tessera.emrtd.internal.sm.SmCipher

/**
 * The chip side of Basic Access Control (Doc 9303 Part 11, section 4.3.1 and Appendix D.3): GET
 * CHALLENGE and EXTERNAL AUTHENTICATE. On success it opens a session in [sm].
 *
 * [mrzInformation] is what the chip was personalized with: document number, date of birth and date
 * of expiry, each with its check digit. [random] gives RND.IC and K.IC.
 */
internal class FakeBac(
    mrzInformation: String,
    private val sm: FakeSm,
    private val crypto: ChipCrypto,
    private val random: (Int) -> ByteArray = crypto::randomBytes,
) : FakeCommandHandler {
    private val kEnc: ByteArray
    private val kMac: ByteArray
    private var rndIc: ByteArray? = null

    override fun powerOff() {
        rndIc = null
    }

    /** How many EXTERNAL AUTHENTICATE commands were refused. */
    var refused: Int = 0
        private set

    init {
        val seed = crypto.digest(HashAlgorithm.SHA1, mrzInformation.encodeToByteArray()).copyOf(16)
        kEnc = KeyDerivation.derive(crypto, seed, 1, DerivedKeyType.DESEDE)
        kMac = KeyDerivation.derive(crypto, seed, 2, DerivedKeyType.DESEDE)
    }

    override fun handle(
        chip: FakeChip,
        command: CommandApdu,
    ): ResponseApdu? {
        if (command.cla != 0x00) return null
        return when (command.ins) {
            0x84 -> challenge(command)
            0x82 -> authenticate(command)
            else -> null
        }
    }

    private fun challenge(c: CommandApdu): ResponseApdu {
        if (c.ne != 8) return status(StatusWord.WRONG_LENGTH)
        val nonce = random(8)
        rndIc = nonce
        return ResponseApdu(nonce, StatusWord.SUCCESS)
    }

    private fun authenticate(c: CommandApdu): ResponseApdu {
        val expected = rndIc ?: return status(StatusWord.CONDITIONS_OF_USE_NOT_SATISFIED)
        rndIc = null
        if (c.data.size != 40) return status(StatusWord.WRONG_LENGTH)
        val eIfd = c.data.copyOf(32)
        val mIfd = c.data.copyOfRange(32, 40)
        val iv = ByteArray(8)
        if (!crypto.retailMac(kMac, CryptoBytes.pad(eIfd, 8)).contentEquals(mIfd)) return refuse()
        val s = crypto.decrypt(BlockCipher.DESEDE, CipherMode.CBC, kEnc, iv, eIfd)
        if (!s.copyOfRange(8, 16).contentEquals(expected)) return refuse()
        val rndIfd = s.copyOf(8)
        val kIfd = s.copyOfRange(16, 32)
        val kIc = random(16)
        val eIc = crypto.encrypt(BlockCipher.DESEDE, CipherMode.CBC, kEnc, iv, expected + rndIfd + kIc)
        val mIc = crypto.retailMac(kMac, CryptoBytes.pad(eIc, 8))
        val seed = ByteArray(16) { (kIfd[it].toInt() xor kIc[it].toInt()).toByte() }
        sm.start(
            SmCipher.DESEDE,
            KeyDerivation.derive(crypto, seed, 1, DerivedKeyType.DESEDE),
            KeyDerivation.derive(crypto, seed, 2, DerivedKeyType.DESEDE),
            expected.copyOfRange(4, 8) + rndIfd.copyOfRange(4, 8),
        )
        return ResponseApdu(eIc + mIc, StatusWord.SUCCESS)
    }

    private fun refuse(): ResponseApdu {
        refused++
        return status(0x6300)
    }

    private fun status(sw: Int) = ResponseApdu(ByteArray(0), sw)
}
