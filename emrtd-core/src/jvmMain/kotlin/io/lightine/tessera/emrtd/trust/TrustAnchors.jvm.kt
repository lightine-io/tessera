package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.jvm.JvmStatic

// The module's source sets already opt in to the experimental chip API (build.gradle.kts), so no
// opt-in annotation is needed on this file. Kotlin has no source set shared by the JVM and Android
// targets, so this file exists twice: here and in androidMain. Keep the two copies identical.

@ExperimentalTesseraChipApi
public actual object TrustAnchors {
    @JvmStatic
    public actual fun load(bytes: ByteArray): TrustAnchorLoad = readTrustAnchors(bytes)

    @JvmStatic
    public actual fun load(
        bytes: ByteArray,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad = readTrustAnchors(bytes, heldRoots)

    @JvmStatic
    public actual fun merge(vararg loads: TrustAnchorLoad): TrustAnchorLoad = mergeTrustAnchorLoads(loads.toList())

    /**
     * Reads [file], a local file the app obtained itself (the SDK never downloads), and hands its
     * bytes to [load]. A file larger than 16 MiB is answered with
     * [TrustAnchorLoadProblem.TOO_LARGE] before it is read into memory. A path that starts with
     * `http://` or `https://` is answered with [TrustAnchorLoadProblem.NOT_A_LOCAL_FILE] and
     * nothing is fetched; a `java.io.File` keeps such a path with its double slash collapsed to
     * one, and that form counts too. Never throws.
     */
    @JvmStatic
    public fun load(file: File): TrustAnchorLoad = load(file, MAX_BYTES, emptyList())

    /** As [load] on a file, with [heldRoots] for a master list's own checks; see the bytes form. */
    @JvmStatic
    public fun load(
        file: File,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad = load(file, MAX_BYTES, heldRoots)

    /**
     * Reads [stream] to its end and hands the bytes to [load]. The stream is not closed. With more
     * than 16 MiB in it the read stops after the cap and the answer is
     * [TrustAnchorLoadProblem.TOO_LARGE]. An I/O failure is answered with
     * [TrustAnchorLoadProblem.NOT_READABLE]. Never throws.
     */
    @JvmStatic
    public fun load(stream: InputStream): TrustAnchorLoad = load(stream, MAX_BYTES, emptyList())

    /** As [load] on a stream, with [heldRoots] for a master list's own checks; see the bytes form. */
    @JvmStatic
    public fun load(
        stream: InputStream,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad = load(stream, MAX_BYTES, heldRoots)

    /** The same read as [load] with a different cap, so tests can exercise [TrustAnchorLoadProblem.TOO_LARGE]. */
    internal fun load(
        file: File,
        maxBytes: Long,
        heldRoots: List<TrustAnchor> = emptyList(),
    ): TrustAnchorLoad {
        if (isWebAddress(file.path)) return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_A_LOCAL_FILE)
        return try {
            when {
                !file.exists() -> trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_FOUND)

                // A folder, a device or a pipe: its length says nothing, and reading it may not end.
                !file.isFile -> trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)

                file.length() > maxBytes -> trustAnchorLoadFailed(TrustAnchorLoadProblem.TOO_LARGE)

                // Read through the cap too: the file may have grown since its length was read.
                else -> file.inputStream().use { load(it, maxBytes, heldRoots) }
            }
        } catch (e: IOException) {
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        } catch (e: SecurityException) {
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        }
    }

    /** The same read as [load] with a different cap, so tests can exercise [TrustAnchorLoadProblem.TOO_LARGE]. */
    internal fun load(
        stream: InputStream,
        maxBytes: Long,
        heldRoots: List<TrustAnchor> = emptyList(),
    ): TrustAnchorLoad =
        try {
            val bytes = readAtMost(stream, maxBytes + 1)
            if (bytes.size > maxBytes) {
                trustAnchorLoadFailed(TrustAnchorLoadProblem.TOO_LARGE)
            } else {
                readTrustAnchors(bytes, heldRoots)
            }
        } catch (e: IOException) {
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        } catch (e: SecurityException) {
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
