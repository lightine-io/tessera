package io.lightine.tessera.emrtd

import io.lightine.tessera.emrtd.lds.BiometricGroup
import io.lightine.tessera.emrtd.lds.Dg11View
import io.lightine.tessera.emrtd.lds.Dg12View
import io.lightine.tessera.emrtd.lds.Dg13View
import io.lightine.tessera.emrtd.lds.Dg16View
import io.lightine.tessera.emrtd.lds.Dg1View
import io.lightine.tessera.emrtd.lds.DisplayedImageGroup
import io.lightine.tessera.emrtd.lds.EfComView
import io.lightine.tessera.emrtd.lds.SecurityObjectView
import io.lightine.tessera.types.ExperimentalTesseraChipApi
import io.lightine.tessera.types.errors.ChipError
import io.lightine.tessera.types.vocabulary.ChipAccessProtocol

/**
 * The outcome of [ChipReader.read].
 *
 * - [Complete]: every step ran. Some requested files may still be missing or unreadable; they are
 *   listed in [ChipRead.fileErrors].
 * - [Partial]: the read stopped part way, for example because the document left the reader's field.
 *   What was read before is kept.
 * - [Failed]: nothing could be read from the eMRTD application, for example because the access key
 *   does not match.
 *
 * None of them is a judgment on the document: a chip that cannot be read, or that is blank, does
 * not by itself make a document unusable (ICAO Doc 9303 Part 11, section 2).
 *
 * In Swift these are the classes `ChipReadResultComplete`, `ChipReadResultPartial` and
 * `ChipReadResultFailed`; none is generic.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public sealed class ChipReadResult {
    /** Every step ran; [read] holds what the chip gave. */
    public class Complete internal constructor(
        public val read: ChipRead,
    ) : ChipReadResult() {
        /** `Complete(...)` with the counts, never content. */
        override fun toString(): String = "Complete($read)"
    }

    /** The read stopped because of [stoppedBy]; [read] holds what was read before. */
    public class Partial internal constructor(
        public val read: ChipRead,
        public val stoppedBy: ChipError,
    ) : ChipReadResult() {
        /** `Partial(...)` with the counts and the error. */
        override fun toString(): String = "Partial($read, stoppedBy=$stoppedBy)"
    }

    /**
     * Nothing could be read from the eMRTD application, because of [error]. [access] says how far
     * access got: `null` when no access protocol completed.
     */
    public class Failed internal constructor(
        public val error: ChipError,
        public val access: AccessReport?,
    ) : ChipReadResult() {
        /** `Failed(error)`. */
        override fun toString(): String = "Failed($error)"
    }
}

/**
 * Everything one chip read produced: how access went, the raw files, the parsed views beside them,
 * the per-file problems and the checks.
 *
 * The raw bytes of every file read are always in [files], even when its view could not be made.
 * The views are conveniences on top of them. `toString()` shows counts only, never content.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property access How the reader got access to the chip.
 * @property files Every file read, in the order it was read, as the chip sent it.
 * @property fileErrors Files that were asked for and could not be read or parsed: not found,
 *   protected, malformed. A file missing here and in [files] was not asked for.
 * @property efCom EF.COM, when read and parsed.
 * @property dg1 DG1, the chip's MRZ: its document has `ReadMethod.NFC_CHIP`.
 * @property dg2 DG2, the face containers; image bytes untouched.
 * @property dg5 DG5, the displayed portrait.
 * @property dg7 DG7, the displayed signature or usual mark.
 * @property dg11 DG11, additional personal details.
 * @property dg12 DG12, additional document details.
 * @property dg13 DG13, optional details.
 * @property dg16 DG16, persons to notify.
 * @property securityObject EF.SOD, when read and parsed.
 * @property checks The facts: one [ChipCheck] per check, in a fixed order (passive authentication,
 *   then chip authentication, the PACE chip authentication mapping and active authentication).
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipRead internal constructor(
    public val access: AccessReport,
    public val files: List<ChipFile>,
    public val fileErrors: List<ChipError>,
    public val efCom: EfComView?,
    public val dg1: Dg1View?,
    public val dg2: BiometricGroup?,
    public val dg5: DisplayedImageGroup?,
    public val dg7: DisplayedImageGroup?,
    public val dg11: Dg11View?,
    public val dg12: Dg12View?,
    public val dg13: Dg13View?,
    public val dg16: Dg16View?,
    public val securityObject: SecurityObjectView?,
    public val checks: List<ChipCheck>,
) {
    /** The file with identifier [fileId] (for example `0x0101` for DG1), or `null` when it was not read. */
    public fun file(fileId: Int): ChipFile? = files.firstOrNull { it.fileId == fileId }

    /** Data group [number] (1 to 16) of the eMRTD application, or `null` when it was not read. */
    public fun dataGroup(number: Int): ChipFile? = files.firstOrNull { it.dataGroup == number }

    /** The outcome of the check of [kind], or `null` when the read has no such check. */
    public fun outcomeOf(kind: ChipCheckKind): CheckOutcome? = checks.firstOrNull { it.kind == kind }?.outcome

    /** Counts only: files, file errors and checks. */
    override fun toString(): String =
        "ChipRead(access=${access.protocol}, files=${files.size}, fileErrors=${fileErrors.size}, checks=${checks.size})"
}

/**
 * One file as the chip sent it.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property fileId The two-byte file identifier: `0x011C` EF.CardAccess, `0x011D` EF.CardSecurity in
 *   the master file or EF.SOD in the application, `0x011E` EF.COM, `0x0101` to `0x0110` DG1 to DG16.
 * @property inMasterFile `true` for a file of the master file (EF.CardAccess, EF.CardSecurity,
 *   EF.ATR/INFO), `false` for a file of the eMRTD application.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipFile internal constructor(
    public val fileId: Int,
    public val inMasterFile: Boolean,
    private val content: ByteArray,
) {
    /** The data group number (1 to 16) for a data group of the application, else `null`. */
    public val dataGroup: Int?
        get() = if (!inMasterFile && fileId in 0x0101..0x0110) fileId - 0x0100 else null

    /** The number of bytes. */
    public val size: Int
        get() = content.size

    /** A copy of the bytes as the chip sent them. */
    public fun bytes(): ByteArray = content.copyOf()

    internal fun raw(): ByteArray = content

    /** The file identifier and size, never content. */
    override fun toString(): String = "ChipFile(${fileId.toString(16).uppercase().padStart(4, '0')}, $size bytes)"
}

/**
 * How the reader got access to the chip, and what the chip offered.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property protocol The access protocol used: PACE, BAC or none (a plain chip).
 * @property password The kind of key used, or `null` with no access protocol.
 * @property paceProtocolOid The PACE protocol object identifier used, with PACE.
 * @property paceMapping The PACE mapping used, with PACE.
 * @property paceParameterId The PACE standardized domain parameter ID used, with PACE.
 * @property cardAccessRead Whether EF.CardAccess was read.
 * @property offeredPace The PACE suites EF.CardAccess lists, usable or not.
 * @property extendedLengthUsed Whether extended-length APDUs were allowed for this read: your
 *   option, the transport and the chip's own statement in EF.ATR/INFO all said yes.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class AccessReport internal constructor(
    public val protocol: ChipAccessProtocol,
    public val password: ChipPassword?,
    public val paceProtocolOid: String?,
    public val paceMapping: ChipPaceMapping?,
    public val paceParameterId: Int?,
    public val cardAccessRead: Boolean,
    public val offeredPace: List<ChipPaceOffer>,
    public val extendedLengthUsed: Boolean,
) {
    /** The protocol, mapping and parameter ID. */
    override fun toString(): String = "AccessReport($protocol, $paceMapping, $paceParameterId)"
}

/**
 * The kind of key that opened the chip.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipPassword {
    /** The MRZ values. */
    MRZ,

    /** The card access number. */
    CAN,
}

/**
 * The three PACE mappings (Part 11, section 4.4.3.3).
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public enum class ChipPaceMapping {
    /** The generic mapping. */
    GENERIC,

    /** The integrated mapping. */
    INTEGRATED,

    /** The chip authentication mapping. */
    CHIP_AUTHENTICATION,
}

/**
 * One PACE suite EF.CardAccess lists.
 *
 * Experimental: see [ExperimentalTesseraChipApi].
 *
 * @property protocolOid The protocol object identifier as listed.
 * @property mapping The mapping the identifier names, or `null` when it names none the SDK knows.
 * @property parameterId The domain parameter ID as listed, or `null`.
 * @property readerCanRun Whether this reader runs the suite.
 * @since 1.0.0
 */
@ExperimentalTesseraChipApi
public class ChipPaceOffer internal constructor(
    public val protocolOid: String,
    public val mapping: ChipPaceMapping?,
    public val parameterId: Int?,
    public val readerCanRun: Boolean,
) {
    /** The identifier, the parameter ID and whether the reader runs it. */
    override fun toString(): String = "ChipPaceOffer($protocolOid, $parameterId, readerCanRun=$readerCanRun)"
}
