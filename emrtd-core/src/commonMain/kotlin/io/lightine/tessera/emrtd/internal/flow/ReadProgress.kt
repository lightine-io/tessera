package io.lightine.tessera.emrtd.internal.flow

import io.lightine.tessera.emrtd.ChipAuthMethod
import io.lightine.tessera.emrtd.ChipFileRef
import io.lightine.tessera.emrtd.ChipReadProgress
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol
import kotlin.math.floor
import kotlin.math.nextDown

/**
 * The read's progress as a work estimate that never goes back (the "remaining span" rule).
 *
 * Work is counted in units of about one byte of protected READ BINARY. The plan holds an estimate
 * of the units each item still needs; on each step with `d` units just done and `R` units still
 * planned, `p <- p + (1 - p) * d / (d + R)`. So `p` never goes back (`d >= 0`), it reaches 1 only
 * when nothing is left, a too-small estimate slows the bar and a too-large one makes it jump
 * forward. A new attempt replaces the plan and keeps `p`. The weights are internal and tunable.
 */
internal class ProgressTracker {
    private var p = 0.0
    private val plan = LinkedHashMap<String, Long>()

    /** Application files this attempt has finished, read or not. */
    var filesDone = 0
        private set

    /** Application files this attempt plans to read, once EF.COM is read. */
    var filesPlanned: Int? = null

    /** The raw fraction, for tests. */
    val fraction: Double get() = p

    /** A new attempt: a fresh plan for [dataGroups]; the fraction keeps its value. */
    fun beginAttempt(dataGroups: Set<Int>) {
        plan.clear()
        filesDone = 0
        filesPlanned = null
        plan[PROBE] = PROBE_UNITS
        plan[ACCESS] = PACE_UNITS
        plan[fileKey(LdsIds.EF_COM, false)] = fileEstimate(LdsIds.EF_COM, false)
        plan[fileKey(LdsIds.EF_SOD, false)] = fileEstimate(LdsIds.EF_SOD, false)
        for (n in (dataGroups + setOf(14, 15)).sorted()) plan[fileKey(0x0100 + n, false)] = fileEstimate(0x0100 + n, false)
        plan[AUTH] = CA_UNITS
        plan[CHECKS] = CHECK_UNITS
    }

    /** Sets what [key] still needs, with no work done: the fraction does not move. */
    fun estimate(
        key: String,
        units: Long,
    ) {
        plan[key] = units.coerceAtLeast(0)
    }

    /** [done] units of work were just done, and [key] still needs [left]. */
    fun work(
        key: String,
        done: Long,
        left: Long,
    ) {
        plan[key] = left.coerceAtLeast(0)
        step(done)
    }

    /** One application file finished. */
    fun fileFinished() {
        filesDone++
    }

    /** Removes [key] from the plan without counting it as work. */
    fun drop(key: String) {
        plan.remove(key)
    }

    fun has(key: String): Boolean = key in plan

    private fun step(done: Long) {
        if (done <= 0) return
        val remaining = plan.values.sum()
        p += (1 - p) * done.toDouble() / (done + remaining).toDouble()
        if (p > 1.0) p = 1.0
    }

    /** The progress for an update: below 1.0 and at most 99 percent, since only a complete read ends the work. */
    fun snapshot(): ChipReadProgress {
        val f = if (p >= 1.0) 1.0.nextDown() else p
        return ChipReadProgress(f, floor(f * 100).toInt().coerceAtMost(99), filesDone, filesPlanned)
    }

    /** The progress of a complete read: 1.0 and 100 percent. */
    fun complete(): ChipReadProgress = ChipReadProgress(1.0, 100, filesDone, filesPlanned)

    companion object {
        const val PROBE = "probe"
        const val ACCESS = "access"
        const val AUTH = "auth"
        const val CHECKS = "checks"

        const val COMMAND_UNITS = 100L
        const val PROBE_UNITS = 3 * COMMAND_UNITS
        const val BAC_UNITS = 2_000L
        const val PACE_UNITS = 10_000L
        const val CA_UNITS = 7_000L
        const val AA_UNITS = 3_500L
        const val CHECK_UNITS = 1_000L

        fun fileKey(
            fileId: Int,
            inMasterFile: Boolean,
        ): String = "file:$fileId:$inMasterFile"

        fun accessUnits(protocol: ChipAccessProtocol): Long =
            when (protocol) {
                ChipAccessProtocol.PACE -> PACE_UNITS
                ChipAccessProtocol.BAC -> BAC_UNITS
                else -> COMMAND_UNITS
            }

        /** A file's estimate before its length is known: its selection plus its usual size. */
        fun fileEstimate(
            fileId: Int,
            inMasterFile: Boolean,
        ): Long {
            val size =
                when {
                    inMasterFile && fileId == 0x011D -> 1_500L
                    inMasterFile -> 100L
                    fileId == 0x0102 -> 20_000L
                    fileId == 0x0107 -> 5_000L
                    fileId == LdsIds.EF_SOD -> 2_000L
                    fileId == 0x010E -> 400L
                    fileId in setOf(0x0101, 0x010B, 0x010C, 0x010F) -> 300L
                    fileId == LdsIds.EF_COM -> 100L
                    else -> 500L
                }
            return COMMAND_UNITS + size
        }
    }
}

/** Where a read flow's states and progress go. Never sees document data. */
internal fun interface FlowSink {
    fun update(
        state: ChipReadState,
        progress: ChipReadProgress,
    )
}

/**
 * Turns read-flow milestones into states and progress, for one attempt. The flow calls it at each
 * step; it never throws (a sink that throws is the sink's problem and is caught here).
 */
internal class FlowReporter(
    private val tracker: ProgressTracker,
    private val sink: FlowSink,
) {
    /** The last state reported, for the ending of a read that stopped. */
    var lastState: ChipReadState? = null
        private set

    private var inAccess = true
    private var fileBytes = 0
    private var fileSawChunk = false

    fun beginAttempt(dataGroups: Set<Int>) {
        kept.clear()
        tracker.beginAttempt(dataGroups)
        inAccess = true
    }

    fun openingAccess(protocol: ChipAccessProtocol?) = emit(ChipReadState.OpeningAccess(protocol))

    /** EF.ATR/INFO and EF.CardAccess are done: the protocol is known. */
    fun probeDone(protocol: ChipAccessProtocol) {
        tracker.estimate(ProgressTracker.ACCESS, ProgressTracker.accessUnits(protocol))
        tracker.work(ProgressTracker.PROBE, ProgressTracker.PROBE_UNITS, 0)
        if (protocol == ChipAccessProtocol.PACE) {
            tracker.estimate(ProgressTracker.fileKey(0x011D, true), ProgressTracker.fileEstimate(0x011D, true))
        }
        openingAccess(protocol)
    }

    /** The protocol changed while opening (a refused BAC challenge on a plain chip). */
    fun accessProtocol(protocol: ChipAccessProtocol) = openingAccess(protocol)

    fun accessGranted(protocol: ChipAccessProtocol) {
        inAccess = false
        tracker.work(ProgressTracker.ACCESS, ProgressTracker.accessUnits(protocol), 0)
    }

    fun fileStarted(
        fileId: Int,
        inMasterFile: Boolean,
        bytesAlready: Int = 0,
    ) {
        fileBytes = bytesAlready
        fileSawChunk = false
        if (!inAccess) emit(ChipReadState.ReadingFile(ChipFileRef(fileId, inMasterFile), bytesAlready, null))
    }

    fun fileChunk(
        fileId: Int,
        inMasterFile: Boolean,
        bytesRead: Int,
        bytesTotal: Int?,
    ) {
        val key = ProgressTracker.fileKey(fileId, inMasterFile)
        val done = (bytesRead - fileBytes).coerceAtLeast(0) + if (fileSawChunk) 0 else ProgressTracker.COMMAND_UNITS
        fileSawChunk = true
        fileBytes = bytesRead
        val left = if (bytesTotal != null) (bytesTotal - bytesRead).toLong() else ProgressTracker.fileEstimate(fileId, inMasterFile) / 2
        if (inAccess) return
        tracker.work(key, done, left)
        emit(ChipReadState.ReadingFile(ChipFileRef(fileId, inMasterFile), bytesRead, bytesTotal))
    }

    /** A file ended, read or not. [application] counts it in the file counts. */
    fun fileEnded(
        fileId: Int,
        inMasterFile: Boolean,
    ) {
        val key = ProgressTracker.fileKey(fileId, inMasterFile)
        if (!inAccess) tracker.work(key, if (fileSawChunk) 0 else ProgressTracker.COMMAND_UNITS, 0)
        fileSawChunk = false
        if (!inMasterFile) tracker.fileFinished()
    }

    // Files kept from an earlier attempt: never planned again in this attempt.
    private val kept = HashSet<Int>()

    /** A file kept from an earlier attempt: counted as done with no new work, and not planned again. */
    fun fileKept(fileId: Int) {
        kept += fileId
        tracker.drop(ProgressTracker.fileKey(fileId, false))
        tracker.fileFinished()
    }

    /** The application files this attempt will read, from EF.COM and EF.SOD. */
    fun planFiles(fileIds: Set<Int>) {
        for (n in 1..16) {
            val fid = 0x0100 + n
            val key = ProgressTracker.fileKey(fid, false)
            if (fid !in fileIds || fid in kept) {
                tracker.drop(key)
            } else if (!tracker.has(key)) {
                tracker.estimate(key, ProgressTracker.fileEstimate(fid, false))
            }
        }
        tracker.filesPlanned = fileIds.size + 2
    }

    /** A file was planned but will not be read. */
    fun fileDropped(fileId: Int) = tracker.drop(ProgressTracker.fileKey(fileId, false))

    fun authenticating(method: ChipAuthMethod) = emit(ChipReadState.AuthenticatingChip(method))

    fun authenticated(method: ChipAuthMethod) {
        val units = if (method == ChipAuthMethod.CHIP_AUTHENTICATION) ProgressTracker.CA_UNITS else ProgressTracker.AA_UNITS
        tracker.work(ProgressTracker.AUTH, units, 0)
    }

    /** The chip and active authentication phase is over; whatever did not run leaves the plan. */
    fun authenticationPhaseDone() = tracker.work(ProgressTracker.AUTH, 0, 0)

    fun checkingFiles() = emit(ChipReadState.CheckingFiles)

    private fun emit(state: ChipReadState) {
        lastState = state
        try {
            sink.update(state, tracker.snapshot())
        } catch (e: Exception) {
            // The sink belongs to the caller; it never changes the read.
        }
    }
}
