package io.lightine.tessera.types.vocabulary

/**
 * Why a check came out [CheckOutcome.Failed], [CheckOutcome.NotApplicable] or
 * [CheckOutcome.NotRun]: a fact about the inputs to the check, never a verdict on the document.
 *
 * Every module that reports checks names its reasons in its own enum implementing this interface
 * (`ChipCheckReason` and `MasterListCheckReason` in `emrtd-core`, `MrzNotRunReason` in
 * `mrz-core`), so a [CheckOutcome] carries a typed reason with no strings and no generics: the
 * reason is a plain [CheckReason], and each module's enum says which reasons it can produce. Every
 * reason has a [code], so an app prints or counts a reason without a cast to `Enum`.
 *
 * @since 1.0.0
 */
public interface CheckReason {
    /**
     * A stable, machine-readable name of this reason, for logs, metrics and keys of your own texts,
     * so a reason is never printed through its enum class: lower-case words joined by hyphens,
     * after the name of the module that gives it and a dot (`chip.hash-differs`,
     * `master-list.signature-does-not-verify`, `mrz.invalid-length`). A code never changes once
     * released, is never empty, is different for every reason of every module, and never holds a
     * value from the document.
     *
     * @since 1.0.0
     */
    public val code: String
}
