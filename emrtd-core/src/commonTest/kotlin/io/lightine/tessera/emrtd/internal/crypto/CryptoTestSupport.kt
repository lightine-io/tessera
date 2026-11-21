package io.lightine.tessera.emrtd.internal.crypto

/**
 * Runs [block] with this platform's [ChipCrypto]. Every platform has a backend (BouncyCastle on JVM
 * and Android, OpenSSL on iOS), so the vector tests run everywhere and never skip.
 */
internal inline fun withCrypto(block: (ChipCrypto) -> Unit) {
    block(chipCrypto())
}

internal fun hex(s: String): ByteArray = s.filter { !it.isWhitespace() }.hexToByteArray()

internal fun ByteArray.toHex(): String = toHexString(HexFormat.UpperCase)

/** DER `SEQUENCE { INTEGER r, INTEGER s }` for test signatures (short-form lengths only). */
internal fun derSignature(
    r: ByteArray,
    s: ByteArray,
): ByteArray {
    fun integer(v: ByteArray): ByteArray {
        var x = CryptoBytes.stripLeadingZeros(v)
        if (x.isEmpty() || x[0] < 0) x = byteArrayOf(0) + x
        return byteArrayOf(0x02, x.size.toByte()) + x
    }
    val body = integer(r) + integer(s)
    require(body.size < 0x80)
    return byteArrayOf(0x30, body.size.toByte()) + body
}

/** Standard parameters for [id] as EC parameters (fails the test if they are DH). */
internal fun ecParams(id: Int): EcDomainParameters = StandardDomainParameterValues.forId(id) as EcDomainParameters

/** Standard parameters for [id] as DH parameters. */
internal fun dhParams(id: Int): DhDomainParameters = StandardDomainParameterValues.forId(id) as DhDomainParameters
