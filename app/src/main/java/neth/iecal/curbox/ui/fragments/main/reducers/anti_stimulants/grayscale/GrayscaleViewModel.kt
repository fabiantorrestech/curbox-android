package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.utils.DataStoreManager

class GrayscaleViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    
    private val _groups = MutableStateFlow<List<GrayscaleGroup>>(emptyList())
    val groups: StateFlow<List<GrayscaleGroup>> = _groups

    var currentTimeConfig: AppTimeConfig = AppTimeConfig()

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _groups.value = settings.grayscaleGroups
            }
        }
    }

    fun addGroup(group: GrayscaleGroup) = viewModelScope.launch {
        addGroupAndWait(group)
    }

    suspend fun addGroupAndWait(group: GrayscaleGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val currentGroups = currentSettings.grayscaleGroups.toMutableList()
        currentGroups.add(group)
        updateGroups(currentGroups)
    }

    fun removeGroup(group: GrayscaleGroup) = viewModelScope.launch {
        removeGroupAndWait(group)
    }

    suspend fun removeGroupAndWait(group: GrayscaleGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val currentGroups = currentSettings.grayscaleGroups.toMutableList()
        currentGroups.remove(group)
        updateGroups(currentGroups)
    }

    fun updateGroup(group: GrayscaleGroup) = viewModelScope.launch {
        updateGroupAndWait(group)
    }

    suspend fun updateGroupAndWait(group: GrayscaleGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val currentGroups = currentSettings.grayscaleGroups.toMutableList()
        val index = currentGroups.indexOfFirst { it.groupId == group.groupId }
        if (index != -1) {
            currentGroups[index] = group
            updateGroups(currentGroups)
        }
    }

    private suspend fun updateGroups(newGroups: List<GrayscaleGroup>) {
        dataStoreManager.updateGrayscaleGroups(newGroups)
        requestGrayscaleRefresh()
    }

    private fun requestGrayscaleRefresh() {
        val intent = Intent(GrayScaleFilter.INTENT_ACTION_REFRESH_GRAYSCALE)
        getApplication<Application>().sendBroadcast(intent)
    }
}
