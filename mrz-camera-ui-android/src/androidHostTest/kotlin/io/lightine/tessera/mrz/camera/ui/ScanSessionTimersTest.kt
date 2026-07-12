package io.lightine.tessera.mrz.camera.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Locks the start-gating of the camera-scoped [`struggleTimeout`][MrzScannerConfig.struggleTimeout] hint, which
 * runs through [awaitCameraActiveThenTimeout]: it must start counting only once the camera is active (the first
 * non-null preview surface), never while the preview is still loading. The gate is a pure suspend function over
 * a surface-request flow, so virtual time ([runTest]) exercises it with no real camera and no wall-clock wait —
 * the [String] element type stands in for the Android `SurfaceRequest` the production caller passes (the gate
 * only checks nullness). (The session-level `scanTimeout` deadline uses a different mechanism — the
 * lifecycle-aware `rememberScanDeadline` — verified on-device; its pure formatter is covered by
 * [ScanCountdownFormatTest].)
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy(Duration) / runCurrent — TestScope virtual-time control
class ScanSessionTimersTest {
    @Test
    fun `does not fire while the camera never becomes active`() =
        runTest {
            val surface = MutableStateFlow<String?>(null) // stays null = camera still loading
            var fired = false
            val job = launch { awaitCameraActiveThenTimeout(surface, 5.seconds) { fired = true } }

            advanceTimeBy(10.seconds) // well past the finite timeout
            runCurrent()

            assertFalse(fired, "timeout must not fire while the camera is still loading (no preview surface)")
            job.cancel()
        }

    @Test
    fun `fires only after the camera becomes active, counting from activation`() =
        runTest {
            val surface = MutableStateFlow<String?>(null)
            var fired = false
            val job = launch { awaitCameraActiveThenTimeout(surface, 5.seconds) { fired = true } }

            advanceTimeBy(3.seconds) // time spent loading — must not count toward the deadline
            runCurrent()
            assertFalse(fired, "camera not active yet, so the clock has not started")

            surface.value = "surface-request" // camera goes active now
            runCurrent()

            advanceTimeBy(4.seconds) // 4s since activation (< 5s) — but 7s since start
            runCurrent()
            assertFalse(fired, "must count 5s from activation, not from screen open")

            advanceTimeBy(2.seconds) // now 6s since activation (> 5s)
            runCurrent()
            assertTrue(fired, "fires once the timeout elapses after the camera became active")
            job.join()
        }

    @Test
    fun `counts from activation when the camera is already active at start`() =
        runTest {
            val surface = MutableStateFlow<String?>("surface-request") // already active
            var fired = false
            val job = launch { awaitCameraActiveThenTimeout(surface, 5.seconds) { fired = true } }

            advanceTimeBy(4.seconds)
            runCurrent()
            assertFalse(fired)

            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(fired)
            job.join()
        }

    @Test
    fun `an infinite timeout never fires`() =
        runTest {
            val surface = MutableStateFlow<String?>("surface-request") // active immediately
            var fired = false
            val job = launch { awaitCameraActiveThenTimeout(surface, Duration.INFINITE) { fired = true } }

            advanceTimeBy(10.seconds)
            runCurrent()

            assertFalse(fired, "INFINITE means no deadline — it never arms")
            job.join() // returns immediately (non-finite short-circuit), so the job completes
        }
}
