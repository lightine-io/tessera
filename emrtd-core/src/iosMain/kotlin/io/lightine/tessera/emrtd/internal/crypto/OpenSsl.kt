@file:OptIn(ExperimentalForeignApi::class)

package io.lightine.tessera.emrtd.internal.crypto

import io.lightine.tessera.emrtd.internal.openssl.BIGNUM
import io.lightine.tessera.emrtd.internal.openssl.BN_CTX
import io.lightine.tessera.emrtd.internal.openssl.BN_CTX_free
import io.lightine.tessera.emrtd.internal.openssl.BN_CTX_new
import io.lightine.tessera.emrtd.internal.openssl.BN_bin2bn
import io.lightine.tessera.emrtd.internal.openssl.BN_bn2binpad
import io.lightine.tessera.emrtd.internal.openssl.BN_clear_free
import io.lightine.tessera.emrtd.internal.openssl.BN_is_odd
import io.lightine.tessera.emrtd.internal.openssl.BN_new
import io.lightine.tessera.emrtd.internal.openssl.EC_GROUP
import io.lightine.tessera.emrtd.internal.openssl.EC_GROUP_free
import io.lightine.tessera.emrtd.internal.openssl.EC_GROUP_new_curve_GFp
import io.lightine.tessera.emrtd.internal.openssl.EC_GROUP_set_generator
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_clear_free
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_get_affine_coordinates
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_is_at_infinity
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_is_on_curve
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_new
import io.lightine.tessera.emrtd.internal.openssl.EC_POINT_set_affine_coordinates
import io.lightine.tessera.emrtd.internal.openssl.ERR_clear_error
import io.lightine.tessera.emrtd.internal.openssl.EVP_MD
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_free
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_CTX_new_from_name
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_free
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_fromdata
import io.lightine.tessera.emrtd.internal.openssl.EVP_PKEY_fromdata_init
import io.lightine.tessera.emrtd.internal.openssl.EVP_sha1
import io.lightine.tessera.emrtd.internal.openssl.EVP_sha224
import io.lightine.tessera.emrtd.internal.openssl.EVP_sha256
import io.lightine.tessera.emrtd.internal.openssl.EVP_sha384
import io.lightine.tessera.emrtd.internal.openssl.EVP_sha512
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_BLD
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_BLD_free
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_BLD_new
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_BLD_push_BN
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_BLD_push_octet_string
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_BLD_push_utf8_string
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_BLD_to_param
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PARAM_free
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_EC_A
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_EC_B
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_EC_COFACTOR
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_EC_FIELD_TYPE
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_EC_GENERATOR
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_EC_ORDER
import io.lightine.tessera.emrtd.internal.openssl.OSSL_PKEY_PARAM_EC_P
import io.lightine.tessera.emrtd.internal.openssl.SN_X9_62_prime_field
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

// Shared OpenSSL plumbing for the iOS backend (OpenSslChipCrypto) and the iOS test signer: an
// object scope that frees what it made, BIGNUM and EC point conversion, and key import. Parameter
// names come from OpenSSL's own headers (core_names.h, obj_mac.h) through cinterop, never typed
// out as strings here.

/** Runs [block] in a fresh [Ossl] scope; everything made in it is freed, and OpenSSL's error queue cleared, at the end. */
internal inline fun <T> ossl(block: Ossl.() -> T): T {
    val scope = Ossl()
    try {
        return scope.block()
    } finally {
        scope.close()
        ERR_clear_error()
    }
}

/** Owns every OpenSSL object made in one call and frees them (secrets cleared) when closed. */
internal class Ossl {
    private val bns = mutableListOf<CPointer<BIGNUM>>()
    private val points = mutableListOf<CPointer<EC_POINT>>()
    private val groups = mutableListOf<CPointer<EC_GROUP>>()
    private val pkeys = mutableListOf<CPointer<EVP_PKEY>>()
    private var bnCtx: CPointer<BN_CTX>? = null

    val ctx: CPointer<BN_CTX>
        get() = bnCtx ?: (BN_CTX_new() ?: osslFail("no BN context")).also { bnCtx = it }

    fun bn(bytes: ByteArray): CPointer<BIGNUM> = (bytes.ptr { BN_bin2bn(it, bytes.size, null) } ?: osslFail("no BIGNUM")).also { bns += it }

    fun newBn(): CPointer<BIGNUM> = (BN_new() ?: osslFail("no BIGNUM")).also { bns += it }

    /** Takes ownership of [bn], made by OpenSSL elsewhere, so the scope frees it. */
    fun ownBn(bn: CPointer<BIGNUM>): CPointer<BIGNUM> = bn.also { bns += it }

    /** [bn] as exactly [length] big-endian bytes; fails if it does not fit. */
    fun bytes(
        bn: CPointer<BIGNUM>,
        length: Int,
    ): ByteArray {
        val out = ByteArray(length)
        if (length == 0) return out
        if (out.ptr { BN_bn2binpad(bn, it, length) } != length) osslFail("value does not fit")
        return out
    }

    /** [curve] as an OpenSSL group, with its generator, order and cofactor unless [withGenerator] is false. */
    fun group(
        curve: EcDomainParameters,
        withGenerator: Boolean = true,
    ): CPointer<EC_GROUP> {
        // The shared size policy (KeyLimits), as the BouncyCastle backend applies it.
        if (KeyLimits.bitLength(curve.p) > KeyLimits.MAX_EC_FIELD_BITS) throw ChipCryptoException("curve field too large")
        val p = bn(curve.p)
        if (BN_is_odd(p) != 1) throw ChipCryptoException("curve prime is not a positive odd number")
        val g = (EC_GROUP_new_curve_GFp(p, bn(curve.a), bn(curve.b), ctx) ?: osslFail("invalid curve")).also { groups += it }
        if (withGenerator) {
            val gen = point(g, curve, curve.generator)
            osslOk(EC_GROUP_set_generator(g, gen, bn(curve.n), bn(curve.cofactor)), "invalid generator")
        }
        return g
    }

    fun newPoint(g: CPointer<EC_GROUP>): CPointer<EC_POINT> = (EC_POINT_new(g) ?: osslFail("no point")).also { points += it }

    /** [point] as an OpenSSL point; throws if it is not on [curve]. */
    fun point(
        g: CPointer<EC_GROUP>,
        curve: EcDomainParameters,
        point: EcPoint,
    ): CPointer<EC_POINT> {
        if (CryptoBytes.compareUnsigned(point.x, curve.p) >= 0 || CryptoBytes.compareUnsigned(point.y, curve.p) >= 0) {
            throw ChipCryptoException("point is not on the curve")
        }
        val pt = newPoint(g)
        if (EC_POINT_set_affine_coordinates(g, pt, bn(point.x), bn(point.y), ctx) != 1 ||
            EC_POINT_is_on_curve(g, pt, ctx) != 1
        ) {
            throw ChipCryptoException("point is not on the curve")
        }
        return pt
    }

    /** [pt] in affine coordinates at the field length, or null for the point at infinity. */
    fun fromPoint(
        g: CPointer<EC_GROUP>,
        curve: EcDomainParameters,
        pt: CPointer<EC_POINT>,
    ): EcPoint? {
        if (EC_POINT_is_at_infinity(g, pt) == 1) return null
        val x = newBn()
        val y = newBn()
        osslOk(EC_POINT_get_affine_coordinates(g, pt, x, y, ctx), "no affine coordinates")
        return EcPoint(bytes(x, curve.fieldLength), bytes(y, curve.fieldLength))
    }

    /** Pushes [curve] as explicit prime-field parameters (no named curve: chips use custom domains). */
    fun MemScope.pushEcDomain(
        b: CPointer<OSSL_PARAM_BLD>,
        curve: EcDomainParameters,
    ) {
        val gen = EcPointCodec.encode(curve, curve.generator)
        osslOk(OSSL_PARAM_BLD_push_utf8_string(b, c(OSSL_PKEY_PARAM_EC_FIELD_TYPE), c(SN_X9_62_prime_field), 0u), "key build failed")
        osslOk(OSSL_PARAM_BLD_push_BN(b, c(OSSL_PKEY_PARAM_EC_P), bn(curve.p)), "key build failed")
        osslOk(OSSL_PARAM_BLD_push_BN(b, c(OSSL_PKEY_PARAM_EC_A), bn(curve.a)), "key build failed")
        osslOk(OSSL_PARAM_BLD_push_BN(b, c(OSSL_PKEY_PARAM_EC_B), bn(curve.b)), "key build failed")
        pushOctets(b, OSSL_PKEY_PARAM_EC_GENERATOR, gen)
        osslOk(OSSL_PARAM_BLD_push_BN(b, c(OSSL_PKEY_PARAM_EC_ORDER), bn(curve.n)), "key build failed")
        osslOk(OSSL_PARAM_BLD_push_BN(b, c(OSSL_PKEY_PARAM_EC_COFACTOR), bn(curve.cofactor)), "key build failed")
    }

    fun MemScope.pushBn(
        b: CPointer<OSSL_PARAM_BLD>,
        name: String,
        value: ByteArray,
    ) = osslOk(OSSL_PARAM_BLD_push_BN(b, c(name), bn(value)), "key build failed")

    fun MemScope.pushOctets(
        b: CPointer<OSSL_PARAM_BLD>,
        name: String,
        value: ByteArray,
    ) = value.ptr { osslOk(OSSL_PARAM_BLD_push_octet_string(b, c(name), it, value.size.convert()), "key build failed") }

    /**
     * A key of [type] ("RSA", "DSA", "EC") imported from the parameters [push] adds, with
     * [selection] (EVP_PKEY_PUBLIC_KEY or EVP_PKEY_KEYPAIR). Freed with the scope.
     */
    fun fromData(
        type: String,
        selection: Int,
        push: MemScope.(CPointer<OSSL_PARAM_BLD>) -> Unit,
    ): CPointer<EVP_PKEY> {
        // The builder and the OSSL_PARAM array it makes keep pointers to the key names and string
        // values, never copies, so the C strings live in one arena that outlasts the import
        // (memScoped around the whole call).
        return memScoped {
            val bld = OSSL_PARAM_BLD_new() ?: osslFail("no param builder")
            var pctx: CPointer<EVP_PKEY_CTX>? = null
            try {
                push(bld)
                val params = OSSL_PARAM_BLD_to_param(bld) ?: osslFail("key build failed")
                try {
                    pctx = EVP_PKEY_CTX_new_from_name(null, type, null) ?: osslFail("no key context")
                    osslOk(EVP_PKEY_fromdata_init(pctx), "key build failed")
                    val out = alloc<CPointerVar<EVP_PKEY>>()
                    osslOk(EVP_PKEY_fromdata(pctx, out.ptr, selection, params), "key does not fit the signature scheme")
                    ownKey(out.value ?: osslFail("key build failed"))
                } finally {
                    OSSL_PARAM_free(params)
                }
            } finally {
                EVP_PKEY_CTX_free(pctx)
                OSSL_PARAM_BLD_free(bld)
            }
        }
    }

    /** Takes ownership of [key] so the scope frees it. */
    fun ownKey(key: CPointer<EVP_PKEY>): CPointer<EVP_PKEY> = key.also { pkeys += it }

    fun close() {
        pkeys.forEach { EVP_PKEY_free(it) }
        points.forEach { EC_POINT_clear_free(it) }
        groups.forEach { EC_GROUP_free(it) }
        bns.forEach { BN_clear_free(it) }
        bnCtx?.let { BN_CTX_free(it) }
    }
}

internal fun osslMd(algorithm: HashAlgorithm): CPointer<EVP_MD>? =
    when (algorithm) {
        HashAlgorithm.SHA1 -> EVP_sha1()
        HashAlgorithm.SHA224 -> EVP_sha224()
        HashAlgorithm.SHA256 -> EVP_sha256()
        HashAlgorithm.SHA384 -> EVP_sha384()
        HashAlgorithm.SHA512 -> EVP_sha512()
    }

/** Fails with [message] unless OpenSSL returned 1 (its success value). */
internal fun osslOk(
    result: Int,
    message: String,
) {
    if (result != 1) osslFail(message)
}

/** Clears OpenSSL's error queue (its text never reaches the caller) and throws [ChipCryptoException]. */
internal fun osslFail(message: String): Nothing {
    ERR_clear_error()
    throw ChipCryptoException(message)
}

/** A C copy of [s] that lives as long as this scope. */
internal fun MemScope.c(s: String): CPointer<ByteVar> = s.cstr.getPointer(this)

/** Runs [block] with a pointer to this array's bytes (a valid pointer even when it is empty). */
internal inline fun <R> ByteArray.ptr(block: (CPointer<UByteVar>) -> R): R {
    val target = if (isEmpty()) ByteArray(1) else this
    return target.usePinned { block(it.addressOf(0).reinterpret()) }
}
