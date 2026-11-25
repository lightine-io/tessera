package io.lightine.tessera.emrtd.trust

import io.lightine.tessera.emrtd.internal.asn1.Asn1Exception
import io.lightine.tessera.emrtd.internal.asn1.DerReader
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.io.encoding.Base64

/**
 * Reads root certificates (trust anchors) from content the app already has, in one call, so the app
 * does not need to know the file formats: what a master list is, what DER and PEM are, or which file
 * extension means what.
 *
 * [load] works the content out from the bytes alone, never from a file name, in this order: a CSCA
 * master list (CMS SignedData with the ICAO master list content type), one certificate in DER, PEM
 * text holding one or many certificates, then several certificates in DER one after another.
 * Nothing readable is thrown away when some items are unreadable:
 * [TrustAnchorLoad.anchors] holds every certificate that could be read and
 * [TrustAnchorLoad.unreadable] reports each item that could not, with its position.
 *
 * The SDK never downloads or bundles root certificates, and it does not decide whether a master
 * list or any root in it is to be trusted: that is the app's choice. Who signed a master list is
 * not verified by this helper: the list's own checks ride along in [TrustAnchorLoad.masterList] for
 * the app to read and judge, exactly as [MasterListReader] reports them.
 *
 * The file helpers ([load] on a `java.io.File` or `java.io.InputStream` on the JVM and Android,
 * [load] on a path on iOS) read a local file only. The SDK never downloads: a string that starts
 * with `http://` or `https://` is answered with [TrustAnchorLoadProblem.NOT_A_LOCAL_FILE] and
 * nothing is fetched, and the app downloads the file itself and passes the local path. A file
 * larger than 16 MiB is answered with [TrustAnchorLoadProblem.TOO_LARGE] before it is read into
 * memory; a real master list is about 1 MB.
 *
 * Usage (Kotlin):
 * ```kotlin
 * val loaded = TrustAnchors.load(bytes) // load(file) on the JVM and Android, load(path) on iOS
 * if (loaded.problem != null) {
 *     // record the problem and no anchors were read
 * }
 * val options = ChipReadOptions(accessKey, trustAnchors = loaded.anchors)
 * ```
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public expect object TrustAnchors {
    /**
     * Reads root certificates from [bytes]. The content is worked out from the bytes alone, never
     * from a file name. Never throws for bad input: every failure is a typed
     * [TrustAnchorLoad.problem].
     *
     * @return [TrustAnchorLoad] with what could be read, what could not, and what the content is.
     */
    public fun load(bytes: ByteArray): TrustAnchorLoad

    /**
     * Reads root certificates from [bytes], as [load] does, and checks a master list's own
     * signature against [heldRoots]: root certificates the app already holds (for example from an
     * earlier load). With none, that check reports that it did not run
     * ([TrustAnchorLoadKind.MASTER_LIST] only; other content has no signature to check).
     *
     * The roots in [heldRoots] are not added to [TrustAnchorLoad.anchors]: merge the loads you
     * want to trust with [TrustAnchorLoad.plus]. Who is to be trusted stays the app's choice.
     *
     * @param heldRoots Roots the app already holds, used only for the master list's own checks.
     * @return [TrustAnchorLoad] with what could be read, what could not, and what the content is.
     * @since 1.0.0
     */
    public fun load(
        bytes: ByteArray,
        heldRoots: List<TrustAnchor>,
    ): TrustAnchorLoad

    /**
     * Joins several loads into one: the anchors of all of them, without repeats (two anchors with
     * the same encoded bytes count once; the first is kept), in the order given.
     *
     * The result has kind [TrustAnchorLoadKind.CERTIFICATE] when it holds anchors, and
     * [TrustAnchorLoadKind.UNRECOGNIZED] with the first load's problem when it holds none; one load
     * alone is returned unchanged. It carries every load's
     * [TrustAnchorLoad.unreadable] entries, and no master list or problem of its own: read those on
     * the loads you passed. With no loads, the result is [TrustAnchorLoadProblem.NOT_RECOGNIZED].
     *
     * @since 1.0.0
     */
    public fun merge(vararg loads: TrustAnchorLoad): TrustAnchorLoad
}

/** The largest file the file helpers read into memory: 16 MiB. A real master list is about 1 MB. */
internal const val MAX_BYTES: Long = 16L * 1024L * 1024L

/**
 * The most PEM blocks or DER items one content may hold: 10 000. A real master list holds a few
 * hundred certificates; without a cap, 16 MiB of tiny items would take far more memory than the
 * content itself.
 */
internal const val MAX_ITEMS: Int = 10_000

/** Reads root certificates from content bytes: master list, one DER certificate, PEM, DER runs. */
internal fun readTrustAnchors(
    bytes: ByteArray,
    heldRoots: List<TrustAnchor> = emptyList(),
): TrustAnchorLoad {
    when (val list = MasterListReader.read(bytes, heldRoots)) {
        is MasterListRead.Read -> {
            return TrustAnchorLoad(
                deduplicated(list.masterList.anchors),
                TrustAnchorLoadKind.MASTER_LIST,
                emptyList(),
                list.masterList,
                null,
            )
        }

        is MasterListRead.Unreadable -> {}
    }
    when (val one = TrustAnchor.fromDer(bytes)) {
        is TrustAnchorParse.Parsed -> {
            return TrustAnchorLoad(listOf(one.anchor), TrustAnchorLoadKind.CERTIFICATE, emptyList(), null, null)
        }

        is TrustAnchorParse.Unreadable -> {}
    }
    val blocks = pemCertificateBlocks(bytes)
    if (blocks != null && blocks.size > MAX_ITEMS) return trustAnchorLoadFailed(TrustAnchorLoadProblem.TOO_LARGE)
    if (blocks != null) {
        val anchors = ArrayList<TrustAnchor>()
        val unreadable = ArrayList<UnreadableTrustAnchor>()
        blocks.forEachIndexed { position, candidate ->
            val der =
                try {
                    candidate?.let { Base64.decode(it) }
                } catch (e: IllegalArgumentException) {
                    null
                }
            if (der == null) {
                unreadable.add(UnreadableTrustAnchor(position, TrustAnchorProblem.MALFORMED_ENCODING, 0))
            } else {
                when (val p = TrustAnchor.fromDer(der)) {
                    is TrustAnchorParse.Parsed -> anchors.add(p.anchor)
                    is TrustAnchorParse.Unreadable -> unreadable.add(UnreadableTrustAnchor(position, p.problem, p.offset))
                }
            }
        }
        return TrustAnchorLoad(deduplicated(anchors), TrustAnchorLoadKind.PEM_CERTIFICATES, unreadable, null, null)
    }
    val elements =
        try {
            DerReader(bytes).readElements()
        } catch (e: Asn1Exception) {
            null
        }
    if (elements != null && elements.size > MAX_ITEMS) return trustAnchorLoadFailed(TrustAnchorLoadProblem.TOO_LARGE)
    if (elements != null) {
        val anchors = ArrayList<TrustAnchor>()
        val unreadable = ArrayList<UnreadableTrustAnchor>()
        elements.forEachIndexed { position, element ->
            when (val p = TrustAnchor.fromDer(element)) {
                is TrustAnchorParse.Parsed -> anchors.add(p.anchor)
                is TrustAnchorParse.Unreadable -> unreadable.add(UnreadableTrustAnchor(position, p.problem, p.offset))
            }
        }
        if (anchors.isNotEmpty()) {
            return TrustAnchorLoad(deduplicated(anchors), TrustAnchorLoadKind.CERTIFICATE, unreadable, null, null)
        }
    }
    return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_RECOGNIZED)
}

/** Joins [loads] without repeats; see [TrustAnchors.merge]. */
internal fun mergeTrustAnchorLoads(loads: List<TrustAnchorLoad>): TrustAnchorLoad {
    if (loads.isEmpty()) return trustAnchorLoadFailed(TrustAnchorLoadProblem.NOT_RECOGNIZED)
    if (loads.size == 1) return loads[0]
    val anchors = deduplicated(loads.flatMap { it.anchors })
    val unreadable = loads.flatMap { it.unreadable }
    if (anchors.isEmpty()) {
        val problem = loads.firstNotNullOfOrNull { it.problem } ?: TrustAnchorLoadProblem.NOT_RECOGNIZED
        return TrustAnchorLoad(emptyList(), TrustAnchorLoadKind.UNRECOGNIZED, unreadable, null, problem)
    }
    return TrustAnchorLoad(anchors, TrustAnchorLoadKind.CERTIFICATE, unreadable, null, null)
}

/** The answer when nothing could be loaded: no anchors, and [problem] saying why. */
internal fun trustAnchorLoadFailed(problem: TrustAnchorLoadProblem): TrustAnchorLoad =
    TrustAnchorLoad(emptyList(), TrustAnchorLoadKind.UNRECOGNIZED, emptyList(), null, problem)

/** True when [path] names a web address rather than a local file: the SDK never downloads. A
 * `java.io.File` stores a web address with its double slash collapsed to one, so both forms count. */
internal fun isWebAddress(path: String): Boolean =
    path.startsWith("http:/", ignoreCase = true) || path.startsWith("https:/", ignoreCase = true)

private fun DerReader.readElements(): List<ByteArray> {
    val elements = ArrayList<ByteArray>()
    // One past the cap is enough to tell the content holds too many.
    while (hasMore && elements.size <= MAX_ITEMS) elements.add(read().encoded())
    return elements
}

/**
 * One entry per `-----BEGIN CERTIFICATE-----` block in [bytes], or `null` when the bytes hold no
 * such marker. Each entry is the block's base64 body without whitespace, or `null` when the block
 * is unusable (its END marker is missing). Text around the blocks is ignored, and line endings do
 * not matter.
 */
private fun pemCertificateBlocks(bytes: ByteArray): List<ByteArray?>? {
    val begin = "-----BEGIN CERTIFICATE-----".encodeToByteArray()
    val end = "-----END CERTIFICATE-----".encodeToByteArray()
    val blocks = ArrayList<ByteArray?>()
    var from = 0
    while (true) {
        val start = indexOf(bytes, begin, from)
        if (start < 0) break
        val stop = indexOf(bytes, end, start + begin.size)
        if (stop < 0) {
            blocks.add(null)
            break
        }
        blocks.add(base64Body(bytes, start + begin.size, stop))
        // One past the cap is enough to tell the content holds too many.
        if (blocks.size > MAX_ITEMS) break
        from = stop + end.size
    }
    return if (blocks.isEmpty()) null else blocks
}

private fun base64Body(
    bytes: ByteArray,
    from: Int,
    to: Int,
): ByteArray {
    val out = ByteArray(to - from)
    var n = 0
    for (i in from until to) {
        if (!isPemWhitespace(bytes[i])) out[n++] = bytes[i]
    }
    return out.copyOf(n)
}

private fun isPemWhitespace(b: Byte): Boolean =
    b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\n'.code.toByte() ||
        b == '\r'.code.toByte() || b == 0x0B.toByte() || b == 0x0C.toByte()

private fun indexOf(
    haystack: ByteArray,
    needle: ByteArray,
    from: Int,
): Int {
    if (needle.isEmpty()) return maxOf(from, 0)
    outer@ for (i in maxOf(from, 0)..haystack.size - needle.size) {
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

private class DerBytes(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is DerBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/** [anchors] with repeats removed (by encoded bytes), the first of each kept, the order kept. */
private fun deduplicated(anchors: List<TrustAnchor>): List<TrustAnchor> {
    val seen = HashSet<DerBytes>()
    val out = ArrayList<TrustAnchor>()
    for (anchor in anchors) {
        if (seen.add(DerBytes(anchor.encodedBytes()))) out.add(anchor)
    }
    return out
}

/**
 * What [TrustAnchors.load] read: the anchors, what the content turned out to be, and what could not
 * be read. Never an exception: a failure is [TrustAnchorLoad.problem].
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class TrustAnchorLoad internal constructor(
    /** Every certificate that could be read, de-duplicated by its encoded bytes, in the order it was read. */
    public val anchors: List<TrustAnchor>,
    /** What the content turned out to be. [kind] may gain members in a later release: keep an `else` branch in a `when` over it. */
    public val kind: TrustAnchorLoadKind,
    /** One entry per certificate that could not be read, in order. A master list reports its own count instead: [masterList]. */
    public val unreadable: List<UnreadableTrustAnchor>,
    /** The master list and its signature checks when [kind] is [TrustAnchorLoadKind.MASTER_LIST], otherwise `null`. */
    public val masterList: MasterList?,
    /** Why nothing was loaded when [kind] is [TrustAnchorLoadKind.UNRECOGNIZED], otherwise `null`. */
    public val problem: TrustAnchorLoadProblem?,
) {
    /**
     * This load and [other] joined: see [TrustAnchors.merge].
     *
     * @since 1.0.0
     */
    public operator fun plus(other: TrustAnchorLoad): TrustAnchorLoad = mergeTrustAnchorLoads(listOf(this, other))

    /** The kind, the counts and the problem; never certificate content. */
    override fun toString(): String =
        "TrustAnchorLoad(kind=$kind, problem=$problem, anchors=${anchors.size}, unreadable=${unreadable.size})"
}

/**
 * What the content turned out to be.
 *
 * This list may gain members in a later release: keep an `else` branch in a `when` over it.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class TrustAnchorLoadKind {
    /** A CSCA master list: CMS SignedData holding certificates (Doc 9303 Part 12, 9). */
    MASTER_LIST,

    /** DER: one certificate, or several certificates one after another. */
    CERTIFICATE,

    /** PEM text: one or many `-----BEGIN CERTIFICATE-----` blocks, with any text around them. */
    PEM_CERTIFICATES,

    /** The content is none of the kinds this reader knows; also what a failed file read reports. */
    UNRECOGNIZED,
}

/**
 * Why [TrustAnchors.load] loaded nothing. Each value is a fact about the content or the file, never
 * a verdict on any certificate in it.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class TrustAnchorLoadProblem {
    /** The path names no file that exists. */
    NOT_FOUND,

    /** The file exists but its bytes could not be read: it may be a folder, or the app lacks permission. */
    NOT_READABLE,

    /**
     * The content is larger than the 16 MiB cap, so it was not read into memory, or it holds more
     * than 10 000 PEM blocks or DER items, far more than any master list.
     */
    TOO_LARGE,

    /** The bytes are none of the kinds this reader knows: not a master list, not a certificate, not PEM. */
    NOT_RECOGNIZED,

    /** The string names a web address, not a local file. The SDK never downloads; the app fetches the file itself. */
    NOT_A_LOCAL_FILE,
}

/**
 * One certificate that could not be read: where it sat, and the same problem
 * [TrustAnchor.fromDer] reports.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class UnreadableTrustAnchor internal constructor(
    /** The item's position in the content, counted from 0: the PEM block's number among the BEGIN markers, or the DER element's number. */
    public val position: Int,
    /** Why the certificate could not be read, as [TrustAnchor.fromDer] reports it. */
    public val problem: TrustAnchorProblem,
    /** The byte offset where the problem was found, counted from the item's first byte; 0 when the item itself could not be decoded. */
    public val offset: Int,
) {
    /** The position, the problem and the offset; never certificate content. */
    override fun toString(): String = "UnreadableTrustAnchor(position=$position, problem=$problem, offset=$offset)"
}
