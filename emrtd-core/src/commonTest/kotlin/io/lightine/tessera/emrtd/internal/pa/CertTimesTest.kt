package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.x509.CertTime
import kotlin.test.Test
import kotlin.test.assertEquals

class CertTimesTest {
    @Test
    fun epochSecondsBecomeUtcCalendarTimes() {
        assertEquals(CertTime(1970, 1, 1, 0, 0, 0), CertTimes.fromEpochSeconds(0))
        assertEquals(CertTime(2000, 2, 29, 23, 59, 59), CertTimes.fromEpochSeconds(951_868_799))
        assertEquals(CertTime(2038, 1, 19, 3, 14, 8), CertTimes.fromEpochSeconds(2_147_483_648))
        assertEquals(CertTime(1969, 12, 31, 23, 59, 59), CertTimes.fromEpochSeconds(-1))
        assertEquals(CertTime(2100, 3, 1, 0, 0, 0), CertTimes.fromEpochSeconds(4_107_542_400))
    }
}
