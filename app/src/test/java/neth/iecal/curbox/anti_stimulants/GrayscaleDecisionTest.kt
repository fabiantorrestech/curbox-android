package neth.iecal.curbox.anti_stimulants

import neth.iecal.curbox.anti_stimulants.GrayscaleDecision.Action
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayscaleDecisionTest {

    private val app = "com.example.app"
    private val monday = 0
    private val noon = 12 * 60

    private fun group(
        vararg packages: String,
        active: Boolean = true,
        config: AppTimeConfig = AppTimeConfig(everydayIntervals = mutableListOf())
    ) = GrayscaleGroup(
        groupName = "test",
        packages = hashSetOf(*packages),
        timeConfig = config,
        isActive = active
    )

    @Test
    fun noGroupsNeverWantsGrayscale() {
        assertFalse(GrayscaleDecision.shouldGrayscale(app, emptyList(), monday, noon))
    }

    @Test
    fun appOutsideEveryGroupDoesNotWantGrayscale() {
        assertFalse(GrayscaleDecision.shouldGrayscale(app, listOf(group("other.app")), monday, noon))
    }

    @Test
    fun inactiveGroupIsIgnored() {
        assertFalse(GrayscaleDecision.shouldGrayscale(app, listOf(group(app, active = false)), monday, noon))
    }

    @Test
    fun allGroupsDisabledBehavesLikeNoGroups() {
        val groups = listOf(group(app, active = false), group(app, "other.app", active = false))
        val wants = GrayscaleDecision.shouldGrayscale(app, groups, monday, noon)

        assertFalse(wants)
        assertEquals(Action.NONE, GrayscaleDecision.nextAction(wants, true, false))
        assertEquals(Action.DISABLE, GrayscaleDecision.nextAction(wants, true, true))
    }

    @Test
    fun groupWithoutIntervalsAppliesAllDay() {
        assertTrue(GrayscaleDecision.shouldGrayscale(app, listOf(group(app)), monday, noon))
    }

    @Test
    fun everydayIntervalIsRespected() {
        val nineToFive = AppTimeConfig(everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0)))
        val groups = listOf(group(app, config = nineToFive))

        assertTrue(GrayscaleDecision.shouldGrayscale(app, groups, monday, noon))
        assertFalse(GrayscaleDecision.shouldGrayscale(app, groups, monday, 18 * 60))
        assertFalse(GrayscaleDecision.shouldGrayscale(app, groups, monday, 17 * 60))
    }

    @Test
    fun overnightIntervalWrapsPastMidnight() {
        val overnight = AppTimeConfig(everydayIntervals = mutableListOf(TimeInterval(22, 0, 6, 0)))
        val groups = listOf(group(app, config = overnight))

        assertTrue(GrayscaleDecision.shouldGrayscale(app, groups, monday, 23 * 60))
        assertTrue(GrayscaleDecision.shouldGrayscale(app, groups, monday, 5 * 60))
        assertFalse(GrayscaleDecision.shouldGrayscale(app, groups, monday, noon))
    }

    @Test
    fun perDayIntervalsOnlyApplyOnTheirDay() {
        val mondayOnly = AppTimeConfig(
            isEveryday = false,
            dailyIntervals = mutableMapOf(monday to mutableListOf(TimeInterval(9, 0, 17, 0)))
        )
        val groups = listOf(group(app, config = mondayOnly))

        assertTrue(GrayscaleDecision.shouldGrayscale(app, groups, monday, noon))
        assertTrue(GrayscaleDecision.shouldGrayscale(app, groups, 1, noon))
        assertFalse(GrayscaleDecision.shouldGrayscale(app, groups, monday, 18 * 60))
    }

    @Test
    fun enablesWhenWantedAndFilterIsOff() {
        assertEquals(Action.ENABLE, GrayscaleDecision.nextAction(true, false, false))
    }

    @Test
    fun doesNothingWhenWantedAndFilterIsAlreadyOn() {
        assertEquals(Action.NONE, GrayscaleDecision.nextAction(true, true, false))
        assertEquals(Action.NONE, GrayscaleDecision.nextAction(true, true, true))
    }

    @Test
    fun disablesOnlyTheFilterCurboxEnabled() {
        assertEquals(Action.DISABLE, GrayscaleDecision.nextAction(false, true, true))
    }

    @Test
    fun leavesSomeoneElsesFilterAlone() {
        assertEquals(Action.NONE, GrayscaleDecision.nextAction(false, true, false))
    }

    @Test
    fun doesNothingWhenNotWantedNotOwnerAndFilterIsOff() {
        assertEquals(Action.NONE, GrayscaleDecision.nextAction(false, false, false))
    }

    @Test
    fun ownerStillDisablesWhenTheSystemReportsOff() {
        assertEquals(Action.DISABLE, GrayscaleDecision.nextAction(false, false, true))
    }
}
