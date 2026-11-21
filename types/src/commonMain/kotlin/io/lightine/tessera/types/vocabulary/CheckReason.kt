package io.lightine.tessera.types.vocabulary

/**
 * Why a check came out [CheckOutcome.Failed], [CheckOutcome.NotApplicable] or
 * [CheckOutcome.NotRun]: a fact about the inputs to the check, never a verdict on the document.
 *
 * Every module that reports checks names its reasons in its own enum implementing this marker
 * interface (`ChipCheckReason` and `MasterListCheckReason` in `emrtd-core`, `MrzNotRunReason` in
 * `mrz-core`), so a [CheckOutcome] carries a typed reason with no strings and no generics: the
 * reason is a plain [CheckReason], and each module's enum says which reasons it can produce.
 *
 * @since 1.0.0
 */
public interface CheckReason
