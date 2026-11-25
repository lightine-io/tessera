@file:OptIn(ExperimentalObjCRefinement::class)

package io.lightine.tessera.emrtd

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HidesFromObjC

/**
 * Marks a chip-reading declaration that is public only so Tessera's own NFC modules can share it
 * (the read coordinator that the Android and iOS entry points drive). It is not part of Tessera's
 * API: it can change or go away in any release, without notice and without a deprecation cycle.
 *
 * Using it is a compile error unless the caller opts in, which only Tessera's own modules do. Marked
 * declarations are left out of the ABI dumps and hidden from Objective-C and Swift.
 *
 * @since 1.0.0
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Tessera chip API, shared between Tessera's own modules. It can change or go away in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@HidesFromObjC
@MustBeDocumented
public annotation class InternalTesseraChipApi
