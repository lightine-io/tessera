package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.ChipReader
import io.lightine.tessera.emrtd.fakechip.ChipReply
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcError
import io.lightine.tessera.types.errors.NfcTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import platform.CoreNFC.NFCErrorDomain

/** The eMRTD application's identifier as Core NFC reports `initialSelectedAID` (Part 10, 2.1). */
internal const val EMRTD_AID = "A0000002471001"

/**
 * The scripted chip as Core NFC's ISO 7816 tag (TES-346): each command's reply comes back on the
 * session's queue after its latency in virtual time; a lost link is Core NFC's code 102
 * (`tagResponseError`), as recorded on devices; a silent chip keeps the completion forever, so
 * only the SDK's own command limit ends the wait.
 *
 * [connectedLimitMillis], when set, models the platform's limit on one connected tag (DES i33,
 * about 20 s): a command sent after it fails with code 100 (`tagConnectionLost`); with
 * [IosSim.connectedLimitEndsSession], the system also ends the whole session then, with code 202,
 * as a device did mid-read.
 */
internal class ScriptedIso7816(
    private val sim: IosSim,
    override val initialSelectedAid: String?,
    override val supportsPace: Boolean? = null,
    private val port: FakeSessionPort? = null,
) : Iso7816Channel {
    var connectedAt: Long = 0
    val held = mutableListOf<(ByteArray?, NfcError?) -> Unit>()

    override fun send(
        command: ByteArray,
        onDone: (answer: ByteArray?, error: NfcError?) -> Unit,
    ) {
        val limit = sim.connectedLimitMillis
        if (limit != null && sim.now() - connectedAt >= limit) {
            sim.script.respond(command, sim.now())
            sim.later(0) { onDone(null, IosSim.codeError(CoreNfcErrorCode.TAG_CONNECTION_LOST)) }
            val session = port
            if (sim.connectedLimitEndsSession && session != null && session.systemEnds == 0 && !session.invalidated) {
                sim.later(0) {
                    session.systemEnd(
                        IosSim.codeError(CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY, CoreNfcErrorSource.SESSION_END),
                        CoreNfcErrorCode.SESSION_TERMINATED_UNEXPECTEDLY,
                    )
                }
            }
            return
        }
        when (val reply = sim.script.respond(command, sim.now())) {
            is ChipReply.Answer -> sim.later(reply.delayMillis) { onDone(reply.bytes, null) }
            is ChipReply.LinkLost -> sim.later(reply.delayMillis) { onDone(null, IosSim.codeError(CoreNfcErrorCode.TAG_RESPONSE_ERROR)) }
            ChipReply.Silent -> held += onDone
        }
    }
}

/** A tag in the field: each connect is a new link to the scripted chip (it powers up again). */
internal class ScriptedTag(
    private val sim: IosSim,
    private val channel: ScriptedIso7816,
) : DetectedTag {
    override val iso7816: Iso7816Channel get() = channel

    override fun connect(onDone: (failure: ConnectFailure?) -> Unit) {
        val failure = sim.connectFailures.removeFirstOrNull()
        if (failure != null) {
            // As Core NFC: a lost tag (100) and a chip that did not answer (101) may be tried again.
            val retryable =
                failure is io.lightine.tessera.types.errors.NfcTagLost ||
                    (failure is NfcTimeout && failure.limit == NfcTimeout.Limit.COMMAND)
            sim.later(sim.connectMillis) { onDone(ConnectFailure(failure, retryable)) }
            return
        }
        sim.script.newLink()
        channel.connectedAt = sim.now() + sim.connectMillis
        sim.later(sim.connectMillis) { onDone(null) }
    }
}

/**
 * A whole iPhone read in virtual time (TES-346): [CoreNfcChipRead] with fake Core NFC sessions and
 * the scripted chip behind a fake tag. The document is near the phone ([documentInField]): the
 * session finds it [arriveAfterMillis] after it begins, and again [returnAfterMillis] after each
 * restart of polling, as Core NFC does when the document is still there.
 */
internal class IosSim(
    private val test: TestScope,
    val script: ScriptedChip,
    val initialSelectedAid: String? = EMRTD_AID,
    val registry: ChipReadRegistry = ChipReadRegistry(),
) {
    private val runner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
    val now: () -> Long = { test.testScheduler.currentTime }
    val ports = mutableListOf<FakeSessionPort>()
    val tags = mutableListOf<ScriptedTag>()
    val channels = mutableListOf<ScriptedIso7816>()

    var documentInField = true
    var arriveAfterMillis = 1_000L
    var returnAfterMillis = 600L
    var connectMillis = 20L
    var connectedLimitMillis: Long? = null

    /** Whether the system ends the session when [connectedLimitMillis] passes on a tag (code 202). */
    var connectedLimitEndsSession = false

    /** Connect failures for the next connects, in order (a lost link while connecting, for example). */
    val connectFailures = ArrayDeque<NfcError>()

    /** When set, the system refuses to begin the next session with this error (code 203: busy). */
    var refuseBeginWith: NfcError? = null

    val env =
        CoreNfcReadEnvironment(
            readingAvailable = { true },
            openSession = { events, _ ->
                FakeSessionPort(events).also { port ->
                    port.clock = now
                    port.onBegin = { p ->
                        val refusal = refuseBeginWith
                        if (refusal != null) {
                            refuseBeginWith = null
                            // A refused session never becomes active.
                            p.reportActive = false
                            later(0) { p.systemEnd(refusal) }
                        } else {
                            findDocument(p, arriveAfterMillis)
                        }
                    }
                    port.onRestart = { p -> findDocument(p, returnAfterMillis) }
                    ports += port
                }
            },
            scope = runner,
            now = now,
            registry = registry,
            reader = ChipReader(),
        )

    val port: FakeSessionPort get() = ports.last()

    /** Every text the sheet showed, over every session, in order. */
    val alerts: List<String> get() = ports.flatMap { it.alerts }

    fun later(
        millis: Long,
        block: () -> Unit,
    ): Job =
        runner.launch {
            delay(millis)
            block()
        }

    private fun findDocument(
        port: FakeSessionPort,
        afterMillis: Long,
    ) {
        later(afterMillis) {
            if (documentInField && !port.invalidated && port.systemEnds == 0) {
                val channel = ScriptedIso7816(this, initialSelectedAid, port = port).also { channels += it }
                val tag = ScriptedTag(this, channel).also { tags += it }
                port.events.onTagsDetected(listOf(tag))
            }
        }
    }

    fun start(
        options: ChipReadOptions,
        settings: CoreNfcReadSettings = CoreNfcReadSettings(),
        texts: CoreNfcSheetTexts = CoreNfcDefaultSheetTexts,
        telemetry: TelemetrySink? = null,
    ): Pair<CoreNfcChipRead, SimRecorder> {
        val recorder = SimRecorder(now)
        // A sink goes in through the settings, as an app passes it to the easy entry point.
        val withSink = if (telemetry == null) settings else settings.toBuilder().telemetry(telemetry).build()
        val read = CoreNfcChipRead.start(options, recorder, texts, withSink, env)
        test.runCurrent()
        return read to recorder
    }

    /** Moves virtual time on in small steps until [recorder] has its ending, or [limitMillis] passed. */
    fun runUntilEnded(
        recorder: SimRecorder,
        limitMillis: Long = 120_000,
    ) {
        val until = now() + limitMillis
        while (recorder.endings.isEmpty() && now() < until) {
            test.advanceTimeBy(STEP_MILLIS)
            test.runCurrent()
        }
        // Let what the ending set off (closing the session, late callbacks) run too.
        test.advanceTimeBy(STEP_MILLIS)
        test.runCurrent()
    }

    fun step(millis: Long) {
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    /** The system ends the current session with Core NFC code [code], and reports the code as a device does. */
    fun systemEnds(code: Long) {
        port.systemEnd(codeError(code, CoreNfcErrorSource.SESSION_END), code)
        test.runCurrent()
    }

    fun close() = runner.cancel()

    companion object {
        const val STEP_MILLIS = 5L

        fun codeError(
            code: Long,
            source: CoreNfcErrorSource = CoreNfcErrorSource.TAG_OPERATION,
        ): NfcError = nfcErrorFor(NFCErrorDomain, code, source)
    }
}
