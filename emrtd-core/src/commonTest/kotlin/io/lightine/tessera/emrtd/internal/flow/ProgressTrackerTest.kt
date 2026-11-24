package io.lightine.tessera.emrtd.internal.flow

import io.lightine.tessera.emrtd.ChipReadProgress
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "remaining span" progress: property tests over random read shapes, seeded so they repeat. */
class ProgressTrackerTest {
    private class Collect : FlowSink {
        val values = mutableListOf<ChipReadProgress>()

        override fun update(
            state: ChipReadState,
            progress: ChipReadProgress,
        ) {
            values += progress
        }
    }

    /** One random attempt: access, files of random (sometimes huge, sometimes unknown) size, authentication. */
    private fun attempt(
        r: FlowReporter,
        rnd: Random,
        stopAt: Int?,
    ) {
        r.beginAttempt(setOf(1, 2))
        r.openingAccess(null)
        r.probeDone(if (rnd.nextBoolean()) ChipAccessProtocol.PACE else ChipAccessProtocol.BAC)
        r.accessGranted(ChipAccessProtocol.BAC)
        val files = listOf(0x011E, 0x011D, 0x0101, 0x0102, 0x0107)
        r.planFiles(setOf(0x0101, 0x0102, 0x0107))
        var step = 0
        for (fid in files) {
            val total =
                when (rnd.nextInt(4)) {
                    0 -> 0
                    1 -> rnd.nextInt(1, 300)
                    2 -> rnd.nextInt(300, 40_000)
                    else -> 4 * 1024 * 1024
                }
            r.fileStarted(fid, false)
            var read = 0
            while (read < total) {
                read = minOf(total, read + 223 + rnd.nextInt(0, 30_000))
                r.fileChunk(fid, false, read, if (rnd.nextInt(5) == 0) null else total)
                if (stopAt != null && step++ == stopAt) return
            }
            r.fileEnded(fid, false)
        }
        r.authenticationPhaseDone()
        r.checkingFiles()
    }

    @Test
    fun progressNeverGoesBackAndStaysBelowOneUntilComplete() {
        repeat(300) { seed ->
            val rnd = Random(seed)
            val tracker = ProgressTracker()
            val sink = Collect()
            val r = FlowReporter(tracker, sink)
            // Up to three attempts, each cut off at a random step, then one that finishes.
            repeat(rnd.nextInt(0, 3)) { attempt(r, rnd, rnd.nextInt(0, 12)) }
            attempt(r, rnd, null)
            val fractions = sink.values.map { it.fraction }
            assertEquals(fractions.sorted(), fractions, "seed $seed")
            assertTrue(sink.values.all { it.fraction in 0.0..<1.0 && it.percent in 0..99 }, "seed $seed")
            assertTrue(fractions.last() > 0.0, "seed $seed: ${fractions.last()}")
            val done = tracker.complete()
            assertEquals(1.0, done.fraction)
            assertEquals(100, done.percent)
        }
    }

    @Test
    fun aNewAttemptKeepsTheValueThenFillsAgain() {
        val tracker = ProgressTracker()
        val sink = Collect()
        val r = FlowReporter(tracker, sink)
        attempt(r, Random(7), 4)
        val before = tracker.fraction
        r.beginAttempt(setOf(1, 2))
        assertEquals(before, tracker.fraction)
        r.openingAccess(null)
        assertEquals(before, sink.values.last().fraction)
        r.probeDone(ChipAccessProtocol.BAC)
        r.accessGranted(ChipAccessProtocol.BAC)
        assertTrue(tracker.fraction > before)
    }

    @Test
    fun theEstimatesAreSaneForUnknownAndHugeSizes() {
        val tracker = ProgressTracker()
        val r = FlowReporter(tracker, Collect())
        r.beginAttempt(setOf(1, 2))
        r.probeDone(ChipAccessProtocol.PACE)
        r.accessGranted(ChipAccessProtocol.PACE)
        r.fileStarted(0x0102, false)
        r.fileChunk(0x0102, false, 223, null)
        val unknown = tracker.fraction
        r.fileChunk(0x0102, false, 446, 4 * 1024 * 1024)
        // A huge file: the bar slows down and stays far from the end.
        assertTrue(tracker.fraction - unknown < 0.01, "${tracker.fraction}")
        r.fileChunk(0x0102, false, 4 * 1024 * 1024, 4 * 1024 * 1024)
        r.fileEnded(0x0102, false)
        assertTrue(tracker.snapshot().percent <= 99)
        assertTrue(tracker.fraction < 1.0)
    }
}
