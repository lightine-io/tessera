package io.lightine.tessera.emrtd.internal.securityinfo

import io.lightine.tessera.emrtd.internal.tlv.TlvParser

/**
 * A public key data object: the `7F49` template with an object identifier and context-specific
 * data objects in a fixed order (Doc 9303 Part 11, 9.4.2 to 9.4.4). Values are kept exactly as
 * encoded (unsigned big-endian). The object identifier says what the key is for; the shape of the
 * children says which kind of key it is.
 */
internal sealed interface PublicKeyDataObject {
    val oid: String

    /** RSA (Table 9): modulus `81`, exponent `82`. */
    class Rsa(
        override val oid: String,
        val modulus: ByteArray,
        val exponent: ByteArray,
    ) : PublicKeyDataObject

    /** DH (Table 10): prime `81`, subgroup order `82`, generator `83`, public value `84`. */
    class Dh(
        override val oid: String,
        val p: ByteArray,
        val q: ByteArray,
        val g: ByteArray,
        val y: ByteArray,
    ) : PublicKeyDataObject

    /**
     * ECDH (Table 11). [parameters] is `null` when the domain parameters are absent (a key for a
     * known curve); then only [publicPoint] (`86`) is present.
     */
    class Ec(
        override val oid: String,
        val parameters: DomainParameters.ExplicitEc?,
        val publicPoint: ByteArray,
    ) : PublicKeyDataObject
}

internal object PublicKeyDataObjectParser {
    const val TEMPLATE_TAG = 0x7F49
    private const val OID_TAG = 0x06

    /** Reads a complete `7F49` data object from [bytes]. */
    fun parse(bytes: ByteArray): PublicKeyDataObject =
        guardedParse {
            val template = TlvParser().parseSingle(bytes)
            if (template.tag != TEMPLATE_TAG) fail(SecurityInfoFailure.UNEXPECTED_STRUCTURE)
            val children = template.children()
            if (children.isEmpty() || children[0].tag != OID_TAG) fail(SecurityInfoFailure.UNEXPECTED_STRUCTURE, template.valueOffset)
            val oid = decodeOid(children[0].value())
            val tags = children.drop(1).map { it.tag }
            val values = children.drop(1).map { it.value() }
            if (values.any { it.isEmpty() }) fail(SecurityInfoFailure.INVALID_VALUE, template.valueOffset)
            when {
                tags == listOf(0x81, 0x82) -> {
                    PublicKeyDataObject.Rsa(oid, values[0], values[1])
                }

                tags == listOf(0x81, 0x82, 0x83, 0x84) -> {
                    PublicKeyDataObject.Dh(oid, values[0], values[1], values[2], values[3])
                }

                tags == listOf(0x86) -> {
                    PublicKeyDataObject.Ec(oid, null, values[0])
                }

                tags == EC_TAGS || tags == EC_TAGS + 0x87 -> {
                    val cofactor = if (tags.size == EC_TAGS.size + 1) values[6] else null
                    val explicit = DomainParameters.ExplicitEc(values[0], values[1], values[2], values[3], values[4], cofactor)
                    PublicKeyDataObject.Ec(oid, explicit, values[5])
                }

                // Includes partial EC domain parameters: all present (cofactor aside) or all absent.
                else -> {
                    fail(SecurityInfoFailure.UNEXPECTED_STRUCTURE, template.valueOffset)
                }
            }
        }

    private val EC_TAGS = listOf(0x81, 0x82, 0x83, 0x84, 0x85, 0x86)

    private fun decodeOid(value: ByteArray): String {
        // Reuse the DER reader's OID decoding by framing the value as an OBJECT IDENTIFIER element.
        if (value.isEmpty() || value.size > 127) fail(SecurityInfoFailure.INVALID_VALUE)
        val framed = byteArrayOf(0x06, value.size.toByte()) + value
        return guardedParse {
            io.lightine.tessera.emrtd.internal.asn1
                .DerReader(framed)
                .readOid()
        }
    }
}
