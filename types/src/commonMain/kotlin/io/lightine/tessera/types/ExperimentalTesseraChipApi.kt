package io.lightine.tessera.types

/**
 * Marks the chip-reading API (NFC), which is still experimental.
 *
 * Declarations with this mark can change or go away in any release, without a deprecation cycle:
 * they are outside the no-break promise for public APIs until the mark is removed. Every change to
 * them is still listed in the changelog.
 *
 * Using them is a compile error unless the caller opts in, with
 * `@OptIn(ExperimentalTesseraChipApi::class)` on the using declaration or with the compiler option
 * `-opt-in=io.lightine.tessera.types.ExperimentalTesseraChipApi`. Swift has no opt-in: the Swift
 * documentation of these declarations says "experimental" instead.
 *
 * @since 1.0.0
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Experimental Tessera chip-reading API. It can change or go away in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class ExperimentalTesseraChipApi
