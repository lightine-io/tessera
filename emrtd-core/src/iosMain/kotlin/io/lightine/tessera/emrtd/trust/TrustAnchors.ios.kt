package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.posix.memcpy

@ExperimentalTesseraChipApi
public actual object TrustAnchors {
    public actual fun load(bytes: ByteArray): TrustAnchorLoad = readTrustAnchors(bytes)

    /**
     * Reads the local file at [path], so a Swift caller can pass `url.path`. The SDK never
     * downloads: a string that starts with `http://` or `https://` is answered with
     * [TrustAnchorLoadProblem.NOT_A_LOCAL_FILE] and nothing is fetched, and the app downloads the
     * file itself and passes the local path. A file larger than 16 MiB is answered with
     * [TrustAnchorLoadProblem.TOO_LARGE] before it is read into memory; a real master list is about
     * 1 MB. Never throws.
     */
    public fun load(path: String): TrustAnchorLoad = load(path, MAX_BYTES)

    /** The same read as [load] with a different cap, so tests can exercise [TrustAnchorLoadProblem.TOO_LARGE]. */
    internal fun load(
        path: String,
        maxBytes: Long,
    ): TrustAnchorLoad {
        if (isWebAddress(path)) return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_A_LOCAL_FILE)
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_FOUND)
        val size = fileSizeBytes(manager, path) ?: return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        if (size > maxBytes) return trustAnchorLoadFailed(TrustAnchorLoadProblem.TOO_LARGE)
        val data = manager.contentsAtPath(path) ?: return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        return readTrustAnchors(data.toKotlinByteArray())
    }
}

/** The file's size in bytes, or `null` when its attributes could not be read. */
@OptIn(ExperimentalForeignApi::class)
private fun fileSizeBytes(
    manager: NSFileManager,
    path: String,
): Long? = (manager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longValue()

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toKotlinByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isEmpty()) return out
    val source = this.bytes ?: return out
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) }
    return out
}
