package io.lightine.tessera.emrtd.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.os.Bundle
import io.lightine.tessera.emrtd.ChipReadOptions
import io.lightine.tessera.emrtd.fakechip.ChipReply
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.telemetry.TelemetrySink
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * The scripted chip as the platform's IsoDep (TES-346). Calls block, as on the platform, so the
 * chip's latency cannot wait on the test scheduler: it is added to [AndroidSim.chipMillis], a clock
 * the read's own clock includes. An answer slower than the time limit the transport set, a silent
 * chip, and a lost link all throw `TagLostException`, as the platform does (DES a32, a33: a
 * time-out looks like a lost tag).
 */
internal class ScriptedIsoDep(
    private val sim: AndroidSim,
) : IsoDepChannel {
    override val maxTransceiveLength: Int = 261
    override val isExtendedLengthApduSupported: Boolean = false

    @Volatile override var isConnected: Boolean = false
        private set

    val timeouts = mutableListOf<Int>()
    var closeCalls = 0

    override fun connect() {
        if (sim.paused) throw TagLostException("Tag was lost.")
        sim.script.newLink()
        isConnected = true
    }

    override fun setTimeout(timeoutMillis: Int) {
        timeouts += timeoutMillis
    }

    private val limit: Long get() = timeouts.lastOrNull()?.toLong() ?: PLATFORM_DEFAULT_TIMEOUT_MILLIS

    override fun transceive(command: ByteArray): ByteArray {
        if (!isConnected) throw IllegalStateException("Call connect() first!")
        if (!sim.enabled || sim.paused) throw java.io.IOException("Transceive failed")
        return when (val reply = sim.script.respond(command, sim.now())) {
            is ChipReply.Answer -> {
                if (reply.delayMillis > limit) {
                    sim.chipMillis += limit
                    throw TagLostException("Tag was lost.")
                }
                sim.chipMillis += reply.delayMillis
                reply.bytes
            }

            is ChipReply.LinkLost -> {
                sim.chipMillis += reply.delayMillis
                throw TagLostException("Tag was lost.")
            }

            ChipReply.Silent -> {
                sim.chipMillis += limit
                throw TagLostException("Tag was lost.")
            }
        }
    }

    override fun close() {
        closeCalls++
        isConnected = false
    }

    companion object {
        /** The platform's ISO-DEP default before the transport sets its own (DES a37). */
        const val PLATFORM_DEFAULT_TIMEOUT_MILLIS = 618L
    }
}

/**
 * A whole Android read in virtual time (TES-346): [NfcChipRead] with a fake platform whose every
 * connection is a new link to the scripted chip. The read's coroutines run on the test scheduler;
 * the chip's time runs on [chipMillis] (see [ScriptedIsoDep]).
 */
internal class AndroidSim(
    private val test: TestScope,
    val script: ScriptedChip,
) {
    private val dispatcher = StandardTestDispatcher(test.testScheduler)

    @Volatile var chipMillis = 0L

    /**
     * The read's clock: the scheduler's time, the chip's time, and an offset like a phone's uptime
     * (the platform clock never starts at 0, so a sum with a large wait can overflow, as on a device).
     */
    val now: () -> Long = { UPTIME_MILLIS + test.testScheduler.currentTime + chipMillis }

    @Volatile var enabled = true

    /**
     * The platform has turned reader mode off (the Activity paused, the screen locked, or the
     * reader option is off): connects fail with `TagLostException`, commands with an IOException.
     */
    @Volatile var paused = false
    val channels = mutableListOf<ScriptedIsoDep>()

    /** Tags that speak no ISO-DEP. */
    val notIsoDep = mutableSetOf<Tag>()

    private val platform =
        object : NfcChipPlatform {
            override fun isEnabled(adapter: NfcAdapter): Boolean = enabled

            override fun connectionOf(
                tag: Tag,
                timeoutMillis: Long,
                onReleased: () -> Unit,
            ): IsoDepConnection? {
                if (tag in notIsoDep) return null
                val channel = ScriptedIsoDep(this@AndroidSim).also { channels += it }
                return connectionOver(channel, timeoutMillis, dispatcher, onReleased)
            }
        }

    val env = NfcReadEnvironment(platform, dispatcher, now, NfcReadRegistry())

    fun start(
        options: ChipReadOptions,
        settings: NfcReadSettings = NfcReadSettings(),
        telemetry: TelemetrySink? = null,
    ): Pair<NfcChipRead, SimRecorder> {
        val recorder = SimRecorder(now)
        // A sink goes in through the settings, as an app passes it to the easy entry point.
        val withSink = if (telemetry == null) settings else settings.toBuilder().telemetry(telemetry).build()
        val read = NfcChipRead.start(options, adapter(), recorder, withSink, env)
        test.runCurrent()
        return read to recorder
    }

    fun step(millis: Long) {
        test.advanceTimeBy(millis)
        test.runCurrent()
    }

    /** Moves virtual time on until [recorder] has its ending, or [limitMillis] passed. */
    fun runUntilEnded(
        recorder: SimRecorder,
        limitMillis: Long = 120_000,
    ) {
        val until = now() + limitMillis
        while (recorder.endings.isEmpty() && now() < until) step(STEP_MILLIS)
        step(STEP_MILLIS)
    }

    companion object {
        const val STEP_MILLIS = 5L

        /** About 58 days of uptime, in milliseconds. */
        const val UPTIME_MILLIS = 5_000_000_000L

        /** An empty platform `Tag`: the fake platform never looks inside. */
        fun aTag(): Tag {
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

        fun adapter(): NfcAdapter =
            NfcAdapter::class.java
                .getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
    }
}
