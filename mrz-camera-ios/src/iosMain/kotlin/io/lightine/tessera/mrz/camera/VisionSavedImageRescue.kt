package io.lightine.tessera.mrz.camera

/**
 * The pure logic of the saved-image **two-pass MRZ rescue** (TES-130), separated from the Vision/ImageIO
 * plumbing in [VisionSavedImageRecognizer] so every decision is host-testable without a device.
 *
 * **Why a second pass exists.** On a full-page photo, Vision reads the MRZ badly in ways the live camera
 * never sees (the live path crops to the guide band first): it splits rows into fragments, and its
 * `.accurate` language modelling collapses long `<` filler runs, so rows come back short of their fixed
 * ICAO width and the exact-width shape match downstream can never fire. Both were verified on real photos
 * (2026-08-09, TES-129/TES-130): a 30-char TD1 row arriving as 25 + 5, and filler rows arriving 22–24 of 30.
 *
 * **What the rescue does — selection, never invention.** Pass 1 locates rows that *look like* MRZ content
 * (charset-heavy, contains filler). Each candidate region is re-cropped tight and re-read (both Vision
 * levels — FAST reads chevrons more faithfully). From every read, the rescue keeps lines whose
 * whitespace-stripped length exactly matches an ICAO MRZ width, and splits a read whose length is an exact
 * small multiple of one width (a tight MRZ block that Vision returned as one concatenated observation).
 * Every kept line is an actual OCR read, character for character — nothing is padded or guessed (the
 * TES-18 stance); the parser's check digits downstream remain the arbiter of every rescued line.
 */
internal object VisionSavedImageRescue {
    // ICAO Doc 9303 fixed MRZ line widths: TD1 (3×30), TD2/MRV-B (2×36), TD3/MRV-A (2×44). Deliberately
    // duplicated from mrz-core's internal shape table — these are stable spec constants, and the module
    // boundary keeps core's internals private.
    internal val MRZ_LINE_WIDTHS: List<Int> = listOf(30, 36, 44)

    // How many concatenated rows a single over-merged read may be split into — the deepest ICAO shape has
    // 3 lines, so a longer multiple is noise, not an MRZ block.
    private const val MAX_SPLIT_ROWS: Int = 3

    // A row qualifies as an MRZ candidate when at least this fraction of its characters are MRZ-alphabet
    // (A–Z, 0–9, `<`) — real MRZ rows are 100% but OCR noise bleeds a little — and it contains at least one
    // filler chevron (every MRZ line format has filler somewhere; VIZ text lines have none).
    private const val MRZ_CHARSET_FRACTION: Double = 0.8

    // Minimum stripped length for candidacy — shorter runs (dates, document numbers in the VIZ) match the
    // charset but are not MRZ rows; the shortest real MRZ artefact worth re-cropping is well above this.
    private const val MIN_CANDIDATE_LENGTH: Int = 10

    /** Whether a pass-1 row's joined, whitespace-stripped text marks it as a candidate MRZ row. */
    internal fun isMrzCandidate(stripped: String): Boolean {
        if (stripped.length < MIN_CANDIDATE_LENGTH) return false
        if ('<' !in stripped) return false
        val mrzChars = stripped.count { it in 'A'..'Z' || it in '0'..'9' || it == '<' }
        return mrzChars.toDouble() / stripped.length >= MRZ_CHARSET_FRACTION
    }

    /**
     * The lines rescued from one re-read of a candidate region: keeps exact-width reads, and splits a read
     * whose stripped length is an exact multiple (2..[MAX_SPLIT_ROWS]) of one width — a tight MRZ block
     * Vision sometimes returns as ONE concatenated observation (photo-verified: 90 = 3×30). Split chunks
     * preserve the read's own character order; nothing is added or dropped.
     */
    internal fun rescuedLines(stripped: String): List<String> {
        for (width in MRZ_LINE_WIDTHS) {
            if (stripped.length == width) return listOf(stripped)
        }
        for (width in MRZ_LINE_WIDTHS) {
            if (stripped.length % width == 0) {
                val rows = stripped.length / width
                if (rows in 2..MAX_SPLIT_ROWS) return stripped.chunked(width)
            }
        }
        return emptyList()
    }

    /**
     * Maps a candidate region — an oriented-space, bottom-left-origin normalized rect (Vision's
     * `boundingBox` space, which pass 1 reported against the EXIF-oriented image) — into the RAW bitmap's
     * top-left-origin normalized space, undoing the EXIF [orientation]. Returns null for orientations the
     * rescue does not handle (the mirrored EXIF variants never produced by a camera); callers then skip
     * the second pass rather than crop the wrong region.
     *
     * Handled: 1 (up), 3 (180°), 6 (raw is the displayed image rotated 90° CCW — the usual back-camera
     * portrait photo), 8 (the 90° CW counterpart).
     */
    internal fun rawCropRect(
        region: NormalizedRect,
        orientation: Int,
    ): NormalizedRect? {
        // Oriented bottom-left → oriented top-left first.
        val x = region.x
        val y = 1.0 - region.y - region.height
        val w = region.width
        val h = region.height
        return when (orientation) {
            1 -> NormalizedRect(x, y, w, h)
            3 -> NormalizedRect(1.0 - x - w, 1.0 - y - h, w, h)
            6 -> NormalizedRect(y, 1.0 - x - w, h, w)
            8 -> NormalizedRect(1.0 - y - h, x, h, w)
            else -> null
        }
    }

    /** A normalized rectangle in whichever space the caller documents. Plain data, host-testable. */
    internal data class NormalizedRect(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) {
        /** This rect grown by the given paddings and clamped to the unit square. */
        internal fun padded(
            padX: Double,
            padY: Double,
        ): NormalizedRect {
            val left = (x - padX).coerceIn(0.0, 1.0)
            val bottom = (y - padY).coerceIn(0.0, 1.0)
            val right = (x + width + padX).coerceIn(0.0, 1.0)
            val top = (y + height + padY).coerceIn(0.0, 1.0)
            return NormalizedRect(left, bottom, right - left, top - bottom)
        }
    }

    /** The bounding [NormalizedRect] of one grouped row's fragments (Vision bottom-left space). */
    internal fun rowRegion(row: List<TextFragment>): NormalizedRect {
        val minX = row.minOf { it.x }
        val minY = row.minOf { it.y }
        val maxX = row.maxOf { it.x + it.width }
        val maxY = row.maxOf { it.y + it.height }
        return NormalizedRect(minX, minY, maxX - minX, maxY - minY)
    }
}
