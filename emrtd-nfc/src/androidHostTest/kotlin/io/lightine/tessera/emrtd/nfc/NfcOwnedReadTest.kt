package io.lightine.tessera.emrtd.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.os.Looper
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A main thread of our own: posted blocks wait in a queue until the test runs them, first in, first out. */
internal class FakeMain : MainThread {
    @Volatile var onMain = true
    private val pending = ArrayDeque<() -> Unit>()

    override fun isCurrent(): Boolean = onMain

    override fun post(block: () -> Unit) {
        synchronized(pending) { pending.addLast(block) }
    }

    /** Runs what was posted, on "the main thread". */
    fun idle() {
        while (true) {
            val next = synchronized(pending) { pending.removeFirstOrNull() } ?: return
            val was = onMain
            onMain = true
            try {
                next()
            } finally {
                onMain = was
            }
        }
    }

    val queued: Int get() = synchronized(pending) { pending.size }
}

/** The screen side of the platform, scripted: what is shown, what reader mode answers, and every call in order. */
internal class FakeActivities(
    private val events: MutableList<String>,
) : ActivityPlatform {
    @Volatile var usable = true

    @Volatile var enableAnswer: NfcUnavailable.Reason? = null

    @Volatile var hasAdapter = true
    val enabledWith: MutableList<NfcReadSettings> = Collections.synchronizedList(mutableListOf())
    val disabledFor: MutableList<Activity> = Collections.synchronizedList(mutableListOf())
    val enabledFor: MutableList<Activity> = Collections.synchronizedList(mutableListOf())

    override fun adapter(activity: Activity): NfcAdapter? = if (hasAdapter) theAdapter() else null

    override fun isUsable(activity: Activity): Boolean = usable

    override fun enable(
        activity: Activity,
        callback: NfcAdapter.ReaderCallback,
        settings: NfcReadSettings,
    ): NfcUnavailable.Reason? {
        events += "enable"
        enabledWith += settings
        enabledFor += activity
        return enableAnswer
    }

    override fun disable(activity: Activity) {
        events += "disable"
        disabledFor += activity
    }
}

/** Records calls into a shared list, with the thread each came on. */
private class OrderedObserver(
    private val events: MutableList<String>,
) : ChipReadObserver {
    val endings: MutableList<ChipReadEnding> = Collections.synchronizedList(mutableListOf())
    val updates: MutableList<ChipReadUpdate> = Collections.synchronizedList(mutableListOf())
    val onMainThread: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    override fun onUpdate(update: ChipReadUpdate) {
        updates += update
        events += "update:${update.state}"
        onMainThread += (Looper.myLooper() == Looper.getMainLooper())
    }

    override fun onEnding(ending: ChipReadEnding) {
        endings += ending
        events += "ending"
        onMainThread += (Looper.myLooper() == Looper.getMainLooper())
    }
}

/**
 * TES-346: the Activity-owning start. Reader mode goes on once and off once, off before the ending
 * is heard, and never switched in between; the screen is held weakly; a destroyed screen ends the
 * read; the observer is handed to the main thread in order, with the ending last and once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcOwnedReadTest {
    private val options get() = ChipReadOptions(ChipAccessKey.Can("123456"), setOf(1, 2))

    private class Rig(
        val platform: FakeNfcPlatform,
        val main: FakeMain,
        val activities: FakeActivities,
        val events: MutableList<String>,
        val env: NfcReadEnvironment,
    )

    private fun TestScope.rig(): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeNfcPlatform(dispatcher)
        val main = FakeMain()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val activities = FakeActivities(events)
        val env =
            NfcReadEnvironment(platform, dispatcher, { testScheduler.currentTime }, NfcReadRegistry(), main = main, activities = activities)
        return Rig(platform, main, activities, events, env)
    }

    /** Runs the virtual clock a bounded time, so a read that waits for ever cannot spin a failing test for ever. */
    private fun TestScope.settle() {
        advanceTimeBy(60_000)
        runCurrent()
    }

    private fun anActivity(): Activity =
        Robolectric
            .buildActivity(Activity::class.java)
            .create()
            .start()
            .resume()
            .get()

    private fun Rig.start(
        activity: Activity,
        observer: ChipReadObserver,
        settings: NfcReadSettings = NfcReadSettings(),
        options: ChipReadOptions = this@NfcOwnedReadTest.options,
    ) = NfcChipRead.start(activity, options, observer, settings, env)

    @Test
    fun `the same options for a second read are rejected at once with no reader mode`() =
        runTest {
            val rig = rig()
            val reused = options
            val first = rig.start(anActivity(), OrderedObserver(rig.events), options = reused)
            runCurrent()
            first.cancel()
            settle()
            rig.main.idle()
            rig.events.clear()

            val observer = OrderedObserver(rig.events)
            rig.start(anActivity(), observer, options = reused)
            settle()
            rig.main.idle()
            val ending = assertIs<ChipReadEnding.KeyRejected>(observer.endings.single())
            assertEquals(
                io.lightine.tessera.types.errors.ChipAccessDenied.Reason.KEY_CLEARED,
                assertIs<io.lightine.tessera.types.errors.ChipAccessDenied>(ending.error).reason,
            )
            assertEquals(listOf("update:Starting", "ending"), rig.events.toList(), "no reader mode for a used key")
        }

    @Test
    fun `reader mode goes on once at the start and off once before the ending is heard, on a complete read`() =
        runTest {
            val rig = rig()
            val observer = OrderedObserver(rig.events)
            val activity = anActivity()
            val read = rig.start(activity, observer)
            assertEquals(listOf("enable"), rig.events.toList(), "reader mode is on when start returns, before any call is handed over")
            rig.main.idle()
            assertEquals("update:Starting", rig.events[1])
            runCurrent()
            read.onTagDiscovered(aTag())
            settle()
            rig.main.idle()

            assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(1, rig.activities.enabledFor.size)
            assertEquals(1, rig.activities.disabledFor.size)
            assertSame(activity, rig.activities.enabledFor.single())
            assertSame(activity, rig.activities.disabledFor.single())
            // Nothing toggled in between, and the off came before the ending was heard.
            assertEquals(listOf(1, 1), listOf(rig.events.count { it == "enable" }, rig.events.count { it == "disable" }))
            assertTrue(rig.events.indexOf("enable") < rig.events.indexOf("disable"))
            assertTrue(rig.events.indexOf("disable") < rig.events.indexOf("ending"))
            assertEquals("ending", rig.events.last())
        }

    @Test
    fun `the settings reach reader mode and the read is the callback that was registered`() =
        runTest {
            val rig = rig()
            val settings = NfcReadSettings(presenceCheckDelayMillis = 250, platformSounds = false)
            val read = rig.start(anActivity(), OrderedObserver(rig.events), settings)
            runCurrent()
            assertSame(settings, rig.activities.enabledWith.single())
            assertEquals(
                250L,
                rig.activities.enabledWith
                    .single()
                    .presenceCheckDelayMillis,
            )
            assertFalse(
                rig.activities.enabledWith
                    .single()
                    .platformSounds,
            )
            read.cancel()
        }

    @Test
    fun `cancel ends once, turns reader mode off once and lets go of the screen`() =
        runTest {
            val rig = rig()
            val observer = OrderedObserver(rig.events)
            val read = rig.start(anActivity(), observer)
            runCurrent()
            assertTrue(read.heldActivity != null)
            read.cancel()
            read.cancel()
            settle()
            rig.main.idle()

            assertEquals(ChipReadEnding.Cancelled.By.APP, assertIs<ChipReadEnding.Cancelled>(observer.endings.single()).by)
            assertEquals(1, rig.activities.disabledFor.size)
            assertTrue(rig.events.indexOf("disable") < rig.events.indexOf("ending"))
            assertNull(read.heldActivity, "no screen is held after the ending")
        }

    @Test
    fun `a screen that is finishing or destroyed ends the read as not started with a typed reason and touches nothing`() =
        runTest {
            val rig = rig()
            rig.activities.usable = false
            val observer = OrderedObserver(rig.events)
            val read = rig.start(anActivity(), observer)
            runCurrent()
            rig.main.idle()

            assertEquals(
                NfcUnavailable.Reason.ACTIVITY_DESTROYED,
                assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason,
            )
            assertTrue(rig.activities.enabledFor.isEmpty())
            assertTrue(rig.activities.disabledFor.isEmpty(), "reader mode was never switched on, so it is not switched off")
            assertNull(read.heldActivity)
            assertEquals(listOf("update:Starting", "ending"), rig.events)
        }

    @Test
    fun `a refused reader mode ends as not started with the platform's reason, with no waiting state and no off`() =
        runTest {
            for (reason in listOf(
                NfcUnavailable.Reason.NOT_SUPPORTED,
                NfcUnavailable.Reason.DISABLED,
                NfcUnavailable.Reason.NOT_PERMITTED,
                NfcUnavailable.Reason.REQUEST_REFUSED,
                NfcUnavailable.Reason.TAG_READING_DISABLED,
            )) {
                val rig = rig()
                rig.activities.enableAnswer = reason
                val observer = OrderedObserver(rig.events)
                rig.start(anActivity(), observer)
                runCurrent()
                rig.main.idle()
                assertEquals(reason, assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason, "$reason")
                assertTrue(observer.updates.none { it.state is ChipReadState.WaitingForDocument }, "$reason")
                assertTrue(rig.activities.disabledFor.isEmpty(), "$reason")
                // The slot is free again: a new read can start.
                rig.activities.enableAnswer = null
                val second = OrderedObserver(rig.events)
                val read = rig.start(anActivity(), second)
                runCurrent()
                assertTrue(second.endings.isEmpty(), "$reason")
                read.cancel()
                settle()
            }
        }

    @Test
    fun `a second read at once is refused and does not switch the first read's reader mode`() =
        runTest {
            val rig = rig()
            val first = OrderedObserver(rig.events)
            val firstRead = rig.start(anActivity(), first)
            runCurrent()
            val second = OrderedObserver(rig.events)
            rig.start(anActivity(), second)
            runCurrent()
            rig.main.idle()

            assertEquals(NfcUnavailable.Reason.READ_IN_PROGRESS, assertIs<ChipReadEnding.NotStarted>(second.endings.single()).reason)
            assertEquals(1, rig.activities.enabledFor.size, "only the first read turned reader mode on")
            assertTrue(rig.activities.disabledFor.isEmpty(), "and the refusal did not turn it off")
            assertTrue(first.endings.isEmpty())
            firstRead.cancel()
            settle()
            rig.main.idle()
            assertEquals(1, rig.activities.disabledFor.size)
        }

    @Test
    fun `a new read started in the ending finds reader mode off first and is not switched off behind its back`() =
        runTest {
            val rig = rig()
            val activity = anActivity()
            lateinit var second: NfcChipRead
            val observer =
                object : ChipReadObserver {
                    override fun onUpdate(update: ChipReadUpdate) = Unit

                    override fun onEnding(ending: ChipReadEnding) {
                        rig.events += "ending"
                        second = rig.start(activity, OrderedObserver(rig.events))
                    }
                }
            val read = rig.start(activity, observer, NfcReadSettings(observerOn = ObserverThread.CALLER))
            runCurrent()
            // The ending is decided off the main thread: the off and the hand-over wait for the main thread.
            rig.main.onMain = false
            val ender = thread { read.cancel() }
            ender.join(5_000)
            assertTrue(rig.events.none { it == "ending" }, "the app does not hear the ending before reader mode is off")
            rig.main.idle()
            runCurrent()
            rig.main.idle()
            assertEquals(
                listOf("enable", "disable", "ending", "enable"),
                rig.events.filter {
                    it == "enable" || it == "disable" ||
                        it == "ending"
                },
            )
            assertFalse(second.isEnded)
            second.cancel()
            settle()
        }

    @Test
    fun `a screen destroyed during the read ends it as ended by the system with a typed reason`() =
        runTest {
            val rig = rig()
            val controller =
                Robolectric
                    .buildActivity(Activity::class.java)
                    .create()
                    .start()
                    .resume()
            val observer = OrderedObserver(rig.events)
            val read = rig.start(controller.get(), observer)
            runCurrent()
            assertTrue(observer.endings.isEmpty())

            controller.pause().stop().destroy()
            runCurrent()
            rig.main.idle()

            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.ACTIVITY_DESTROYED), ending.reason)
            assertTrue(read.isEnded)
            assertNull(read.heldActivity)
            assertEquals(1, rig.activities.disabledFor.size)
            assertTrue(rig.events.indexOf("disable") < rig.events.indexOf("ending"))
        }

    @Test
    fun `while the screen is paused after a lost link the earlier tag is not retried, and it is once the screen resumes`() =
        runTest {
            val rig = rig()
            val controller =
                Robolectric
                    .buildActivity(Activity::class.java)
                    .create()
                    .start()
                    .resume()
            val observer = OrderedObserver(rig.events)
            rig.platform.next += ScriptedChipChannel(failOnCommand = 7, failure = android.nfc.TagLostException("Tag was lost."))
            val read = rig.start(controller.get(), observer)
            runCurrent()
            val tag = aTag()
            read.onTagDiscovered(tag)
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            assertEquals(1, rig.platform.tagsConnected.size)
            controller.pause()

            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, rig.platform.tagsConnected.size, "no retry of the earlier tag while paused")
            assertTrue(observer.endings.isEmpty())

            controller.resume()
            advanceTimeBy(NfcChipRead.SAME_TAG_RETRY_MILLIS)
            runCurrent()
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            rig.main.idle()
            assertEquals(listOf(tag, tag), rig.platform.tagsConnected.toList())
            assertEquals(2, assertIs<ChipReadEnding.Complete>(observer.endings.single()).attempts)
        }

    /** A read that lost its link mid-read and let go of the earlier tag, whose connect failed: it waits for a new one. */
    private fun TestScope.waitingForANewTag(
        rig: Rig,
        activity: Activity,
        observer: OrderedObserver,
    ): NfcChipRead {
        rig.platform.next += ScriptedChipChannel(failOnCommand = 7, failure = android.nfc.TagLostException("Tag was lost."))
        val read = rig.start(activity, observer)
        runCurrent()
        val tag = aTag()
        read.onTagDiscovered(tag)
        advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
        runCurrent()
        rig.platform.gone += tag
        advanceTimeBy(NfcChipRead.SAME_TAG_RETRY_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(tag, tag), rig.platform.tagsConnected.toList(), "one try of the earlier tag")
        return read
    }

    @Test
    fun `a pause while the read waits for a new tag makes no call, and a tag found after the resume completes the read`() =
        runTest {
            val rig = rig()
            val controller =
                Robolectric
                    .buildActivity(Activity::class.java)
                    .create()
                    .start()
                    .resume()
            val observer = OrderedObserver(rig.events)
            val read = waitingForANewTag(rig, controller.get(), observer)
            controller.pause()
            advanceTimeBy(5_000)
            runCurrent()
            rig.main.idle()
            assertEquals(2, rig.platform.tagsConnected.size, "nothing is tried while waiting for a new tag")
            assertEquals(listOf("enable"), rig.events.filter { it == "enable" || it == "disable" }, "reader mode is never switched")
            assertTrue(observer.endings.isEmpty())

            controller.resume()
            read.onTagDiscovered(aTag())
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            rig.main.idle()
            assertEquals(2, assertIs<ChipReadEnding.Complete>(observer.endings.single()).attempts)
            assertEquals(3, rig.platform.tagsConnected.size)
            assertEquals(0, (rig.platform.connections[1] as ScriptedChipChannel).closeCalls, "no platform call after the failed connect")
        }

    @Test
    fun `a screen destroyed while the read waits for a new tag ends it at once with no further call on the tag`() =
        runTest {
            val rig = rig()
            val controller =
                Robolectric
                    .buildActivity(Activity::class.java)
                    .create()
                    .start()
                    .resume()
            val observer = OrderedObserver(rig.events)
            val read = waitingForANewTag(rig, controller.get(), observer)
            controller.pause().stop().destroy()
            runCurrent()
            rig.main.idle()

            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.ACTIVITY_DESTROYED), ending.reason)
            assertTrue(read.isEnded)
            settle()
            assertEquals(2, rig.platform.tagsConnected.size)
            assertEquals(1, rig.activities.enabledFor.size)
        }

    @Test
    fun `the paused screen does not change when a document that does not return ends the read`() =
        runTest {
            val rig = rig()
            val controller =
                Robolectric
                    .buildActivity(Activity::class.java)
                    .create()
                    .start()
                    .resume()
            val observer = OrderedObserver(rig.events)
            rig.platform.next += ScriptedChipChannel(failOnCommand = 7, failure = android.nfc.TagLostException("Tag was lost."))
            val read = rig.start(controller.get(), observer)
            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            controller.pause()
            settle()
            rig.main.idle()
            assertIs<ChipReadEnding.Partial>(observer.endings.single())
            assertEquals(1, rig.platform.tagsConnected.size)
        }

    @Test
    fun `an ending after the screen went away does not turn reader mode off on it`() =
        runTest {
            val rig = rig()
            val observer = OrderedObserver(rig.events)
            val read = rig.start(anActivity(), observer)
            runCurrent()
            // The screen is finishing or destroyed when another thread delivers the ending.
            rig.activities.usable = false
            read.cancel()
            settle()
            rig.main.idle()
            assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
            assertTrue(rig.activities.disabledFor.isEmpty(), "no platform call on a screen that went away")
            assertNull(read.heldActivity)
        }

    @Test
    fun `after the ending the read keeps no reference that stops the screen being collected`() =
        runTest {
            val rig = rig()
            val controller =
                Robolectric
                    .buildActivity(Activity::class.java)
                    .create()
                    .start()
                    .resume()
            val read = rig.start(controller.get(), OrderedObserver(rig.events))
            runCurrent()
            read.cancel()
            settle()
            rig.main.idle()
            assertNull(read.heldActivity)
            // The lifecycle watcher is gone too: a later destroy does not touch the finished read.
            controller.pause().stop().destroy()
            runCurrent()
            assertEquals(1, rig.events.count { it == "ending" })
            assertEquals(1, rig.activities.disabledFor.size)
        }

    // --- The observer's thread ------------------------------------------------------------------

    @Test
    fun `with the main option nothing is heard before the main thread runs, then every call in order and one ending last`() =
        runTest {
            val rig = rig()
            val observer = OrderedObserver(rig.events)
            val read = rig.start(anActivity(), observer, NfcReadSettings(observerOn = ObserverThread.MAIN))
            runCurrent()
            read.onTagDiscovered(aTag())
            settle()
            assertTrue(
                observer.updates.isEmpty() && observer.endings.isEmpty(),
                "nothing is delivered until the main thread runs its queue",
            )
            assertTrue(rig.main.queued > 0)

            rig.main.idle()
            assertEquals(1, observer.endings.size)
            assertEquals("ending", rig.events.last())
            val states = observer.updates.map { it.state.toString() }
            assertEquals("Starting", states.first())
            assertEquals(
                listOf("Starting", "WaitingForDocument(0ms)", "DocumentFound", "Connecting"),
                states.take(4),
            )
        }

    @Test
    fun `with the caller option the updates are heard at once and only the ending waits for reader mode to be off`() =
        runTest {
            val rig = rig()
            val observer = OrderedObserver(rig.events)
            val read = rig.start(anActivity(), observer, NfcReadSettings(observerOn = ObserverThread.CALLER))
            runCurrent()
            assertEquals(
                "Starting",
                observer.updates
                    .first()
                    .state
                    .toString(),
            )
            read.cancel()
            settle()
            rig.main.idle()
            assertEquals(1, observer.endings.size)
            assertEquals("ending", rig.events.last())
        }

    @Test
    fun `the real main looper keeps the order of calls made from other threads and delivers the ending last, once`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val handedOver = Collections.synchronizedList(mutableListOf<Boolean>())
        val app =
            object : ChipReadObserver {
                override fun onUpdate(update: ChipReadUpdate) {
                    events += "u"
                    handedOver += (Looper.myLooper() == Looper.getMainLooper())
                }

                override fun onEnding(ending: ChipReadEnding) {
                    events += "e"
                    handedOver += (Looper.myLooper() == Looper.getMainLooper())
                }
            }
        val handoff =
            io.lightine.tessera.emrtd
                .HandoffObserver(app, AndroidMainThread::post)
        val progress =
            io.lightine.tessera.emrtd
                .ChipReadProgress(0.0, 0, 0, null)
        val update = ChipReadUpdate(ChipReadState.Starting, progress, 1, 3)
        val done = CountDownLatch(1)
        thread {
            repeat(50) { handoff.onUpdate(update) }
            handoff.onEnding(ChipReadEnding.NotStarted(NfcUnavailable.Reason.DISABLED, progress))
            handoff.onEnding(ChipReadEnding.NotStarted(NfcUnavailable.Reason.DISABLED, progress))
            handoff.onUpdate(update)
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(events.isEmpty(), "handed to the main looper, not run on the calling thread")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(List(50) { "u" } + "e", events.toList())
        assertTrue(handedOver.all { it })
    }

    // --- The screen check of the platform wiring ---------------------------------------------------

    @Test
    fun `the platform's screen check says no for a finishing or destroyed screen and yes otherwise`() {
        val created = Robolectric.buildActivity(Activity::class.java).create()
        assertTrue(PlatformActivities.isUsable(created.get()), "created: the platform applies reader mode when it resumes")
        val shown =
            Robolectric
                .buildActivity(Activity::class.java)
                .create()
                .start()
                .resume()
        assertTrue(PlatformActivities.isUsable(shown.get()))
        shown.get().finish()
        assertFalse(PlatformActivities.isUsable(shown.get()), "finishing")
        val gone =
            Robolectric
                .buildActivity(Activity::class.java)
                .create()
                .start()
                .resume()
        gone.pause().stop().destroy()
        assertFalse(PlatformActivities.isUsable(gone.get()), "destroyed")
    }

    @Test
    fun `the presence-check delay is a Long in milliseconds that is cut to what the platform takes`() {
        assertEquals(1_000, NfcReaderMode.extras().getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
        assertEquals(250, NfcReaderMode.extras(250L).getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
        assertEquals(Int.MAX_VALUE, NfcReaderMode.extras(Long.MAX_VALUE).getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
        assertEquals(0, NfcReaderMode.extras(-5L).getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
        assertEquals(NfcReadSettings.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS, NfcReaderMode.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS)
    }
}
