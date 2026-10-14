package io.lightine.tessera.mrz.camera

/**
 * A failure to obtain a reading, reported on [MrzScanResult.CaptureError]; never thrown.
 *
 * Each member has a stable English [code] to switch on. A report is terminal when it ends the
 * camera session and non-terminal when the session stays running; each member says which.
 *
 * @since 0.2.0
 */
public sealed interface CameraError {
    /** A stable English code to switch on, for example `camera.ocr_failed`. */
    public val code: String

    /**
     * Diagnostic detail, not for display and never document data.
     *
     * It can hold raw platform exception text: do not forward it to a remote log.
     */
    public val message: String

    /**
     * The OCR engine failed on a frame or saved image, for example by throwing.
     *
     * Non-terminal: the session continues with the next frame.
     *
     * @property message diagnostic detail.
     * @since 0.2.0
     */
    public data class OcrFailed(
        override val message: String,
    ) : CameraError {
        /** Stable code: `camera.ocr_failed`. */
        override val code: String get() = "camera.ocr_failed"
    }

    /**
     * The camera could not be opened, or the platform reported a failure.
     *
     * Terminal, except on Android when CameraX reports a temporary error and retries: that report
     * is non-terminal and the session stays bound.
     *
     * @property message diagnostic detail.
     * @since 0.2.0
     */
    public data class CameraUnavailable(
        override val message: String,
    ) : CameraError {
        /** Stable code: `camera.unavailable`. */
        override val code: String get() = "camera.unavailable"
    }

    /**
     * The camera permission is not granted.
     *
     * Terminal. The SDK never requests the permission: request it, then call
     * [MrzCameraScanner.start] again.
     *
     * @property message diagnostic detail.
     * @since 0.2.0
     */
    public data class PermissionDenied(
        override val message: String,
    ) : CameraError {
        /** Stable code: `camera.permission_denied`. */
        override val code: String get() = "camera.permission_denied"
    }

    /**
     * Another client holds the camera.
     *
     * Non-terminal: the session stays bound and capture resumes when the other client releases the
     * camera.
     *
     * @property message diagnostic detail.
     * @since 0.2.0
     */
    public data class CameraInUse(
        override val message: String,
    ) : CameraError {
        /** Stable code: `camera.in_use`. */
        override val code: String get() = "camera.in_use"
    }
}
