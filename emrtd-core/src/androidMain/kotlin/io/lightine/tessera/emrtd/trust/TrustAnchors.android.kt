package io.lightine.tessera.emrtd.trust

import android.content.Context
import android.net.Uri
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

// The module's source sets already opt in to the experimental chip API (build.gradle.kts), so no
// opt-in annotation is needed on this file. Kotlin has no source set shared by the JVM and Android
// targets, so this file exists twice: here and in jvmMain. Keep the two copies identical, except for
// the content-address forms (`Context` and `Uri`), which only Android has.

@ExperimentalTesseraChipApi
public actual object TrustAnchors {
    public actual fun load(bytes: ByteArray): TrustAnchorLoad = readTrustAnchors(bytes)

    public actual fun load(
        bytes: ByteArray,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad = readTrustAnchors(bytes, heldRoots)

    public actual fun merge(vararg loads: TrustAnchorLoad): TrustAnchorLoad = mergeTrustAnchorLoads(loads.toList())

    /**
     * Reads [file], a local file the app obtained itself (the SDK never downloads), and hands its
     * bytes to [load]. A file larger than 16 MiB is answered with
     * [TrustAnchorLoadProblem.TOO_LARGE] before it is read into memory. A path that starts with
     * `http://` or `https://` is answered with [TrustAnchorLoadProblem.NOT_A_LOCAL_FILE] and
     * nothing is fetched; a `java.io.File` keeps such a path with its double slash collapsed to
     * one, and that form counts too. Never throws.
     */
    public fun load(file: File): TrustAnchorLoad = load(file, MAX_BYTES, emptyList())

    /** As [load] on a file, with [heldRoots] for a master list's own checks; see the bytes form. */
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
    public fun load(stream: InputStream): TrustAnchorLoad = load(stream, MAX_BYTES, emptyList())

    /** As [load] on a stream, with [heldRoots] for a master list's own checks; see the bytes form. */
    public fun load(
        stream: InputStream,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad = load(stream, MAX_BYTES, heldRoots)

    /**
     * Reads the root certificates behind [uri], the content address the Storage Access Framework
     * (the system file picker) gives your app, through the content resolver of [context]. The
     * stream is opened and closed here; the bytes go to [load]. Android only.
     *
     * Call it off the main thread: it reads the whole content. Nothing is fetched from the
     * network: an address that starts with `http://` or `https://` is answered with
     * [TrustAnchorLoadProblem.NOT_A_LOCAL_FILE] without being opened, and the app downloads the file
     * itself. Content larger than 16 MiB is answered with [TrustAnchorLoadProblem.TOO_LARGE].
     * Never throws: every failure is a typed [TrustAnchorLoad.problem].
     *
     * - A content address nobody answers for: [TrustAnchorLoadProblem.NOT_FOUND].
     * - A provider that gives no stream, an app with no permission for the address (a refused
     *   permission grant), or any other failure to read: [TrustAnchorLoadProblem.NOT_READABLE].
     *
     * @param context Any context of the app.
     * @param uri The content address of the file the person picked.
     * @param heldRoots Roots the app already holds, used only for a master list's own checks, as in
     *   [load] on bytes.
     * @since 1.0.0
     */
    public fun load(
        context: Context,
        uri: Uri,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad = load(context, uri, MAX_BYTES, heldRoots)

    /** As [load] on a content address, with no held roots. */
    public fun load(
        context: Context,
        uri: Uri,
    ): TrustAnchorLoad = load(context, uri, MAX_BYTES, emptyList())

    internal fun load(
        context: Context,
        uri: Uri,
        maxBytes: Long,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad {
        if (isWebAddress(uri.toString())) return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_A_LOCAL_FILE)
        return try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
            } else {
                stream.use { load(it, maxBytes, heldRoots) }
            }
        } catch (e: FileNotFoundException) {
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_FOUND)
        } catch (e: IOException) {
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        } catch (e: RuntimeException) {
            // A permission the app lacks (SecurityException), a provider that failed or died: the
            // platform's provider calls throw many kinds, and none of them is a certificate problem.
            trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_READABLE)
        }
    }

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
