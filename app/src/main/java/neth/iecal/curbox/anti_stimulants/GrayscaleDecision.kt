package neth.iecal.curbox.anti_stimulants

import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.data.models.TimeInterval

internal object GrayscaleDecision {

    enum class Action { ENABLE, DISABLE, NONE }

    fun shouldGrayscale(
        packageName: String,
        groups: List<GrayscaleGroup>,
        dayIndex: Int,
        minutesOfDay: Int
    ): Boolean {
        for (group in groups) {
            if (!group.isActive) continue
            if (!group.packages.contains(packageName)) continue

            val config = group.timeConfig
            val intervals = if (config.isEveryday) {
                config.everydayIntervals
            } else {
                config.dailyIntervals[dayIndex]
            }

            if (intervals.isNullOrEmpty()) return true
            if (intervals.any { isWithinInterval(minutesOfDay, it) }) return true
        }
        return false
    }

    fun isWithinInterval(currentMinutes: Int, interval: TimeInterval): Boolean {
        val start = interval.startHour * 60 + interval.startMinute
        val end = interval.endHour * 60 + interval.endMinute
        return if (start <= end) {
            currentMinutes in start until end
        } else {
            currentMinutes >= start || currentMinutes < end
        }
    }

    fun nextAction(
        wantsGrayscale: Boolean,
        systemGrayscaleOn: Boolean,
        enabledByCurbox: Boolean
    ): Action = when {
        wantsGrayscale && !systemGrayscaleOn -> Action.ENABLE
        // Ignores systemGrayscaleOn on purpose: the Shizuku fallback writes asynchronously, so a
        // pending enable can still read "off" here and would be left on if we skipped the disable.
        !wantsGrayscale && enabledByCurbox -> Action.DISABLE
        else -> Action.NONE
    }
}
