package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

// The module's source sets already opt in to the experimental chip API (build.gradle.kts), so no
// opt-in annotation is needed on this file. Kotlin has no source set shared by the JVM and Android
// targets, so this file exists twice: here and in jvmMain. Keep the two copies identical.

@ExperimentalTesseraChipApi
public actual object TrustAnchors {
    public actual fun load(bytes: ByteArray): TrustAnchorLoad = readTrustAnchors(bytes)

    /**
     * Reads [file], a local file the app obtained itself (the SDK never downloads), and hands its
     * bytes to [load]. A file larger than 16 MiB is answered with
     * [TrustAnchorLoadProblem.TOO_LARGE] before it is read into memory. A path that starts with
     * `http://` or `https://` is answered with [TrustAnchorLoadProblem.NOT_A_LOCAL_FILE] and
     * nothing is fetched; a `java.io.File` keeps such a path with its double slash collapsed to
     * one, and that form counts too. Never throws.
     */
    public fun load(file: File): TrustAnchorLoad = load(file, MAX_BYTES)

    /**
     * Reads [stream] to its end and hands the bytes to [load]. The stream is not closed. With more
     * than 16 MiB in it the read stops after the cap and the answer is
     * [TrustAnchorLoadProblem.TOO_LARGE]. An I/O failure is answered with
     * [TrustAnchorLoadProblem.NOT_READABLE]. Never throws.
     */
    public fun load(stream: InputStream): TrustAnchorLoad = load(stream, MAX_BYTES)

    /** The same read as [load] with a different cap, so tests can exercise [TrustAnchorLoadProblem.TOO_LARGE]. */
    internal fun load(
        file: File,
        maxBytes: Long,
    ): TrustAnchorLoad {
        if (isWebAddress(file.path)) return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_A_LOCAL_FILE)
        if (!file.exists()) return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_FOUND)
        if (file.length() > maxBytes) return trustAnchorLoadFailed(TrustAnchorLoadProblem.TOO_LARGE)
        return try {
            readTrustAnchors(file.readBytes())
        } catch (e: IOException) {
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        }
    }

    /** The same read as [load] with a different cap, so tests can exercise [TrustAnchorLoadProblem.TOO_LARGE]. */
    internal fun load(
        stream: InputStream,
        maxBytes: Long,
    ): TrustAnchorLoad =
        try {
            val bytes = readAtMost(stream, maxBytes + 1)
            if (bytes.size > maxBytes) {
                trustAnchorLoadFailed(TrustAnchorLoadProblem.TOO_LARGE)
            } else {
                readTrustAnchors(bytes)
            }
        } catch (e: IOException) {
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        }
}

private fun readAtMost(
    stream: InputStream,
    limit: Long,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (total < limit) {
        val room = minOf(buffer.size.toLong(), limit - total).toInt()
        val n = stream.read(buffer, 0, room)
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}
