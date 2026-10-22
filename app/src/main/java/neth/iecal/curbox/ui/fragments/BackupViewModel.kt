package neth.iecal.curbox.ui.fragments

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.utils.BackupManager

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    enum class Status { IDLE, WORKING, SUCCEEDED, FAILED }
    private val manager = BackupManager(application)
    private val _status = MutableStateFlow(Status.IDLE)
    val status = _status.asStateFlow()

    fun export(uri: Uri) = run { manager.export(uri) }
    fun restore(uri: Uri) = run { manager.restore(uri) }

    fun dismissResult() {
        if (_status.value != Status.WORKING) _status.value = Status.IDLE
    }

    private fun run(operation: suspend () -> Unit) {
        if (_status.value == Status.WORKING) return
        _status.value = Status.WORKING
        viewModelScope.launch {
            try {
                operation()
                _status.value = Status.SUCCEEDED
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CrashLogger(getApplication()).logNonFatalError(error)
                _status.value = Status.FAILED
            }
        }
    }
}
