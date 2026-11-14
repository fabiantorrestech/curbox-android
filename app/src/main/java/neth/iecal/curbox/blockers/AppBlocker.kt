package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.UnlockGapConfig
import neth.iecal.curbox.data.models.UnlockGapSchedule
import neth.iecal.curbox.data.models.upgradeLegacyAppGroupConfigs
import neth.iecal.curbox.hardcoded.TRANSIENT_SYSTEM_PACKAGES
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.ShizukuRunner
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.activeWindow
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.isActivePackage
import neth.iecal.curbox.utils.nextChangeAfter
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class AppBlocker : BaseBlocker() {

    companion object {
        private const val TIMER_NOTIFICATION_ID = 1001
        /**
         * Refreshes information about warning screen, cheat hours and blocked app list
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "neth.iecal.curbox.refresh.appblocker"

        /**
         * Add cooldown to an app group.
         * This broadcast should always be sent together with the following keys:
         * selected_time: Long -> Duration of cooldown in millis
         * result_id : String -> ID of the app group to be put into cooldown
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.appblocker.cooldown"
        const val INTENT_ACTION_WARNING_SCREEN_VISIBILITY =
            "neth.iecal.curbox.appblocker.warning_screen_visibility"
        const val EXTRA_WARNING_SCREEN_VISIBLE = "warning_screen_visible"

        /** Cooldown intent extra: the unlock came from the emergency password. */
        const val EXTRA_EMERGENCY = "emergency"
        /** Warning screen extras for the growing wait between unlocks. */
        const val EXTRA_UNLOCK_GAP_ENDS_AT = "unlock_gap_ends_at"
        const val EXTRA_NEXT_UNLOCK_GAP_MN = "next_unlock_gap_mn"
        const val EXTRA_EMERGENCY_UNLOCKS_LEFT = "emergency_unlocks_left"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private lateinit var prefs: SharedPreferences

    /**
     * Stores which blocked groups have been allowed by the user and until when.
     * group-id -> end-time-in-real-time-millis
     */
    private var cooldownGroupsList = ConcurrentHashMap<String, Long>()

    private data class AppGroupEntry(
        val groupId: String,
        val config: AppGroupConfig,
        val groupPackages: List<String>,
        val warningConfig: AppBlockerWarningScreenConfig
    )

    private val blockedAppsList = ConcurrentHashMap<String, MutableList<AppGroupEntry>>()
    // Holds the warning config last shown per group, used for the cooldown intent's default duration.
    private val appBlockerWarningScrnConfgs = ConcurrentHashMap<String, AppBlockerWarningScreenConfig>()

    @Volatile
    private var unlockGapConfig = UnlockGapConfig()

    private lateinit var usageStats : UsageStatsHelper
    private var lastPackage = ""
    private lateinit var service: BaseBlockingService
    private var settingsJob: kotlinx.coroutines.Job? = null
    private var isWarningScreenVisible = false


    // responsible to trigger a recheck for what app user is currently using even when no event is received. Used in putting the usage recheck logic into
    // cooldown for an app and later when the cooldown duration is over, trigger a recheck
    private val handler = Handler(Looper.getMainLooper())

    private val activeRunnables = ConcurrentHashMap<String, Runnable>()

    private lateinit var notificationManager: TimerNotification

    private val ignoredApps = mutableListOf("com.android.systemui")

    fun doAppBlockerCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val packageName = event.packageName?.toString() ?: return

        if (packageName == service.packageName &&
            (isWarningScreenVisible || event.className?.toString() == WarningActivity::class.java.name)
        ) {
            isWarningScreenVisible = true
            return
        }

        if (lastPackage == packageName || ignoredApps.contains(packageName)) {
            return
        }
        if (!service.isActivePackage(packageName)) return

        // A share sheet or permission dialog opened from an app is not leaving it,
        // so it must not end that app's on each open unlock. Groups that list one still apply.
        if (packageName in TRANSIENT_SYSTEM_PACKAGES && !blockedAppsList.containsKey(packageName)) {
            return
        }

        clearFinishedOnEachOpenSessions(packageName)

        val now = System.currentTimeMillis()

        blockedAppsList[packageName]?.let { entries ->
            for (entry in entries) {
                if (!entry.warningConfig.isOnOpenConfig ||
                    isGroupInCooldown(entry.groupId, now)
                ) {
                    continue
                }
                val activeWindow = entry.config.schedule.activeWindow(now)
                if (activeWindow == null) {
                    entry.config.schedule.nextChangeAfter(now)?.let { nextChange ->
                        setUpForcedRefreshChecker(
                            "schedule:${entry.groupId}:$packageName",
                            nextChange
                        )
                    }
                    continue
                }

                notificationManager.stopTimer()
                showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                return
            }

            var minRemaining = Long.MAX_VALUE
            for (entry in entries) {
                if (entry.warningConfig.isOnOpenConfig) continue
                if (isGroupInCooldown(entry.groupId, now)) continue
                val activeWindow = entry.config.schedule.activeWindow(now)
                if (activeWindow == null) {
                    entry.config.schedule.nextChangeAfter(now)?.let { nextChange ->
                        setUpForcedRefreshChecker(
                            "schedule:${entry.groupId}:$packageName",
                            nextChange
                        )
                    }
                    continue
                }

                val currentUsage = runBlocking {
                    usageStats.getForegroundUsageBetween(
                        entry.groupPackages.toSet(),
                        activeWindow.startMs,
                        minOf(now, activeWindow.endMs)
                    )
                }
                val usageLimitMillis = getUsageLimitForToday(entry.config.usage) * 60_000L
                val remainingUsage = usageLimitMillis - currentUsage

                if (remainingUsage <= 0) {
                    notificationManager.stopTimer()
                    showWarningScreen(packageName, entry.groupId, entry.warningConfig)
                    return
                }
                if (remainingUsage < minRemaining) minRemaining = remainingUsage
                setUpForcedRefreshChecker(
                    "schedule:${entry.groupId}:$packageName",
                    activeWindow.endMs
                )
            }

            if (minRemaining != Long.MAX_VALUE) {
                notificationManager.startTimer(
                    totalMillis = minRemaining,
                    timerId = packageName,
                    title = service.getString(R.string.notification_title_remaining_usage)
                )
                setUpForcedRefreshChecker("usage:$packageName", System.currentTimeMillis() + minRemaining)
            } else {
                showNextCooldownNotification()
            }
            lastPackage = packageName
            return
        }

        showNextCooldownNotification()

        lastPackage = packageName
    }

    /**
     * An on-each-open unlock lasts only while the user stays within that group's selected apps.
     */
    private fun clearFinishedOnEachOpenSessions(currentPackage: String) {
        blockedAppsList[lastPackage]
            .orEmpty()
            .asSequence()
            .filter { it.warningConfig.isOnOpenConfig }
            .filter { currentPackage !in it.groupPackages }
            .map(AppGroupEntry::groupId)
            .distinct()
            .filter(cooldownGroupsList::containsKey)
            .forEach { groupId ->
                recordOnEachOpenUnlockEnd(groupId, System.currentTimeMillis())
                removeCooldownFrom(groupId)
            }
    }

    // All senders are in this app, including its other processes (the same UID).
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
            addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
            addAction(INTENT_ACTION_WARNING_SCREEN_VISIBILITY)
        }
        ContextCompat.registerReceiver(
            service,
            refreshReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun onDestroy() {
        service.unregisterReceiver(refreshReceiver)
        notificationManager.release()
        handler.removeCallbacksAndMessages(null)
        activeRunnables.clear()
        settingsJob?.cancel()
    }

    fun setupAppBlocker(service: BaseBlockingService) {
        this.service = service
        if (!this::notificationManager.isInitialized) {
            notificationManager = TimerNotification(service, TIMER_NOTIFICATION_ID)
        }
        prefs = service.getSharedPreferences("app_blocker_prefs", Context.MODE_PRIVATE)
        usageStats = UsageStatsHelper(service)
        loadPersistedData()

        ignoredApps.add(getCurrentKeyboardPackageName(service)?:"com.google.android.inputmethod.latin")
        ignoredApps.add("com.google.android.apps.wellbeing")

        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                Log.d("AppBlocker", "Settings updated, groups count: ${settings.blockedAppGroups.size}")
                unlockGapConfig = settings.unlockGapConfig

                val newBlockedAppsList = ConcurrentHashMap<String, MutableList<AppGroupEntry>>()
                settings.blockedAppGroups.upgradeLegacyAppGroupConfigs().forEach { group ->
                    if (!group.isActive) return@forEach

                    try {
                        val config = group.config ?: return@forEach
                        val groupPackages = group.selectedPackages.map(String::trim)
                        val entry = AppGroupEntry(
                            group.id,
                            config,
                            groupPackages,
                            group.warningScreenConfig
                        )
                        groupPackages.forEach { pkg ->
                            newBlockedAppsList.getOrPut(pkg) { mutableListOf() }.add(entry)
                        }
                    } catch (e: Exception) {
                        Log.e("AppBlocker", "Error loading group ${group.name}", e)
                    }
                }

                // Atomic-like update of the maps
                blockedAppsList.clear()
                blockedAppsList.putAll(newBlockedAppsList)

                Log.d("AppBlocker", "Loaded ${blockedAppsList.size} scheduled usage apps")
                
                // Force a check for the currently open app after settings change
                handler.post {
                    try {
                        val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                        if (currentPackage != null) {
                            Log.d("AppBlocker", "Forcing re-check for current package: $currentPackage")
                            lastPackage = "" // Reset lastPackage to ensure doAppBlockerCheck doesn't return early
                            // Construct a dummy event to trigger the check
                            val dummyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                            dummyEvent.packageName = currentPackage
                            doAppBlockerCheck(dummyEvent)
                            dummyEvent.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e("AppBlocker", "Error in forced re-check", e)
                    }
                }
            }
        }
    }

    private fun handlePutCooldownIntentBroadcast(intent: Intent) {
        val groupId = intent.getStringExtra("result_id") ?: return

        val durationMillis = intent.getLongExtra(
            "selected_time",
            appBlockerWarningScrnConfgs[groupId]?.timeInterval ?: 10L
        )
        if (durationMillis <= 0) return
        Log.d("cooldown for ", durationMillis.toString())
        val currentTimeMillis = System.currentTimeMillis()
        val realTimeEndMillis = if (durationMillis > Long.MAX_VALUE - currentTimeMillis) {
            Long.MAX_VALUE
        } else {
            currentTimeMillis + durationMillis
        }

        if (intent.getBooleanExtra(EXTRA_EMERGENCY, false)) {
            if (emergencyUnlocksLeft(currentTimeMillis) <= 0) return
            recordEmergencyUnlock(currentTimeMillis)
        } else if (warningConfigFor(groupId)?.isUnlockGapEnabled == true) {
            recordUnlock(groupId, currentTimeMillis, realTimeEndMillis)
        }

        putCooldownTo(groupId, realTimeEndMillis)
        showNextCooldownNotification()
        setUpForcedRefreshChecker("cooldown:$groupId", realTimeEndMillis, groupId)
    }

    /**
     * The group's current warning settings. The service can be recreated while the warning
     * screen is open, so the last shown config is only a fallback.
     */
    private fun warningConfigFor(groupId: String): AppBlockerWarningScreenConfig? =
        blockedAppsList.values.asSequence()
            .flatten()
            .firstOrNull { it.groupId == groupId }
            ?.warningConfig
            ?: appBlockerWarningScrnConfgs[groupId]

    /** Unlocks of this group since the last day end. Emergency unlocks are not counted. */
    private fun unlockStreak(groupId: String, now: Long): Int {
        val lastUnlockAt = prefs.getLong("gap_last_unlock_$groupId", 0L)
        if (lastUnlockAt < UnlockGapSchedule.lastDayEndAtOrBefore(now, unlockGapConfig)) return 0
        return prefs.getInt("gap_streak_$groupId", 0)
    }

    private fun recordUnlock(groupId: String, now: Long, unlockEndsAt: Long) {
        val streak = unlockStreak(groupId, now) + 1
        prefs.edit {
            putInt("gap_streak_$groupId", streak)
            putLong("gap_last_unlock_$groupId", now)
            putLong("gap_unlock_end_$groupId", unlockEndsAt)
        }
    }

    /** An on each open unlock is stored as ending in a day; it really ends when the user leaves. */
    private fun recordOnEachOpenUnlockEnd(groupId: String, now: Long) {
        if (prefs.getLong("gap_unlock_end_$groupId", 0L) > now) {
            prefs.edit { putLong("gap_unlock_end_$groupId", now) }
        }
    }

    /** When the next unlock of this group is allowed, or 0 when there is no wait. */
    private fun unlockGapEndsAt(groupId: String, config: AppBlockerWarningScreenConfig, now: Long): Long {
        if (!config.isUnlockGapEnabled) return 0L
        val streak = unlockStreak(groupId, now)
        if (streak == 0) return 0L
        val unlockEnd = prefs.getLong("gap_unlock_end_$groupId", 0L)
        val gapMillis = UnlockGapSchedule.gapMinutes(config, streak) * 60_000L
        val gapEnd = if (unlockEnd > Long.MAX_VALUE - gapMillis) Long.MAX_VALUE else unlockEnd + gapMillis
        // The streak starts over at the day end, so the wait must not run past it
        return minOf(gapEnd, UnlockGapSchedule.nextDayEndAfter(now, unlockGapConfig))
    }

    private fun emergencyUnlockTimesSince(dayStart: Long): List<Long> =
        prefs.getString("emergency_unlock_times", "").orEmpty()
            .split(",")
            .mapNotNull { it.toLongOrNull() }
            .filter { it >= dayStart }

    private fun emergencyUnlocksLeft(now: Long): Int {
        val config = unlockGapConfig
        if (config.emergencyPasswordHash.isEmpty()) return 0
        val dayStart = UnlockGapSchedule.lastDayEndAtOrBefore(now, config)
        val used = emergencyUnlockTimesSince(dayStart).size
        return (config.emergencyUnlocksPerDay - used).coerceAtLeast(0)
    }

    private fun recordEmergencyUnlock(now: Long) {
        val dayStart = UnlockGapSchedule.lastDayEndAtOrBefore(now, unlockGapConfig)
        val times = emergencyUnlockTimesSince(dayStart) + now
        prefs.edit { putString("emergency_unlock_times", times.joinToString(",")) }
    }

    private fun getUsageLimitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) {
            config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            config.dailyLimits[dayOfWeek]
        }
    }

    private fun loadPersistedData() {
        val cooldownKeys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        cooldownKeys.forEach { groupId ->
            val endTime = prefs.getLong("cooldown_$groupId", 0L)
            if (endTime > System.currentTimeMillis()) {
                cooldownGroupsList[groupId] = endTime
                setUpForcedRefreshChecker("cooldown:$groupId", endTime, groupId)
            }
        }
    }

    private fun persistCooldownData() {
        prefs.edit {
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
            cooldownGroupsList.forEach { (groupId, endTime) ->
                putLong("cooldown_$groupId", endTime)
            }
        }
    }

    private fun putCooldownTo(groupId: String, realTimeEnd: Long) {
        cooldownGroupsList[groupId] = realTimeEnd
        persistCooldownData()
    }

    private fun removeCooldownFrom(groupId: String) {
        cooldownGroupsList.remove(groupId)
        prefs.edit {
            remove("cooldown_$groupId")
            putStringSet("cooldown_keys", cooldownGroupsList.keys.toSet())
        }
    }

    private fun isGroupInCooldown(groupId: String, now: Long = System.currentTimeMillis()): Boolean {
        val endTime = cooldownGroupsList[groupId] ?: return false
        if (endTime > now) return true
        removeCooldownFrom(groupId)
        return false
    }

    private fun showNextCooldownNotification() {
        val now = System.currentTimeMillis()
        val next = cooldownGroupsList.filterValues { it > now }.minByOrNull { it.value }
        if (next == null) {
            notificationManager.stopTimer()
            return
        }
        notificationManager.startTimer(
            totalMillis = next.value - now,
            timerId = "app_cooldown:${next.key}:${next.value}",
            title = service.getString(R.string.notification_remaining_usage_lockdown),
            onFinishCallback = { showNextCooldownNotification() }
        )
    }

    private fun setUpForcedRefreshChecker(
        checkId: String,
        realTimeEndMillis: Long,
        cooldownGroupId: String? = null
    ) {
        activeRunnables[checkId]?.let { handler.removeCallbacks(it) }

        val delayMillis = realTimeEndMillis - System.currentTimeMillis()
        if (delayMillis <= 0) return // Time is already up

        val runnable = Runnable {
            try {
                cooldownGroupId?.let(::removeCooldownFrom)
                val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                if (currentPackage != null) {
                    lastPackage = ""
                    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                    event.packageName = currentPackage
                    doAppBlockerCheck(event)
                    event.recycle()
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Recheck error: $e")
                // Retry in 1 minute if UI check failed
                setUpForcedRefreshChecker(
                    checkId,
                    System.currentTimeMillis() + 60_000L,
                    cooldownGroupId
                )
            } finally {
                activeRunnables.remove(checkId)
            }
        }

        activeRunnables[checkId] = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun showWarningScreen(packageName: String, groupId: String, warningConfig: AppBlockerWarningScreenConfig) {
        if (service.isDelayOver(1000)) {

            // Remember the warning that was shown so the cooldown intent can read its default duration
            appBlockerWarningScrnConfgs[groupId] = warningConfig

            Log.d("AppBlocker", "Showing warning screen for $packageName")
            notificationManager.stopTimer()
            service.pressHome()
            lastPackage = ""

            try {
                if (packageName != service.packageName && AppSuspendHelper.isShizukuAvailable()) {
                    ShizukuRunner.executeCommand(
                        "am force-stop $packageName",
                        object : ShizukuRunner.CommandResultListener {})
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Shizuku force-stop failed", e)
            }

            if (warningConfig.isWarningDialogHidden) return
            isWarningScreenVisible = true

            val now = System.currentTimeMillis()
            val unlockGapEndsAt = unlockGapEndsAt(groupId, warningConfig, now)
            val nextUnlockGapMinutes = if (warningConfig.isUnlockGapEnabled) {
                UnlockGapSchedule.gapMinutes(warningConfig, unlockStreak(groupId, now) + 1)
            } else {
                0L
            }
            val emergencyUnlocksLeft = emergencyUnlocksLeft(now)

            handler.postDelayed({
                val dialogIntent = Intent(service, WarningActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                    putExtra("result_id", groupId)
                    putExtra("launch_package", packageName)
                    putExtra(
                        "warning_config",
                        Gson().toJson(warningConfig)
                    )
                    putExtra(EXTRA_UNLOCK_GAP_ENDS_AT, unlockGapEndsAt)
                    putExtra(EXTRA_NEXT_UNLOCK_GAP_MN, nextUnlockGapMinutes)
                    putExtra(EXTRA_EMERGENCY_UNLOCKS_LEFT, emergencyUnlocksLeft)
                }
                service.startActivity(dialogIntent)
            }, 100)
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_APP_BLOCKER -> setupAppBlocker(service)
                INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> handlePutCooldownIntentBroadcast(intent)
                INTENT_ACTION_WARNING_SCREEN_VISIBILITY -> {
                    isWarningScreenVisible = intent.getBooleanExtra(
                        EXTRA_WARNING_SCREEN_VISIBLE,
                        false
                    )
                }
            }
        }
    }
}
