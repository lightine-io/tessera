package io.lightine.tessera.mrz.model

import io.lightine.tessera.types.vocabulary.MrzFormat

/**
 * A parsed MRZ document: [TD1], [TD2], [TD3], [MrvA], or [MrvB].
 *
 * @since 0.1.0
 */
public sealed class MrzDocument {
    /** The MRZ lines the document was read from. */
    public abstract val rawLines: List<String>

    /** The format of this document. */
    public abstract val format: MrzFormat

    /** The fields every format carries. */
    public abstract val commonFields: CommonFields
}
