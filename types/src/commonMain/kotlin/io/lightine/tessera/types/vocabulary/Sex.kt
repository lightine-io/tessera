package io.lightine.tessera.types.vocabulary

/**
 * The sex value recorded on the document. Decoded by the parser from the MRZ sex character
 * and used by the generator to choose the character to encode.
 *
 * MRZ encoding (per ICAO Doc 9303 Part 4 §4.2.2.2 and the corresponding sections in
 * Parts 5/6/7):
 * - [MALE] is encoded as `M`
 * - [FEMALE] is encoded as `F`
 * - [UNSPECIFIED] is encoded as the filler character `<` per the spec (Note p/f: *"the
 *   filler character `<` shall be used in this field in the MRZ and an X in this field
 *   in the VIZ"*). Consumer demand for emitting `X` in the MRZ from the generator side is
 *   tracked as a deferred enhancement in the project's issue tracker
 *   under "Sex field encoding choice (`<` vs `X`)".
 *
 * On the parse side, three outcomes for the sex field:
 * - `M` / `F` / `<` — canonical per spec; no error or warning
 * - `X` — spec lists `X` for the VIZ only, but real-world practice uses it in the MRZ
 *   for non-binary or unspecified documents. The SDK accepts the deviation but emits
 *   [`MrzSexCharacterX`][io.lightine.tessera.types.errors.MrzSexCharacterX] as a warning
 * - Any other character (e.g., `Z`, `0`) — emits
 *   [`MrzInvalidSexValue`][io.lightine.tessera.types.errors.MrzInvalidSexValue] as a
 *   validation failure rather than being silently coerced (Principle 1 — reader, not oracle).
 */
public enum class Sex {
    MALE,
    FEMALE,
    UNSPECIFIED,
}
