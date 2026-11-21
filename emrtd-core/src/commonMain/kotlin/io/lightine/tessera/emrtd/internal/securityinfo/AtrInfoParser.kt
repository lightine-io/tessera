package io.lightine.tessera.emrtd.internal.securityinfo

import io.lightine.tessera.emrtd.internal.tlv.TlvParser

/**
 * The extended-length facts EF.ATR/INFO (FID 2F01, SFI 01) states (Doc 9303 Part 10, 3.11.1).
 *
 * [cardCapabilities] is tag `47`, three bytes, or `null` if absent. The flags below read its third
 * byte; they are `false` when the byte is absent. [maxCommandBytes] and [maxResponseBytes] come
 * from tag `7F66` and are `null` when it is absent. A reader uses extended length only when the
 * chip says it supports it (Part 11, 9.3.1.2); this class reports what the file says.
 */
internal class AtrInfo(
    val cardCapabilities: ByteArray?,
    val maxCommandBytes: Int?,
    val maxResponseBytes: Int?,
) {
    private fun thirdByteBit(mask: Int): Boolean = cardCapabilities?.let { (it[2].toInt() and mask) != 0 } ?: false

    /** Command chaining supported (byte 3, bit 8). */
    val commandChaining: Boolean get() = thirdByteBit(0x80)

    /** Extended Lc and Le fields supported (byte 3, bit 7). */
    val extendedLcLe: Boolean get() = thirdByteBit(0x40)

    /** Extended length information is present in the file (byte 3, bit 6). */
    val extendedLengthInfoInFile: Boolean get() = thirdByteBit(0x20)

    /** Short EF identifiers supported (byte 1, bit 3). */
    val shortEfIdentifiers: Boolean get() = cardCapabilities?.let { (it[0].toInt() and 0x04) != 0 } ?: false
}

internal object AtrInfoParser {
    private const val CAPABILITIES_TAG = 0x47
    private const val EXTENDED_LENGTH_TAG = 0x7F66
    private const val INTEGER_TAG = 0x02
    private const val CAPABILITIES_LENGTH = 3
    private const val MAX_INTEGER_BYTES = 4

    fun parse(bytes: ByteArray): AtrInfo =
        guardedParse {
            val objects = TlvParser().parseAll(bytes)
            val caps = objects.firstOrNull { it.tag == CAPABILITIES_TAG }?.value()
            if (caps != null && caps.size != CAPABILITIES_LENGTH) fail(SecurityInfoFailure.INVALID_VALUE)
            var maxCommand: Int? = null
            var maxResponse: Int? = null
            objects.firstOrNull { it.tag == EXTENDED_LENGTH_TAG }?.let { ext ->
                val sizes = ext.children().filter { it.tag == INTEGER_TAG }
                if (sizes.size < 2) fail(SecurityInfoFailure.UNEXPECTED_STRUCTURE, ext.offset)
                maxCommand = unsigned(sizes[0].value())
                maxResponse = unsigned(sizes[1].value())
            }
            AtrInfo(caps, maxCommand, maxResponse)
        }

    private fun unsigned(value: ByteArray): Int {
        if (value.isEmpty() || value.size > MAX_INTEGER_BYTES) fail(SecurityInfoFailure.INVALID_VALUE)
        var r = 0L
        value.forEach { r = (r shl 8) or (it.toLong() and 0xFF) }
        if (r > Int.MAX_VALUE) fail(SecurityInfoFailure.INVALID_VALUE)
        return r.toInt()
    }
}
