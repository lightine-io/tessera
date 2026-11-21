package io.lightine.tessera.emrtd.lds

import io.lightine.tessera.types.ExperimentalTesseraChipApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [ChipDate] and [ChipDateTime] hold the dates a chip stores about the document, so their
 * `toString()` shows only which parts the chip wrote down, never a date value.
 *
 * The dates below are the project's specimen dates (the SDK's own synthetic person, born 1990-03-15
 * with a document that expires 2034-03-14, never a real document), and each string must contain
 * none of them.
 */
@OptIn(ExperimentalTesseraChipApi::class)
class ChipDateToStringCarriesNoDocumentValuesTest {
    private val forbidden =
        listOf(
            "1990",
            "1990-03-15",
            "03-15",
            "0315",
            "2034",
            "2034-03-14",
            "03-14",
            "0314",
            "123456",
        )

    @Test
    fun chip_date_and_chip_date_time_print_no_date_values() {
        val birth = ChipDate(year = 1990, month = 3, day = 15)
        val expiry = ChipDate(year = 2034, month = 3, day = 14)
        val personalizationTime = ChipDateTime(date = expiry, hour = 12, minute = 34, second = 56)
        val unknown = ChipDate(year = null, month = null, day = null)

        val rendered =
            listOf(
                birth.toString(),
                expiry.toString(),
                personalizationTime.toString(),
                unknown.toString(),
            )
        rendered.forEach { string ->
            forbidden.forEach { value ->
                assertFalse(value in string, "toString carries the specimen date part '$value': $string")
            }
        }
    }

    @Test
    fun to_string_shows_only_shape() {
        assertEquals(
            "ChipDate(year=true, month=true, day=true)",
            ChipDate(year = 1990, month = 3, day = 15).toString(),
        )
        assertEquals(
            "ChipDateTime(ChipDate(year=true, month=true, day=true))",
            ChipDateTime(date = ChipDate(year = 1990, month = 3, day = 15), hour = 12, minute = 34, second = 56).toString(),
        )
    }
}
