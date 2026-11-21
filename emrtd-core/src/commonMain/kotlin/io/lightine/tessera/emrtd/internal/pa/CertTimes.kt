package io.lightine.tessera.emrtd.internal.pa

import io.lightine.tessera.emrtd.internal.x509.CertTime

/** Conversions into [CertTime], the time type the certificate checks compare. */
internal object CertTimes {
    /**
     * The UTC time [epochSeconds] after 1970-01-01T00:00:00Z, in whole seconds (the read flow turns
     * the caller's instant into this). Civil-from-days per H. Hinnant's public-domain algorithm.
     */
    fun fromEpochSeconds(epochSeconds: Long): CertTime {
        val days = epochSeconds.floorDiv(86_400L)
        val secondOfDay = epochSeconds.mod(86_400L).toInt()
        val z = days + 719_468
        val era = z.floorDiv(146_097L)
        val doe = (z - era * 146_097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return CertTime(year, month, day, secondOfDay / 3600, secondOfDay / 60 % 60, secondOfDay % 60)
    }
}
