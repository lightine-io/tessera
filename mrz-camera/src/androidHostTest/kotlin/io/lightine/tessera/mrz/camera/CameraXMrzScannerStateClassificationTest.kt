package io.lightine.tessera.mrz.camera

import androidx.camera.core.CameraState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Host test (JVM, no device) for [classifyCameraState] — the recoverable-vs-critical decision
 * [CameraXMrzScanner] makes for every androidx [CameraState] error code. It locks two things the live
 * in-use scenario cannot be staged for on an emulator (no camera contention): that CameraX's *recoverable*
 * codes map to a **non-terminal** observation (so the scanner stays bound and CameraX recovers) and its
 * *critical* codes to a **terminal** one (so the session ends), and that the permission collapse — CameraX
 * reporting a denial as the critical `ERROR_CAMERA_FATAL_ERROR` — is disambiguated by the read-only
 * permission state into the actionable [CameraError.PermissionDenied].
 *
 * `classifyCameraState` is a pure function over the int code + a permission Boolean, so it runs entirely on
 * the JVM (the codes are compile-time `static final int` constants, inlined — no Android runtime is
 * touched). This is the Android counterpart of mrz-camera-ios's `AVCaptureMrzScannerErrorMappingTest`:
 * report every observable capture failure as the correct sealed result, never throw, never decide for the
 * caller ([ADR-020](https://lightine.youtrack.cloud/articles/TES-A-49);
 * reader-not-oracle).
 */
class CameraXMrzScannerStateClassificationTest {
    @Test
    fun in_use_codes_are_recoverable_CameraInUse() {
        for (code in listOf(CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE)) {
            val decision =
                assertIs<CameraStateDecision.Recoverable>(
                    classifyCameraState(code, hasCameraPermission = true),
                    "code $code (in use) must be recoverable so CameraX can retry",
                )
            assertIs<CameraError.CameraInUse>(decision.error, "code $code maps to CameraInUse")
        }
    }

    @Test
    fun other_recoverable_code_is_recoverable_CameraUnavailable() {
        val decision =
            assertIs<CameraStateDecision.Recoverable>(
                classifyCameraState(CameraState.ERROR_OTHER_RECOVERABLE_ERROR, hasCameraPermission = true),
            )
        assertIs<CameraError.CameraUnavailable>(decision.error)
    }

    @Test
    fun recoverable_classification_does_not_depend_on_permission() {
        // A recoverable code is about contention / retry, not permission — the read-only permission state
        // only disambiguates the *critical* path, so a recoverable code stays recoverable either way.
        for (held in listOf(true, false)) {
            assertIs<CameraStateDecision.Recoverable>(
                classifyCameraState(CameraState.ERROR_CAMERA_IN_USE, hasCameraPermission = held),
            )
        }
    }

    @Test
    fun critical_codes_with_permission_are_terminal_CameraUnavailable() {
        for (code in CRITICAL_CODES) {
            val decision =
                assertIs<CameraStateDecision.Terminal>(
                    classifyCameraState(code, hasCameraPermission = true),
                    "code $code (critical) must be terminal — CameraX does not auto-recover",
                )
            assertIs<CameraError.CameraUnavailable>(decision.error, "code $code with permission ⇒ CameraUnavailable")
        }
    }

    @Test
    fun critical_codes_without_permission_are_terminal_PermissionDenied() {
        // CameraX collapses a permission denial into the critical ERROR_CAMERA_FATAL_ERROR with a null
        // cause, so a critical code observed while CAMERA is NOT held is reported as the actionable cause.
        for (code in CRITICAL_CODES) {
            val decision =
                assertIs<CameraStateDecision.Terminal>(
                    classifyCameraState(code, hasCameraPermission = false),
                )
            assertIs<CameraError.PermissionDenied>(decision.error, "code $code without permission ⇒ PermissionDenied")
        }
    }

    @Test
    fun decisions_carry_the_stable_switch_on_able_code_string() {
        assertEquals(
            "camera.in_use",
            classifyCameraState(CameraState.ERROR_CAMERA_IN_USE, hasCameraPermission = true).error.code,
        )
        assertEquals(
            "camera.unavailable",
            classifyCameraState(CameraState.ERROR_CAMERA_FATAL_ERROR, hasCameraPermission = true).error.code,
        )
        assertEquals(
            "camera.permission_denied",
            classifyCameraState(CameraState.ERROR_CAMERA_FATAL_ERROR, hasCameraPermission = false).error.code,
        )
    }

    private companion object {
        // CameraX's critical codes (no auto-recovery) — everything outside the recoverable {1,2,3} set.
        val CRITICAL_CODES =
            listOf(
                CameraState.ERROR_STREAM_CONFIG,
                CameraState.ERROR_CAMERA_DISABLED,
                CameraState.ERROR_CAMERA_FATAL_ERROR,
                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
                CameraState.ERROR_CAMERA_REMOVED,
            )
    }
}
