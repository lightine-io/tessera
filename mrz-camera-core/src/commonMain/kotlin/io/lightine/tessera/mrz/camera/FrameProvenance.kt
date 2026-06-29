package io.lightine.tessera.mrz.camera

import io.lightine.tessera.types.vocabulary.ReadMethod

/**
 * The provenance of the frames a [MrzFrameAnalyzer] reads — the two read methods a frame analyzer can
 * honestly produce. Passed to the analyzer's constructor; the analyzer stamps the corresponding
 * [ReadMethod] on every result's
 * [`ResultMetadata.readMethod`][io.lightine.tessera.mrz.parsing.ResultMetadata] (Principle 5 — report how
 * the data reached the SDK).
 *
 * Deliberately a two-value type rather than the full [ReadMethod] enum: a frame analyzer reads either a
 * live camera feed or a pre-captured image, and nothing else. Constraining the parameter here stops a
 * consumer from stamping a frame read as `MANUAL_ENTRY`, `NFC_CHIP`, or `MIXED` — provenances those
 * reading methods own and that a frame analyzer cannot honestly claim
 * ([ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63); Principle 4 — honest about what we know).
 */
public enum class FrameProvenance(
    internal val readMethod: ReadMethod,
) {
    /** Frames from a live camera feed (the analyzer's default; camera reading, 0.2.0+). */
    LIVE_CAMERA(ReadMethod.LIVE_CAMERA),

    /** Frames decoded from a pre-existing saved image (image reading, 0.3.0+). */
    PRE_CAPTURED_IMAGE(ReadMethod.PRE_CAPTURED_IMAGE),
}
