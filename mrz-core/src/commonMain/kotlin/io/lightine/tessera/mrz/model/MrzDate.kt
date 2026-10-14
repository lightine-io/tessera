package io.lightine.tessera.mrz.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * An MRZ date: the printed year, month, and day, plus an inferred full date.
 *
 * @property rawYear The two year characters as printed.
 * @property rawMonth The two month characters as printed.
 * @property rawDay The two day characters as printed.
 * @property computedYear The four-digit year chosen by century inference. `null` if
 *   [inferenceMethod] is [MrzDateInferenceMethod.RAW_ONLY].
 * @property computedDate The full date. `null` if [inferenceMethod] is
 *   [MrzDateInferenceMethod.RAW_ONLY].
 * @property inferenceMethod How the century was chosen. Default: [MrzDateInferenceMethod.RAW_ONLY].
 * @property componentsFormCalendarDate `true` if the components form a real date in the 2000s or
 *   the 1900s, `false` if they form none, `null` if they are not two-digit numbers. Default:
 *   `null`.
 * @property componentsExceedBirthAgeLimit Birth dates only. `true` if the date is real and not
 *   after the reference time but more than 130 years before it, `false` if inference succeeded or
 *   the date is after the reference time, `null` otherwise. Default: `null`.
 * @since 0.1.0
 */
public data class MrzDate(
    val rawYear: String,
    val rawMonth: String,
    val rawDay: String,
    val computedYear: Int? = null,
    val computedDate: LocalDate? = null,
    val inferenceMethod: MrzDateInferenceMethod = MrzDateInferenceMethod.RAW_ONLY,
    val componentsFormCalendarDate: Boolean? = null,
    val componentsExceedBirthAgeLimit: Boolean? = null,
) {
    /** Factories that parse printed date components. */
    public companion object {
        internal const val MAX_PLAUSIBLE_AGE_YEARS: Int = 130
        private const val EXPIRY_PAST_WINDOW_YEARS = 10
        private const val EXPIRY_FUTURE_WINDOW_YEARS = 50

        /**
         * Parses a birth date and infers its century.
         *
         * @param rawYear The two year characters.
         * @param rawMonth The two month characters.
         * @param rawDay The two day characters.
         * @param referenceTime The moment to judge against, compared as a UTC date. Default: the
         *   system clock.
         * @return A date with [MrzDate.computedDate] set from the first of the 2000s and the 1900s
         *   whose date is not after the reference time and at most 130 years before it. If neither
         *   fits, only the printed components are set and the method is
         *   [MrzDateInferenceMethod.RAW_ONLY].
         */
        public fun parseBirth(
            rawYear: String,
            rawMonth: String,
            rawDay: String,
            referenceTime: Instant = Clock.System.now(),
        ): MrzDate {
            val parsed =
                parseRawComponents(rawYear, rawMonth, rawDay)
                    ?: return rawOnly(
                        rawYear,
                        rawMonth,
                        rawDay,
                        componentsFormCalendarDate = null,
                        componentsExceedBirthAgeLimit = null,
                    )
            val componentsFormCalendar = anyCenturyFormsCalendarDate(parsed)
            val refDate = referenceTime.toLocalDateTime(TimeZone.UTC).date
            val pickedYear =
                pickBirthYear(parsed, centuryBase = 2000, ref = refDate)
                    ?: pickBirthYear(parsed, centuryBase = 1900, ref = refDate)
                    ?: return rawOnly(
                        rawYear,
                        rawMonth,
                        rawDay,
                        componentsFormCalendarDate = componentsFormCalendar,
                        componentsExceedBirthAgeLimit =
                            if (componentsFormCalendar) {
                                anyBirthCandidateInPast(parsed, refDate)
                            } else {
                                null
                            },
                    )
            return MrzDate(
                rawYear = rawYear,
                rawMonth = rawMonth,
                rawDay = rawDay,
                computedYear = pickedYear,
                computedDate = LocalDate(pickedYear, parsed.month, parsed.day),
                inferenceMethod = MrzDateInferenceMethod.SLIDING_WINDOW_BIRTH,
                componentsFormCalendarDate = true,
                componentsExceedBirthAgeLimit = false,
            )
        }

        /**
         * Parses an expiry date and infers its century.
         *
         * @param rawYear The two year characters.
         * @param rawMonth The two month characters.
         * @param rawDay The two day characters.
         * @param referenceTime The moment to judge against, compared as a UTC date. Default: the
         *   system clock.
         * @return A date with [MrzDate.computedDate] set from the first of the 2000s and the 1900s
         *   whose year is from 10 years before to 50 years after the reference year. If neither
         *   fits, only the printed components are set and the method is
         *   [MrzDateInferenceMethod.RAW_ONLY].
         */
        public fun parseExpiry(
            rawYear: String,
            rawMonth: String,
            rawDay: String,
            referenceTime: Instant = Clock.System.now(),
        ): MrzDate {
            val parsed =
                parseRawComponents(rawYear, rawMonth, rawDay)
                    ?: return rawOnly(rawYear, rawMonth, rawDay, componentsFormCalendarDate = null)
            val componentsFormCalendar = anyCenturyFormsCalendarDate(parsed)
            val refDate = referenceTime.toLocalDateTime(TimeZone.UTC).date
            val pickedYear =
                pickExpiryYear(parsed, centuryBase = 2000, ref = refDate)
                    ?: pickExpiryYear(parsed, centuryBase = 1900, ref = refDate)
                    ?: return rawOnly(rawYear, rawMonth, rawDay, componentsFormCalendarDate = componentsFormCalendar)
            return MrzDate(
                rawYear = rawYear,
                rawMonth = rawMonth,
                rawDay = rawDay,
                computedYear = pickedYear,
                computedDate = LocalDate(pickedYear, parsed.month, parsed.day),
                inferenceMethod = MrzDateInferenceMethod.SLIDING_WINDOW_EXPIRY,
                componentsFormCalendarDate = true,
            )
        }

        private fun rawOnly(
            rawYear: String,
            rawMonth: String,
            rawDay: String,
            componentsFormCalendarDate: Boolean?,
            componentsExceedBirthAgeLimit: Boolean? = null,
        ): MrzDate =
            MrzDate(
                rawYear = rawYear,
                rawMonth = rawMonth,
                rawDay = rawDay,
                computedYear = null,
                computedDate = null,
                inferenceMethod = MrzDateInferenceMethod.RAW_ONLY,
                componentsFormCalendarDate = componentsFormCalendarDate,
                componentsExceedBirthAgeLimit = componentsExceedBirthAgeLimit,
            )

        private data class ParsedComponents(
            val twoDigitYear: Int,
            val month: Int,
            val day: Int,
        )

        private fun parseRawComponents(
            rawYear: String,
            rawMonth: String,
            rawDay: String,
        ): ParsedComponents? {
            if (rawYear.length != 2 || rawMonth.length != 2 || rawDay.length != 2) return null
            val y = rawYear.toIntOrNull() ?: return null
            val m = rawMonth.toIntOrNull() ?: return null
            val d = rawDay.toIntOrNull() ?: return null
            return ParsedComponents(y, m, d)
        }

        private fun anyCenturyFormsCalendarDate(parsed: ParsedComponents): Boolean =
            tryConstructDate(2000 + parsed.twoDigitYear, parsed.month, parsed.day) != null ||
                tryConstructDate(1900 + parsed.twoDigitYear, parsed.month, parsed.day) != null

        private fun anyBirthCandidateInPast(
            parsed: ParsedComponents,
            ref: LocalDate,
        ): Boolean {
            for (centuryBase in listOf(2000, 1900)) {
                val candidateYear = centuryBase + parsed.twoDigitYear
                val candidateDate = tryConstructDate(candidateYear, parsed.month, parsed.day) ?: continue
                if (candidateDate <= ref) return true
            }
            return false
        }

        private fun pickBirthYear(
            parsed: ParsedComponents,
            centuryBase: Int,
            ref: LocalDate,
        ): Int? {
            val candidateYear = centuryBase + parsed.twoDigitYear
            val candidateDate = tryConstructDate(candidateYear, parsed.month, parsed.day) ?: return null
            if (candidateDate > ref) return null
            if (ref.year - candidateYear > MAX_PLAUSIBLE_AGE_YEARS) return null
            return candidateYear
        }

        private fun pickExpiryYear(
            parsed: ParsedComponents,
            centuryBase: Int,
            ref: LocalDate,
        ): Int? {
            val candidateYear = centuryBase + parsed.twoDigitYear
            tryConstructDate(candidateYear, parsed.month, parsed.day) ?: return null
            if (candidateYear < ref.year - EXPIRY_PAST_WINDOW_YEARS) return null
            if (candidateYear > ref.year + EXPIRY_FUTURE_WINDOW_YEARS) return null
            return candidateYear
        }

        private fun tryConstructDate(
            year: Int,
            month: Int,
            day: Int,
        ): LocalDate? =
            try {
                LocalDate(year, month, day)
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
