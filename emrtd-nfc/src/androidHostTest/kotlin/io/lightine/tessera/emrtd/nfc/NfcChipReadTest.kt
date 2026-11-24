package io.lightine.tessera.emrtd.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.os.Bundle
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.types.errors.NfcTagLost
import io.lightine.tessera.types.errors.NfcTimeout
import io.lightine.tessera.types.errors.NfcUnavailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An EF.COM that claims no data groups: enough of a plain chip for a read to run for real. */
private val EF_COM_NONE: ByteArray =
    byteArrayOf(
        0x60,
        0x12,
        0x5F,
        0x02,
        0x04,
        '0'.code.toByte(),
        '1'.code.toByte(),
        '0'.code.toByte(),
        '8'.code.toByte(),
        0x5F,
        0x36,
        0x06,
        '0'.code.toByte(),
        '4'.code.toByte(),
        '3'.code.toByte(),
        '0'.code.toByte(),
        '0'.code.toByte(),
        '0'.code.toByte(),
        0x5C,
        0x00,
    )

/** The eMRTD application's identifier, ICAO Doc 9303 Part 10, section 2.1. */
private val LDS1_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01)

private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
private val SW_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())

/** A plain chip's file map: EF.COM only, everything else missing. */
private val PLAIN_CHIP = mapOf(0x011E to EF_COM_NONE)

/**
 * A scripted stand-in for the platform's IsoDep that answers the reader's commands from an
 * in-memory file map, as a plain chip with no access control does (ICAO Doc 9303 Part 11,
 * section 4.1 Note 1): enough of the ISO-DEP command set for a real read to run, no radio.
 *
 * The [failOnCommand]-th command throws [failure], as the platform does when the document leaves
 * the field mid-read; the [blockOnCommand]-th command blocks until the test releases it, as the
 * platform blocks while the chip does not answer. [events], when given, records connect, close,
 * block and release in order, across channels, for ordering assertions.
 */
private class ScriptedChipChannel(
    private val files: Map<Int, ByteArray>,
    private val failOnCommand: Int = Int.MAX_VALUE,
    private val failure: Throwable? = null,
    private val blockOnCommand: Int = Int.MAX_VALUE,
    private val name: String = "chip",
    private val events: MutableList<String>? = null,
) : IsoDepChannel {
    override val maxTransceiveLength: Int = 261
    override val isExtendedLengthApduSupported: Boolean = false

    var connected = false
    var closeCalls = 0
    val commands = mutableListOf<ByteArray>()
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    private var current: ByteArray? = null

    override val isConnected: Boolean get() = connected

    override fun connect() {
        connected = true
        events?.add("$name-connect")
    }

    override fun setTimeout(timeoutMillis: Int) = Unit

    override fun close() {
        closeCalls++
        connected = false
        events?.add("$name-close")
        closed.countDown()
    }

    override fun transceive(command: ByteArray): ByteArray {
        commands += command
        if (commands.size == blockOnCommand) {
            events?.add("$name-blocked")
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "the test never released the command" }
            events?.add("$name-released")
        }
        if (commands.size == failOnCommand) failure?.let { throw it }
        return when (command[1].toInt() and 0xFF) {
            0xA4 -> select(command)
            0xB0 -> readBinary(command)
            else -> byteArrayOf(0x6D.toByte(), 0x00)
        }
    }

    private fun select(command: ByteArray): ByteArray {
        // A 4-byte SELECT is the master file with no data; a longer one carries Lc and the data.
        val data =
            if (command.size > 5) {
                val length = command[4].toInt() and 0xFF
                if (length == 0) byteArrayOf() else command.copyOfRange(5, 5 + length)
            } else {
                byteArrayOf()
            }
        return when {
            data.isEmpty() -> {
                current = null // the master file
                SW_OK
            }

            data.contentEquals(LDS1_AID) -> {
                SW_OK
            }

            data.size == 2 -> {
                val fid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                current = files[fid] ?: return SW_NOT_FOUND
                SW_OK
            }

            else -> {
                SW_NOT_FOUND
            }
        }
    }

    private fun readBinary(command: ByteArray): ByteArray {
        val file = current ?: return SW_NOT_FOUND
        val offset = ((command[2].toInt() and 0x7F) shl 8) or (command[3].toInt() and 0xFF)
        if (offset >= file.size) return byteArrayOf(0x62.toByte(), 0x82.toByte()) // end of file before Ne
        val le = (command.last().toInt() and 0xFF).let { if (it == 0) 256 else it }
        return file.copyOfRange(offset, minOf(file.size, offset + le)) + SW_OK
    }
}

/**
 * The scripted platform: whether NFC is on, and the channel behind each tag it hands out. It wires
 * every channel exactly as [PlatformNfcChip] does, so the read's release tracking runs for real.
 */
private class FakeNfcPlatform(
    private val io: CoroutineDispatcher,
) : NfcChipPlatform {
    var enabled = true

    /** The channel for the next tag; a fresh plain chip when `null`. */
    var nextChannel: IsoDepChannel? = null

    /** Says "no ISO-DEP" to the next tag, as a tag that speaks none. */
    var nextTagSpeaksNone = false

    /** Every connection handed out, in order: the first is the first tag's. */
    val connections = mutableListOf<IsoDepChannel>()

    override fun isEnabled(adapter: NfcAdapter): Boolean = enabled

    override fun connectionOf(
        tag: Tag,
        timeoutMillis: Int,
        onReleased: () -> Unit,
    ): IsoDepConnection? {
        if (nextTagSpeaksNone) {
            nextTagSpeaksNone = false
            return null
        }
        val channel = nextChannel ?: ScriptedChipChannel(PLAIN_CHIP)
        nextChannel = null
        connections += channel
        return connectionOver(channel, timeoutMillis, io, onReleased)
    }
}

/** Keeps every update and ending, in order. Thread-safe: the read may deliver from its own thread. */
private class RecordingChipObserver : ChipReadObserver {
    val updates = Collections.synchronizedList(mutableListOf<ChipReadUpdate>())
    val endings = Collections.synchronizedList(mutableListOf<ChipReadEnding>())

    override fun onUpdate(update: ChipReadUpdate) {
        updates += update
    }

    override fun onEnding(ending: ChipReadEnding) {
        endings += ending
    }
}

/** An empty platform `Tag`, good enough to hand the read: the fake platform never looks at it. */
private fun aTag(): Tag {
    val constructor =
        Tag::class.java.declaredConstructors.maxByOrNull { it.parameterTypes.size }
            ?: error("android.nfc.Tag has no constructor")
    val arguments =
        constructor.parameterTypes
            .map { type ->
                when (type) {
                    ByteArray::class.java -> byteArrayOf()
                    Array<String>::class.java -> arrayOf<String>()
                    Array<Bundle>::class.java -> arrayOf<Bundle>()
                    IntArray::class.java -> intArrayOf()
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    else -> null
                }
            }.toTypedArray()
    constructor.isAccessible = true
    return constructor.newInstance(*arguments) as Tag
}

/**
 * An NFC adapter instance under Robolectric, where the platform's own lookup returns `null`. It is
 * the token the read holds; the fake platform decides what it reports.
 */
private fun theAdapter(): NfcAdapter =
    NfcAdapter::class.java
        .getDeclaredConstructor()
        .apply { isAccessible = true }
        .newInstance()

/**
 * TES-346: the Android read entry point drives the shared coordinator for real. Every test feeds a
 * scripted tag, runs the read on virtual time (a real pool where a command must block), and asserts
 * the fake channel was closed and the observer got exactly one ending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcChipReadTest {
    private val options = ChipReadOptions(ChipAccessKey.Can("123456"), setOf(1, 2))
    private val pool = Executors.newFixedThreadPool(4)
    private val poolDispatcher: CoroutineDispatcher = pool.asCoroutineDispatcher()

    @After
    fun stop() {
        pool.shutdownNow()
    }

    private fun TestScope.newRead(
        observer: RecordingChipObserver,
        platform: FakeNfcPlatform,
        settings: NfcReadSettings = NfcReadSettings(),
        adapter: NfcAdapter? = theAdapter(),
    ): NfcChipRead =
        NfcChipRead(
            options,
            observer,
            adapter,
            settings,
            StandardTestDispatcher(testScheduler),
            platform,
        )

    /** The observer's states, oldest first. */
    private fun states(observer: RecordingChipObserver): List<ChipReadState> = observer.updates.map { it.state }

    private fun assertOneEnding(observer: RecordingChipObserver) {
        assertEquals(1, observer.endings.size, "updates: ${observer.updates}")
    }

    // --- The read runs to a complete ending ---------------------------------------------------

    @Test
    fun `the happy path runs the states in order and ends complete`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)

            runCurrent() // the read is waiting for a document
            read.onTagDiscovered(aTag())
            advanceUntilIdle()

            // The exact order for a plain chip: the entry point's states around the shared flow's.
            assertEquals(
                listOf(
                    "Starting",
                    "WaitingForDocument(0ms)",
                    "DocumentFound",
                    "Connecting",
                    "OpeningAccess(null)",
                    "OpeningAccess(NONE)",
                    "ReadingFile(ChipFileRef(011D, application), 0 of ? bytes)",
                    "CheckingFiles",
                ),
                observer.updates.map { it.state.toString() },
                "${observer.updates}",
            )
            assertTrue(observer.updates.all { it.attempt == 1 && it.maxAttempts == 3 })
            assertOneEnding(observer)
            val ending = assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(1, ending.attempts)
            assertEquals(1.0, ending.progress.fraction)
            assertEquals(1, platform.connections.size)
            assertEquals(1, (platform.connections.single() as ScriptedChipChannel).closeCalls)
        }

    @Test
    fun `no command goes out before the settle time has passed`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(399)

            val chip = platform.connections.single() as ScriptedChipChannel
            assertTrue(chip.commands.isEmpty(), "a command went out before the settle time")
            assertFalse(chip.connected, "the tag's link was set up before the settle time")
            assertEquals(0, chip.closeCalls, "nothing was opened yet: nothing to close")

            advanceUntilIdle()
            assertOneEnding(observer)
            assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(1, chip.closeCalls, "the chip was never closed")
            assertTrue(chip.commands.isNotEmpty())
        }

    @Test
    fun `cancel during the settle cuts it short with no link opened`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(200)
            read.cancel()

            assertOneEnding(observer)
            val ending = assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
            assertEquals(ChipReadEnding.Cancelled.By.APP, ending.by)
            assertTrue(read.isEnded)

            // The tag was taken but its link never opened: no command went out, nothing to close.
            val chip = platform.connections.single() as ScriptedChipChannel
            assertTrue(chip.commands.isEmpty(), "a command went out before the read was cancelled")
            assertFalse(chip.connected, "the tag's link was opened")
            assertEquals(0, chip.closeCalls)

            advanceUntilIdle()
            assertOneEnding(observer) // the unwind changed nothing
        }

    // --- The tag is not a document -------------------------------------------------------------

    @Test
    fun `a tag that speaks no ISO-DEP ends the read with no document found`() =
        runTest {
            val platform =
                FakeNfcPlatform(StandardTestDispatcher(testScheduler)).apply { nextTagSpeaksNone = true }
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceUntilIdle()

            assertOneEnding(observer)
            val ending = assertIs<ChipReadEnding.Failed>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.NOT_SUPPORTED), ending.error)
            assertEquals(0, ending.attempts)
            assertFalse(states(observer).contains(ChipReadState.DocumentFound), "${states(observer)}")
            assertEquals(0, platform.connections.size)
        }

    // --- The read cannot start ----------------------------------------------------------------

    @Test
    fun `a device without an adapter ends the read as not started`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()

            newRead(observer, platform, adapter = null)
            advanceUntilIdle()

            assertOneEnding(observer)
            assertEquals(
                NfcUnavailable.Reason.NOT_SUPPORTED,
                assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason,
            )
            assertEquals(0, platform.connections.size)
        }

    @Test
    fun `NFC switched off before the read ends it as not started`() =
        runTest {
            val platform =
                FakeNfcPlatform(StandardTestDispatcher(testScheduler)).apply { enabled = false }
            val observer = RecordingChipObserver()

            newRead(observer, platform)
            advanceUntilIdle()

            assertOneEnding(observer)
            assertEquals(
                NfcUnavailable.Reason.DISABLED,
                assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason,
            )
            assertEquals(0, platform.connections.size)
        }

    // --- The lost link ------------------------------------------------------------------------

    @Test
    fun `a lost link then the document again continues on a new transport`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)
            val chip1 =
                ScriptedChipChannel(PLAIN_CHIP, failOnCommand = 7, failure = TagLostException("Tag was lost."), name = "chip1")
            platform.nextChannel = chip1

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(400)
            runCurrent() // attempt 1 loses the link; the read waits for the document again

            assertTrue(read.isEnded.not(), "the read should wait for the document")
            assertEquals(1, chip1.closeCalls)
            assertContains(states(observer), ChipReadState.DocumentFound)

            val chip2 = ScriptedChipChannel(PLAIN_CHIP, name = "chip2")
            platform.nextChannel = chip2
            read.onTagDiscovered(aTag())
            advanceUntilIdle()

            assertOneEnding(observer)
            val ending = assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(2, ending.attempts)
            assertTrue(chip1 !== chip2, "the read must use a new transport, never the old tag")
            assertEquals(1, chip1.closeCalls)
            assertEquals(1, chip2.closeCalls)
            assertFalse(chip2.commands.isEmpty())
            val lost = states(observer).filterIsInstance<ChipReadState.LinkLost>()
            assertEquals(1, lost.size)
            assertEquals(2, lost.single().restartsLeft)
            assertEquals(2, states(observer).count { it == ChipReadState.DocumentFound })
        }

    @Test
    fun `a lost link with no document back ends with what was read`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)
            platform.nextChannel =
                ScriptedChipChannel(PLAIN_CHIP, failOnCommand = 7, failure = TagLostException("Tag was lost."))

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceUntilIdle() // no second tag: the return wait runs out

            assertOneEnding(observer)
            val ending = assertIs<ChipReadEnding.Partial>(observer.endings.single())
            assertEquals(NfcTagLost, ending.stoppedBy)
            assertEquals(1, ending.attempts)
            assertEquals(1, platform.connections.size)
            assertEquals(1, (platform.connections.single() as ScriptedChipChannel).closeCalls)
        }

    // --- Tags that must be ignored ------------------------------------------------------------

    @Test
    fun `a second tag during a read is ignored and never resurfaces`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(200) // mid-settle: the first tag's read is running
            read.onTagDiscovered(aTag())
            assertEquals(1, platform.connections.size, "the tag during the read was taken")

            advanceUntilIdle() // the read completes on the first tag
            assertOneEnding(observer)
            assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(1, platform.connections.size)

            read.onTagDiscovered(aTag()) // after the ending
            assertEquals(1, platform.connections.size, "the tag after the ending was taken")
            assertEquals(1, (platform.connections.single() as ScriptedChipChannel).closeCalls)
        }

    // --- Cancel during a blocked command ------------------------------------------------------

    @Test
    fun `cancel during a blocked command ends at once and closes the transport`() {
        val platform = FakeNfcPlatform(poolDispatcher)
        val observer = RecordingChipObserver()
        val chip = ScriptedChipChannel(PLAIN_CHIP, blockOnCommand = 1)
        platform.nextChannel = chip
        val read =
            NfcChipRead(
                options,
                observer,
                theAdapter(),
                NfcReadSettings(settleDelayMillis = 0),
                poolDispatcher,
                platform,
            )

        // Reader mode re-offers a document that stays on the phone: the read takes one of these.
        val deadline = System.currentTimeMillis() + 10_000
        while (!chip.entered.await(50, TimeUnit.MILLISECONDS)) {
            assertTrue(System.currentTimeMillis() < deadline, "no command was blocked")
            read.onTagDiscovered(aTag())
        }

        read.cancel()
        assertOneEnding(observer) // the ending went out at once, on the cancelling thread
        val ending = assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
        assertEquals(ChipReadEnding.Cancelled.By.APP, ending.by)
        assertTrue(read.isEnded)

        read.cancel() // idempotent
        assertOneEnding(observer)

        chip.release.countDown()
        assertTrue(chip.closed.await(10, TimeUnit.SECONDS), "the platform link was not closed")
        assertEquals(1, chip.closeCalls)
        assertEquals(1, chip.commands.size, "nothing was sent after the cancelled command")
    }

    // --- NFC off mid-read ----------------------------------------------------------------------

    @Test
    fun `NFC switched off mid-read ends through the system ending`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            val read = newRead(observer, platform)
            platform.nextChannel =
                ScriptedChipChannel(PLAIN_CHIP, failOnCommand = 7, failure = TagLostException("Tag was lost."))

            runCurrent()
            platform.enabled = false
            read.onTagDiscovered(aTag())
            advanceTimeBy(400)
            runCurrent() // the attempt fails, and NFC being off ends the read at once

            assertOneEnding(observer)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.DISABLED), ending.reason)
            assertTrue(read.isEnded, "the read must not wait for a document that cannot come back")
            assertEquals(1, (platform.connections.single() as ScriptedChipChannel).closeCalls)
        }

    // --- One live read per process -------------------------------------------------------------

    @Test
    fun `a second read while one is live ends at once as not started`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val firstObserver = RecordingChipObserver()
            val secondObserver = RecordingChipObserver()
            val first = newRead(firstObserver, platform)
            runCurrent() // the first read is live, waiting for a document

            val second = newRead(secondObserver, platform)
            runCurrent()

            assertOneEnding(secondObserver)
            assertEquals(
                NfcUnavailable.Reason.READ_IN_PROGRESS,
                assertIs<ChipReadEnding.NotStarted>(secondObserver.endings.single()).reason,
            )
            assertEquals(0, secondObserver.updates.size, "a refused read reports nothing else")
            assertTrue(firstObserver.endings.isEmpty(), "the running read was untouched")
            assertEquals(0, platform.connections.size)

            first.cancel()
            assertOneEnding(firstObserver)
            assertIs<ChipReadEnding.Cancelled>(firstObserver.endings.single())
        }

    // --- The document wait ---------------------------------------------------------------------

    @Test
    fun `the wait ticks every 2 seconds and its limit ends the read as a timeout`() =
        runTest {
            val platform = FakeNfcPlatform(StandardTestDispatcher(testScheduler))
            val observer = RecordingChipObserver()
            newRead(observer, platform, settings = NfcReadSettings(waitForDocumentMillis = 5_000))

            runCurrent() // the read is waiting for a document
            advanceUntilIdle() // 5 seconds of virtual time pass with no document

            assertOneEnding(observer)
            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcTimeout(5_000), ending.reason)
            val waits = states(observer).filterIsInstance<ChipReadState.WaitingForDocument>().map { it.waitedMillis }
            assertEquals(listOf(0L, 2_000L, 4_000L), waits)
            assertEquals(0, platform.connections.size)
        }

    // --- The earlier read's close --------------------------------------------------------------

    @Test
    fun `a read cancelled mid-command releases the tag before the next read connects`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val platform = FakeNfcPlatform(poolDispatcher)
        val firstObserver = RecordingChipObserver()
        val secondObserver = RecordingChipObserver()
        val blocked =
            ScriptedChipChannel(PLAIN_CHIP, blockOnCommand = 1, name = "blocked", events = events)
        platform.nextChannel = blocked
        val first =
            NfcChipRead(
                options,
                firstObserver,
                theAdapter(),
                NfcReadSettings(settleDelayMillis = 0),
                poolDispatcher,
                platform,
            )
        first.onTagDiscovered(aTag())
        val firstDeadline = System.currentTimeMillis() + 10_000
        while (!blocked.entered.await(50, TimeUnit.MILLISECONDS)) {
            assertTrue(System.currentTimeMillis() < firstDeadline, "no command was blocked")
            first.onTagDiscovered(aTag())
        }

        first.cancel()
        val second =
            NfcChipRead(
                options,
                secondObserver,
                theAdapter(),
                NfcReadSettings(settleDelayMillis = 0),
                poolDispatcher,
                platform,
            )
        platform.nextChannel = ScriptedChipChannel(PLAIN_CHIP, name = "next", events = events)
        val secondDeadline = System.currentTimeMillis() + 10_000
        while (platform.connections.size < 2) {
            assertTrue(System.currentTimeMillis() < secondDeadline, "the next read never took the tag")
            second.onTagDiscovered(aTag())
            Thread.sleep(20)
        }

        // The earlier close is still talking to the tag: the next read must hold its first command
        // until the platform released it.
        val hold = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < hold) Thread.sleep(10)
        assertFalse(events.contains("next-connect"), "the next read connected before the earlier close ended: $events")
        assertFalse(secondObserver.endings.isNotEmpty(), "the next read ended before the earlier close ended")

        blocked.release.countDown()
        assertTrue(secondObserver.endings.awaitNotEmpty(10_000), "the next read never ended")

        assertOneEnding(secondObserver)
        assertIs<ChipReadEnding.Complete>(secondObserver.endings.single())
        assertOneEnding(firstObserver)
        assertEquals(1, blocked.closeCalls)
        assertEquals(1, (platform.connections.last() as ScriptedChipChannel).closeCalls)
        assertEquals(
            listOf("blocked-connect", "blocked-blocked", "blocked-released", "blocked-close", "next-connect"),
            events.filter { it != "next-close" },
            "the next read must connect only after the earlier close released the tag",
        )
    }

    /** Waits until the list is not empty; `true` when it is. */
    private fun <T> List<T>.awaitNotEmpty(
        millis: Long,
        sleeper: () -> Unit = { Thread.sleep(10) },
    ): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (isEmpty() && System.currentTimeMillis() < deadline) sleeper()
        return isNotEmpty()
    }

    // --- Reader mode ---------------------------------------------------------------------------

    @Test
    fun `reader mode flags cover NFC-A and NFC-B and skip the platform's NDEF check`() {
        assertEquals(
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            NfcReaderMode.FLAGS,
        )
        assertEquals(1_000, NfcReaderMode.DEFAULT_PRESENCE_CHECK_DELAY_MILLIS)
    }

    @Test
    fun `reader mode extras carry the presence-check delay`() {
        val extras = NfcReaderMode.extras()
        assertEquals(1_000, extras.getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
        assertEquals(500, NfcReaderMode.extras(500).getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
    }

    // --- The settings --------------------------------------------------------------------------

    @Test
    fun `the settings defaults match the documented values`() {
        val settings = NfcReadSettings()
        assertEquals(LinkLossPolicy.Resume(2), settings.linkLoss)
        assertEquals(5_000, settings.timeoutMillis)
        assertEquals(400L, settings.settleDelayMillis)
        assertEquals(15_000L, settings.waitForReturnMillis)
        assertNull(settings.waitForDocumentMillis)
        assertEquals(
            "NfcReadSettings(linkLoss=Resume(2), timeoutMillis=5000, settleDelayMillis=400, " +
                "waitForReturnMillis=15000, waitForDocumentMillis=null)",
            settings.toString(),
        )
    }

    @Test
    fun `the builder mirrors the constructor`() {
        val settings =
            NfcReadSettings
                .Builder()
                .linkLoss(LinkLossPolicy.ReadAgain(1))
                .timeoutMillis(3_000)
                .settleDelayMillis(100)
                .waitForReturnMillis(8_000)
                .waitForDocumentMillis(30_000)
                .build()
        assertEquals(LinkLossPolicy.ReadAgain(1), settings.linkLoss)
        assertEquals(3_000, settings.timeoutMillis)
        assertEquals(100L, settings.settleDelayMillis)
        assertEquals(8_000L, settings.waitForReturnMillis)
        assertEquals(30_000L, settings.waitForDocumentMillis)
    }

    @Test
    fun `the settings refuse values the platform would refuse`() {
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(timeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(settleDelayMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(waitForReturnMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(waitForDocumentMillis = -1) }
    }
}
