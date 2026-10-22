package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.ScheduledUsageConfig
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordGroupRestrictionComparatorTest {
    private val oldBlocker = blocker(startHour = 9, usageMinutes = 60)

    @Test
    fun lowerUsageAllowanceIsStricter() {
        assertTrue(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 9, usageMinutes = 30)
            )
        )
    }

    @Test
    fun shorterActiveScheduleIsWeaker() {
        assertFalse(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 10, usageMinutes = 60)
            )
        )
    }

    @Test
    fun keywordOrderAndDuplicatesDoNotWeakenTheGroup() {
        val group = oldBlocker.keywordGroups.single().copy(selectedKeywords = listOf("first", "second"))
        val old = oldBlocker.copy(keywordGroups = listOf(group))
        val proposed = old.copy(keywordGroups = listOf(group.copy(
            selectedKeywords = listOf("second", "first", "first")
        )))
        assertTrue(RestrictionComparator.keywordBlocker(old, proposed))
    }

    @Test(timeout = 5000)
    fun handlesLargeWordListUpdates() {
        val keywords = List(100_000) { "domain$it.example" }
        val group = oldBlocker.keywordGroups.single().copy(selectedKeywords = keywords)
        val old = oldBlocker.copy(keywordGroups = listOf(group))
        assertTrue(RestrictionComparator.keywordBlocker(old, old))
        val expanded = old.copy(keywordGroups = listOf(group.copy(
            selectedKeywords = keywords + "additional.example"
        )))
        assertTrue(RestrictionComparator.keywordBlocker(old, expanded))
        val removed = expanded.copy(keywordGroups = listOf(group.copy(
            selectedKeywords = keywords.drop(1) + "additional.example"
        )))
        assertFalse(RestrictionComparator.keywordBlocker(old, removed))
    }

    @Test
    fun higherUsageAllowanceIsWeaker() {
        assertFalse(
            RestrictionComparator.keywordBlocker(
                oldBlocker,
                blocker(startHour = 9, usageMinutes = 90)
            )
        )
    }

    private fun blocker(startHour: Int, usageMinutes: Long) = KeywordBlocker(
        isActive = true,
        keywordGroups = listOf(
            KeywordGroup(
                id = "group",
                selectedKeywords = listOf("example"),
                config = ScheduledUsageConfig(
                    schedule = AppTimeConfig(
                        everydayIntervals =
                            mutableListOf(TimeInterval(startHour, 0, 17, 0))
                    ),
                    usage = AppUsageConfig(uniformLimit = usageMinutes)
                ),
                isActive = true
            )
        )
    )
}
