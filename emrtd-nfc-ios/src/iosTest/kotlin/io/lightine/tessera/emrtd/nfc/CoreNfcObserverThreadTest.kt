package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ObserverThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TES-346: the observer's thread option on iPhone, read the same as on Android: order kept, one ending, last. */
class CoreNfcObserverThreadTest {
    private class Queue {
        val pending = mutableListOf<() -> Unit>()

        fun runAll() {
            while (pending.isNotEmpty()) pending.removeAt(0)()
        }
    }

    @Test
    fun theSettingsDefaultToTheMainQueueAndTheBuilderCanChangeIt() {
        assertEquals(ObserverThread.MAIN, CoreNfcReadSettings().observerOn)
        assertEquals(ObserverThread.CALLER, CoreNfcReadSettings(observerOn = ObserverThread.CALLER).observerOn)
        val built = CoreNfcReadSettings.Builder().observerOn(ObserverThread.CALLER).build()
        assertEquals(ObserverThread.CALLER, built.observerOn)
        assertEquals(ObserverThread.CALLER, built.toBuilder().build().observerOn)
        assertEquals(
            ObserverThread.MAIN,
            built
                .toBuilder()
                .observerOn(ObserverThread.MAIN)
                .build()
                .observerOn,
        )
        assertTrue("observerOn=MAIN" in CoreNfcReadSettings().toString())
    }

    @Test
    fun withTheMainOptionNothingIsHeardBeforeTheQueueRunsThenEveryCallInOrderAndOneEndingLast() =
        chipReadTest {
            val h = this
            val queue = Queue()
            h.mainQueue = { queue.pending += it }
            val (read, rec) = h.start()
            assertTrue(rec.updates.isEmpty() && rec.endings.isEmpty(), "nothing is heard before start's caller yields the main queue")
            read.cancel()
            read.cancel()
            h.step()
            assertTrue(rec.updates.isEmpty() && rec.endings.isEmpty())
            queue.runAll()
            assertEquals(1, rec.endings.size)
            assertEquals(ChipReadEnding.Cancelled.By.APP, (rec.ending as ChipReadEnding.Cancelled).by)
            assertEquals("Starting", rec.names.first())
            assertEquals("Stopping", rec.names.last())
            h.assertNothingLeft(read)
        }

    @Test
    fun withTheCallerOptionTheCallsAreHeardAtOnce() =
        chipReadTest {
            val h = this
            val queue = Queue()
            h.mainQueue = { queue.pending += it }
            val (read, rec) = h.start(settings = CoreNfcReadSettings(observerOn = ObserverThread.CALLER))
            assertEquals("Starting", rec.names.first())
            read.cancel()
            assertEquals(1, rec.endings.size)
            assertTrue(queue.pending.isEmpty(), "nothing went through the main queue")
            h.assertNothingLeft(read)
        }
}
