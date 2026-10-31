@file:OptIn(ExperimentalObjCRefinement::class)

package io.lightine.tessera.mrz.camera

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HidesFromObjC

/**
 * Marks a declaration that is public only so Tessera's own modules can share it (for example the camera
 * modules sharing one rule from mrz-camera-core). It is not part of Tessera's API: it can change or go away
 * in any release, without notice and without a deprecation cycle.
 *
 * Using it is a compile error unless the caller opts in, which only Tessera's own modules do. Marked
 * declarations are left out of the ABI dumps (so `checkKotlinAbi` does not freeze them) and hidden from
 * Objective-C and Swift.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Tessera API, shared between Tessera's own modules. It can change or go away in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@HidesFromObjC
@MustBeDocumented
public annotation class InternalTesseraApi
