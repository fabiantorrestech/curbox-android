package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.KeywordMatcher
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.WebsiteUsageWindow
import neth.iecal.curbox.utils.activeWindow
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import java.util.Calendar

class KeywordBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)

    private val _keywordBlockerConfig = MutableStateFlow(KeywordBlocker())
    val keywordBlockerConfig: StateFlow<KeywordBlocker> = _keywordBlockerConfig
    private val _temporaryDisableAvailable = MutableStateFlow(true)
    val temporaryDisableAvailable: StateFlow<Boolean> = _temporaryDisableAvailable

    var currentUsageConfig = AppUsageConfig()
    var currentTimeConfig = AppTimeConfig.allDay()
    var warningScrnConfig = AppBlockerWarningScreenConfig()

    /**
     * Time left in the current schedule before this group hits its usage limit.
     * Usage is combined across every keyword in the group and every browser.
     */
    suspend fun getRemainingUsageMillis(group: KeywordGroup): Long? {
        val config = group.config ?: return null
        val window = config.schedule.activeWindow() ?: return null

        val limitMillis = limitForToday(config.usage) * 60_000L
        if (limitMillis <= 0L) return 0L
        val used = withContext(Dispatchers.Default) {
            val patterns = KeywordMatcher.compileKeywords(group.selectedKeywords)
            val usageEndMs = minOf(System.currentTimeMillis(), window.endMs)
            val startDate = java.time.Instant.ofEpochMilli(window.startMs)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val endDate = java.time.Instant.ofEpochMilli(usageEndMs)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            val dates = buildList {
                var date = startDate
                while (!date.isAfter(endDate)) {
                    add(TimeTools.dayKey(date))
                    date = date.plusDays(1)
                }
            }
            val rows = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(getApplication()).websiteStatsDao().getStatsForDates(dates)
            }.filter { KeywordMatcher.matchesPatterns(patterns, it.urlIdentifier) }
            WebsiteUsageWindow.sum(rows, window.startMs, usageEndMs)
        }
        return (limitMillis - used).coerceAtLeast(0L)
    }

    private fun limitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) config.uniformLimit
        else config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
    }

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _keywordBlockerConfig.value = settings.keywordBlockerConfig
            }
        }
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _temporaryDisableAvailable.value = !settings.settingsChangeDelayConfig2.isEnabled &&
                    !settings.settingsChangeDelayConfig2.requireTamperProtectionOff
            }
        }
    }


    private fun requestKeywordBlockerRefresh() {
        val intent = Intent(neth.iecal.curbox.blockers.KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
        getApplication<Application>().sendBroadcast(intent)
    }
    private fun updateConfig(transform: (neth.iecal.curbox.data.models.KeywordBlocker) -> neth.iecal.curbox.data.models.KeywordBlocker) {
        viewModelScope.launch {
            persistConfig(transform)
        }
    }

    private suspend fun persistConfig(transform: (KeywordBlocker) -> KeywordBlocker) {
        dataStoreManager.updateKeywordBlockerConfig(transform)
        requestKeywordBlockerRefresh()
    }

    fun activateIfNeeded() {
        viewModelScope.launch {
            if (dataStoreManager.settings.first().keywordBlockerConfig.isActive) return@launch
            dataStoreManager.updateKeywordBlockerConfig { it.copy(isActive = true) }
            requestKeywordBlockerRefresh()
        }
    }

    fun setBlockAllExceptSupported(enabled: Boolean) {
        updateConfig { it.copy(blockAllExceptSupported = enabled) }
    }

    fun addGroup(group: KeywordGroup) = viewModelScope.launch {
        addGroupAndWait(group)
    }

    suspend fun addGroupAndWait(group: KeywordGroup) {
        persistConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.add(group)
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupById(group: KeywordGroup) = viewModelScope.launch {
        updateGroupByIdAndWait(group)
    }

    suspend fun updateGroupByIdAndWait(group: KeywordGroup) {
        persistConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == group.id }
            if (index != -1) {
                groups[index] = group
            }
            config.copy(keywordGroups = groups)
        }
    }

    fun deleteGroup(groupId: String) = viewModelScope.launch {
        deleteGroupAndWait(groupId)
    }

    suspend fun deleteGroupAndWait(groupId: String) {
        persistConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.removeAll { it.id == groupId }
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupActiveState(groupId: String, isActive: Boolean) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == groupId }
            if (index != -1) {
                groups[index] = groups[index].copy(
                    isActive = isActive,
                    temporarilyDisabledUntilMs = 0L
                )
            }
            config.copy(keywordGroups = groups)
        }
    }

    fun temporarilyDisableGroup(groupId: String, durationMinutes: Long) {
        viewModelScope.launch {
            if (dataStoreManager.temporarilyDisableKeywordGroup(groupId, durationMinutes)) {
                requestKeywordBlockerRefresh()
            }
        }
    }
}
