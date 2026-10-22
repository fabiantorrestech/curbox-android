package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.MindfulMessageConfig
import neth.iecal.curbox.utils.DataStoreManager

class MindfulMessagesViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)

    private val _configState = MutableStateFlow(MindfulMessageConfig())
    val configState: StateFlow<MindfulMessageConfig> = _configState.asStateFlow()

    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _configState.value = settings.mindfulMessageConfig
            }
        }
    }

    fun updateIsActive(isActive: Boolean) {
        val current = _configState.value
        if (current.isActive != isActive) updateConfig(current.copy(isActive = isActive))
    }

    fun updateMessages(messages: String) {
        val current = _configState.value
        if (current.messages != messages) updateConfig(current.copy(messages = messages))
    }

    fun updateTextSize(textSize: Float) {
        val current = _configState.value
        if (current.textSize != textSize) updateConfig(current.copy(textSize = textSize))
    }

    fun updateTextColor(textColor: Int) {
        val current = _configState.value
        if (current.textColor != textColor) updateConfig(current.copy(textColor = textColor))
    }

    fun updateBgColor(bgColor: Int) {
        val current = _configState.value
        if (current.bgColor != bgColor) updateConfig(current.copy(bgColor = bgColor))
    }

    fun updateBgOpacity(bgOpacity: Int) {
        val current = _configState.value
        if (current.bgOpacity != bgOpacity) updateConfig(current.copy(bgOpacity = bgOpacity))
    }

    fun updateTextOpacity(textOpacity: Int) {
        val current = _configState.value
        if (current.textOpacity != textOpacity) updateConfig(current.copy(textOpacity = textOpacity))
    }

    fun updatePosition(positionX: Float, positionY: Float) {
        val current = _configState.value
        if (current.positionX != positionX || current.positionY != positionY) {
            updateConfig(current.copy(positionX = positionX, positionY = positionY))
        }
    }

    fun updateSelectedApps(selectedApps: List<String>) {
        val current = _configState.value
        if (current.selectedApps != selectedApps) updateConfig(current.copy(selectedApps = selectedApps))
    }

    private fun updateConfig(config: MindfulMessageConfig) {
        viewModelScope.launch {
            dataStoreManager.updateMindfulMessageConfig(config)
        }
    }
}
