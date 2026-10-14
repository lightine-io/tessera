package io.lightine.tessera.mrz.camera

import io.lightine.tessera.types.vocabulary.ReadMethod

/**
 * Where an analyzer's frames come from; sets the reading method on every result.
 *
 * @since 0.2.0
 */
public enum class FrameProvenance(
    internal val readMethod: ReadMethod,
) {
    /** A live camera feed. This is the analyzer's default; the reading method is `LIVE_CAMERA`. */
    LIVE_CAMERA(ReadMethod.LIVE_CAMERA),

    /** A saved image; the reading method is `PRE_CAPTURED_IMAGE`. */
    PRE_CAPTURED_IMAGE(ReadMethod.PRE_CAPTURED_IMAGE),
}
