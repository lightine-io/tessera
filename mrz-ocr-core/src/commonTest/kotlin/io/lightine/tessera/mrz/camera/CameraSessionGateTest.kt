package io.lightine.tessera.mrz.camera

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host tests for [CameraSessionGate] — the shared start/stop bookkeeping behind both platform scanners.
 * Uses a controllable [Job] in place of a real camera coroutine so the lifecycle is driven
 * deterministically without a device.
 */
class CameraSessionGateTest {
    @Test
    fun start_launches_when_idle() =
        runTest {
            val gate = CameraSessionGate()
            var launches = 0
            gate.start {
                launches++
                Job()
            }

            assertEquals(1, launches)
            assertTrue(gate.isRunning)
        }

    @Test
    fun start_is_idempotent_while_running() =
        runTest {
            val gate = CameraSessionGate()
            var launches = 0
            val running = Job()
            gate.start {
                launches++
                running
            }
            gate.start {
                launches++
                running
            }

            assertEquals(1, launches, "a second start while running must not launch again")
            assertTrue(gate.isRunning)
        }

    @Test
    fun start_works_again_after_the_session_completes_on_its_own() =
        runTest {
            // The regression guard: the results flow ending by itself (a terminal CaptureError) must
            // leave the gate idle, so the documented prompt-and-retry start() actually restarts.
            val gate = CameraSessionGate()
            var launches = 0
            val first = Job()
            gate.start {
                launches++
                first
            }

            first.complete() // the stream ended on its own — NOT via stop()
            assertFalse(gate.isRunning, "the gate must clear itself when the session completes")

            gate.start {
                launches++
                Job()
            }
            assertEquals(2, launches, "start() must launch a fresh session after the previous one ended")
        }

    @Test
    fun stop_cancels_the_session_and_allows_restart() =
        runTest {
            val gate = CameraSessionGate()
            var launches = 0
            val first = Job()
            gate.start {
                launches++
                first
            }

            gate.stop()
            assertTrue(first.isCancelled)
            assertFalse(gate.isRunning)

            gate.start {
                launches++
                Job()
            }
            assertEquals(2, launches)
        }

    @Test
    fun a_completing_old_session_does_not_clear_a_freshly_started_one() =
        runTest {
            // Identity guard: if an old job's completion handler fires after a new session started, it
            // must not clear the new session.
            val gate = CameraSessionGate()
            val first = Job()
            gate.start { first }
            gate.stop() // clears the gate; first is cancelled but its completion handler may run later

            val second = Job()
            gate.start { second }
            first.complete() // old handler runs now — must NOT clear `second`

            assertTrue(gate.isRunning, "the old session completing must not clear the new one")
            second.cancel()
        }
}
