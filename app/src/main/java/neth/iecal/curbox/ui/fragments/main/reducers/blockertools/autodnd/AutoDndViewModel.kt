package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AutoDndGroup
import neth.iecal.curbox.utils.DataStoreManager

class AutoDndViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    
    private val _groups = MutableStateFlow<List<AutoDndGroup>>(emptyList())
    val groups: StateFlow<List<AutoDndGroup>> = _groups

    var currentTimeConfig: AppTimeConfig = AppTimeConfig()

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _groups.value = settings.autoDndGroups
            }
        }
    }

    fun addGroup(group: AutoDndGroup) = viewModelScope.launch {
        addGroupAndWait(group)
    }

    suspend fun addGroupAndWait(group: AutoDndGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val currentGroups = currentSettings.autoDndGroups.toMutableList()
        currentGroups.add(group)
        updateGroups(currentGroups)
    }

    fun removeGroup(group: AutoDndGroup) = viewModelScope.launch {
        removeGroupAndWait(group)
    }

    suspend fun removeGroupAndWait(group: AutoDndGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val currentGroups = currentSettings.autoDndGroups.toMutableList()
        currentGroups.removeIf { it.groupId == group.groupId }
        updateGroups(currentGroups)
    }

    fun updateGroup(group: AutoDndGroup) = viewModelScope.launch {
        updateGroupAndWait(group)
    }

    suspend fun updateGroupAndWait(group: AutoDndGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val currentGroups = currentSettings.autoDndGroups.toMutableList()
        val index = currentGroups.indexOfFirst { it.groupId == group.groupId }
        if (index != -1) {
            currentGroups[index] = group
            updateGroups(currentGroups)
        }
    }

    private suspend fun updateGroups(newGroups: List<AutoDndGroup>) {
        dataStoreManager.updateAutoDndGroups(newGroups)
        requestFocusBlockerRefresh()
    }

    private fun requestFocusBlockerRefresh() {
        val intent = Intent(FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
        getApplication<Application>().sendBroadcast(intent)
    }
}
