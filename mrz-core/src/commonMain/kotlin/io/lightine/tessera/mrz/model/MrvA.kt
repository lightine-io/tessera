package io.lightine.tessera.mrz.model

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * A parsed MRV-A (Type-A visa) MRZ: two lines of 44 characters.
 *
 * @property rawLines The MRZ lines the document was read from.
 * @property commonFields The fields every format carries.
 * @property optionalData Optional data as printed: line 2, characters 29-44.
 * @since 0.1.0
 */
public data class MrvA(
    override val rawLines: List<String>,
    override val commonFields: CommonFields,
    val optionalData: String,
) : MrzDocument() {
    /** Always [MrzFormat.MRV_A]. */
    override val format: MrzFormat = MrzFormat.MRV_A
}
