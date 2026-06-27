package io.lightine.tessera.mrz.camera

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient
import platform.AVFoundation.AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit test for [AVCaptureMrzScanner]'s capture-failure contract.
 *
 * Two host-testable seams stand in for the live camera the iOS Simulator does not have. (1) The *terminal*
 * capture-failure → [CameraError] mapping (`cameraErrorFor`): a setup failure (permission / no camera) or a
 * session *runtime error* — the latter a genuine media-services / hardware fault that cannot be summoned
 * even on a device (see the project's issue tracker) — is surfaced as the correct typed [CameraError] carrying
 * the original message, never thrown. (2) The *recoverable* in-use trigger (`isVideoDeviceInUseReason`):
 * which `AVCaptureSession` interruption reason the scanner treats as "another client took the camera" and
 * surfaces as a **non-terminal** [CameraError.CameraInUse] (the session stays bound and AVFoundation
 * resumes when the interruption ends) — the Android counterpart host-tested this way is `classifyCameraState`.
 * Together they are the iOS half of the "report the observable failure as a sealed result, never throw,
 * never decide for the caller" contract
 * ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49);
 * reader-not-oracle). The exceptions and the two functions are `internal` purely to make this reachable.
 */
@OptIn(ExperimentalForeignApi::class)
class AVCaptureMrzScannerErrorMappingTest {
    private fun mapping(cause: Throwable): CameraError? {
        // Constructing the scanner touches no camera (capture setup is lazy, on start()); close() just
        // cancels its scope and the owned recognizer.
        val scanner = AVCaptureMrzScanner()
        try {
            return scanner.cameraErrorFor(cause)
        } finally {
            scanner.close()
        }
    }

    @Test
    fun permission_failure_is_surfaced_as_PermissionDenied() {
        val error = assertIs<CameraError.PermissionDenied>(mapping(CameraPermissionException("permission not granted")))
        assertEquals("permission not granted", error.message, "the original failure message is preserved")
    }

    @Test
    fun only_the_video_device_in_use_reason_triggers_a_recoverable_in_use() {
        // The non-terminal in-use path keys off the interruption reason, not an exception: only
        // videoDeviceInUseByAnotherClient is "another client took the camera"; backgrounding (and every
        // other reason) is left to recover silently, and a missing reason is not in-use.
        val scanner = AVCaptureMrzScanner()
        try {
            assertTrue(
                scanner.isVideoDeviceInUseReason(AVCaptureSessionInterruptionReasonVideoDeviceInUseByAnotherClient),
                "videoDeviceInUseByAnotherClient is the recoverable in-use reason",
            )
            assertFalse(
                scanner.isVideoDeviceInUseReason(AVCaptureSessionInterruptionReasonVideoDeviceNotAvailableInBackground),
                "backgrounding is not an in-use reason — it recovers silently",
            )
            assertFalse(scanner.isVideoDeviceInUseReason(null), "a missing reason is not in-use")
        } finally {
            scanner.close()
        }
    }

    @Test
    fun runtime_error_is_surfaced_as_CameraUnavailable() {
        // The path that is otherwise un-triggerable: a session runtime error closes the channel with a
        // CameraUnavailableException, which must reach the consumer as CameraUnavailable.
        val error = assertIs<CameraError.CameraUnavailable>(mapping(CameraUnavailableException("session runtime error")))
        assertEquals("session runtime error", error.message)
    }

    @Test
    fun an_unclassified_failure_falls_back_to_CameraUnavailable() {
        // An open failure the scanner did not anticipate still reaches the consumer as a terminal
        // CameraUnavailable rather than being swallowed or thrown out of the results stream.
        val error = assertIs<CameraError.CameraUnavailable>(mapping(IllegalStateException("unexpected")))
        assertEquals("unexpected", error.message)
    }
}
