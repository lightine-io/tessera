package io.lightine.tessera.mrz.model

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * A parsed MRV-B (Type-B visa) MRZ: two lines of 36 characters.
 *
 * @property rawLines The MRZ lines the document was read from.
 * @property commonFields The fields every format carries.
 * @property optionalData Optional data as printed: line 2, characters 29-36.
 * @since 0.1.0
 */
public data class MrvB(
    override val rawLines: List<String>,
    override val commonFields: CommonFields,
    val optionalData: String,
) : MrzDocument() {
    /** Always [MrzFormat.MRV_B]. */
    override val format: MrzFormat = MrzFormat.MRV_B
}
