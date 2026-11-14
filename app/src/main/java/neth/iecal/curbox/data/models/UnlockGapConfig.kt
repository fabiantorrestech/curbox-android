package neth.iecal.curbox.data.models

import java.util.Calendar

/**
 * Shared settings for the growing wait between app group unlocks.
 * The gap sizes themselves live in each group's [AppBlockerWarningScreenConfig].
 */
data class UnlockGapConfig(
    /**
     * When each day ends, as minutes after the following midnight, Sunday first.
     * 180 for Friday means Friday's streak ends on Saturday at 3:00.
     */
    val dayEndMinutes: List<Int> = List(7) { 0 },

    /** SHA-256 hash of the emergency password. Empty means emergency unlock is off. */
    val emergencyPasswordHash: String = "",

    /** How many emergency unlocks are allowed between two day ends. */
    val emergencyUnlocksPerDay: Int = 1
) {
    fun emergencyUnlockPrefs() = EmergencyUnlockPrefs(emergencyPasswordHash, emergencyUnlocksPerDay)

    companion object {
        const val MAX_DAY_END_MINUTES = 12 * 60 - 1
        const val MAX_EMERGENCY_UNLOCKS_PER_DAY = 10
    }
}

/**
 * The day end part of [UnlockGapConfig], gated on its own as [GatedSettingsField.UNLOCK_GAP]
 * so a pending day end move never holds back a stricter emergency unlock change.
 */
data class UnlockGapDayEnds(
    val dayEndMinutes: List<Int> = List(7) { 0 }
)

/** The emergency unlock part of [UnlockGapConfig], gated on its own as [GatedSettingsField.EMERGENCY_UNLOCK]. */
data class EmergencyUnlockPrefs(
    val emergencyPasswordHash: String = "",
    val emergencyUnlocksPerDay: Int = 1
)

object UnlockGapSchedule {

    /** The most recent day end at or before [now]. */
    fun lastDayEndAtOrBefore(now: Long, config: UnlockGapConfig): Long {
        // A day end falls on the morning after its weekday, so only the last two weekdays can own it
        return (-2..-1).map { dayEndOfWeekday(now, it, config) }
            .filter { it <= now }
            .maxOrNull() ?: Long.MIN_VALUE
    }

    /** The first day end after [now]: this morning's if it is still ahead, else tomorrow's. */
    fun nextDayEndAfter(now: Long, config: UnlockGapConfig): Long =
        (-1..0).map { dayEndOfWeekday(now, it, config) }
            .filter { it > now }
            .minOrNull() ?: Long.MAX_VALUE

    /** Gap in minutes after the [unlockNumber]th unlock of the day, counting from 1. */
    fun gapMinutes(config: AppBlockerWarningScreenConfig, unlockNumber: Int): Long {
        if (unlockNumber <= 0) return 0L
        val start = config.unlockGapStartMn.coerceAtLeast(0).toLong()
        val step = config.unlockGapStepMn.coerceAtLeast(0).toLong()
        val max = config.unlockGapMaxMn.coerceAtLeast(0).toLong()
        return minOf(start + step * (unlockNumber - 1), max)
    }

    /**
     * The day end of the weekday [dayOffset] days from [now], on the following morning. Set as a
     * wall clock time, so a daylight saving change that night does not move it by an hour.
     */
    private fun dayEndOfWeekday(now: Long, dayOffset: Int, config: UnlockGapConfig): Long =
        startOfDay(now, dayOffset).apply {
            val minutes = dayEndMinutesFor(config, get(Calendar.DAY_OF_WEEK) - 1)
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }.timeInMillis

    private fun dayEndMinutesFor(config: UnlockGapConfig, weekday: Int): Int =
        config.dayEndMinutes.getOrElse(weekday) { 0 }.coerceIn(0, UnlockGapConfig.MAX_DAY_END_MINUTES)

    private fun startOfDay(now: Long, dayOffset: Int): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
