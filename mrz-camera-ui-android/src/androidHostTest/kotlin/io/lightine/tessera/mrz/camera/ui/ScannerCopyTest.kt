package io.lightine.tessera.mrz.camera.ui

import io.lightine.tessera.mrz.model.MrzDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * TES-112: the decided default copy for the two button labels and the unresolved-century date. Checks the
 * shipped `strings.xml` values (an app can still override each key) and the date's day-first order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScannerCopyTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun manual_entry_button_says_read() {
        assertEquals("Read", context.getString(R.string.tessera_scanner_manual_read))
    }

    @Test
    fun camera_permission_button_says_continue() {
        // A screen before the system permission prompt must not use "Allow"-like words (Apple HIG).
        assertEquals("Continue", context.getString(R.string.tessera_scanner_permission_grant_action))
    }

    @Test
    fun unresolved_date_shows_the_printed_parts_day_first_with_the_two_digit_year() {
        val date = MrzDate(rawYear = "94", rawMonth = "06", rawDay = "23")
        assertEquals("23 06 94", unresolvedDateText(date))
        assertEquals("23 06 94", context.getString(R.string.tessera_scanner_date_unresolved_format, unresolvedDateText(date)))
    }
}
