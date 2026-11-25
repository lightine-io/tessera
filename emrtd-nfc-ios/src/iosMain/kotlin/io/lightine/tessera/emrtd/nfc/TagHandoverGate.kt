package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.ApduTransportException
import io.lightine.tessera.types.errors.NfcTagLost
import kotlin.concurrent.Volatile

/**
 * Stops an attempt on purpose, between two commands, before the system's limit on one tag
 * connection (TES-346): Core NFC's vendor engineers have stated 20 seconds per tag connection, and
 * on a device a session whose tag passed it was ended by the system mid-read. The read then asks
 * the system for a fresh connection and resumes ([CoreNfcReadSettings.tagTimeLimitMillis]).
 *
 * The decision is made only when a command is about to go out, never while one is out, so a
 * command in flight always finishes first. With `left` the time to [limitMillis] on this tag:
 * - never inside a step that makes the chip do public-key work (BAC's EXTERNAL AUTHENTICATE,
 *   PACE's and chip authentication's GENERAL AUTHENTICATE), nor right after such a step or after
 *   MSE, GET CHALLENGE or INTERNAL AUTHENTICATE: the next command is the chip's first answer under
 *   the keys the step set up (the SELECT that confirms chip authentication, for example);
 * - before a command that starts such a step (MSE, which starts PACE and chip authentication; GET
 *   CHALLENGE, which starts BAC; INTERNAL AUTHENTICATE) when `left` is below [heavyReserveMillis],
 *   so the step never starts with too little time to finish;
 * - before a SELECT, a file boundary, when `left` is below [FILE_BOUNDARY_WINDOW_MILLIS];
 * - before any other command once `left` is 0, between two READ BINARY commands for example.
 *
 * Each time it would hand over, [mayHandOver] is asked (the read's bounds); `true` takes one
 * handover. Once it handed over, every command fails with [NfcTagLost] without being sent, as on
 * a lost link, so the read flow keeps the bytes of a cut-off file for the resume.
 */
internal class TagHandoverGate(
    private val base: ApduTransport,
    private val tagMillis: () -> Long,
    private val limitMillis: Long,
    private val heavyReserveMillis: Long,
    private val mayHandOver: () -> Boolean,
) : ApduTransport by base {
    /** The time on the tag when the gate handed over; `null` until it did. */
    @Volatile
    var handedOverAt: Long? = null
        private set

    // The attempt's commands go one at a time, so this needs no lock.
    private var afterHeavy = false

    override suspend fun transceive(command: ByteArray): ByteArray {
        if (handedOverAt != null) throw ApduTransportException(NfcTagLost)
        val ins = if (command.size >= 4) command[1].toInt() and 0xFF else -1
        if (!afterHeavy && ins !in CONTINUES_HEAVY_STEP) {
            val tag = tagMillis()
            val left = limitMillis - tag
            val due =
                left <= 0 ||
                    (ins in STARTS_HEAVY_STEP && left < heavyReserveMillis) ||
                    (ins == INS_SELECT && left < FILE_BOUNDARY_WINDOW_MILLIS)
            if (due && mayHandOver()) {
                handedOverAt = tag
                throw ApduTransportException(NfcTagLost)
            }
        }
        afterHeavy = ins in HEAVY_STEP
        return base.transceive(command)
    }

    companion object {
        /**
         * How near the limit a file boundary is taken: 2 seconds, so a handover at a file
         * boundary costs at most that much of the connection and leaves no file to continue.
         */
        const val FILE_BOUNDARY_WINDOW_MILLIS = 2_000L

        private const val INS_SELECT = 0xA4
        private const val INS_MSE = 0x22
        private const val INS_EXTERNAL_AUTHENTICATE = 0x82
        private const val INS_GET_CHALLENGE = 0x84
        private const val INS_GENERAL_AUTHENTICATE = 0x86
        private const val INS_INTERNAL_AUTHENTICATE = 0x88

        /** Commands that start a step of public-key work (or its MSE). */
        private val STARTS_HEAVY_STEP = setOf(INS_MSE, INS_GET_CHALLENGE, INS_INTERNAL_AUTHENTICATE)

        /** Commands inside such a step: never handed over before. */
        private val CONTINUES_HEAVY_STEP = setOf(INS_EXTERNAL_AUTHENTICATE, INS_GENERAL_AUTHENTICATE)

        /** Commands after which the next one belongs to the step too (its first answer under new keys). */
        private val HEAVY_STEP = STARTS_HEAVY_STEP + CONTINUES_HEAVY_STEP
    }
}
