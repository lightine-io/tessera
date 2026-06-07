package io.lightine.tessera.mrz.parsing

import io.lightine.tessera.mrz.transliteration.TransliterationDetails
import io.lightine.tessera.types.errors.MrzValidationError
import io.lightine.tessera.types.errors.MrzWarning
import io.lightine.tessera.types.vocabulary.ReadMethod

/**
 * Diagnostic context attached to every [ParseResult] and [`GenerationResult`][io.lightine.tessera.mrz.generation.GenerationResult].
 * Present on success, partial success, and failure variants alike — even when the
 * operation failed, this metadata reports the provenance, warnings, and validation
 * failures observed.
 *
 * - [readMethod] — the provenance of the input (see
 *   [`ReadMethod`][io.lightine.tessera.types.vocabulary.ReadMethod]). The string parser and
 *   generator report [`ReadMethod.BACKEND_STRING_INPUT`][io.lightine.tessera.types.vocabulary.ReadMethod.BACKEND_STRING_INPUT]
 *   (their input is a plain string); live-camera reading reports
 *   [`ReadMethod.LIVE_CAMERA`][io.lightine.tessera.types.vocabulary.ReadMethod.LIVE_CAMERA]. Further
 *   values activate as their reading methods land in later releases.
 * - [warnings] — informational observations that do not prevent the result from being
 *   usable. Empty list when there are none.
 * - [validationFailures] — per-field validation failures. Non-empty for
 *   [`ParseResult.PartialSuccess`][ParseResult.PartialSuccess]; may be non-empty for
 *   parse [`Failure`][ParseResult.Failure] when validation ran before the failure was
 *   determined.
 * - [transliterationDetails] — populated only by the primitive-input generator methods
 *   when a transliteration profile applied (see
 *   [`docs/features/transliteration.md`](https://github.com/lightine-io/tessera/blob/main/docs/features/transliteration.md)).
 *   `null` for parse paths and for generation paths that took no profile.
 */
public data class ResultMetadata(
    val readMethod: ReadMethod,
    val warnings: List<MrzWarning>,
    val validationFailures: List<MrzValidationError>,
    val transliterationDetails: TransliterationDetails? = null,
)
