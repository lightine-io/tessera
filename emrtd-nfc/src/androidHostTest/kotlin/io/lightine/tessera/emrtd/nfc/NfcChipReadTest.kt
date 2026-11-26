package io.lightine.tessera.emrtd.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.os.Bundle
import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipCommandTimeLimit
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.ObserverThread
import io.lightine.tessera.types.errors.NfcNoAnswer
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
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An EF.COM that claims no data groups: enough of a plain chip for a read to run for real. */
private val EF_COM_NONE: ByteArray =
    byteArrayOf(0x60, 0x12, 0x5F, 0x02, 0x04) + "0108".encodeToByteArray() +
        byteArrayOf(0x5F, 0x36, 0x06) + "040000".encodeToByteArray() + byteArrayOf(0x5C, 0x00)

/** The eMRTD application's identifier, ICAO Doc 9303 Part 10, section 2.1. */
private val LDS1_AID = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47, 0x10, 0x01)

private val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
private val SW_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())

/** A plain chip's file map: EF.COM only, everything else missing. */
internal val PLAIN_CHIP = mapOf(0x011E to EF_COM_NONE)

/** The command a plain chip's read fails on to lose the link after access: the 7th. */
private const val AFTER_ACCESS = 7

/**
 * A scripted stand-in for the platform's IsoDep that answers from an in-memory file map, as a
 * plain chip with no access control does (ICAO Doc 9303 Part 11, section 4.1 Note 1).
 *
 * [connectFailure] makes the connect throw; [failOnCommand] makes that command throw [failure];
 * [blockOnCommand] or [blockConnect] block until the test releases them, as the platform blocks
 * while the chip does not answer. [events] records connect, close and blocking in order.
 */
internal class ScriptedChipChannel(
    private val files: Map<Int, ByteArray> = PLAIN_CHIP,
    private val failOnCommand: Int = Int.MAX_VALUE,
    private val failure: Throwable? = null,
    private val connectFailure: Throwable? = null,
    private val blockOnCommand: Int = Int.MAX_VALUE,
    private val blockConnect: Boolean = false,
    private val name: String = "chip",
    private val events: MutableList<String>? = null,
) : IsoDepChannel {
    override val maxTransceiveLength: Int = 261
    override val isExtendedLengthApduSupported: Boolean = false

    @Volatile var connected = false

    @Volatile var closeCalls = 0
    val commands: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    private var current: ByteArray? = null

    override val isConnected: Boolean get() = connected

    override fun connect() {
        events?.add("$name-connect")
        if (blockConnect) {
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "the test never released the connect" }
        }
        connectFailure?.let { throw it }
        connected = true
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
        val data =
            if (command.size > 5) {
                val length = command[4].toInt() and 0xFF
                if (length == 0) byteArrayOf() else command.copyOfRange(5, 5 + length)
            } else {
                byteArrayOf()
            }
        return when {
            data.isEmpty() -> {
                current = null
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
        if (offset >= file.size) return byteArrayOf(0x62.toByte(), 0x82.toByte())
        val le = (command.last().toInt() and 0xFF).let { if (it == 0) 256 else it }
        return file.copyOfRange(offset, minOf(file.size, offset + le)) + SW_OK
    }
}

/**
 * The scripted platform: whether NFC is on, and the channel behind each connection it hands out,
 * wired exactly as [PlatformNfcChip] does, so the read's release tracking runs for real.
 */
internal class FakeNfcPlatform(
    private val io: CoroutineDispatcher,
) : NfcChipPlatform {
    @Volatile var enabled = true

    /** Channels for the next connections, in order; a fresh plain chip when empty. */
    val next = ArrayDeque<IsoDepChannel>()

    /** Tags that speak no ISO-DEP. */
    val notIsoDep: MutableSet<Tag> = Collections.synchronizedSet(mutableSetOf())

    /** Tags that left the field: a connect on them fails, as on the platform. */
    val gone: MutableSet<Tag> = Collections.synchronizedSet(mutableSetOf())

    /** When set, the platform's release of each link is held until the test runs it. */
    var holdReleases = false
    val heldReleases = mutableListOf<() -> Unit>()

    val connections: MutableList<IsoDepChannel> = Collections.synchronizedList(mutableListOf())
    val tagsConnected: MutableList<Tag> = Collections.synchronizedList(mutableListOf())

    var enabledChecks = 0

    override fun isEnabled(adapter: NfcAdapter): Boolean {
        enabledChecks++
        return enabled
    }

    /** NFC on, but its option for reading tags off (Android 15 on). */
    @Volatile var tagReadingOff = false

    override fun isTagReadingOff(adapter: NfcAdapter): Boolean = tagReadingOff

    /** When set, handing out a connection throws it: a platform fault the read does not expect. */
    @Volatile var connectionFault: RuntimeException? = null

    override fun connectionOf(
        tag: Tag,
        timeoutMillis: Long,
        onReleased: () -> Unit,
    ): IsoDepConnection? {
        connectionFault?.let { throw it }
        if (tag in notIsoDep) return null
        val channel =
            if (tag in gone) {
                ScriptedChipChannel(connectFailure = TagLostException("Tag was lost."), name = "gone")
            } else {
                synchronized(next) { next.removeFirstOrNull() } ?: ScriptedChipChannel()
            }
        connections += channel
        tagsConnected += tag
        val released: () -> Unit = if (holdReleases) ({ synchronized(heldReleases) { heldReleases += onReleased } }) else onReleased
        return connectionOver(channel, timeoutMillis, io, released)
    }
}

/** Keeps every update and ending, in order; [waiting] opens once the reader waits for a document. */
internal class RecordingChipObserver : ChipReadObserver {
    val updates: MutableList<ChipReadUpdate> = Collections.synchronizedList(mutableListOf())
    val endings: MutableList<ChipReadEnding> = Collections.synchronizedList(mutableListOf())
    val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val waiting = CountDownLatch(1)
    val ended = CountDownLatch(1)

    override fun onUpdate(update: ChipReadUpdate) {
        updates += update
        if (update.state is ChipReadState.WaitingForDocument) waiting.countDown()
    }

    override fun onEnding(ending: ChipReadEnding) {
        endings += ending
        threads += Thread.currentThread().name
        ended.countDown()
    }

    fun states(): List<ChipReadState> = updates.map { it.state }

    fun names(): List<String> = updates.map { it.state.toString() }
}

/** An empty platform `Tag`, good enough to hand the read: the fake platform never looks inside. */
internal fun aTag(): Tag {
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

/** An NFC adapter instance under Robolectric; the fake platform decides what it reports. */
internal fun theAdapter(): NfcAdapter =
    NfcAdapter::class.java
        .getDeclaredConstructor()
        .apply { isAccessible = true }
        .newInstance()

/**
 * TES-346: the Android read entry point drives the shared coordinator for real, on virtual time.
 * Only the tests that need a command or a connect to block in the platform use a real thread pool,
 * and those wait on latches, never on the clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcChipReadTest {
    // A new key for each use: the entry point clears the key of a read at its ending.
    private val options get() = ChipReadOptions(ChipAccessKey.Can("123456"), setOf(1, 2))
    private val pool = Executors.newFixedThreadPool(4) { Thread(it, "read-pool") }
    private val poolDispatcher: CoroutineDispatcher = pool.asCoroutineDispatcher()

    @After
    fun stop() {
        pool.shutdownNow()
    }

    private class Rig(
        val platform: FakeNfcPlatform,
        val env: NfcReadEnvironment,
    )

    private fun TestScope.rig(): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val platform = FakeNfcPlatform(dispatcher)
        return Rig(platform, NfcReadEnvironment(platform, dispatcher, { testScheduler.currentTime }, NfcReadRegistry()))
    }

    private fun poolRig(): Rig {
        val platform = FakeNfcPlatform(poolDispatcher)
        val clock = System::currentTimeMillis
        return Rig(platform, NfcReadEnvironment(platform, poolDispatcher, clock, NfcReadRegistry()))
    }

    private fun Rig.start(
        observer: RecordingChipObserver,
        settings: NfcReadSettings = NfcReadSettings(),
        adapter: NfcAdapter? = theAdapter(),
        options: ChipReadOptions = this@NfcChipReadTest.options,
    ): NfcChipRead = NfcChipRead.start(options, adapter, observer, settings, env)

    private fun assertOneEnding(observer: RecordingChipObserver) {
        assertEquals(1, observer.endings.size, "updates: ${observer.updates}")
    }

    private fun ScriptedChipChannel.closedOnce() = assertEquals(1, closeCalls, "the link must be closed exactly once")

    // --- A read that runs to its end -----------------------------------------------------------

    @Test
    fun `the happy path runs the states in order and ends complete`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceUntilIdle()

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
                observer.names(),
            )
            assertTrue(observer.updates.all { it.attempt == 1 && it.maxAttempts == 3 })
            val ending = assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(1, ending.attempts)
            assertEquals(1.0, ending.progress.fraction)
            (rig.platform.connections.single() as ScriptedChipChannel).closedOnce()
            assertTrue(read.isEnded)
        }

    @Test
    fun `no command and no connect before the settle time has passed`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS - 1)
            runCurrent()

            val chip = rig.platform.connections.single() as ScriptedChipChannel
            assertTrue(chip.commands.isEmpty())
            assertFalse(chip.connected)

            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(observer.endings.single())
            chip.closedOnce()
        }

    @Test
    fun `cancel during the settle ends at once with nothing opened`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(200)
            read.cancel()

            assertEquals(ChipReadState.Stopping, observer.updates.last().state)
            assertEquals(ChipReadEnding.Cancelled.By.APP, assertIs<ChipReadEnding.Cancelled>(observer.endings.single()).by)
            read.cancel()
            advanceUntilIdle()
            assertOneEnding(observer)
            val chip = rig.platform.connections.single() as ScriptedChipChannel
            assertTrue(chip.commands.isEmpty())
            assertFalse(chip.connected)
            assertEquals(0, chip.closeCalls, "nothing was opened, so nothing is closed")
        }

    // --- Before the read can start --------------------------------------------------------------

    @Test
    fun `a device without an adapter ends the read as not started`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            rig.start(observer, adapter = null)
            advanceUntilIdle()

            assertEquals(listOf("Starting"), observer.names())
            assertEquals(NfcUnavailable.Reason.NOT_SUPPORTED, assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason)
            assertTrue(rig.platform.connections.isEmpty())
        }

    @Test
    fun `NFC off at the start ends the read as not started`() =
        runTest {
            val rig = rig()
            rig.platform.enabled = false
            val observer = RecordingChipObserver()
            rig.start(observer)
            advanceUntilIdle()

            assertEquals(NfcUnavailable.Reason.DISABLED, assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason)
        }

    @Test
    fun `NFC on with its option for reading tags off ends the read as not started`() =
        runTest {
            val rig = rig()
            rig.platform.tagReadingOff = true
            val observer = RecordingChipObserver()
            rig.start(observer)
            advanceUntilIdle()

            assertEquals(
                NfcUnavailable.Reason.TAG_READING_DISABLED,
                assertIs<ChipReadEnding.NotStarted>(observer.endings.single()).reason,
            )
            assertTrue(rig.platform.connections.isEmpty())
        }

    @Test
    fun `the option for reading tags switched off while waiting ends the read at the next tick`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            rig.start(observer)
            runCurrent()
            advanceTimeBy(500)
            rig.platform.tagReadingOff = true
            advanceTimeBy(1_500)
            runCurrent()

            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.TAG_READING_DISABLED), ending.reason)
        }

    @Test
    fun `a platform fault the read does not expect ends once as an unexpected fault`() =
        runTest {
            val rig = rig()
            rig.platform.connectionFault = IllegalStateException("a platform fault")
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(1_000)
            runCurrent()

            val ending = assertIs<ChipReadEnding.Failed>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.UNEXPECTED_FAULT), ending.error)
            assertTrue(read.isEnded)
        }

    @Test
    fun `the callback form ends as not started when the app's reader mode is refused`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            runCurrent()
            // No NFC feature on the test device: the platform has no adapter for the Activity.
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

            val refused = NfcReaderMode.enable(activity, read)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(NfcUnavailable.Reason.NOT_SUPPORTED, refused)
            val ending = assertIs<ChipReadEnding.NotStarted>(observer.endings.single())
            assertEquals(refused, ending.reason)
            read.cancel()
            assertOneEnding(observer)
            assertTrue(
                rig.env.registry
                    .isLive()
                    .not(),
                "a refused read frees the slot",
            )
        }

    @Test
    fun `a key whose check digits do not add up ends before anything else and holds no slot`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val badKey = ChipAccessKey.Mrz("T22000129", '4', "900315", '2', "340314", '1')
            rig.start(observer, options = ChipReadOptions(badKey, setOf(1)))
            assertIs<ChipReadEnding.KeyRejected>(observer.endings.single())
            assertEquals(0, rig.platform.enabledChecks, "the key check comes before NFC is looked at")

            val next = RecordingChipObserver()
            val read = rig.start(next)
            runCurrent()
            assertTrue(next.endings.isEmpty(), "a rejected key must not hold the one-read slot")
            read.cancel()
        }

    @Test
    fun `the same options for a second read are rejected at once and nothing is looked at`() =
        runTest {
            val rig = rig()
            val reused = options
            rig.start(RecordingChipObserver(), options = reused).cancel()
            advanceUntilIdle()
            val checks = rig.platform.enabledChecks
            val observer = RecordingChipObserver()
            rig.start(observer, options = reused)
            advanceUntilIdle()
            val ending = assertIs<ChipReadEnding.KeyRejected>(observer.endings.single())
            assertEquals(
                io.lightine.tessera.types.errors.ChipAccessDenied.Reason.KEY_CLEARED,
                assertIs<io.lightine.tessera.types.errors.ChipAccessDenied>(ending.error).reason,
            )
            assertEquals(checks, rig.platform.enabledChecks, "the key check comes before NFC is looked at")
            assertTrue(rig.platform.connections.isEmpty())
        }

    @Test
    fun `a second read while one is live ends at once and a read after the ending starts`() =
        runTest {
            val rig = rig()
            val first = RecordingChipObserver()
            val read = rig.start(first)
            runCurrent()

            val second = RecordingChipObserver()
            rig.start(second)
            assertEquals(listOf("Starting"), second.names())
            assertEquals(NfcUnavailable.Reason.READ_IN_PROGRESS, assertIs<ChipReadEnding.NotStarted>(second.endings.single()).reason)
            assertTrue(first.endings.isEmpty())

            read.cancel()
            val third = RecordingChipObserver()
            val again = rig.start(third)
            runCurrent()
            assertTrue(third.endings.isEmpty(), "the slot is free once the first read has its ending")
            again.onTagDiscovered(aTag())
            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(third.endings.single())
        }

    // --- Tags the read skips or ignores ----------------------------------------------------------

    @Test
    fun `a tag that speaks no ISO-DEP is skipped and the read keeps waiting`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            val transitCard = aTag()
            rig.platform.notIsoDep += transitCard

            runCurrent()
            read.onTagDiscovered(transitCard)
            advanceTimeBy(1_000)
            assertTrue(observer.endings.isEmpty())
            assertFalse(observer.states().contains(ChipReadState.DocumentFound))

            read.onTagDiscovered(aTag())
            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(observer.endings.single())
        }

    @Test
    fun `a tag during a command or after the ending is ignored`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            rig.platform.next += ScriptedChipChannel(failOnCommand = AFTER_ACCESS, failure = TagLostException("Tag was lost."))
            val first = aTag()

            runCurrent()
            read.onTagDiscovered(first)
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            // The link is up and the read is between commands: this tag is dropped, not kept for
            // after the loss.
            val during = aTag()
            read.onTagDiscovered(during)
            runCurrent()
            assertTrue(observer.states().any { it is ChipReadState.LinkLost })
            rig.platform.gone += first
            advanceTimeBy(NfcReadSettings.DEFAULT_WAIT_FOR_RETURN_MILLIS)
            runCurrent()
            assertFalse(during in rig.platform.tagsConnected, "a tag found during a command was used")
            assertIs<ChipReadEnding.Partial>(observer.endings.single())

            val taken = rig.platform.tagsConnected.size
            read.onTagDiscovered(aTag())
            advanceUntilIdle()
            assertOneEnding(observer)
            assertEquals(taken, rig.platform.tagsConnected.size, "a tag found after the ending was used")
        }

    // --- Connects that fail -----------------------------------------------------------------------

    @Test
    fun `a tag found during a failed connect is the one the read uses next`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            rig.platform.next += ScriptedChipChannel(connectFailure = TagLostException("Tag was lost."), name = "bounced")

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(100)
            val again = aTag() // the document bounced: the platform hands over a newer Tag
            read.onTagDiscovered(again)
            advanceUntilIdle()

            assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(again, rig.platform.tagsConnected.last())
            // A connect that threw left nothing connected: the link is let go with no platform call.
            assertEquals(0, (rig.platform.connections.first() as ScriptedChipChannel).closeCalls, "a failed connect was closed")
            assertTrue(observer.updates.all { it.attempt == 1 }, "a failed connect uses no attempt")
        }

    @Test
    fun `three failed connects in a row end the read with no answer, as on iPhone`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            repeat(3) { rig.platform.next += ScriptedChipChannel(connectFailure = TagLostException("Tag was lost.")) }

            runCurrent()
            repeat(3) {
                read.onTagDiscovered(aTag())
                // Not advanceUntilIdle: a waiting read reports every 2 seconds without end.
                advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
                runCurrent()
            }
            val ending = assertIs<ChipReadEnding.Failed>(observer.endings.single())
            assertEquals(NfcNoAnswer, ending.error)
            assertEquals(0, ending.attempts)
            assertNull(ending.systemCode, "Android reports no codes")
            assertTrue(
                rig.platform.connections.all { (it as ScriptedChipChannel).closeCalls == 0 },
                "no platform call after a failed connect",
            )
        }

    // --- The document waits -----------------------------------------------------------------------

    @Test
    fun `the wait ticks every 2 seconds and the document limit counts across failed connects`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer, NfcReadSettings(waitForDocumentMillis = 5_000))
            rig.platform.next += ScriptedChipChannel(connectFailure = TagLostException("Tag was lost."))

            runCurrent()
            advanceTimeBy(1_000)
            read.onTagDiscovered(aTag()) // found, but the link cannot be set up
            advanceTimeBy(3_999 - 1_000)
            runCurrent()
            assertTrue(observer.endings.isEmpty())
            advanceTimeBy(1_001)
            runCurrent()

            // The app's own limit: neither the system nor the person ended the read.
            val ending = assertIs<ChipReadEnding.Failed>(observer.endings.single())
            assertEquals(NfcTimeout(5_000, NfcTimeout.Limit.WAIT_FOR_DOCUMENT), ending.error)
            assertEquals(0, ending.attempts)
            assertEquals(5_000, currentTime)
            val waits = observer.states().filterIsInstance<ChipReadState.WaitingForDocument>().map { it.waitedMillis }
            assertEquals(0L, waits.first())
            assertTrue(waits.containsAll(listOf(2_000L, 4_000L)), "$waits")
        }

    @Test
    fun `with the default settings a read that finds no document ends after 2 minutes`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            rig.start(observer)
            runCurrent()
            advanceTimeBy(119_999)
            runCurrent()
            assertTrue(observer.endings.isEmpty())
            advanceTimeBy(2)
            runCurrent()

            val ending = assertIs<ChipReadEnding.Failed>(observer.endings.single())
            assertEquals(NfcTimeout(120_000, NfcTimeout.Limit.WAIT_FOR_DOCUMENT), ending.error)
            assertEquals(0, ending.attempts)
        }

    @Test
    fun `with no limit set a read that finds no document is still waiting after 2 minutes`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer, NfcReadSettings(waitForDocumentMillis = NfcReadSettings.NO_LIMIT))
            runCurrent()
            advanceTimeBy(600_000)
            runCurrent()

            assertTrue(observer.endings.isEmpty())
            read.cancel()
            runCurrent()
        }

    @Test
    fun `NFC switched off while waiting ends the read at the next tick`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            rig.start(observer)
            runCurrent()
            advanceTimeBy(500)
            rig.platform.enabled = false
            advanceTimeBy(1_500)
            runCurrent()

            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.DISABLED), ending.reason)
            assertEquals(2_000, currentTime)
        }

    // --- Lost links -------------------------------------------------------------------------------

    @Test
    fun `a lost link then a new tag goes on with a new link and counts attempts one way`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            val chip1 = ScriptedChipChannel(failOnCommand = AFTER_ACCESS, failure = TagLostException("Tag was lost."), name = "chip1")
            val chip2 = ScriptedChipChannel(name = "chip2")
            rig.platform.next += chip1
            rig.platform.next += chip2

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            assertFalse(read.isEnded)
            chip1.closedOnce()
            read.onTagDiscovered(aTag())
            advanceUntilIdle()

            val ending = assertIs<ChipReadEnding.Complete>(observer.endings.single())
            assertEquals(2, ending.attempts)
            chip2.closedOnce()
            val lost = observer.updates.single { it.state is ChipReadState.LinkLost }
            assertEquals(2, (lost.state as ChipReadState.LinkLost).restartsLeft)
            assertEquals(1, lost.attempt, "LinkLost belongs to the attempt that lost the link")
            val after = observer.updates.dropWhile { it.state !is ChipReadState.LinkLost }.drop(1)
            assertTrue(after.isNotEmpty() && after.all { it.attempt == 2 }, "$after")
        }

    @Test
    fun `with no new tag after a lost link the earlier tag is tried again`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            rig.platform.next += ScriptedChipChannel(failOnCommand = AFTER_ACCESS, failure = TagLostException("Tag was lost."))
            val tag = aTag()

            runCurrent()
            read.onTagDiscovered(tag)
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            val lostAt = currentTime
            advanceUntilIdle()

            assertEquals(2, assertIs<ChipReadEnding.Complete>(observer.endings.single()).attempts)
            assertEquals(listOf(tag, tag), rig.platform.tagsConnected)
            assertTrue(currentTime >= lostAt + NfcChipRead.SAME_TAG_RETRY_MILLIS)
            assertTrue(rig.platform.connections.all { (it as ScriptedChipChannel).closeCalls == 1 })
        }

    @Test
    fun `a document that does not return ends the read when the return wait runs out`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            rig.platform.next += ScriptedChipChannel(failOnCommand = AFTER_ACCESS, failure = TagLostException("Tag was lost."))
            val tag = aTag()

            runCurrent()
            read.onTagDiscovered(tag)
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            rig.platform.gone += tag
            val lostAt = currentTime
            advanceTimeBy(NfcReadSettings.DEFAULT_WAIT_FOR_RETURN_MILLIS - 1)
            runCurrent()
            assertTrue(observer.endings.isEmpty(), "ended before the return wait ran out")
            // LinkLost stays, reported again every 2 seconds once the earlier tag was let go.
            assertTrue(observer.states().last() is ChipReadState.LinkLost)
            advanceTimeBy(1)
            runCurrent()

            val ending = assertIs<ChipReadEnding.Partial>(observer.endings.single())
            assertEquals(NfcTagLost, ending.stoppedBy)
            assertEquals(lostAt + NfcReadSettings.DEFAULT_WAIT_FOR_RETURN_MILLIS, currentTime)
            // One try of the earlier tag: its connect failed, so it was let go and never called again.
            assertEquals(2, rig.platform.connections.size)
            assertEquals(listOf(1, 0), rig.platform.connections.map { (it as ScriptedChipChannel).closeCalls })
        }

    @Test
    fun `NFC switched off mid-read ends through the system ending with no LinkLost`() =
        runTest {
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer)
            rig.platform.next += ScriptedChipChannel(failOnCommand = AFTER_ACCESS, failure = TagLostException("Tag was lost."))

            runCurrent()
            read.onTagDiscovered(aTag())
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS - 1)
            rig.platform.enabled = false
            advanceUntilIdle()

            val ending = assertIs<ChipReadEnding.EndedBySystem>(observer.endings.single())
            assertEquals(NfcUnavailable(NfcUnavailable.Reason.DISABLED), ending.reason)
            assertTrue(observer.states().none { it is ChipReadState.LinkLost })
            (rig.platform.connections.single() as ScriptedChipChannel).closedOnce()
        }

    // --- The earlier link's close -----------------------------------------------------------------

    @Test
    fun `the next link waits for the earlier close and goes ahead when the wait runs out`() =
        runTest {
            val rig = rig()
            rig.platform.holdReleases = true
            val firstObserver = RecordingChipObserver()
            val first = rig.start(firstObserver)
            runCurrent()
            first.onTagDiscovered(aTag())
            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(firstObserver.endings.single())
            assertEquals(1, rig.platform.heldReleases.size, "the close ran but the platform still holds the tag")

            // A read whose earlier close is still out: it connects only once the close is done.
            val secondObserver = RecordingChipObserver()
            val second = rig.start(secondObserver)
            runCurrent()
            second.onTagDiscovered(aTag())
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS + 1_000)
            runCurrent()
            val waiting = rig.platform.connections[1] as ScriptedChipChannel
            assertFalse(waiting.connected || waiting.commands.isNotEmpty(), "connected while the earlier close was out")
            rig.platform.heldReleases
                .removeAt(0)
                .invoke()
            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(secondObserver.endings.single())

            // A close that never reports back delays the next link by the command time limit only.
            val thirdObserver = RecordingChipObserver()
            val third = rig.start(thirdObserver, NfcReadSettings(settleDelayMillis = 0))
            runCurrent()
            val began = currentTime
            third.onTagDiscovered(aTag())
            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(thirdObserver.endings.single())
            assertEquals(began + NfcReadSettings.DEFAULT_COMMAND_TIMEOUT_MILLIS, currentTime)
        }

    // --- Blocking platform calls (real threads, latches only) -------------------------------------

    @Test
    fun `cancel during a blocked command ends at once and closes the link after it`() {
        val rig = poolRig()
        val observer = RecordingChipObserver()
        val chip = ScriptedChipChannel(blockOnCommand = 1)
        rig.platform.next += chip
        val read = rig.start(observer, NfcReadSettings(settleDelayMillis = 0))
        assertTrue(observer.waiting.await(10, TimeUnit.SECONDS))
        read.onTagDiscovered(aTag())
        assertTrue(chip.entered.await(10, TimeUnit.SECONDS), "no command was blocked")

        read.cancel()
        assertEquals(Thread.currentThread().name, observer.threads.single(), "the ending must come at once, on the cancelling thread")
        assertEquals(ChipReadState.Stopping, observer.updates.last().state)
        assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
        read.cancel()
        assertOneEnding(observer)

        chip.release.countDown()
        assertTrue(chip.closed.await(10, TimeUnit.SECONDS), "the link was not closed")
        chip.closedOnce()
        assertEquals(1, chip.commands.size, "a command went out after the cancelled one")
    }

    @Test
    fun `cancel during a blocked connect still closes the link`() {
        val rig = poolRig()
        val observer = RecordingChipObserver()
        val chip = ScriptedChipChannel(blockConnect = true)
        rig.platform.next += chip
        val read = rig.start(observer, NfcReadSettings(settleDelayMillis = 0))
        assertTrue(observer.waiting.await(10, TimeUnit.SECONDS))
        read.onTagDiscovered(aTag())
        assertTrue(chip.entered.await(10, TimeUnit.SECONDS), "the connect never started")

        read.cancel()
        assertIs<ChipReadEnding.Cancelled>(observer.endings.single())
        chip.release.countDown()
        assertTrue(chip.closed.await(10, TimeUnit.SECONDS), "a connect that finished after the cancel left the tag connected")
        assertTrue(chip.commands.isEmpty())
    }

    @Test
    fun `cancel racing a lost link and NFC off gives exactly one ending every time`() {
        repeat(40) { round ->
            val rig = poolRig()
            val observer = RecordingChipObserver()
            val chip = ScriptedChipChannel(blockOnCommand = 2, failOnCommand = 2, failure = TagLostException("Tag was lost."))
            rig.platform.next += chip
            val read = rig.start(observer, NfcReadSettings(settleDelayMillis = 0))
            assertTrue(observer.waiting.await(10, TimeUnit.SECONDS))
            read.onTagDiscovered(aTag())
            assertTrue(chip.entered.await(10, TimeUnit.SECONDS))
            if (round % 2 == 0) rig.platform.enabled = false
            val go = CountDownLatch(1)
            val canceller =
                Thread {
                    go.await()
                    read.cancel()
                }.apply { start() }
            go.countDown()
            chip.release.countDown()
            canceller.join(10_000)
            assertTrue(observer.ended.await(10, TimeUnit.SECONDS))
            assertTrue(chip.closed.await(10, TimeUnit.SECONDS))
            read.cancel()
            assertOneEnding(observer)
            chip.closedOnce()
        }
    }

    // --- Reader mode and settings -----------------------------------------------------------------

    @Test
    fun `reader mode flags cover NFC-A and NFC-B and skip the platform's NDEF check`() {
        assertEquals(
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            NfcReaderMode.FLAGS,
        )
        assertEquals(1_000, NfcReaderMode.extras().getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
        assertEquals(500, NfcReaderMode.extras(500).getInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY))
    }

    @Test
    fun `the settings defaults match the iPhone read's where the meaning is the same`() {
        val settings = NfcReadSettings()
        assertEquals(LinkLossPolicy.Resume(2), settings.linkLoss)
        assertEquals(400L, settings.settleDelayMillis)
        assertEquals(5_000L, settings.commandTimeoutMillis)
        assertEquals(1_500L, settings.commandTimeoutBeforeAccessMillis)
        assertEquals(2_000L, settings.readTimeoutMillis)
        assertEquals(15_000L, settings.waitForReturnMillis)
        assertEquals(120_000L, settings.waitForDocumentMillis)
        assertEquals(1_000L, settings.presenceCheckDelayMillis)
        assertTrue(settings.platformSounds)
        assertEquals(ObserverThread.MAIN, settings.observerOn)
        assertEquals(
            "NfcReadSettings(linkLoss=Resume(2), settleDelayMillis=400, commandTimeoutMillis=5000, readTimeoutMillis=2000, " +
                "commandTimeoutBeforeAccessMillis=1500, waitForReturnMillis=15000, waitForDocumentMillis=120000, " +
                "presenceCheckDelayMillis=1000, " +
                "platformSounds=true, observerOn=MAIN)",
            settings.toString(),
        )
        val built =
            NfcReadSettings
                .Builder()
                .linkLoss(LinkLossPolicy.ReadAgain(1))
                .commandTimeoutMillis(3_000)
                .settleDelayMillis(100)
                .waitForReturnMillis(8_000)
                .waitForDocumentMillis(30_000)
                .commandTimeoutBeforeAccessMillis(800)
                .readTimeoutMillis(1_200)
                .build()
        assertEquals(
            "NfcReadSettings(linkLoss=ReadAgain(1), settleDelayMillis=100, commandTimeoutMillis=3000, readTimeoutMillis=1200, " +
                "commandTimeoutBeforeAccessMillis=800, waitForReturnMillis=8000, waitForDocumentMillis=30000, " +
                "presenceCheckDelayMillis=1000, " +
                "platformSounds=true, observerOn=MAIN)",
            built.toString(),
        )
        // A limit set, then taken away with the no-limit call, waits for ever.
        assertEquals(
            NfcReadSettings.NO_LIMIT,
            built
                .toBuilder()
                .noLimitForDocument()
                .build()
                .waitForDocumentMillis,
        )
        assertEquals(800L, built.toBuilder().build().commandTimeoutBeforeAccessMillis, "the builder keeps the value")
        assertEquals(1_200L, built.toBuilder().build().readTimeoutMillis, "the builder keeps the value")
    }

    @Test
    fun `the settings refuse values the platform would refuse`() {
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(commandTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(commandTimeoutMillis = Int.MAX_VALUE + 1L) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(settleDelayMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(waitForReturnMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(waitForDocumentMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(presenceCheckDelayMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(presenceCheckDelayMillis = Int.MAX_VALUE + 1L) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(commandTimeoutBeforeAccessMillis = 0) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(commandTimeoutBeforeAccessMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(commandTimeoutBeforeAccessMillis = Int.MAX_VALUE + 1L) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings.Builder().commandTimeoutBeforeAccessMillis(0).build() }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(readTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(readTimeoutMillis = -1) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings(readTimeoutMillis = Int.MAX_VALUE + 1L) }
        assertFailsWith<IllegalArgumentException> { NfcReadSettings.Builder().readTimeoutMillis(0).build() }
        // Each kind of command gets its limit; the two shorter ones never exceed the long one.
        val three = NfcReadSettings(commandTimeoutMillis = 4_000, readTimeoutMillis = 1_000, commandTimeoutBeforeAccessMillis = 700)
        assertEquals(700L, three.timeoutFor(ChipCommandTimeLimit.BEFORE_ACCESS))
        assertEquals(1_000L, three.timeoutFor(ChipCommandTimeLimit.READ))
        assertEquals(4_000L, three.timeoutFor(ChipCommandTimeLimit.LONG))
        val capped = NfcReadSettings(commandTimeoutMillis = 1_000, readTimeoutMillis = Int.MAX_VALUE.toLong())
        assertEquals(1_000L, capped.timeoutFor(ChipCommandTimeLimit.READ))
        assertEquals(1L, NfcReadSettings(readTimeoutMillis = 1).timeoutFor(ChipCommandTimeLimit.READ))
        // The edges are accepted; a value above the general limit gives way to it.
        assertEquals(1L, NfcReadSettings(commandTimeoutBeforeAccessMillis = 1).effectiveTimeoutBeforeAccessMillis)
        assertEquals(
            1_000L,
            NfcReadSettings(
                commandTimeoutMillis = 1_000,
                commandTimeoutBeforeAccessMillis = Int.MAX_VALUE.toLong(),
            ).effectiveTimeoutBeforeAccessMillis,
        )
    }

    // --- The polish round: the telemetry sink, the key's lifetime, the settings builder ------------

    @Test
    fun `a telemetry sink in the settings gets the read's step events and the read ends complete`() =
        runTest {
            val rig = rig()
            val events = Collections.synchronizedList(mutableListOf<io.lightine.tessera.telemetry.TelemetryEvent>())
            val sink =
                object : io.lightine.tessera.telemetry.TelemetrySink {
                    override fun record(event: io.lightine.tessera.telemetry.TelemetryEvent) {
                        events += event
                    }
                }
            val observer = RecordingChipObserver()
            val read = rig.start(observer, settings = NfcReadSettings(telemetry = sink))
            runCurrent()
            read.onTagDiscovered(aTag())
            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(observer.endings.single())
            val steps = events.filterIsInstance<io.lightine.tessera.emrtd.ChipReadEvent>()
            assertTrue(steps.isNotEmpty(), "the sink must hear the read's steps: $events")
            assertTrue(steps.any { it.step == io.lightine.tessera.emrtd.ChipReadStep.READ_FILE })
        }

    @Test
    fun `the read clears the key at its ending, every kind of ending`() =
        runTest {
            // Complete.
            val complete = ChipAccessKey.Can("123456")
            val rig = rig()
            val a = RecordingChipObserver()
            val read = rig.start(a, options = ChipReadOptions(complete, setOf(1)))
            runCurrent()
            assertFalse(complete.isCleared, "the key stays usable while the read runs")
            read.onTagDiscovered(aTag())
            advanceUntilIdle()
            assertIs<ChipReadEnding.Complete>(a.endings.single())
            assertTrue(complete.isCleared, "complete")

            // Cancelled by the app.
            val cancelled = ChipAccessKey.Can("123456")
            val rig2 = rig()
            val read2 = rig2.start(RecordingChipObserver(), options = ChipReadOptions(cancelled, setOf(1)))
            runCurrent()
            read2.cancel()
            assertTrue(cancelled.isCleared, "cancelled")

            // Not started: the device has no NFC.
            val none = ChipAccessKey.Can("123456")
            rig().start(RecordingChipObserver(), adapter = null, options = ChipReadOptions(none, setOf(1)))
            runCurrent()
            assertTrue(none.isCleared, "not started")
        }

    @Test
    fun `a lost link keeps the key usable for the restart and the ending clears it`() =
        runTest {
            val key = ChipAccessKey.Can("123456")
            val rig = rig()
            val observer = RecordingChipObserver()
            val read = rig.start(observer, options = ChipReadOptions(key, setOf(1)))
            rig.platform.next += ScriptedChipChannel(failOnCommand = AFTER_ACCESS, failure = TagLostException("Tag was lost."))
            val tag = aTag()
            runCurrent()
            read.onTagDiscovered(tag)
            advanceTimeBy(NfcReadSettings.DEFAULT_SETTLE_DELAY_MILLIS)
            runCurrent()
            rig.platform.gone += tag
            advanceTimeBy(1_000)
            runCurrent()
            assertFalse(key.isCleared, "waiting for the document to return: the restart needs the key")
            assertTrue(observer.endings.isEmpty())
            read.cancel()
            assertTrue(key.isCleared)
        }

    @Test
    fun `the settings builder changes one value and toBuilder starts from an existing object`() {
        val sink =
            object : io.lightine.tessera.telemetry.TelemetrySink {
                override fun record(event: io.lightine.tessera.telemetry.TelemetryEvent) = Unit
            }
        val base =
            NfcReadSettings
                .Builder()
                .linkLoss(LinkLossPolicy.readAgain(1))
                .telemetry(sink)
                .build()
        val changed = base.toBuilder().settleDelayMillis(0).build()
        assertEquals(0L, changed.settleDelayMillis)
        assertEquals(LinkLossPolicy.ReadAgain(1), changed.linkLoss)
        assertTrue(changed.telemetry === sink)
        assertEquals(400L, base.settleDelayMillis)
        assertNull(NfcReadSettings().telemetry)
    }

    @Test
    fun `the entry point asks the availability question the same way reader mode does`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(NfcReaderMode.availability(context), NfcChipRead.availability(context))
        // The answer type is the one the iPhone read returns.
        val answer: NfcUnavailable.Reason? = NfcChipRead.availability(context)
        assertTrue(answer == null || answer in NfcUnavailable.Reason.entries)
    }
}
