package io.lightine.tessera.mrz.validation

import io.lightine.tessera.types.vocabulary.CheckOutcome
import io.lightine.tessera.types.vocabulary.MrzField

/**
 * One check the validator ran (or did not run) on a document, with the field it covers and its
 * outcome.
 *
 * The records say which checks ran and how each went, so a consumer can tell "the document number
 * check digit agreed" from "that check was not run". They are facts about the checks, never a
 * verdict on the document.
 *
 * @property check The check.
 * @property field The field the check covers, the same as [MrzCheck.field].
 * @property outcome The outcome of the check.
 * @since 1.0.0
 */
public data class MrzCheckRecord(
    val check: MrzCheck,
    val field: MrzField,
    val outcome: CheckOutcome,
)
