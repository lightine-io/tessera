package io.lightine.tessera.emrtd.internal.crypto

/**
 * Byte helpers the protocols share: constant-time compare, wiping, ISO/IEC 9797-1 padding method 2,
 * and unsigned big-endian integer handling. Pure Kotlin, so every platform gets the same behaviour.
 */
internal object CryptoBytes {
    /**
     * Whether [a] and [b] hold the same bytes, in time that depends only on their lengths (for MACs
     * and authentication tokens). Lengths are not secret: different lengths return `false` at once.
     */
    fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** Overwrites [bytes] with zeros (keys, seeds and secrets once used). */
    fun wipe(vararg bytes: ByteArray?) {
        for (b in bytes) b?.fill(0)
    }

    /**
     * ISO/IEC 9797-1 padding method 2 (Doc 9303 Part 11, section 9.8.3): a mandatory 0x80 byte, then
     * zeros up to a multiple of [blockSize].
     */
    fun pad(
        data: ByteArray,
        blockSize: Int,
    ): ByteArray {
        require(blockSize > 0) { "block size must be positive" }
        val padded = ByteArray((data.size / blockSize + 1) * blockSize)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        return padded
    }

    /** Removes [pad]'s padding; null when [data] does not end in a valid padding. */
    fun unpad(data: ByteArray): ByteArray? {
        var i = data.size - 1
        while (i >= 0 && data[i] == 0.toByte()) i--
        if (i < 0 || data[i] != 0x80.toByte()) return null
        return data.copyOf(i)
    }

    /** [value] without leading zero bytes (an empty array for zero). */
    fun stripLeadingZeros(value: ByteArray): ByteArray {
        var i = 0
        while (i < value.size && value[i] == 0.toByte()) i++
        return if (i == 0) value.copyOf() else value.copyOfRange(i, value.size)
    }

    /**
     * [value] as an unsigned big-endian number of exactly [length] bytes: left-padded with zeros, or
     * with surplus leading zeros removed. Throws [ChipCryptoException] when the number needs more
     * than [length] bytes.
     */
    fun toFixedLength(
        value: ByteArray,
        length: Int,
    ): ByteArray {
        val stripped = stripLeadingZeros(value)
        if (stripped.size > length) throw ChipCryptoException("integer longer than $length bytes")
        val out = ByteArray(length)
        stripped.copyInto(out, length - stripped.size)
        return out
    }

    /** Compares two unsigned big-endian numbers: negative, zero or positive like [Comparable]. */
    fun compareUnsigned(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        val x = stripLeadingZeros(a)
        val y = stripLeadingZeros(b)
        if (x.size != y.size) return x.size - y.size
        for (i in x.indices) {
            val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    /** Whether two unsigned big-endian numbers are equal (leading zeros ignored). */
    fun unsignedEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = compareUnsigned(a, b) == 0

    /** Whether the unsigned number [value] is zero. */
    fun isZero(value: ByteArray): Boolean = value.all { it == 0.toByte() }

    /** The unsigned big-endian bytes of a small non-negative [value]. */
    fun ofInt(value: Int): ByteArray {
        require(value >= 0) { "negative value" }
        return stripLeadingZeros(
            byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte()),
        )
    }
}
