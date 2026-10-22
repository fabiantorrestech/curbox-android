package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayscaleRestrictionComparatorTest {
    @Test
    fun emptyEverydayScheduleMeansAllDay() {
        val allDay = group(AppTimeConfig(everydayIntervals = mutableListOf()))
        val limited = group(AppTimeConfig(everydayIntervals = mutableListOf(TimeInterval(9, 0, 10, 0))))
        assertFalse(RestrictionComparator.grayscaleGroups(listOf(allDay), listOf(limited)))
        assertTrue(RestrictionComparator.grayscaleGroups(listOf(limited), listOf(allDay)))
    }

    @Test
    fun missingCustomDaysMeanAllDay() {
        val allDay = group(AppTimeConfig(isEveryday = false))
        val limited = group(AppTimeConfig(isEveryday = false, dailyIntervals = mutableMapOf(
            0 to mutableListOf(TimeInterval(9, 0, 10, 0))
        )))
        assertFalse(RestrictionComparator.grayscaleGroups(listOf(allDay), listOf(limited)))
        assertTrue(RestrictionComparator.grayscaleGroups(listOf(limited), listOf(allDay)))
    }

    @Test
    fun overnightCoverageUsesTheSameDayAsRuntime() {
        val overnight = group(AppTimeConfig(isEveryday = false, dailyIntervals = mutableMapOf(
            0 to mutableListOf(TimeInterval(22, 0, 2, 0))
        )))
        val split = group(AppTimeConfig(isEveryday = false, dailyIntervals = mutableMapOf(
            0 to mutableListOf(TimeInterval(0, 0, 2, 0), TimeInterval(22, 0, 24, 0))
        )))
        assertTrue(RestrictionComparator.grayscaleGroups(listOf(overnight), listOf(split)))
        assertTrue(RestrictionComparator.grayscaleGroups(listOf(split), listOf(overnight)))
    }

    private fun group(schedule: AppTimeConfig) = GrayscaleGroup(
        groupId = "group", isActive = true, timeConfig = schedule
    )
}
