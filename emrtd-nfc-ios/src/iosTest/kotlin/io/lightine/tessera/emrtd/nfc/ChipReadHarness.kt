package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipAccessKey
import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadObserver
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.ChipReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

/** Records a read's updates and endings, in order. */
internal class Recorder : ChipReadObserver {
    val updates = mutableListOf<ChipReadUpdate>()
    val endings = mutableListOf<ChipReadEnding>()

    /** The clock the times below are read from; the harness's virtual clock when a test sets it. */
    var clock: () -> Long = { 0L }

    /** When each update and the ending came, by [clock]. */
    val updateTimes = mutableListOf<Long>()
    val endingTimes = mutableListOf<Long>()

    val states: List<ChipReadState> get() = updates.map { it.state }

    /** The states by name, for example `WaitingForDocument`. */
    val names: List<String> get() = states.map { it::class.simpleName ?: "?" }

    val ending: ChipReadEnding get() = endings.single()

    override fun onUpdate(update: ChipReadUpdate) {
        updates += update
        updateTimes += clock()
    }

    override fun onEnding(ending: ChipReadEnding) {
        endings += ending
        endingTimes += clock()
    }
}

/**
 * A whole read against fake Core NFC objects, on the test's virtual clock: the read's coroutine,
 * its waits and its time limits all run on the test scheduler, so [step] moves time exactly.
 */
internal class ChipReadHarness(
    private val test: TestScope,
    val registry: ChipReadRegistry = ChipReadRegistry(),
) {
    private val runner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))

    /** The scope the read runs on, for a chip that answers late. */
    val scope: CoroutineScope get() = runner
    val ports = mutableListOf<FakeSessionPort>()
    var readingAvailable = true
    var reportEnd = true
    var onBegin: (FakeSessionPort) -> Unit = {}

    /** Put inside each new port's text setter, before the read sets its first text. */
    var onSetAlert: (String) -> Unit = {}

    /** What the read's main queue does with a posted block: at once by default, or queued by a test. */
    var mainQueue: (() -> Unit) -> Unit = { it() }

    /** The worker each read's sheet calls run on: at once, in the caller, by default. */
    var sheetWorker: () -> SerialWorker = { InlineWorker }

    /** The worker each read's restartPolling calls run on: at once, in the caller, by default. */
    var restartWorker: () -> SerialWorker = { InlineWorker }
    val now: () -> Long = { test.testScheduler.currentTime }

    val env =
        CoreNfcReadEnvironment(
            readingAvailable = { readingAvailable },
            openSession = { events ->
                FakeSessionPort(events).also {
                    it.clock = now
                    it.reportEnd = reportEnd
                    it.onBegin = onBegin
                    it.onSetAlert = onSetAlert
                    ports += it
                }
            },
            scope = runner,
            now = now,
            registry = registry,
            reader = ChipReader(),
            mainQueue = { block -> mainQueue(block) },
            sheetWorker = { sheetWorker() },
            restartWorker = { restartWorker() },
        )

    val port: FakeSessionPort get() = ports.last()

    fun start(
        options: ChipReadOptions = canOptions(),
        settings: CoreNfcReadSettings = CoreNfcReadSettings(),
        texts: CoreNfcSheetTexts = CoreNfcDefaultSheetTexts,
        recorder: Recorder = Recorder(),
    ): Pair<CoreNfcChipRead, Recorder> {
        val read = CoreNfcChipRead.start(options, recorder, texts, settings, env)
        test.runCurrent()
        return read to recorder
    }

    /** Moves the virtual clock by [millis] and runs what is due. */
    fun step(millis: Long = 0) {
        if (millis > 0) test.advanceTimeBy(millis)
        test.runCurrent()
    }

    /** Puts [tag] on the reader of the current session. */
    fun present(tag: DetectedTag) {
        port.events.onTagsDetected(listOf(tag))
        step()
    }

    /** The system ends the current session with Core NFC code [code]. */
    fun systemEnds(code: Long) {
        port.systemEnd(nfcErrorFor(platform.CoreNFC.NFCErrorDomain, code, CoreNfcErrorSource.SESSION_END), code)
        step()
    }

    /**
     * No system session is left: every port was ended exactly once (by the SDK or by the system),
     * and nothing keeps the read alive.
     */
    fun assertNothingLeft(read: CoreNfcChipRead) {
        for (p in ports) {
            if (p.began) assertEquals(1, p.endings.size + p.systemEnds, "each session ends once: ${p.endings}, ${p.systemEnds}")
        }
        assertFalse(LiveSessions.contains(read), "the read is not kept alive")
        assertFalse(registry.isReleasing(), "no session is being released")
    }

    fun close() = runner.cancel()

    companion object {
        val CHECK_TIME: Instant = Instant.parse("2026-06-01T12:00:00Z")

        fun canOptions(): ChipReadOptions = ChipReadOptions(ChipAccessKey.Can("123456"), setOf(1), checkTime = CHECK_TIME)

        /** An MRZ key from synthetic fields, with correct check digits unless [wrongDigit]. */
        fun mrzOptions(wrongDigit: Boolean = false): ChipReadOptions {
            val number = "UT0000001"
            val birth = "800101"
            val expiry = "300101"
            val numberDigit = if (wrongDigit) shift(checkDigit(number)) else checkDigit(number)
            val key = ChipAccessKey.Mrz(number, numberDigit, birth, checkDigit(birth), expiry, checkDigit(expiry))
            return ChipReadOptions(key, setOf(1), checkTime = CHECK_TIME)
        }

        private fun shift(c: Char): Char = '0' + ((c - '0' + 1) % 10)

        /** ICAO Doc 9303 Part 3, section 4.9: weights 7, 3, 1. */
        fun checkDigit(field: String): Char {
            val weights = intArrayOf(7, 3, 1)
            val sum =
                field
                    .mapIndexed { i, c ->
                        val value =
                            when (c) {
                                in '0'..'9' -> c - '0'
                                in 'A'..'Z' -> c - 'A' + 10
                                else -> 0
                            }
                        value * weights[i % 3]
                    }.sum()
            return '0' + sum % 10
        }
    }
}

/** Runs [block] with a fresh harness and releases its coroutines afterwards. */
internal fun chipReadTest(block: suspend ChipReadHarness.() -> Unit) =
    runTest {
        val harness = ChipReadHarness(this)
        try {
            harness.block()
        } finally {
            harness.close()
        }
    }
