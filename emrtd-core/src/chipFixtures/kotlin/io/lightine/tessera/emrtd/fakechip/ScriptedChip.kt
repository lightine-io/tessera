package io.lightine.tessera.emrtd.fakechip

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.emrtd.ChipStartSelection
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/** What the scripted link does with one command: an answer after a delay, a lost link, or nothing at all. */
internal sealed class ChipReply {
    /** Virtual milliseconds from the command to the reply. */
    abstract val delayMillis: Long

    /** The chip's answer (data and status word), after [delayMillis]. */
    class Answer(
        val bytes: ByteArray,
        override val delayMillis: Long,
    ) : ChipReply() {
        val sw: Int get() = if (bytes.size < 2) -1 else ((bytes[bytes.size - 2].toInt() and 0xFF) shl 8) or (bytes.last().toInt() and 0xFF)

        override fun toString(): String = "Answer(${bytes.size} bytes, sw=${sw.toString(16)}, ${delayMillis}ms)"
    }

    /** A transport-level failure with no status word, after [delayMillis]: the frame or the link is gone. */
    class LinkLost(
        override val delayMillis: Long,
    ) : ChipReply() {
        override fun toString(): String = "LinkLost(${delayMillis}ms)"
    }

    /** The chip never answers: only the platform's or the SDK's own time limit ends the wait. */
    object Silent : ChipReply() {
        override val delayMillis: Long = Long.MAX_VALUE

        override fun toString(): String = "Silent"
    }
}

/** One command as the scripted link saw it. */
internal class ChipCommand(
    /** From 0, over the chip's whole life (every link). */
    val index: Int,
    /** From 0, within the current link. */
    val indexInLink: Int,
    /** The link, from 1: each connect is a new link. */
    val link: Int,
    val bytes: ByteArray,
    /** The command decoded, or `null` when it is not a valid APDU. */
    val apdu: CommandApdu?,
    val chip: FakeChip,
) {
    val ins: Int get() = apdu?.ins ?: -1

    /** Secure messaging is indicated in the class byte (Part 11, 9.8). */
    val secured: Boolean get() = apdu != null && apdu.cla and 0x0C == 0x0C

    override fun toString(): String = "#$index link $link ins=${ins.toString(16)}${if (secured) " SM" else ""}"
}

/** A command as sent, with its reply and the virtual time it was sent at. */
internal class ChipExchange(
    val command: ChipCommand,
    val reply: ChipReply,
    val sentAtMillis: Long,
) {
    override fun toString(): String = "$command -> $reply @${sentAtMillis}ms"
}

/** What a fault does to a command it matches. */
internal sealed class ChipFaultAction {
    /** Answer with this bare status word and no data, without passing the command to the chip. */
    class Status(
        val sw: Int,
    ) : ChipFaultAction()

    /** Lose the link (no status word) after [afterMillis]. */
    class LoseLink(
        val afterMillis: Long = 0,
    ) : ChipFaultAction()

    /** Never answer. */
    object Silence : ChipFaultAction()

    /** Let the chip answer, then keep only the first [keep] bytes of the answer (its status word may go too). */
    class Truncate(
        val keep: Int,
    ) : ChipFaultAction()

    /** Let the chip answer, then put [extra] in front of its status word (an answer longer than asked for). */
    class Extend(
        val extra: ByteArray,
    ) : ChipFaultAction()

    /** Let the chip answer after [millis] instead of the profile's latency. */
    class Delay(
        val millis: Long,
    ) : ChipFaultAction()

    /** Run [before] (for example to arm a secure-messaging fault in [FakeSm]) and then let the chip answer. */
    class Arm(
        val before: () -> Unit,
    ) : ChipFaultAction()
}

/** A fault: the first [times] commands that [match] get [action]. */
internal class ChipFault(
    var times: Int = 1,
    val action: ChipFaultAction,
    val match: (ChipCommand) -> Boolean,
) {
    var fired: Int = 0
        private set

    fun take(command: ChipCommand): Boolean {
        if (fired >= times || !match(command)) return false
        fired++
        return true
    }
}

/**
 * The scripted chip behind every simulated link (TES-346): a [FakeChip] that answers, plus the
 * script of the link around it, in virtual time.
 *
 * - [latencyMillis] says how long the chip takes to answer each command (the device profile, a
 *   slow chip, or 0).
 * - [faults] change single commands: any status word at any command (bare, even inside secure
 *   messaging), a lost link, no answer at all, a cut or over-long answer, a delay, or a
 *   secure-messaging fault armed in [FakeSm] (a bad MAC).
 * - [newLink] is called by the platform fake at every connect: the chip loses power (its session
 *   keys are gone, Part 11, 9.8.5) and [nextChip], when set, takes its place: the same document,
 *   another document with another key, or the same key with another EF.SOD.
 * - [exchanges] records every command with its reply and the virtual time it was sent at.
 *
 * The platform adapters turn a [ChipReply] into what their platform does: a delayed callback on
 * iOS, a blocking call that throws `TagLostException` on Android, a suspended call in the core.
 */
internal class ScriptedChip(
    chip: FakeChip,
    var latencyMillis: (ChipCommand) -> Long = { 0L },
) {
    var chip: FakeChip = chip
        private set

    /** The chip the next link finds, when another one should take this one's place. */
    var nextChip: FakeChip? = null

    val faults: MutableList<ChipFault> = mutableListOf()
    val exchanges: MutableList<ChipExchange> = mutableListOf()

    /** The links set up so far. */
    var links: Int = 0
        private set

    private var commands = 0
    private var commandsInLink = 0

    /** A new link (a connect): the chip powers up again; [nextChip] takes over when set. */
    fun newLink() {
        links++
        nextChip?.let {
            chip = it
            nextChip = null
        }
        chip.powerCycle()
        commandsInLink = 0
    }

    /** Adds a fault for the first [times] commands that [match]. */
    fun fault(
        action: ChipFaultAction,
        times: Int = 1,
        match: (ChipCommand) -> Boolean,
    ): ChipFault = ChipFault(times, action, match).also { faults += it }

    /** What the chip does with [bytes], sent at [nowMillis] of virtual time. */
    fun respond(
        bytes: ByteArray,
        nowMillis: Long = 0,
    ): ChipReply {
        if (links == 0) newLink()
        val command = ChipCommand(commands++, commandsInLink++, links, bytes.copyOf(), CommandApdu.decode(bytes), chip)
        val fault = faults.firstOrNull { it.take(command) }
        val latency = latencyMillis(command)
        val reply =
            when (val action = fault?.action) {
                is ChipFaultAction.Status -> ChipReply.Answer(byteArrayOf((action.sw shr 8).toByte(), action.sw.toByte()), latency)
                is ChipFaultAction.LoseLink -> ChipReply.LinkLost(action.afterMillis)
                ChipFaultAction.Silence -> ChipReply.Silent
                is ChipFaultAction.Truncate -> chipReply(bytes, latency) { it.copyOf(minOf(action.keep, it.size)) }
                is ChipFaultAction.Extend -> chipReply(bytes, latency) { it.copyOf(it.size - 2) + action.extra + it.copyOfRange(it.size - 2, it.size) }
                is ChipFaultAction.Delay -> chipReply(bytes, action.millis) { it }
                is ChipFaultAction.Arm -> {
                    action.before()
                    chipReply(bytes, latency) { it }
                }
                null -> chipReply(bytes, latency) { it }
            }
        exchanges += ChipExchange(command, reply, nowMillis)
        return reply
    }

    private inline fun chipReply(
        bytes: ByteArray,
        latency: Long,
        change: (ByteArray) -> ByteArray,
    ): ChipReply =
        try {
            ChipReply.Answer(change(chip.answer(bytes)), latency)
        } catch (e: ApduTransportException) {
            ChipReply.LinkLost(latency)
        }

    /** The exchanges of [link]. */
    fun exchangesOf(link: Int): List<ChipExchange> = exchanges.filter { it.command.link == link }
}

/**
 * The scripted chip as a core [ApduTransport] for one link: each command waits its latency on the
 * caller's (virtual) clock; a lost link throws [NfcTagLost]; a silent chip suspends until the
 * caller gives up.
 */
internal class ScriptedTransport(
    private val script: ScriptedChip,
    private val now: () -> Long = { 0L },
) : ApduTransport {
    init {
        script.newLink()
    }

    override val maxCommandLength: Int get() = script.chip.maxCommandLength
    override val supportsExtendedLength: Boolean get() = script.chip.supportsExtendedLength
    override val startSelection: ChipStartSelection get() = script.chip.startSelection

    override suspend fun transceive(command: ByteArray): ByteArray =
        when (val reply = script.respond(command, now())) {
            is ChipReply.Answer -> {
                delay(reply.delayMillis)
                reply.bytes
            }

            is ChipReply.LinkLost -> {
                delay(reply.delayMillis)
                throw ApduTransportException(NfcTagLost)
            }

            ChipReply.Silent -> {
                awaitCancellation()
            }
        }
}
